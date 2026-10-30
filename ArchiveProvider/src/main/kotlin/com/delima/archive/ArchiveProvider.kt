package com.delima.archive

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Provider for the Internet Archive (archive.org) moving image collection.
 *
 * All content served here is public domain or Creative Commons material
 * hosted by the Internet Archive itself. Data comes from two public,
 * key-less JSON endpoints:
 *  - /advancedsearch.php  (search + curated main page shelves)
 *  - /metadata/{id}       (details, file list, subtitles)
 * Playable files are the item's own mp4 derivatives (h.264 / 512Kb MPEG4);
 * the original uploads (often MPEG2) are never returned.
 */
class ArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val mapper = ObjectMapper()

    // Shelf queries verified live on 2026-10-30 (result counts at that time):
    // feature_films 28.5k, animationandcartoons 16k, film noir subject 1.6k,
    // prelinger 10.5k items.
    override val mainPage = mainPageOf(
        "collection:(feature_films) AND mediatype:(movies)" to "Film Terpopuler",
        "collection:(animationandcartoons) AND mediatype:(movies)" to "Animasi Klasik",
        "subject:(\"film noir\") AND mediatype:(movies)" to "Film Noir",
        "collection:(prelinger) AND mediatype:(movies)" to "Arsip Prelinger",
    )

    private fun advancedSearchUrl(query: String, page: Int, sortByDownloads: Boolean): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val sort = if (sortByDownloads) "&sort%5B%5D=downloads+desc" else ""
        return "$mainUrl/advancedsearch.php?q=$encoded" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "$sort&rows=24&page=$page&output=json"
    }

    private suspend fun fetchSearchDocs(
        query: String,
        page: Int,
        sortByDownloads: Boolean,
    ): Pair<List<JsonNode>, Int> {
        val text = app.get(advancedSearchUrl(query, page, sortByDownloads)).text
        val response = mapper.readTree(text).path("response")
        val docs = response.path("docs").map { it }
        val numFound = response.path("numFound").asInt(0)
        return docs to numFound
    }

    private fun JsonNode.intOrNull(): Int? = when {
        isNumber -> asInt()
        isTextual -> asText().toIntOrNull()
        else -> null
    }

    private fun JsonNode.textOrNull(): String? = when {
        isTextual -> asText()
        isArray -> joinToString("\n") { it.asText() }.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun docToSearchResponse(doc: JsonNode): SearchResponse? {
        val id = doc.path("identifier").textOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val title = doc.path("title").textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: id
        return newMovieSearchResponse(title, "$mainUrl/details/$id", TvType.Movie) {
            posterUrl = "$mainUrl/download/$id/__ia_thumb.jpg"
            year = doc.path("year").intOrNull()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (docs, numFound) = fetchSearchDocs(request.data, page, sortByDownloads = true)
        val list = docs.mapNotNull { docToSearchResponse(it) }
        return newHomePageResponse(request.name, list, hasNext = page * 24 < numFound)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Strip characters that would break the Solr query syntax.
        val clean = query.replace(Regex("[^\\p{L}\\p{N} .'-]"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val (docs, _) = fetchSearchDocs(
            "title:($clean) AND mediatype:(movies)",
            page = 1,
            sortByDownloads = false,
        )
        return docs.mapNotNull { docToSearchResponse(it) }
    }

    private fun identifierFromUrl(url: String): String =
        url.substringAfter("/details/").substringBefore("/").substringBefore("?").trim()

    private fun cleanPlot(raw: String): String = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private fun parseDurationMinutes(runtime: String?): Int? {
        val t = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (t.contains(":")) {
            val parts = t.split(":").map { it.trim().toIntOrNull() ?: return null }
            return when (parts.size) {
                2 -> parts[0] + if (parts[1] >= 30) 1 else 0
                3 -> parts[0] * 60 + parts[1]
                else -> null
            }
        }
        val minutes = Regex("(\\d+)\\s*(?:minutes|mins|min)").find(t)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (minutes != null) return minutes
        val seconds = Regex("(\\d+)\\s*seconds").find(t)
            ?.groupValues?.get(1)?.toIntOrNull()
        return seconds?.let { (it + 30) / 60 }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = identifierFromUrl(url)
        if (id.isBlank()) return null
        val root = mapper.readTree(app.get("$mainUrl/metadata/$id").text)
        val meta = root.path("metadata")
        if (meta.isMissingNode) return null
        val title = meta.path("title").textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: id
        val year = meta.path("year").intOrNull()
            ?: meta.path("date").textOrNull()?.take(4)?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            posterUrl = "$mainUrl/download/$id/__ia_thumb.jpg"
            this.year = year
            plot = meta.path("description").textOrNull()?.let { cleanPlot(it) }
            duration = parseDurationMinutes(meta.path("runtime").textOrNull())
        }
    }

    private fun fileUrl(id: String, fileName: String): String {
        val encodedPath = fileName.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "$mainUrl/download/$id/$encodedPath"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = data.trim()
        if (id.isBlank()) return false
        val root = mapper.readTree(app.get("$mainUrl/metadata/$id").text)
        val files = root.path("files")
        if (!files.isArray) return false

        for (file in files) {
            val fileName = file.path("name").textOrNull() ?: continue
            if (fileName.endsWith(".srt", ignoreCase = true)) {
                subtitleCallback(newSubtitleFile("English", fileUrl(id, fileName)))
            }
        }

        val videos = files.filter { file ->
            val fileName = file.path("name").textOrNull().orEmpty()
            val format = file.path("format").textOrNull().orEmpty()
            fileName.endsWith(".mp4", ignoreCase = true) &&
                (format.startsWith("h.264", ignoreCase = true) ||
                    format.contains("MPEG4", ignoreCase = true))
        }.sortedWith(
            compareByDescending<JsonNode> {
                if (it.path("format").textOrNull().orEmpty()
                        .startsWith("h.264", ignoreCase = true)
                ) 1 else 0
            }.thenByDescending { it.path("height").intOrNull() ?: 0 }
        )

        var found = false
        for (file in videos) {
            val fileName = file.path("name").textOrNull() ?: continue
            val format = file.path("format").textOrNull().orEmpty()
            val height = file.path("height").intOrNull()
            val sizeMb = (file.path("size").textOrNull()?.toLongOrNull() ?: 0L) / 1_000_000
            val label = buildString {
                append(format.ifBlank { "MP4" })
                if (height != null) append(" ${height}p")
                if (sizeMb > 0) append(" (${sizeMb} MB)")
            }
            callback(
                newExtractorLink(name, label, fileUrl(id, fileName), ExtractorLinkType.VIDEO) {
                    quality = getQualityFromName(height?.toString())
                }
            )
            found = true
        }
        return found
    }
}

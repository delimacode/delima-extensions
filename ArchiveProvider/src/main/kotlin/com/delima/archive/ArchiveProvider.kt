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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

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
 *
 * v2 changes:
 *  - Listings only include items that actually have a playable mp4
 *    derivative (advancedsearch format filter), so dead items without
 *    any video file no longer show up.
 *  - More shelves: classic TV series, comedy, horror and western films.
 *  - Items whose files form two or more distinct parts/episodes are
 *    presented as a TV series; single-part items stay movies.
 */
class ArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()

    // Only items with a playable mp4 derivative may be listed. Verified
    // live on 2026-10-30: this keeps 17.4k of 28.6k feature_films and
    // correctly drops file-less items (e.g. identifier
    // HisNewJobCharlesChaplin-1915 returns 0 with this filter).
    private val playableFilter = " AND (format:\"h.264\" OR format:\"512Kb MPEG4\")"

    // Shelf queries verified live on 2026-10-30 (playable counts):
    // feature_films 17.4k, animationandcartoons 7.7k, classic_tv 8.3k,
    // comedy 1.2k, horror 773, western 485, film noir 807, prelinger 9.4k.
    override val mainPage = mainPageOf(
        "collection:(feature_films) AND mediatype:(movies)" to "Film Terpopuler",
        "collection:(animationandcartoons) AND mediatype:(movies)" to "Animasi Klasik",
        "collection:(classic_tv) AND mediatype:(movies)" to "Serial TV Klasik",
        "collection:(feature_films) AND subject:(\"comedy\") AND mediatype:(movies)" to "Film Komedi",
        "collection:(feature_films) AND subject:(\"horror\") AND mediatype:(movies)" to "Film Horor",
        "collection:(feature_films) AND subject:(\"western\") AND mediatype:(movies)" to "Film Western",
        "subject:(\"film noir\") AND mediatype:(movies)" to "Film Noir",
        "collection:(prelinger) AND mediatype:(movies)" to "Arsip Prelinger",
    )

    private fun advancedSearchUrl(query: String, page: Int, sortByDownloads: Boolean): String {
        val encoded = URLEncoder.encode(query + playableFilter, "UTF-8")
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

    // --- Playable file handling (shared by load and loadLinks) ---

    private data class VideoFile(
        val name: String,
        val format: String,
        val height: Int?,
        val sizeBytes: Long,
    )

    private fun playableVideos(files: JsonNode): List<VideoFile> {
        if (!files.isArray) return emptyList()
        return files.mapNotNull { file ->
            val fileName = file.path("name").textOrNull() ?: return@mapNotNull null
            val format = file.path("format").textOrNull().orEmpty()
            val lower = fileName.lowercase()
            if (!lower.endsWith(".mp4")) return@mapNotNull null
            if (!(format.startsWith("h.264", ignoreCase = true) ||
                    format.contains("MPEG4", ignoreCase = true))
            ) return@mapNotNull null
            // Trailers and samples are not episodes or the main feature.
            if (listOf("trailer", "sample", "preview", "teaser").any { it in lower }) {
                return@mapNotNull null
            }
            VideoFile(
                name = fileName,
                format = format,
                height = file.path("height").intOrNull(),
                sizeBytes = file.path("size").textOrNull()?.toLongOrNull() ?: 0L,
            )
        }
    }

    private val encodingSuffixes = listOf(
        "_512kb", "_256kb", "_128kb", "_64kb", ".ia", "_hi", "_lo", "_low", "_med",
    )

    private val qualityTail = Regex(
        "(?i)[._\\- ](?:720p|1080p|480p|360p|240p|2160p|x264|x265|h\\.?264|" +
            "bluray|blu-ray|webrip|web-dl|dvdrip|hdrip|hd|sd)$"
    )

    /**
     * Group key identifying one distinct part/episode of an item: the file
     * name without folder, extension, encoding suffixes (_512kb, .ia ...)
     * and trailing quality tokens (720p, BluRay ...). Different encodings
     * of the same content share a key; different episodes do not.
     */
    private fun episodeBase(fileName: String): String {
        var base = fileName.substringAfterLast("/").substringBeforeLast(".")
        var changed = true
        while (changed) {
            changed = false
            for (suffix in encodingSuffixes) {
                if (base.endsWith(suffix, ignoreCase = true)) {
                    base = base.dropLast(suffix.length)
                    changed = true
                }
            }
            if (qualityTail.containsMatchIn(base)) {
                base = qualityTail.replace(base, "")
                changed = true
            }
        }
        return base.trim()
    }

    private fun naturalCompare(a: String, b: String): Int {
        val chunksA = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val chunksB = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val x = chunksA[i]
            val y = chunksB[i]
            val cmp = if (x.first().isDigit() && y.first().isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length)
                    .takeIf { it != 0 } ?: x.compareTo(y)
            } else {
                x.lowercase().compareTo(y.lowercase())
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size.compareTo(chunksB.size)
    }

    private val seasonEpisodeRegex = Regex("(?i)S(\\d{1,2})[ ._-]*E(\\d{1,3})")
    private val seasonFolderRegex = Regex("(?i)season[ ._-]*(\\d{1,2})")

    private fun prettyEpisodeName(base: String, fallbackIndex: Int): String {
        val cleaned = base.replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return cleaned.ifBlank { "Episode $fallbackIndex" }
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
        val plot = meta.path("description").textOrNull()?.let { cleanPlot(it) }
        val poster = "$mainUrl/download/$id/__ia_thumb.jpg"

        val groups = playableVideos(root.path("files")).groupBy { episodeBase(it.name) }
        if (groups.size >= 2) {
            val sortedBases = groups.keys.sortedWith { a, b -> naturalCompare(a, b) }
            val episodes = sortedBases.mapIndexed { index, base ->
                val se = seasonEpisodeRegex.find(base)
                val folderSeason = groups[base]?.firstOrNull()?.name
                    ?.substringBeforeLast("/", "")
                    ?.let { seasonFolderRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                // The episode base travels Base64-url encoded so the
                // episode data URL survives CloudStream's fixUrl intact.
                val epParam = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(base.toByteArray(Charsets.UTF_8))
                newEpisode("$mainUrl/details/$id?ep=$epParam") {
                    name = prettyEpisodeName(base, index + 1)
                    season = se?.groupValues?.get(1)?.toIntOrNull() ?: folderSeason ?: 1
                    episode = se?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            duration = parseDurationMinutes(meta.path("runtime").textOrNull())
        }
    }

    private fun fileUrl(id: String, fileName: String): String {
        val encodedPath = fileName.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "$mainUrl/download/$id/$encodedPath"
    }

    /** LoadLinks data is either a bare identifier (movie) or a details
     *  URL carrying an ?ep=<episode base> parameter (one episode). */
    private fun parseData(data: String): Pair<String, String?> {
        val trimmed = data.trim()
        if (!trimmed.contains("/details/")) return trimmed to null
        val id = identifierFromUrl(trimmed)
        val raw = Regex("[?&]ep=([^&]+)").find(trimmed)?.groupValues?.get(1)
        val ep = raw?.let {
            runCatching {
                String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
            }.getOrElse {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
        }
        return id to ep
    }

    private fun subtitleBase(fileName: String): String {
        var base = fileName.substringAfterLast("/").substringBeforeLast(".")
        // "title.asr.srt" / "title.en.srt" belong to base "title".
        for (suffix in listOf(".asr", ".en", ".eng")) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
            }
        }
        return base
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (id, episode) = parseData(data)
        if (id.isBlank()) return false
        val root = mapper.readTree(app.get("$mainUrl/metadata/$id").text)
        val files = root.path("files")
        if (!files.isArray) return false

        for (file in files) {
            val fileName = file.path("name").textOrNull() ?: continue
            if (fileName.endsWith(".srt", ignoreCase = true)) {
                if (episode == null || subtitleBase(fileName) == episode) {
                    subtitleCallback(newSubtitleFile("English", fileUrl(id, fileName)))
                }
            }
        }

        val videos = playableVideos(files)
            .filter { episode == null || episodeBase(it.name) == episode }
            .sortedWith(
                compareByDescending<VideoFile> {
                    if (it.format.startsWith("h.264", ignoreCase = true)) 1 else 0
                }.thenByDescending { it.height ?: 0 }
            )

        var found = false
        for (file in videos) {
            val height = file.height
            val sizeMb = file.sizeBytes / 1_000_000
            val label = buildString {
                append(file.format.ifBlank { "MP4" })
                if (height != null) append(" ${height}p")
                if (sizeMb > 0) append(" (${sizeMb} MB)")
            }
            callback(
                newExtractorLink(name, label, fileUrl(id, file.name), ExtractorLinkType.VIDEO) {
                    quality = getQualityFromName(height?.toString())
                }
            )
            found = true
        }
        return found
    }
}

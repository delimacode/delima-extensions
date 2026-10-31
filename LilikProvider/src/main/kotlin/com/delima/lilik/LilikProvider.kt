package com.delima.lilik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

// --- Data classes untuk response API ---
data class IdlixResponse<T>(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: T?
)

data class MovieItem(
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("type") val type: String?
)

data class MovieDetail(
    @JsonProperty("title") val title: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("seasons") val seasons: List<Season>?
)

data class Season(
    @JsonProperty("season") val season: Int?,
    @JsonProperty("episodes") val episodes: List<Episode>?
)

data class Episode(
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?
)

data class StreamData(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("subtitles") val subtitles: List<Subtitle>?
)

data class Subtitle(
    @JsonProperty("language") val language: String?,
    @JsonProperty("url") val url: String?
)
// ------------------------------------------

class LilikProvider : MainAPI() {
    // 1. Domain IDLIX langsung
    override var mainUrl = "https://z2.idlixku.com"
    
    override var name = "Lilik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 2. Inisialisasi CloudflareKiller untuk menembus proteksi
    private val interceptor = CloudflareKiller()

    // 3. Endpoint langsung ke API internal IDLIX
    override val mainPage = mainPageOf(
        "$mainUrl/api/movies/trending" to "Movie Trending",
        "$mainUrl/api/series/trending" to "Series Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}?page=$page", interceptor = interceptor)
            .parsedSafe<IdlixResponse<List<MovieItem>>>()
        val items = response?.data ?: emptyList()
        val home = items.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun MovieItem.toSearchResult(): SearchResponse? {
        val title = this.title ?: return null
        val slug = this.slug ?: return null
        val href = slug
        val type = if (this.type == "series") TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = this@toSearchResult.poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Endpoint pencarian langsung ke API internal IDLIX
        val response = app.get("$mainUrl/api/search?q=$query", interceptor = interceptor)
            .parsedSafe<IdlixResponse<List<MovieItem>>>()
        return response?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Coba ambil sebagai Movie
        val movieResponse = app.get("$mainUrl/api/movies/$url", interceptor = interceptor)
            .parsedSafe<IdlixResponse<MovieDetail>>()
        if (movieResponse?.data != null) {
            val detail = movieResponse.data!!
            return newMovieLoadResponse(detail.title ?: "Unknown", url, TvType.Movie, url) {
                this.posterUrl = detail.poster
                this.plot = detail.overview
                this.year = detail.year
            }
        }
        
        // Jika gagal, coba sebagai Series
        val seriesResponse = app.get("$mainUrl/api/series/$url", interceptor = interceptor)
            .parsedSafe<IdlixResponse<MovieDetail>>()
        val series = seriesResponse?.data ?: throw ErrorLoadingException("Konten tidak ditemukan")
        
        val episodes = series.seasons?.flatMap { season ->
            season.episodes?.map { ep ->
                newEpisode("$url|season=${season.season}|episode=${ep.episode}") {
                    this.name = "S${season.season}E${ep.episode} - ${ep.title ?: ""}"
                }
            } ?: emptyList()
        } ?: emptyList()

        return newTvSeriesLoadResponse(series.title ?: "Unknown", url, TvType.TvSeries, episodes) {
            this.posterUrl = series.poster
            this.plot = series.overview
            this.year = series.year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val slug = parts[0]
        val streamUrl: String?

        if (parts.size > 1) {
            // Ekstraksi Link Series
            val season = parts[1].removePrefix("season=")
            val episode = parts[2].removePrefix("episode=")
            val response = app.get("$mainUrl/api/series/$slug/season/$season/episode/$episode/stream", interceptor = interceptor)
                .parsedSafe<IdlixResponse<StreamData>>()
            streamUrl = response?.data?.streamUrl
            response?.data?.subtitles?.forEach { sub ->
                subtitleCallback(newSubtitleFile(sub.language ?: "Unknown", sub.url ?: ""))
            }
        } else {
            // Ekstraksi Link Movie
            val response = app.get("$mainUrl/api/movies/$slug/stream", interceptor = interceptor)
                .parsedSafe<IdlixResponse<StreamData>>()
            streamUrl = response?.data?.streamUrl
            response?.data?.subtitles?.forEach { sub ->
                subtitleCallback(newSubtitleFile(sub.language ?: "Unknown", sub.url ?: ""))
            }
        }

        if (streamUrl.isNullOrEmpty()) return false

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8 // Jika error 3003, ubah ke ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
            }
        )
        return true
    }
}
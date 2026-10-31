package com.delima.lilik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty

// --- Data classes untuk response API ---
data class IdlixResponse<T>(
    @JsonProperty("success") val success: Boolean,
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
    // URL InstaBox Anda (TANPA /api di akhir)
    override var mainUrl = "https://3000-agqzbz3ykpoxnsefybg4gbufjm.instabox.tech"
    
    override var name = "Lilik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Tambahkan /api secara manual di sini
    override val mainPage = mainPageOf(
        "$mainUrl/api/movie/trending" to "Movie Trending",
        "$mainUrl/api/series/trending" to "Series Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}?page=$page").parsedSafe<IdlixResponse<List<MovieItem>>>()
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
        // Tambahkan /api secara manual
        val response = app.get("$mainUrl/api/search?q=$query").parsedSafe<IdlixResponse<List<MovieItem>>>()
        return response?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Tambahkan /api secara manual
        val movieResponse = app.get("$mainUrl/api/movie/$url").parsedSafe<IdlixResponse<MovieDetail>>()
        if (movieResponse?.data != null) {
            val detail = movieResponse.data!!
            return newMovieLoadResponse(detail.title ?: "Unknown", url, TvType.Movie, url) {
                this.posterUrl = detail.poster
                this.plot = detail.overview
                this.year = detail.year
            }
        }
        
        // Tambahkan /api secara manual
        val seriesResponse = app.get("$mainUrl/api/series/$url").parsedSafe<IdlixResponse<MovieDetail>>()
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
            val season = parts[1].removePrefix("season=")
            val episode = parts[2].removePrefix("episode=")
            // Tambahkan /api secara manual
            val response = app.get("$mainUrl/api/series/$slug/season/$season/episode/$episode/stream")
                .parsedSafe<IdlixResponse<StreamData>>()
            streamUrl = response?.data?.streamUrl
            response?.data?.subtitles?.forEach { sub ->
                subtitleCallback(newSubtitleFile(sub.language ?: "Unknown", sub.url ?: ""))
            }
        } else {
            // Tambahkan /api secara manual
            val response = app.get("$mainUrl/api/movie/$slug/stream")
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
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
            }
        )
        return true
    }
}
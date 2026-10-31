package com.delima.lilik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LilikProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Lilik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 1. Menu Utama
    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Movie Terbaru",
        "$mainUrl/series/page/" to "Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.mlw, div.bs, article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .tt, .title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.ifEmpty { this.selectFirst("img")?.attr("src") }
        
        // Cek apakah ini series atau movie
        val type = if (href.contains("/series/") || href.contains("/tv/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    // 2. Pencarian
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.mlw, div.bs, article.item").mapNotNull { it.toSearchResult() }
    }

    // 3. Halaman Detail Film
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.poster img, div.thumb img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p, div.synopsis")?.text()?.trim()
        val year = document.selectFirst("a[href*='/release/'], .year")?.text()?.toIntOrNull()
        val isSeries = url.contains("/series/") || url.contains("/tv/")

        if (isSeries) {
            // Jika Series, ambil daftar episode
            val episodes = document.select("div.eplister ul li a, .episode-list a").mapNotNull { ep ->
                val epName = ep.text()
                val epHref = ep.attr("href")
                newEpisode(epHref) {
                    this.name = epName
                }
            }.reversed() // Urutkan dari episode 1

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // Jika Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // 4. Mengambil Link Video (Streaming)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Mencari iframe server video
        document.select("iframe, .server a").forEach { element ->
            val iframeUrl = if (element.tagName() == "iframe") element.attr("src") else element.attr("href")
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
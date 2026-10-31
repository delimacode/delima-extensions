package com.delima.lilik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class LilikProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Lilik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Inisialisasi CloudflareKiller
    private val interceptor = CloudflareKiller()

    // Menggunakan URL yang terlihat di screenshot Anda
    override val mainPage = mainPageOf(
        "$mainUrl/movie" to "Latest Movies",
        "$mainUrl/series" to "Latest Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Mengambil HTML halaman (menggunakan ?page= untuk paginasi)
        val document = app.get("${request.data}?page=$page", interceptor = interceptor).document
        
        // Mencari elemen kartu film. (Tebakan class umum: div.item, article, atau .mlw)
        // Jika kosong, kita perlu memeriksa struktur HTML aslinya.
        val items = document.select("div.item, article.item, .mlw, div.bs").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Mencari judul (biasanya di h2, h3, atau class .title)
        val title = this.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim() ?: return null
        
        // Mencari link (href)
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Mencari gambar poster (kadang menggunakan data-src untuk lazy load)
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: imgElement?.attr("src")
        
        // Menentukan tipe (Movie atau Series) berdasarkan URL
        val type = if (href.contains("/series/") || href.contains("/tv/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // IDLIX biasanya menggunakan parameter ?q= atau ?s= untuk pencarian
        val document = app.get("$mainUrl/search?q=$query", interceptor = interceptor).document
        return document.select("div.item, article.item, .mlw, div.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).document
        
        val title = document.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img, .thumb img, img.wp-post-image")?.attr("src")
        val plot = document.selectFirst(".description, .synopsis, .entry-content p, div[itemprop='description']")?.text()?.trim()
        val year = document.selectFirst(".year, a[href*='/release/']")?.text()?.toIntOrNull()
        val isSeries = url.contains("/series/") || url.contains("/tv/")

        if (isSeries) {
            // Scraping daftar episode untuk Series
            val episodes = document.select("div.eplister ul li a, .episode-list a, ul#episode_by_num a").mapNotNull { ep ->
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
            // Untuk Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        
        // Mencari iframe server video
        document.select("iframe, .server a, .mirror a").forEach { element ->
            val iframeUrl = if (element.tagName() == "iframe") element.attr("src") else element.attr("href")
            if (iframeUrl.isNotEmpty()) {
                // Ekstrak link video
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
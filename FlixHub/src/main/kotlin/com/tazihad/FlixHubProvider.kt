package com.tazihad

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class FlixHubSearchResponse(
    @param:JsonProperty("results") val results: List<FlixHubSearchResult>? = null
)

data class FlixHubSearchResult(
    @param:JsonProperty("tmdb_id") val tmdbId: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("year") val year: String? = null,
    @param:JsonProperty("rating") val rating: String? = null,
    @param:JsonProperty("poster") val poster: String? = null,
    @param:JsonProperty("is_available") val isAvailable: Boolean? = null,
    @param:JsonProperty("watch_url") val watchUrl: String? = null
)

data class FlixHubListingResponse(
    @param:JsonProperty("html") val html: String? = null,
    @param:JsonProperty("has_more") val hasMore: Boolean? = null,
    @param:JsonProperty("current_page") val currentPage: Int? = null,
    @param:JsonProperty("next_page") val nextPage: Int? = null,
    @param:JsonProperty("total") val total: Int? = null
)

class FlixHubProvider : MainAPI() {
    override var mainUrl = "https://flixhub.net"
    override var name = "FlixHub"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "featured" to "Featured Spotlight",
        "section:Trending Now" to "Trending Now",
        "section:Recent Uploads" to "Recent Uploads",
        "section:Latest TV Series" to "Latest TV Series",
        "section:Hollywood Movies" to "Hollywood Movies",
        "section:South Indian Movies" to "South Indian Movies",
        "section:Bollywood Movies" to "Bollywood Movies",
        "section:Kids Movies" to "Kids Movies",
        "tv-shows" to "All TV Series",
        "movies?category=hollywood" to "Browse Hollywood",
        "movies?category=bollywood" to "Browse Bollywood",
        "movies?category=south-indian" to "Browse South Indian",
        "movies?category=Action" to "Browse Action",
        "movies?category=Adventure" to "Browse Adventure",
        "movies?category=Animation" to "Browse Animation",
        "movies?category=Comedy" to "Browse Comedy",
        "movies?category=Crime" to "Browse Crime",
        "movies?category=Drama" to "Browse Drama",
        "movies?category=Horror" to "Browse Horror",
        "movies?category=Sci-Fi" to "Browse Sci-Fi",
        "movies?category=Thriller" to "Browse Thriller"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // If element is an <article>, extract watch URL from data-watch-url or internal link
        val rawHref = if (tagName().equals("article", ignoreCase = true)) {
            attr("data-watch-url").ifBlank {
                selectFirst("a[href*='/watch/']")?.attr("href") ?: ""
            }
        } else {
            attr("href").ifBlank {
                attr("data-watch-url").ifBlank {
                    selectFirst("a[href*='/watch/']")?.attr("href") ?: ""
                }
            }
        }

        if (rawHref.isBlank()) return null
        val fullHref = fixUrl(rawHref) ?: return null

        val isTv = fullHref.contains("/watch/series/") || attr("data-card-type").equals("series", ignoreCase = true)
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        val imgEl = selectFirst("img")
        var title = selectFirst(".movie-card-browse-title a, .movie-card-browse-title, .movie-title a, .movie-title, h3 a, h3, h4")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = selectFirst("a.movie-card-poster-link")?.attr("aria-label")?.trim()
        }
        if (title.isNullOrBlank()) {
            title = attr("aria-label").trim()
        }
        if (title.isNullOrBlank()) {
            title = imgEl?.attr("alt")?.trim()
        }
        if (title.isNullOrBlank()) return null

        val posterUrl = fixUrl(imgEl?.attr("src"))

        val yearText = selectFirst(".movie-card-meta span, .movie-card-meta, .badge-year")?.text() ?: text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val ratingText = selectFirst(".badge-rating, .rating, .score")?.text()
        val rating = ratingText?.let { Regex("""(\d+\.?\d*)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

        return if (isTv) {
            newTvSeriesSearchResponse(title, fullHref, type) {
                this.posterUrl = posterUrl
                if (rating != null) this.score = Score.from10(rating)
                if (year != null) this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullHref, type) {
                this.posterUrl = posterUrl
                if (rating != null) this.score = Score.from10(rating)
                if (year != null) this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when {
            request.data == "featured" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                val doc = app.get(mainUrl, headers = defaultHeaders).document
                val slides = doc.select(".hero-slide")
                val results = slides.mapNotNull { slide ->
                    val watchHref = slide.selectFirst(".hero-slide-actions a")?.attr("href")?.let(::fixUrl)
                        ?: return@mapNotNull null
                    val isTv = watchHref.contains("/watch/series/")
                    val type = if (isTv) TvType.TvSeries else TvType.Movie
                    val poster = slide.attr("data-hero-poster").ifBlank {
                        slide.selectFirst("img.hero-slide-bg")?.attr("src")
                    }?.let(::fixUrl)
                    val title = slide.selectFirst(".hero-title-logo")?.attr("alt")?.trim()
                        ?: slide.selectFirst("h1, h2, h3")?.text()?.trim()
                        ?: return@mapNotNull null

                    val rating = slide.selectFirst(".hero-badge-rating span")?.text()?.trim()?.toFloatOrNull()

                    if (isTv) {
                        newTvSeriesSearchResponse(title, watchHref, type) {
                            this.posterUrl = poster
                            if (rating != null) this.score = Score.from10(rating)
                        }
                    } else {
                        newMovieSearchResponse(title, watchHref, type) {
                            this.posterUrl = poster
                            if (rating != null) this.score = Score.from10(rating)
                        }
                    }
                }
                newHomePageResponse(request.name, results)
            }
            request.data.startsWith("section:") -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                val sectionName = request.data.removePrefix("section:").trim()
                val doc = app.get(mainUrl, headers = defaultHeaders).document

                // Locate specific section by its h2.section-title-custom
                val targetSection = doc.select("section.content-section-custom").firstOrNull { sec ->
                    sec.selectFirst("h2.section-title-custom")?.text()?.contains(sectionName, ignoreCase = true) == true
                }

                val articles = targetSection?.select("article.movie-card-final") ?: emptyList()
                val seen = mutableSetOf<String>()
                val results = articles.mapNotNull { art ->
                    val res = art.toSearchResponse() ?: return@mapNotNull null
                    if (!seen.add(res.url)) return@mapNotNull null
                    res
                }
                newHomePageResponse(request.name, results)
            }
            else -> {
                val url = if (request.data.contains("?")) {
                    "$mainUrl/${request.data}&page=$page"
                } else {
                    "$mainUrl/${request.data}?page=$page"
                }

                // If page > 1, try the AJAX JSON pagination endpoint first
                val results = if (page > 1) {
                    try {
                        val res = app.get(
                            url,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json",
                                "User-Agent" to defaultHeaders["User-Agent"]!!,
                                "Referer" to "$mainUrl/"
                            )
                        )
                        val listing = parseJson<FlixHubListingResponse>(res.text)
                        val fragment = org.jsoup.Jsoup.parseBodyFragment(listing.html ?: "")
                        val seen = mutableSetOf<String>()
                        fragment.select("article.movie-card-final").mapNotNull { art ->
                            val parsed = art.toSearchResponse() ?: return@mapNotNull null
                            if (!seen.add(parsed.url)) return@mapNotNull null
                            parsed
                        }
                    } catch (_: Throwable) {
                        // Fallback to regular HTML
                        val doc = app.get(url, headers = defaultHeaders).document
                        val seen = mutableSetOf<String>()
                        doc.select("article.movie-card-final").mapNotNull { art ->
                            val parsed = art.toSearchResponse() ?: return@mapNotNull null
                            if (!seen.add(parsed.url)) return@mapNotNull null
                            parsed
                        }
                    }
                } else {
                    val doc = app.get(url, headers = defaultHeaders).document
                    val seen = mutableSetOf<String>()
                    doc.select("article.movie-card-final").mapNotNull { art ->
                        val parsed = art.toSearchResponse() ?: return@mapNotNull null
                        if (!seen.add(parsed.url)) return@mapNotNull null
                        parsed
                    }
                }

                newHomePageResponse(request.name, results)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/suggestions?q=${URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(
            searchUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
                "User-Agent" to defaultHeaders["User-Agent"]!!,
                "Referer" to "$mainUrl/"
            )
        )
        val data = parseJson<FlixHubSearchResponse>(res.text)
        val items = data.results?.filter { it.isAvailable == true && !it.watchUrl.isNullOrBlank() } ?: return emptyList()

        return items.mapNotNull { item ->
            val title = item.title?.trim() ?: return@mapNotNull null
            val watchUrl = item.watchUrl?.let(::fixUrl) ?: return@mapNotNull null
            val isTv = item.type?.equals("TV Series", ignoreCase = true) == true || watchUrl.contains("/watch/series/")
            val type = if (isTv) TvType.TvSeries else TvType.Movie
            val poster = item.poster?.let(::fixUrl)
            val year = item.year?.toIntOrNull()
            val rating = item.rating?.toFloatOrNull()

            if (isTv) {
                newTvSeriesSearchResponse(title, watchUrl, type) {
                    this.posterUrl = poster
                    if (rating != null) this.score = Score.from10(rating)
                    if (year != null) this.year = year
                }
            } else {
                newMovieSearchResponse(title, watchUrl, type) {
                    this.posterUrl = poster
                    if (rating != null) this.score = Score.from10(rating)
                    if (year != null) this.year = year
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = fixUrl(url) ?: return null
        val doc = app.get(fullUrl, headers = defaultHeaders).document

        return if (fullUrl.contains("/watch/series/")) {
            loadTvSeries(fullUrl, doc)
        } else {
            loadMovie(fullUrl, doc)
        }
    }

    private fun parseCast(doc: Document): List<ActorData> {
        return doc.select(".player-cast-card").mapNotNull { card ->
            val name = card.selectFirst(".player-cast-name")?.text()?.trim() ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            val role = card.selectFirst(".player-cast-character")?.text()?.trim()?.ifBlank { null }
            val photo = card.selectFirst(".player-cast-photo img")?.attr("src")?.let(::fixUrl)
            ActorData(
                actor = Actor(name, photo),
                roleString = role
            )
        }
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        var title = doc.selectFirst("h1.player-movie-details-title, h1.movie-title, h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("title")?.text()?.substringBefore("—")?.trim() ?: "Movie"
        }

        val yearText = doc.selectFirst(".player-movie-badge, .search-result-year, .movie-card-meta")?.text() ?: doc.text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst(".player-movie-details-poster img")?.attr("src")?.let(::fixUrl)
            ?: doc.selectFirst("video#player")?.attr("poster")?.let(::fixUrl)

        val backdrop = doc.selectFirst("video#player")?.attr("poster")?.let(::fixUrl)

        val plot = doc.selectFirst("#playerMobileDescText, .player-mobile-synopsis, .player-movie-details-desc")?.text()?.trim()

        val ratingText = doc.selectFirst(".player-movie-rating-num")?.text()?.trim()
        val rating = ratingText?.toFloatOrNull()

        val tags = doc.select(".player-movie-genres a, .movie-card-meta span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("•") && !it.matches(Regex("\\d{4}")) }
            .distinct()

        val actors = parseCast(doc)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    private suspend fun loadTvSeries(url: String, doc: Document): LoadResponse {
        var title = doc.selectFirst("h1.player-movie-details-title, h1.movie-title, h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("title")?.text()?.substringBefore("—")?.trim() ?: "TV Series"
        }

        val yearText = doc.selectFirst(".player-movie-badge, .search-result-year, .movie-card-meta")?.text() ?: doc.text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst(".player-movie-details-poster img")?.attr("src")?.let(::fixUrl)
            ?: doc.selectFirst("video#player")?.attr("poster")?.let(::fixUrl)

        val backdrop = doc.selectFirst("video#player")?.attr("poster")?.let(::fixUrl)

        val plot = doc.selectFirst("#playerMobileDescText, .player-mobile-synopsis, .player-movie-details-desc")?.text()?.trim()

        val ratingText = doc.selectFirst(".player-movie-rating-num")?.text()?.trim()
        val rating = ratingText?.toFloatOrNull()

        val tags = doc.select(".player-movie-genres a, .movie-card-meta span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("•") && !it.matches(Regex("\\d{4}")) }
            .distinct()

        val actors = parseCast(doc)

        val episodeCards = doc.select("article[data-season-number]")
        val episodes = episodeCards.mapNotNull { epEl ->
            val season = epEl.attr("data-season-number").toIntOrNull() ?: 1
            val link = epEl.selectFirst("a.player-sidebar-card-link")?.attr("href")?.let(::fixUrl)
                ?: return@mapNotNull null
            val img = epEl.selectFirst("img")?.attr("src")?.let(::fixUrl)
            val epTitle = epEl.selectFirst(".player-sidebar-card-title")?.text()?.trim()
                ?: "Episode"
            val epDesc = epEl.selectFirst(".player-sidebar-card-desc")?.text()?.trim()

            val epNumMatch = Regex("""(?:Episode|E)\s*(\d+)""").find(epEl.html())
            val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(link) {
                this.name = epTitle
                this.season = season
                this.episode = epNum
                this.posterUrl = img
                this.description = epDesc
            }
        }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = when {
            data.contains("/watch/movie/") -> {
                val slug = data.substringAfter("/watch/movie/").substringBefore("?").trim()
                "$mainUrl/stream/movie/$slug"
            }
            data.contains("/watch/episode/") -> {
                val slug = data.substringAfter("/watch/episode/").substringBefore("?").trim()
                "$mainUrl/stream/episode/$slug"
            }
            else -> data
        }

        var cookieHeader = ""
        try {
            val res = app.get(data, headers = defaultHeaders)
            cookieHeader = res.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val doc = res.document
            // Extract subtitles from <track>
            doc.select("track[src]").forEach { track ->
                val label = track.attr("label").takeIf { it.isNotBlank() } ?: "English"
                val src = fixUrl(track.attr("src"))
                if (!src.isNullOrBlank()) {
                    subtitleCallback.invoke(SubtitleFile(label, src))
                }
            }
        } catch (_: Exception) {
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.headers = buildMap {
                    put("Referer", data)
                    put("User-Agent", defaultHeaders["User-Agent"]!!)
                    if (cookieHeader.isNotBlank()) {
                        put("Cookie", cookieHeader)
                    }
                }
            }
        )

        return true
    }
}

package com.tazihad

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CinePlexProvider : MainAPI() {
    override var mainUrl = "http://cineplexbd.net"
    override var name = "CinePlex"
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
        "trending" to "Weekly Top 20 Trending",
        "top_watch.php" to "Top Watched",
        "category.php?category=English" to "English Movies",
        "category.php?category=Hindi" to "Hindi Movies",
        "category.php?category=Bangla+Movies" to "Bangla Movies",
        "tcategory.php?category=English+Series" to "English TV Series",
        "tcategory.php?category=Hindi+Series" to "Hindi TV Series",
        "tcategory.php?category=Bangla+Series" to "Bangla TV Series",
        "tcategory.php?category=Korean+Series" to "Korean Series",
        "category.php?category=Dual+Audio" to "Dual Audio Movies",
        "category.php?category=Animation" to "Animation Movies",
        "category.php?category=Anime" to "Anime Movies",
        "category.php?category=4K+Movies" to "4K Movies",
        "category.php?category=Korean" to "Korean Movies",
        "category.php?category=Indian+Bangla" to "Indian Bangla Movies",
        "tcategory.php?category=Animation+Series" to "Animation Series",
        "tcategory.php?category=Japanese+Series" to "Japanese Series",
        "tcategory.php?category=Web+Series" to "Web Series",
        "category.php?category=Foreign" to "Foreign Movies",
        "category.php?category=Documentary" to "Documentaries",
        "tcategory.php?category=WWE" to "WWE"
    )

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cleanUrl = url.trim()
        return when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }

    private fun fixItemUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cleanUrl = url.trim()
        return when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val rawHref = attr("href")
        if (rawHref.isBlank()) return null
        val fullHref = fixItemUrl(rawHref) ?: return null

        val isTv = fullHref.contains("watch.php")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        val imgEl = selectFirst("img")
        var title = imgEl?.attr("alt")?.trim()
        if (title.isNullOrBlank()) {
            title = selectFirst("p.truncate, span.truncate, h2, h3, h4")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = attr("title").trim()
        }
        if (title.isBlank()) return null

        val posterUrl = fixImageUrl(imgEl?.attr("src"))

        val rating = selectFirst("span.text-yellow-400, span.meta-rating")?.text()
            ?.trim()?.toFloatOrNull()

        val yearText = selectFirst("span.bg-amber-500\\/20, span.catLabel ~ span")?.text() ?: text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

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
        val targetUrl = when {
            request.data == "trending" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                "$mainUrl/index.php"
            }
            request.data == "top_watch.php" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                "$mainUrl/top_watch.php"
            }
            request.data.contains("?") -> "$mainUrl/${request.data}&page=$page"
            else -> "$mainUrl/${request.data}?page=$page"
        }

        val doc = app.get(targetUrl, headers = defaultHeaders).document

        val cards = if (request.data == "trending") {
            val carouselItems = doc.select("#carouselTrack a[href*='view.php'], #carouselTrack a[href*='watch.php']")
            if (carouselItems.isNotEmpty()) carouselItems else doc.select("#movieGrid a[href*='view.php'], #movieGrid a[href*='watch.php']")
        } else {
            doc.select("a[href*='view.php?id='], a[href*='watch.php?']")
        }

        val seen = mutableSetOf<String>()
        val results = cards.mapNotNull { el ->
            val href = el.attr("href")
            val baseHref = href.substringBefore("&h=")
            if (!seen.add(baseHref)) return@mapNotNull null
            el.toSearchResponse()
        }

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl, headers = defaultHeaders).document

        val cards = doc.select("a[href*='view.php?id='], a[href*='watch.php?']")
        val seen = mutableSetOf<String>()
        return cards.mapNotNull { el ->
            val href = el.attr("href")
            val baseHref = href.substringBefore("&h=")
            if (!seen.add(baseHref)) return@mapNotNull null
            el.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = fixItemUrl(url) ?: return null

        return if (fullUrl.contains("watch.php")) {
            loadTvSeries(fullUrl)
        } else {
            loadMovie(fullUrl)
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders).document
        val id = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1) ?: return null

        var title = doc.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("title")?.text()?.substringBefore("(")?.trim() ?: "Movie"
        }

        val year = Regex("""\((\d{4})\)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst("img.poster")?.attr("src")?.let(::fixImageUrl)

        val backdrop = Regex("""background(?:-image)?:\s*url\(['"]?([^'")]+)['"]?\)""")
            .find(doc.html())?.groupValues?.get(1)?.let(::fixImageUrl)

        val plot = doc.selectFirst("p.text-slate-100")?.text()?.trim()
            ?: doc.selectFirst("#synopsis")?.text()?.trim()

        val rating = Regex("""([0-9]+\.?[0-9]*)""").find(
            doc.selectFirst(".pill, span.text-yellow-400")?.text() ?: ""
        )?.groupValues?.get(1)?.toFloatOrNull()

        val tags = doc.select(".chip").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = doc.select("#castSlider a").mapNotNull { a ->
            val aName = a.selectFirst(".text-xs, .font-bold")?.text()?.trim()
            if (aName.isNullOrBlank()) return@mapNotNull null
            val aImg = a.selectFirst("img")?.attr("src")?.let(::fixImageUrl)
            val aRole = a.selectFirst(".text-sky-400")?.text()?.trim()
            ActorData(Actor(aName, aImg), roleString = aRole)
        }

        val playerUrl = "$mainUrl/player.php?id=$id"

        return newMovieLoadResponse(title, playerUrl, TvType.Movie, playerUrl) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    private suspend fun loadTvSeries(url: String): LoadResponse? {
        val seriesId = Regex("""(?:series_id|id)=(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val canonicalUrl = "$mainUrl/watch.php?id=$seriesId"

        val doc = app.get(canonicalUrl, headers = defaultHeaders).document

        var title = doc.selectFirst("title")?.text()?.substringBefore("—")?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("h1, h2")?.text()?.trim() ?: "TV Series"
        }

        val year = Regex("""\((\d{4})\)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val poster = doc.selectFirst("img[alt='Poster'], img.poster")?.attr("src")?.let(::fixImageUrl)

        val backdrop = Regex("""background(?:-image)?:\s*url\(['"]?([^'")]+)['"]?\)""")
            .find(doc.html())?.groupValues?.get(1)?.let(::fixImageUrl)

        val plot = doc.selectFirst("#synopsis")?.text()?.trim()

        val rating = Regex("""([0-9]+\.?[0-9]*)""").find(
            doc.selectFirst("span.text-yellow-400, .pill")?.text() ?: ""
        )?.groupValues?.get(1)?.toFloatOrNull()

        val tags = doc.select(".meta-cat, .meta-badge").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Season options
        val seasonOptions = doc.select("select[name=season] option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .distinct()

        val baseSeason = if (seasonOptions.isNotEmpty()) seasonOptions.first() else 1
        val initialEpisodes = parseEpisodes(doc, baseSeason, seriesId)

        // Fetch remaining seasons concurrently if multiple seasons exist
        val otherEpisodes = coroutineScope {
            seasonOptions.filter { it != baseSeason }.map { sNum ->
                async {
                    try {
                        val sDoc = app.get("$mainUrl/watch.php?id=$seriesId&season=$sNum", headers = defaultHeaders).document
                        parseEpisodes(sDoc, sNum, seriesId)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val allEpisodes = (initialEpisodes + otherEpisodes).sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
        )

        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    private fun parseEpisodes(doc: Document, seasonNum: Int, seriesId: String): List<Episode> {
        val epElements = doc.select("a.ep-card, a[href*='watch.php?id='][href*='ep=']")
        return epElements.mapNotNull { a ->
            val href = a.attr("href")
            val epNumStr = a.attr("data-ep").takeIf { it.isNotBlank() }
                ?: Regex("""ep=(\d+)""").find(href)?.groupValues?.get(1)
                ?: a.selectFirst(".ep-num")?.text()?.replace(Regex("[^0-9]"), "")
            val epNum = epNumStr?.toIntOrNull() ?: return@mapNotNull null

            val epTitle = a.selectFirst(".truncate")?.text()?.trim()
                ?.ifBlank { "Episode $epNum" } ?: "Episode $epNum"
            val epPoster = a.selectFirst("img")?.attr("src")?.let(::fixImageUrl)
            val epUrl = "$mainUrl/watch.php?id=$seriesId&season=$seasonNum&ep=$epNum&autoplay=1"

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = epPoster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.contains("watch.php")) {
            loadSeriesLinks(data, subtitleCallback, callback)
        } else {
            loadMovieLinks(data, subtitleCallback, callback)
        }
    }

    private suspend fun loadMovieLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""id=(\d+)""").find(data)?.groupValues?.get(1) ?: return false
        val playerUrl = "$mainUrl/player.php?id=$id"
        val response = app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/view.php?id=$id"))
        val html = response.text

        val videoSrc = Regex("""const\s+videoSrc\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return false

        val fullUrl = fixImageUrl(videoSrc) ?: return false
        val isM3u8 = fullUrl.contains(".m3u8")

        val cookieHeader = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = fullUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = playerUrl
                this.headers = buildMap {
                    put("Referer", playerUrl)
                    put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    if (cookieHeader.isNotBlank()) {
                        put("Cookie", cookieHeader)
                    }
                }
            }
        )

        // Extract subtitles if present
        val subTrack = Regex("""<track[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!subTrack.isNullOrBlank()) {
            val subUrl = fixImageUrl(subTrack) ?: subTrack
            subtitleCallback.invoke(SubtitleFile("English", subUrl))
        }

        return true
    }

    private suspend fun loadSeriesLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = mapOf("Referer" to "$mainUrl/"))
        val html = response.text

        val cookieHeader = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val sourceSrc = Regex("""<source\s+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""const\s+videoSrc\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return false

        val fullUrl = fixImageUrl(sourceSrc) ?: return false
        val isM3u8 = fullUrl.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = fullUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.headers = buildMap {
                    put("Referer", data)
                    put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    if (cookieHeader.isNotBlank()) {
                        put("Cookie", cookieHeader)
                    }
                }
            }
        )

        val subTrack = Regex("""<track[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!subTrack.isNullOrBlank()) {
            val subUrl = fixImageUrl(subTrack) ?: subTrack
            subtitleCallback.invoke(SubtitleFile("English", subUrl))
        }

        return true
    }
}

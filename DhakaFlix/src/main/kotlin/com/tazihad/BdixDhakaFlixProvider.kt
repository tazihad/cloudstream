package com.tazihad

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections

open class BdixDhakaFlixProvider : MainAPI() {
    override var mainUrl = "http://172.16.50.7"
    override var name = "DhakaFlix"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    protected val year: Int = Calendar.getInstance().get(Calendar.YEAR)

    // Placeholder used in year-based main page sections. It is resolved to the
    // current year at load time and pagination automatically falls back to
    // previous years (year - 1, year - 2, ...) until content is exhausted.
    private val yearPlaceholder = "__YEAR__"

    // Stop stepping back through older years once we reach this floor.
    private val yearFallbackMinYear = 1960

    protected data class LocalServer(
        val id: String,
        val url: String,
        val serverName: String,
        val tvSeriesKeyword: List<String>
    )

    // Lightweight entry parsed from a folder listing row (name + relative href).
    private data class RowEntry(val name: String, val href: String)

    protected open val servers = listOf(
        LocalServer("7", "http://172.16.50.7", "DHAKA-FLIX-7", emptyList()),
        LocalServer("9", "http://172.16.50.9", "DHAKA-FLIX-9", listOf("Awards", "WWE", "KOREAN", "Documentary", "Anime")),
        LocalServer("12", "http://172.16.50.12", "DHAKA-FLIX-12", listOf("TV-WEB-Series")),
        LocalServer("14", "http://172.16.50.14", "DHAKA-FLIX-14", listOf("KOREAN%20TV%20%26%20WEB%20Series"))
    )

    private val animeKeyword = listOf("Anime%20%26%20Cartoon%20TV%20Series")

    private val combinedTvSeriesKey = "__tv_series_all__"
    private val combinedTvSeriesPaths = listOf(
        "TV-WEB-Series/TV Series ★%20 0%20 —%20 9/",
        "TV-WEB-Series/TV Series ♥%20 A%20 —%20 L/",
        "TV-WEB-Series/TV Series ♦%20 M%20 —%20 R/",
        "TV-WEB-Series/TV Series ♦%20 S%20 —%20 Z/"
    )

    private fun serverById(id: String): LocalServer =
        servers.firstOrNull { it.id == id } ?: servers.last()

    private fun serverForUrl(url: String): LocalServer =
        servers.firstOrNull { url == it.url || url.startsWith("${it.url}/") } ?: servers.last()

    private fun resolveServer(data: String): Pair<LocalServer, String> {
        val separator = data.indexOf('|')
        return if (separator > 0) {
            serverById(data.substring(0, separator)) to data.substring(separator + 1)
        } else {
            servers.last() to data
        }
    }

    init {
        // Clear provider cache on initialization
        getProviderCache(name).clearCache()
    }

    // Clear cache when provider is destroyed
    protected fun finalize() {
        getProviderCache(name).clearCache()
    }
    companion object {
        private val caches = mutableMapOf<String, ProviderCache>()
        private const val POSTER_CACHE_DURATION = 1 * 60 * 60 * 1000L // 1 hour (reduced from 2)
        private const val SEARCH_CACHE_DURATION = 30 * 60 * 1000L // 30 minutes (reduced from 1 hour)  
        private const val TMDB_CACHE_DURATION = 15 * 60 * 1000L // 15 minutes (reduced from 30)
        private const val YEAR_ROWS_CACHE_DURATION = 30 * 60 * 1000L // 30 minutes for per-year listing rows
        private const val BATCH_SIZE = 6 // Increased for better performance
        
        @Synchronized
        private fun getProviderCache(providerName: String): ProviderCache {
            return caches.getOrPut(providerName) { ProviderCache() }
        }
    }

    private class ProviderCache {
        private val posterCache = Collections.synchronizedMap(mutableMapOf<String, Pair<String?, Long>>())
        private val searchCache = Collections.synchronizedMap(mutableMapOf<String, Pair<TmdbSearchResult?, Long>>())
        private val tmdbDetailsCache = Collections.synchronizedMap(mutableMapOf<String, Pair<TmdbDetails?, Long>>())
        private val seasonDetailsCache = Collections.synchronizedMap(mutableMapOf<String, Pair<TmdbSeasonDetails?, Long>>())
        private val bulkSeasonCache = Collections.synchronizedMap(mutableMapOf<String, Pair<Map<Int, TmdbSeasonDetails?>, Long>>())
        private val yearRowsCache = Collections.synchronizedMap(mutableMapOf<String, Pair<List<RowEntry>?, Long>>())

        fun getPosterCache(): MutableMap<String, Pair<String?, Long>> = posterCache
        fun getSearchCache(): MutableMap<String, Pair<TmdbSearchResult?, Long>> = searchCache
        fun getTmdbDetailsCache(): MutableMap<String, Pair<TmdbDetails?, Long>> = tmdbDetailsCache
        fun getSeasonDetailsCache(): MutableMap<String, Pair<TmdbSeasonDetails?, Long>> = seasonDetailsCache
        fun getBulkSeasonCache(): MutableMap<String, Pair<Map<Int, TmdbSeasonDetails?>, Long>> = bulkSeasonCache
        fun getYearRowsCache(): MutableMap<String, Pair<List<RowEntry>?, Long>> = yearRowsCache

        fun <T> getFromCache(
            cache: MutableMap<String, Pair<T?, Long>>,
            key: String,
            duration: Long
        ): T? {
            val (value, timestamp) = cache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < duration) value else null
        }        fun <T> addToCache(
            cache: MutableMap<String, Pair<T?, Long>>,
            key: String,
            value: T?
        ) {
            synchronized(cache) {
                // More aggressive cache cleanup for better performance
                if (cache.size > 50) { // Reduced from 100
                    val currentTime = System.currentTimeMillis()
                    // Remove expired entries
                    val iterator = cache.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if ((currentTime - entry.value.second) > TMDB_CACHE_DURATION) {
                            iterator.remove()
                        }
                    }
                    // If still too large, remove oldest entries
                    if (cache.size > 25) { // Reduced from 50
                        val sortedEntries = cache.entries.sortedBy { it.value.second }
                        sortedEntries.take(sortedEntries.size - 25).forEach { cache.remove(it.key) }
                    }
                }
                cache[key] = Pair(value, System.currentTimeMillis())
            }
        }

        fun clearCache() {
            synchronized(this) {
                getPosterCache().clear()
                getSearchCache().clear()
                getTmdbDetailsCache().clear()
                getSeasonDetailsCache().clear()
                getBulkSeasonCache().clear()
                getYearRowsCache().clear()
            }
        }
    }

    override val mainPage = mainPageOf(
        // English 720p
        "7|English Movies/($yearPlaceholder)/" to "English Movies (720p)",
        // English 1080p
        "14|English Movies (1080p)/($yearPlaceholder) 1080p/" to "English Movies (1080p)",
        // TV Series (all letter ranges combined)
        "12|__tv_series_all__" to "TV Series",
        // Anime
        "9|Anime %26 Cartoon TV Series/Anime-TV Series ♥%20 A%20 —%20 F/" to "Anime TV Series",
        "14|Animation Movies (1080p)/" to "Anime Movies",
        // Movies
        "14|Hindi Movies/($yearPlaceholder)/" to "Hindi Movies",
        "14|SOUTH INDIAN MOVIES/Hindi Dubbed/($yearPlaceholder)/" to "South Movies",
        "14|/KOREAN TV %26 WEB Series/" to "Korean TV & WEB Series",
        // Server 7 movies
        "7|Foreign Language Movies/Japanese Language/" to "Japanese Movies",
        "7|Foreign Language Movies/Korean Language/" to "Korean Movies",
        "7|Foreign Language Movies/Bangla Dubbing Movies/" to "Bangla Dubbing Movies",
        "7|Foreign Language Movies/Pakistani Movie/" to "Pakistani Movies",
        "7|Kolkata Bangla Movies/($yearPlaceholder)/" to "Kolkata Bangla Movies",
        "7|Foreign Language Movies/Chinese Language/" to "Chinese Movies",
        // Server 9 extras
        "9|Documentary/" to "Documentary",
        "9|Awards %26 TV Shows/%23 TV SPECIAL %26 SHOWS/" to "TV SPECIAL & SHOWS",
        "9|Awards %26 TV Shows/%23 AWARDS/" to "Awards",
        "9|WWE %26 AEW Wrestling/WWE Wrestling/%28$yearPlaceholder%29%20PPV/" to "WWE PPV",
        "9|WWE %26 AEW Wrestling/WWE Wrestling/" to "WWE",
        "9|WWE %26 AEW Wrestling/AEW Wrestling/" to "AEW"
    )

    // Number of items to load per page
    private val itemsPerPage = 12

    // Implement lazy loading for TMDB data
    private suspend fun lazyLoadTmdbData(
        name: String,
        isMovie: Boolean,
        loadDetails: Boolean = false
    ): TmdbSearchResult? = coroutineScope {
        val cleanName = cleanNameForSearch(name)
        val cacheKey = "$cleanName:$isMovie"
        val providerCache = getProviderCache(name)
        
        // First try to get from cache
        providerCache.getFromCache(providerCache.getSearchCache(), cacheKey, SEARCH_CACHE_DURATION)?.let { 
            return@coroutineScope it 
        }
        
        // If not in cache and we don't need details immediately, launch async
        if (!loadDetails) {
            launch {
                TmdbHelper.searchTmdb(cleanName, isMovie)?.let { result ->
                    providerCache.addToCache(providerCache.getSearchCache(), cacheKey, result)
                }
            }
            return@coroutineScope null
        }
        
        // If we need details now, do the search
        val result = TmdbHelper.searchTmdb(cleanName, isMovie)
        providerCache.addToCache(providerCache.getSearchCache(), cacheKey, result)
        return@coroutineScope result
    }

    // Bulk load all TMDB data for a TV series to minimize API calls
    private suspend fun bulkLoadTvSeriesData(
        tmdbId: Int,
        seasonNumbers: List<Int>
    ): Map<Int, TmdbSeasonDetails?> = coroutineScope {
        val providerCache = getProviderCache(name)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}"
        
        // First check cache
        val cachedData = providerCache.getBulkSeasonCache()[cacheKey]
        if (cachedData != null && (System.currentTimeMillis() - cachedData.second) < TMDB_CACHE_DURATION) {
            return@coroutineScope cachedData.first
        }
        
        // Load all season data in one optimized call
        val seasonData = TmdbHelper.getAllSeasonDetails(tmdbId, seasonNumbers)
        
        // Cache the bulk data
        providerCache.getBulkSeasonCache()[cacheKey] = Pair(seasonData, System.currentTimeMillis())
        
        // Also cache individual seasons for faster access
        seasonData.forEach { (seasonNum, seasonDetails) ->
            val individualCacheKey = "$tmdbId:$seasonNum"
            providerCache.getSeasonDetailsCache()[individualCacheKey] = Pair(seasonDetails, System.currentTimeMillis())
        }
        
        return@coroutineScope seasonData
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val (server, path) = resolveServer(request.data)
        val isWrestlingSection = isWrestling(path)

        // Year-based sections start at the current year and automatically fall
        // back to previous years (year - 1, year - 2, ...) when the current
        // year's content is exhausted.
        if (path.contains(yearPlaceholder)) {
            return@coroutineScope getYearFallbackHomePage(page, request, server, path, isWrestlingSection)
        }

        // Combined sections merge multiple sub-folders into one listing
        val rows = if (path == combinedTvSeriesKey) {
            combinedTvSeriesPaths.flatMap { subPath ->
                app.get("${server.url}/${server.serverName}/$subPath").document.select("tbody > tr").drop(2)
            }
        } else {
            app.get("${server.url}/${server.serverName}/$path").document.select("tbody > tr").drop(2)
        }
        val totalItems = rows.size
        
        // Ensure page is at least 1
        val safePage = maxOf(1, page)
        val startIndex = ((safePage - 1) * itemsPerPage).coerceAtLeast(0)
        val endIndex = (startIndex + itemsPerPage).coerceAtLeast(startIndex)
        
        // Ensure we don't exceed the available items
        val safeStartIndex = startIndex.coerceAtMost(totalItems)
        val safeEndIndex = endIndex.coerceAtMost(totalItems)
        
        val homeResponse = if (safeStartIndex < safeEndIndex) {
            rows.filterIndexed { index, _ -> index in safeStartIndex until safeEndIndex }
        } else {
            emptyList()
        }        // Process items in smaller batches - enable TMDB posters for wrestling sections, local posters otherwise
        val home = homeResponse.chunked(BATCH_SIZE).flatMap { chunk ->
            chunk.map { post ->
                async {
                    getPostResult(post, server.url, loadTmdbData = isWrestlingSection, loadLocalPosters = !isWrestlingSection)
                }
            }.awaitAll()
        }
        
        val hasNextPage = totalItems > safeEndIndex
        
        newHomePageResponse(request.name, home, hasNextPage)
    }

    // Fetch (and cache) the listing rows for a single year of a year-based section.
    // The path template is resolved by swapping the year placeholder for the
    // requested year. Missing/empty year folders return an empty list.
    private suspend fun getYearRows(
        server: LocalServer,
        pathTemplate: String,
        targetYear: Int
    ): List<RowEntry> {
        val providerCache = getProviderCache(name)
        val key = "${server.id}|$pathTemplate|$targetYear"
        providerCache.getFromCache(providerCache.getYearRowsCache(), key, YEAR_ROWS_CACHE_DURATION)?.let { return it }

        val path = pathTemplate.replace(yearPlaceholder, targetYear.toString())
        val rows = runCatching {
            app.get("${server.url}/${server.serverName}/$path").document.select("tbody > tr").drop(2)
                .mapNotNull { post ->
                    val a = post.select("td.fb-n > a")
                    val name = a.text()
                    val href = a.attr("href")
                    if (name.isBlank() || href.isBlank()) null else RowEntry(name, href)
                }
        }.getOrDefault(emptyList())

        providerCache.addToCache(providerCache.getYearRowsCache(), key, rows)
        return rows
    }

    // Home page for year-based sections. Content is served from the current year
    // first; when the current year is exhausted pagination continues with the
    // previous year, then the year before that, and so on (single, un-split section).
    private suspend fun getYearFallbackHomePage(
        page: Int,
        request: MainPageRequest,
        server: LocalServer,
        pathTemplate: String,
        isWrestlingSection: Boolean
    ): HomePageResponse = coroutineScope {
        val safePage = maxOf(1, page)
        val startIndex = (safePage - 1) * itemsPerPage
        val endIndex = startIndex + itemsPerPage

        // Accumulate rows walking backwards through the years until this page is
        // covered or there are no more year folders to read.
        val accumulated = mutableListOf<RowEntry>()
        var currentYear = year
        while (accumulated.size < endIndex && currentYear >= yearFallbackMinYear) {
            val rows = getYearRows(server, pathTemplate, currentYear)
            if (rows.isNotEmpty()) {
                accumulated.addAll(rows)
            }
            currentYear--
        }
        val ranOutOfYears = currentYear < yearFallbackMinYear

        val slice = if (startIndex < accumulated.size) {
            accumulated.subList(startIndex, minOf(endIndex, accumulated.size))
        } else {
            emptyList()
        }

        val home = slice.chunked(BATCH_SIZE).flatMap { chunk ->
            chunk.map { row ->
                async {
                    getPostResult(
                        rawName = row.name,
                        rawUrl = server.url + row.href,
                        serverUrl = server.url,
                        loadTmdbData = isWrestlingSection,
                        loadLocalPosters = !isWrestlingSection
                    )
                }
            }.awaitAll()
        }

        val hasNextPage = !ranOutOfYears || accumulated.size > endIndex

        newHomePageResponse(request.name, home, hasNextPage)
    }

    private fun cleanFolderName(name: String): String {
        return name.replace(Regex("\\d{3,4}p"), "") // Remove quality tags
            .replace(Regex("\\bHDTV\\b", RegexOption.IGNORE_CASE), "") // Remove HDTV tag
            .replace(Regex("[._]"), " ") // Replace separators with spaces
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .trim()
    }

    private fun isWrestling(url: String): Boolean {
        return url.contains("WWE") || url.contains("AEW")
    }

    private fun cleanNameForSearch(name: String): String {
        // Remove quality tags, file extensions, and brackets content
        return name.replace(Regex("\\d{3,4}p.*"), "") // Remove quality tags
            .replace(Regex("\\.(mkv|mp4|avi|mov)"), "") // Remove file extensions
            .replace(Regex("\\[.*?\\]"), "") // Remove square bracket content
            .replace(Regex("\\s*\\([^)]*TV Series[^)]*\\)"), "") // Remove TV Series with any text in parentheses
            .replace(Regex("\\s*\\([^)]*\\)"), "") // Remove any remaining parentheses content
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .trim()
    }    private suspend fun getPostResult(
        post: Element,
        serverUrl: String,
        loadTmdbData: Boolean = false,
        loadLocalPosters: Boolean = false
    ): SearchResponse {
        val folderHtml = post.select("td.fb-n > a")
        return getPostResult(
            rawName = folderHtml.text(),
            rawUrl = serverUrl + folderHtml.attr("href"),
            serverUrl = serverUrl,
            loadTmdbData = loadTmdbData,
            loadLocalPosters = loadLocalPosters
        )
    }

    private suspend fun getPostResult(
        rawName: String,
        rawUrl: String,
        serverUrl: String,
        loadTmdbData: Boolean = false,
        loadLocalPosters: Boolean = false
    ): SearchResponse {
        val url = rawUrl
        val name = if (isWrestling(url)) cleanFolderName(rawName) else cleanNameForSearch(rawName)  // Keep full folder names for wrestling
        
        // Determine content type based on URL
        val serverKeywords = serverForUrl(url).tvSeriesKeyword
        val tvType = when {
            isAnime(url) -> {
                // If it's anime, check if it's a series or movie
                if (containsAnyLoop(url, serverKeywords)) TvType.Anime else TvType.AnimeMovie
            }
            containsAnyLoop(url, serverKeywords) -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        // Only load TMDB data if specifically requested
        val tmdbData = if (loadTmdbData) {
            lazyLoadTmdbData(name, isMovie = tvType == TvType.Movie || tvType == TvType.AnimeMovie)
        } else null
        
        // Load local posters if requested (lightweight operation)
        val posterUrl = when {
            loadTmdbData -> findPosterUrl(url) // Full poster loading including TMDB fallback
            loadLocalPosters -> DhakaFlixUtils.findPosterLight(url, serverUrl) // Local posters only
            else -> null // No poster loading
        }
        
        return newAnimeSearchResponse(name, url, tvType) {
            addDubStatus(
                dubExist = hasMultiAudio(rawName),  // Check for multi audio indicators
                subExist = false
            )
            
            if (posterUrl?.isNotEmpty() == true) {
                this.posterUrl = posterUrl
            }
        }
    }    override suspend fun search(query: String): List<SearchResponse> {
        // Search across all local servers with lightweight local poster loading
        return servers.map { server ->
            DhakaFlixUtils.doSearch(
                query = query,
                mainUrl = server.url,
                serverName = server.serverName,
                api = this,
                findPosterFunc = { url -> DhakaFlixUtils.findPosterLight(url, serverForUrl(url).url) }
            )
        }.flatten()
    }

    // Use the common utility function for nameFromUrl
    protected fun nameFromUrl(href: String): String {
        return DhakaFlixUtils.nameFromUrl(href)
    }

    // Check if filename contains multi-audio indicators
    private fun hasMultiAudio(filename: String): Boolean {
        val multiAudioIndicators = listOf(
            "dual", "multi", "hindi", "english", "tamil", "telugu", "malayalam", 
            "kannada", "bengali", "urdu", "punjabi", "gujarati", "marathi",
            "audio", "dubbed", "dub", "lang", "language", "multilang"
        )
        
        val lowerFilename = filename.lowercase()
        return multiAudioIndicators.any { indicator ->
            lowerFilename.contains(indicator)
        }
    }      // Update findPosterUrl to prioritize local images over TMDB
    suspend fun findPosterUrl(contentUrl: String): String? {
        val providerCache = getProviderCache(name)
        
        // First check cache
        providerCache.getFromCache(providerCache.getPosterCache(), contentUrl, POSTER_CACHE_DURATION)?.let { 
            return it 
        }
        
        // Priority 1: Look for local images in content folder
        val localPoster = DhakaFlixUtils.findPoster(contentUrl, serverForUrl(contentUrl).url)
        if (localPoster != null) {
            providerCache.addToCache(providerCache.getPosterCache(), contentUrl, localPoster)
            return localPoster
        }
        
        // Priority 2: Try to get from TMDB only if no local poster found
        // Extract name from URL for TMDB search
        val name = contentUrl.split("/").filter { it.isNotEmpty() }.lastOrNull()
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
            ?.let { cleanNameForSearch(it) }
        
        val result = if (name != null) {
            try {
                val tmdbData = lazyLoadTmdbData(name, !containsAnyLoop(contentUrl, serverForUrl(contentUrl).tvSeriesKeyword))
                tmdbData?.posterPath?.let { TmdbHelper.getPosterUrl(it, isDetail = false) }
            } catch (e: Exception) {
                null
            }
        } else null
        
        providerCache.addToCache(providerCache.getPosterCache(), contentUrl, result)
        return result
    }

    /**
     * Helper method to create search responses for shared utility functions
     */
    open fun createSearchResponse(name: String, url: String, posterUrl: String?): SearchResponse {
        return newMovieSearchResponse(name, url, TvType.Movie) {
            if (posterUrl?.isNotEmpty() == true) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val server = serverForUrl(url)
        val doc = app.get(url).document
        var name = ""
        var imageLink = ""
        var link = ""
        var plot: String? = null
        var rating: Int? = null
        var year: Int? = null
        var tmdbId: Int? = null
        
        // Get name from URL and apply clean naming function
        name = url.split("/").filter { it.isNotEmpty() }.last()
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
            .let { cleanNameForSearch(it) }  // Apply the same cleaning logic as search and other functions
        
        // Determine if this is a TV series, anime, or movie based on URL
        val isAnimeContent = isAnime(url)
        val isTvSeries = containsAnyLoop(url, server.tvSeriesKeyword)
        
          // Load TMDB data with details since this is a detail view
        val tmdbData = lazyLoadTmdbData(name, isMovie = !(isTvSeries || isAnimeContent), loadDetails = true)
        
        // Priority 1: Try to get local poster from content folder first
        imageLink = findPosterUrl(url) ?: ""
        
        // Priority 2: If no local poster found, use TMDB poster
        if (imageLink.isEmpty()) {
            imageLink = tmdbData?.posterPath?.let { TmdbHelper.getPosterUrl(it, isDetail = true) } ?: ""
        }
        
        if (tmdbData != null) {
            tmdbId = tmdbData.id
            plot = tmdbData.overview
            rating = tmdbData.rating?.times(10)?.toInt()
            tmdbData.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()?.let {
                if (year == null) year = it
            }
        }

        // Extract year if present in the name
        if (year == null) {
            year = extractYear(name)
        }

        if (isTvSeries || isAnimeContent) {
            val imdbId = tmdbId?.let { TmdbHelper.getImdbIdFromTmdb(it, isMovie = false) }
            val episodesData = mutableListOf<Episode>()
            val seasonNumbers = mutableListOf<Int>()
            val seasonFolders = mutableListOf<Triple<String, String, Pair<Int?, String?>>>()
            
            // First pass: collect all season information without making TMDB calls
            doc.select("tbody > tr:gt(1)").forEach {
                if (it.selectFirst("td.fb-i > img")?.attr("alt") == "folder") {
                    val folderName = it.select("td.fb-n > a").text()
                    val seasonInfo = parseSeasonInfo(folderName)
                    val link = server.url + it.select("td.fb-n > a").attr("href")
                    
                    val seasonNum: Int
                    val seasonName: String?
                    
                    if (seasonInfo.first != null) {
                        seasonNum = seasonInfo.first!!
                        seasonName = null
                    } else {
                        seasonNum = 0 // Special season
                        seasonName = seasonInfo.second
                    }
                    
                    seasonNumbers.add(seasonNum)
                    seasonFolders.add(Triple(link, folderName, Pair(seasonNum, seasonName)))
                } else if (imageLink.isEmpty()) {
                    val filename = it.select("td.fb-n > a").text().lowercase()
                    val href = it.select("td.fb-n > a").attr("href")
                    
                    // Enhanced local image detection with priority order
                    val posterPatterns = listOf(
                        "poster.jpg", "poster.jpeg", "poster.png",
                        "cover.jpg", "cover.jpeg", "cover.png", 
                        "a_AL_.jpg", "a_AL_.jpeg", "a_AL_.png",
                        "thumbnail.jpg", "thumbnail.jpeg", "thumbnail.png"
                    )
                    
                    val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")
                    
                    // Check for exact poster filenames first
                    if (posterPatterns.contains(filename)) {
                        imageLink = server.url + href
                    }
                    // Then check for poster-like names
                    else if ((filename.contains("poster") || filename.contains("cover")) && 
                             imageExtensions.any { ext -> filename.endsWith(ext) }) {
                        imageLink = server.url + href
                    }
                    // Finally any image file as fallback
                    else if (imageExtensions.any { ext -> filename.endsWith(ext) }) {
                        imageLink = server.url + href
                    }
                } else {
                    val folderHtml = it.select("td.fb-n > a")
                    val title = folderHtml.text()
                    val link2 = server.url + folderHtml.attr("href")
                    if (!title.contains(Regex("\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE))) {
                        val episodeNum = episodesData.size + 1
                        // For single season shows, we'll handle TMDB data after bulk loading
                        episodesData.add(
                            newEpisode(link2) {
                                this.name = title // Will be updated with TMDB data later
                                this.season = 1
                                this.episode = episodeNum
                                // Description and other details will be added after bulk TMDB load
                            }
                        )
                    }
                }
            }
            
            // Bulk load all TMDB season data if we have a TMDB ID and seasons
            val bulkSeasonData = if (tmdbId != null && seasonNumbers.isNotEmpty()) {
                bulkLoadTvSeriesData(tmdbId, seasonNumbers.distinct())
            } else emptyMap()
            
            // Second pass: process seasons with bulk-loaded TMDB data
            seasonFolders.forEach { (link, folderName, seasonInfo) ->
                val (seasonNum, seasonName) = seasonInfo
                val seasonData = if (seasonNum != null) bulkSeasonData[seasonNum] else null
                
                // Extract season data with pre-loaded TMDB data
                seasonExtractorOptimized(link, episodesData, seasonNum ?: 0, seasonData, seasonName)
            }
            
            // For single season shows, update episodes with TMDB data
            if (seasonFolders.isEmpty() && episodesData.isNotEmpty() && tmdbId != null) {
                val seasonData = bulkSeasonData[1] // Season 1 for single season shows
                episodesData.forEachIndexed { index, episode ->
                    val episodeDetails = TmdbHelper.getEpisodeFromSeasonData(seasonData, index + 1)
                    // Update episode with TMDB data if available
                    episodesData[index] = newEpisode(episode.data) {
                        this.name = episodeDetails?.name ?: episode.name
                        this.season = 1
                        this.episode = index + 1
                        this.description = episodeDetails?.overview
                        episodeDetails?.stillPath?.let { still ->
                            this.posterUrl = TmdbHelper.getStillUrl(still)
                        }
                    }
                }
            }
            
            val tvType = if (isAnimeContent) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(name, url, tvType, episodesData) {
                this.posterUrl = imageLink
                this.plot = plot
                this.year = year
                this.score = tmdbData?.rating?.let { Score.from10(it) }
                addTMDbId(tmdbId?.toString())
                addImdbId(imdbId)
            }
        } else {
            // Find the movie file link
            doc.select("tbody > tr:gt(1)").forEach {
                val folderHtml = it.select("td.fb-n > a")
                if (folderHtml.isNotEmpty()) {
                    val fileName = folderHtml.text()
                    if (fileName.contains(Regex("\\.(mkv|mp4|avi)$", RegexOption.IGNORE_CASE))) {
link = server.url + folderHtml.attr("href")
                    }
                }
            }
            
            val movieType = if (isAnimeContent) TvType.AnimeMovie else TvType.Movie
            val imdbId = tmdbId?.let { TmdbHelper.getImdbIdFromTmdb(it, isMovie = true) }
            
            newMovieLoadResponse(name, url, movieType, link) {
                this.posterUrl = imageLink
                this.plot = plot
                this.year = year
                this.score = tmdbData?.rating?.let { Score.from10(it) }
                addTMDbId(tmdbId?.toString())
                addImdbId(imdbId)
            }
        }
    }

    private fun parseSeasonInfo(folderName: String): Pair<Int?, String?> {
        // Try to match common season patterns
        val seasonPatterns = listOf(
            Regex("(?i)season\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?i)s(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?i)series\\s*(\\d+)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in seasonPatterns) {
            val match = pattern.find(folderName)
            if (match != null) {
                val seasonNum = match.groupValues[1].toIntOrNull()
                if (seasonNum != null) {
                    return Pair(seasonNum, null) // Return season number, no custom name
                }
            }
        }
        
        // If no season number found, use folder name as custom season name
        // Clean up the folder name for display and normalize common special season names
        val cleanName = folderName.replace(Regex("[%_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { name ->
                // Normalize common special season names
                when (name.lowercase()) {
                    "oav", "oavs" -> "OAV"
                    "ova", "ovas" -> "OVA"
                    "special", "specials" -> "Specials"
                    "movie", "movies" -> "Movies"
                    "extra", "extras" -> "Extras"
                    "bonus" -> "Bonus"
                    else -> name
                }
            }
        
        return Pair(null, cleanName) // Return null for season number, custom name
    }

    private suspend fun seasonExtractor(
        url: String, 
        episodesData: MutableList<Episode>, 
        seasonNum: Int,
        tmdbId: Int?,
        seasonName: String? = null
    ) = withContext(Dispatchers.IO) {
        val server = serverForUrl(url)
        val doc = app.get(url).document
        
        // If we have TMDB ID, fetch season details first
        val seasonDetails = if (tmdbId != null) {
            TmdbHelper.getSeasonDetails(tmdbId, seasonNum)
        } else null
        
        // Get all episode links and parse their episode numbers from filenames
        val episodes = doc.select("tbody > tr:gt(1)").mapNotNull {
            val folderHtml = it.select("td.fb-n > a")
            val name = folderHtml.text()
            val link = server.url + folderHtml.attr("href")
            
            if (!name.contains(Regex("\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE))) {
                // Try to parse episode number from filename
                val episodePattern = Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})")
                val episodeNum = episodePattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                
                // If no episode number found and this is a special season, use sequential numbering
                val finalEpisodeNum = if (episodeNum == null && seasonName != null) {
                    // For special seasons, use sequential numbering starting from 1
                    null // We'll handle this later
                } else {
                    episodeNum
                }
                
                Triple(name, link, finalEpisodeNum)
            } else null
        }
        
        // Sort episodes - those with episode numbers first, then others
        val sortedEpisodes = episodes.sortedWith(compareBy<Triple<String, String, Int?>> { it.third == null }.thenBy { it.third })
        
        // Assign episode numbers to episodes that don't have them
        val finalEpisodes = sortedEpisodes.mapIndexed { index, (name, link, epNum) ->
            Triple(name, link, epNum ?: (index + 1))
        }

        // Process episodes in parallel
        val deferredEpisodes = finalEpisodes.map { (name, link, epNum) ->
            async {
                val episodeDetails = if (tmdbId != null) {
                    // Pass filename to get correct episode details
                    TmdbHelper.getEpisodeDetails(tmdbId, seasonNum, epNum, name)
                } else null
                
                newEpisode(link) {
                    val baseName = episodeDetails?.name ?: cleanEpisodeName(name)
                    // For special seasons, use a clear naming format
                    this.name = if (seasonName != null) {
                        "🎬 $seasonName - $baseName"
                    } else {
                        baseName
                    }
                    this.season = seasonNum
                    this.episode = epNum
                    this.description = if (seasonName != null) {
                        val seasonDesc = "📁 $seasonName Collection"
                        if (episodeDetails?.overview?.isNotEmpty() == true) {
                            "$seasonDesc\n\n${episodeDetails.overview}"
                        } else {
                            seasonDesc
                        }
                    } else {
                        episodeDetails?.overview
                    }
                    episodeDetails?.stillPath?.let { still ->
                        // Use small size for episode stills initially
                        this.posterUrl = TmdbHelper.getStillUrl(still)
                    }
                }
            }
        }

        // Wait for all episodes to be processed and add them to episodesData
        episodesData.addAll(deferredEpisodes.awaitAll())
    }

    // Optimized season extractor that uses pre-loaded TMDB data
    private suspend fun seasonExtractorOptimized(
        url: String, 
        episodesData: MutableList<Episode>, 
        seasonNum: Int,
        seasonData: TmdbSeasonDetails?,
        seasonName: String? = null
    ) = withContext(Dispatchers.IO) {
        val server = serverForUrl(url)
        val doc = app.get(url).document
        
        // Get all episode links and parse their episode numbers from filenames
        val episodes = doc.select("tbody > tr:gt(1)").mapNotNull {
            val folderHtml = it.select("td.fb-n > a")
            val name = folderHtml.text()
            val link = server.url + folderHtml.attr("href")
            
            if (!name.contains(Regex("\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE))) {
                // Try to parse episode number from filename
                val episodePattern = Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})")
                val episodeNum = episodePattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                
                // If no episode number found and this is a special season, use sequential numbering
                val finalEpisodeNum = if (episodeNum == null && seasonName != null) {
                    // For special seasons, use sequential numbering starting from 1
                    null // We'll handle this later
                } else {
                    episodeNum
                }
                
                Triple(name, link, finalEpisodeNum)
            } else null
        }
        
        // Sort episodes - those with episode numbers first, then others
        val sortedEpisodes = episodes.sortedWith(compareBy<Triple<String, String, Int?>> { it.third == null }.thenBy { it.third })
        
        // Assign episode numbers to episodes that don't have them
        val finalEpisodes = sortedEpisodes.mapIndexed { index, (name, link, epNum) ->
            Triple(name, link, epNum ?: (index + 1))
        }

        // Process episodes using pre-loaded TMDB data (much faster!)
        val newEpisodes = finalEpisodes.map { (name, link, epNum) ->
            // Get episode details from pre-loaded season data
            val episodeDetails = TmdbHelper.getEpisodeFromSeasonData(seasonData, epNum)
            
            newEpisode(link) {
                val baseName = episodeDetails?.name ?: cleanEpisodeName(name)
                // For special seasons, use a clear naming format
                this.name = if (seasonName != null) {
                    "🎬 $seasonName - $baseName"
                } else {
                    baseName
                }
                this.season = seasonNum
                this.episode = epNum
                this.description = if (seasonName != null) {
                    val seasonDesc = "📁 $seasonName Collection"
                    if (episodeDetails?.overview?.isNotEmpty() == true) {
                        "$seasonDesc\n\n${episodeDetails.overview}"
                    } else {
                        seasonDesc
                    }
                } else {
                    episodeDetails?.overview
                }
                episodeDetails?.stillPath?.let { still ->
                    // Use small size for episode stills initially
                    this.posterUrl = TmdbHelper.getStillUrl(still)
                }
            }
        }

        // Add all episodes to episodesData
        episodesData.addAll(newEpisodes)
    }

    private fun extractYear(name: String): Int? {
        // Try to extract year from patterns like "(2021)" or "(TV Series 2021-2025)"
        val yearPattern1 = Regex("\\((\\d{4})\\)")
        val yearPattern2 = Regex("\\(TV Series (\\d{4})-\\d{4}\\)")
        
        return yearPattern1.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: yearPattern2.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun cleanEpisodeName(name: String): String {
        // Remove patterns like "S01E01", "1080p", "WEBRip", etc.
        return name.replace(Regex("S\\d{2}E\\d{2}"), "")
            .replace(Regex("\\d{3,4}p"), "")
            .replace(Regex("NF WEBRip"), "")
            .replace(Regex("WEBRip"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\.(mkv|mp4|avi)"), "")
            .trim()
    }

    private fun containsAnyLoop(text: String, keyword: List<String>?): Boolean {
        if (!keyword.isNullOrEmpty()) {
            for (keyword in keyword) {
                if (text.contains(keyword, ignoreCase = true)) {
                    return true // Return immediately if a match is found
                }
            }
        }
        return false // Return false if no match is found after checking all keywords
    }

    private fun isAnime(url: String): Boolean {
        return containsAnyLoop(url, animeKeyword)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                this.name, this.name, url = data, type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    data class SearchResult(
        val search: List<Search>
    )

    data class Search(
        val fetched: Boolean,
        val href: String,
        val managed: Boolean,
        val size: Long?,
        val time: Long
    )
}
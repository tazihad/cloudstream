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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections

open class CityPlexProvider : MainAPI() {
    override var mainUrl = "http://10.247.249.22"
    override var name = "CityPlex"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    protected data class LocalServer(
        val id: String,
        val url: String
    )

    protected open val servers = listOf(
        LocalServer("22", "http://10.247.249.22"),
        LocalServer("3", "http://10.247.249.3"),
        LocalServer("4", "http://10.247.249.4"),
        LocalServer("6", "http://10.247.249.6"),
        LocalServer("7", "http://10.247.249.7"),
        LocalServer("8", "http://10.247.249.8"),
        LocalServer("2", "http://10.247.249.2")
    )

    private val animeKeyword = "Anime%20%26%20Cartoon%20TV%20Series"
    private val seriesKeyword = listOf("Islamic%20WebSeries")

    // Combined sections merge multiple server sub-folders into one listing.
    private val combinedSections = mapOf(
        "__hindi__" to listOf(
            "3|Hindi Movies/",
            "7|Hindi Movies/"
        ),
        "__south__" to listOf(
            "3|South Indian Movies/HINDI DUBBED/",
            "7|SOUTH INDIAN MOVIES/Hindi Dubbed/"
        ),
        "__animation__" to listOf(
            "4|Animation Movies/",
            "4|Animation Movies (1080p)/"
        ),
        "__bangla__" to listOf(
            "4|BANGLA/",
            "8|Bangladeshi Movies/"
        ),
        "__english__" to listOf(
            "2|English Movies/",
            "6|English Movies/"
        ),
        "__foreign__" to listOf(
            "2|Foreign Language Movies/",
            "8|Foreign Language Movies/"
        )
    )

    // Sections whose children are year / language grouping folders get expanded
    // into a flat list of actual content. Everything else is listed directly.

    override val mainPage = mainPageOf(
        // Server 22 - Wrestling & TV
        "22|WWE %26 AEW Wrestling/WWE Wrestling/" to "WWE",
        "22|Awards %26 TV Shows/" to "Awards & TV Shows",
        "22|Documentary/" to "Documentary",
        "22|Islamic WebSeries/" to "Islamic WebSeries",
        // Combined sections (flat expanded lists)
        "1|__hindi__" to "Hindi Movies",
        "1|__south__" to "South Indian Movies",
        "1|__animation__" to "Animation Movies",
        "4|Anime %26 Cartoon TV Series/" to "Anime & Cartoon TV Series",
        "1|__bangla__" to "Bangla Movies",
        "1|__english__" to "English Movies",
        "6|IMDb Top-250 Movies/" to "IMDb Top-250 Movies",
        "2|English Movies (1080p)/" to "English Movies (1080p)",
        "1|__foreign__" to "Foreign Language Movies",
        "8|Kolkata Bangla Movies/" to "Kolkata Bangla Movies"
    )

    private val expandSections = setOf(
        "__hindi__", "__south__", "__animation__", "__bangla__",
        "__english__", "__foreign__", "2|English Movies (1080p)/",
        "8|Kolkata Bangla Movies/"
    )

    private val serverRoots = mapOf(
        "22" to listOf("WWE %26 AEW Wrestling/WWE Wrestling/", "Awards %26 TV Shows/", "Documentary/", "Islamic WebSeries/"),
        "3" to listOf("Hindi Movies/", "South Indian Movies/HINDI DUBBED/"),
        "4" to listOf("Animation Movies/", "Animation Movies (1080p)/", "Anime %26 Cartoon TV Series/", "BANGLA/"),
        "6" to listOf("English Movies/", "IMDb Top-250 Movies/"),
        "7" to listOf("Hindi Movies/", "SOUTH INDIAN MOVIES/Hindi Dubbed/"),
        "8" to listOf("Bangladeshi Movies/", "Foreign Language Movies/", "Kolkata Bangla Movies/"),
        "2" to listOf("English Movies/", "English Movies (1080p)/", "Foreign Language Movies/")
    )

    private val itemsPerPage = 12
    private val maxGroupDepth = 4
    private val batchSize = 6

    private val flatListMutex = Mutex()

    // Grouping folder detection: year folders (e.g. "(2019)", "(1995) & Before",
    // "2000 & Before", "(2024) 1080p") plus known language / structural folders.
    private val yearFolderRegex = Regex("^\\(?(19|20)\\d{2}\\)?( & Before)?( 1080p| 720p| 480p| 2160p)?$")

    private val groupingNames = setOf(
        "BANGLA-(Bangladesh)", "Short_Films", "Bangladeshi Movies", "Natok",
        "Animation Movies (1080p)",
        "Bangla Dubbing Movies", "Brazilian Movie", "Chinese Language", "Danish Language",
        "Dutch Language", "French Language", "German Language", "Indonesian Language",
        "Iranian Movies", "Italian Movie", "Japanese Language", "Korean Language",
        "Norwegian Language", "Other Language", "Pakistani Movie", "Polish Language",
        "Russian Language", "Spanish Language", "Swedish Language", "Thai Language",
        "Turkish Language"
    )

    private fun isGroupingName(name: String): Boolean {
        return yearFolderRegex.matches(name.trim()) || groupingNames.contains(name.trim())
    }

    private fun serverById(id: String): LocalServer =
        servers.firstOrNull { it.id == id } ?: servers.last()

    private fun serverForUrl(url: String): LocalServer =
        servers.firstOrNull { url == it.url || url.startsWith("${it.url}/") } ?: servers.first()

    private fun resolveServer(data: String): Pair<LocalServer, String> {
        val separator = data.indexOf('|')
        return if (separator > 0) {
            serverById(data.substring(0, separator)) to data.substring(separator + 1)
        } else {
            servers.last() to data
        }
    }

    private data class DirEntry(val name: String, val url: String, val server: LocalServer)

    private data class FlatEntry(val name: String, val url: String)

    init {
        getProviderCache().clearCache()
    }

    protected fun finalize() {
        getProviderCache().clearCache()
    }

    companion object {
        private const val POSTER_CACHE_DURATION = 1 * 60 * 60 * 1000L
        private const val SEARCH_CACHE_DURATION = 30 * 60 * 1000L
        private const val TMDB_CACHE_DURATION = 15 * 60 * 1000L

        private var cacheInstance: ProviderCache? = null

        @Synchronized
        private fun getProviderCache(): ProviderCache {
            if (cacheInstance == null) {
                cacheInstance = ProviderCache()
            }
            return cacheInstance!!
        }
    }

    private class ProviderCache {
        private val posterCache = Collections.synchronizedMap(mutableMapOf<String, Pair<String?, Long>>())
        private val searchCache = Collections.synchronizedMap(mutableMapOf<String, Pair<CityPlexTmdbSearchResult?, Long>>())
        private val tmdbDetailsCache = Collections.synchronizedMap(mutableMapOf<String, Pair<CityPlexTmdbDetails?, Long>>())
        private val seasonDetailsCache = Collections.synchronizedMap(mutableMapOf<String, Pair<CityPlexTmdbSeasonDetails?, Long>>())
        private val bulkSeasonCache = Collections.synchronizedMap(mutableMapOf<String, Pair<Map<Int, CityPlexTmdbSeasonDetails?>, Long>>())
        private val flatCache = Collections.synchronizedMap(mutableMapOf<String, List<FlatEntry>>())

        private val TMDB_CACHE_DURATION = 15 * 60 * 1000L

        fun <T> getFromCache(
            cache: MutableMap<String, Pair<T?, Long>>,
            key: String,
            duration: Long
        ): T? {
            val (value, timestamp) = cache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < duration) value else null
        }

        fun <T> addToCache(
            cache: MutableMap<String, Pair<T?, Long>>,
            key: String,
            value: T?
        ) {
            synchronized(cache) {
                if (cache.size > 50) {
                    val currentTime = System.currentTimeMillis()
                    val iterator = cache.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if ((currentTime - entry.value.second) > TMDB_CACHE_DURATION) {
                            iterator.remove()
                        }
                    }
                    if (cache.size > 25) {
                        val sortedEntries = cache.entries.sortedBy { it.value.second }
                        sortedEntries.take(sortedEntries.size - 25).forEach { cache.remove(it.key) }
                    }
                }
                cache[key] = Pair(value, System.currentTimeMillis())
            }
        }

        fun getFlat(key: String): List<FlatEntry>? = flatCache[key]

        fun putFlat(key: String, list: List<FlatEntry>) {
            flatCache[key] = list
        }

        fun getPosterCache() = posterCache
        fun getSearchCache() = searchCache
        fun getTmdbDetailsCache() = tmdbDetailsCache
        fun getSeasonDetailsCache() = seasonDetailsCache
        fun getBulkSeasonCache() = bulkSeasonCache

        fun clearCache() {
            synchronized(this) {
                posterCache.clear()
                searchCache.clear()
                tmdbDetailsCache.clear()
                seasonDetailsCache.clear()
                bulkSeasonCache.clear()
                flatCache.clear()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Folder listing helpers
    // -------------------------------------------------------------------------

    private suspend fun listFolder(server: LocalServer, path: String): List<DirEntry> {
        return app.get("${server.url}/$path").document.select("tbody > tr").drop(2).mapNotNull { row ->
            val a = row.selectFirst("td.fb-n > a") ?: return@mapNotNull null
            val img = row.selectFirst("td.fb-i > img")?.attr("alt")
            if (img != "folder") return@mapNotNull null
            DirEntry(a.text(), server.url + a.attr("href"), server)
        }
    }

    private suspend fun expandEntry(entry: DirEntry, depth: Int): List<FlatEntry> = coroutineScope {
        if (depth >= maxGroupDepth || !isGroupingName(entry.name)) {
            return@coroutineScope listOf(FlatEntry(entry.name, entry.url))
        }

        val children = runCatching { listFolderFromUrl(entry) }.getOrElse { emptyList() }
        if (children.isEmpty()) {
            return@coroutineScope listOf(FlatEntry(entry.name, entry.url))
        }

        children.chunked(batchSize).flatMap { batch ->
            batch.map { child -> async { expandEntry(child, depth + 1) } }.awaitAll()
        }.flatten()
    }

    private suspend fun listFolderFromUrl(entry: DirEntry): List<DirEntry> {
        return app.get(entry.url).document.select("tbody > tr").drop(2).mapNotNull { row ->
            val a = row.selectFirst("td.fb-n > a") ?: return@mapNotNull null
            val img = row.selectFirst("td.fb-i > img")?.attr("alt")
            if (img != "folder") return@mapNotNull null
            DirEntry(a.text(), entry.server.url + a.attr("href"), entry.server)
        }
    }

    private suspend fun buildFlatList(request: MainPageRequest): List<FlatEntry> = coroutineScope {
        val (server, path) = resolveServer(request.data)
        val expand = expandSections.contains(request.data) || expandSections.contains(path)

        val combinedKey = combinedSections[path]
        val parts: List<Pair<LocalServer, String>> = if (combinedKey != null) {
            combinedKey.map { part ->
                val (s, p) = resolveServer(part)
                s to p
            }
        } else {
            listOf(server to path)
        }

        val entries = parts.flatMap { (s, p) ->
            runCatching { listFolder(s, p) }.getOrElse { emptyList() }
        }

        if (expand) {
            entries.chunked(batchSize).flatMap { batch ->
                batch.map { entry -> async { expandEntry(entry, 0) } }.awaitAll()
            }.flatten()
        } else {
            entries.map { FlatEntry(it.name, it.url) }
        }
    }

    private suspend fun getOrBuildFlatList(request: MainPageRequest): List<FlatEntry> {
        val key = request.data
        val providerCache = getProviderCache()
        providerCache.getFlat(key)?.let { return it }

        return flatListMutex.withLock {
            providerCache.getFlat(key)?.let { return it }
            buildAndCache(key, request)
        }
    }

    private suspend fun buildAndCache(key: String, request: MainPageRequest): List<FlatEntry> {
        return try {
            val list = buildFlatList(request)
            getProviderCache().putFlat(key, list)
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // TMDB lazy loading (kept from DhakaFlix)
    // -------------------------------------------------------------------------

    private suspend fun lazyLoadTmdbData(
        name: String,
        isMovie: Boolean,
        loadDetails: Boolean = false
    ): CityPlexTmdbSearchResult? = coroutineScope {
        val cleanName = CityPlexUtils.cleanNameForSearch(name)
        val cacheKey = "$cleanName:$isMovie"
        val providerCache = getProviderCache()

        providerCache.getFromCache(providerCache.getSearchCache(), cacheKey, SEARCH_CACHE_DURATION)
            ?.let { return@coroutineScope it as? CityPlexTmdbSearchResult }

        if (!loadDetails) {
            launch {
                CityPlexTmdbHelper.searchTmdb(cleanName, isMovie)?.let { result ->
                    providerCache.addToCache(providerCache.getSearchCache(), cacheKey, result)
                }
            }
            return@coroutineScope null
        }

        val result = CityPlexTmdbHelper.searchTmdb(cleanName, isMovie)
        providerCache.addToCache(providerCache.getSearchCache(), cacheKey, result)
        return@coroutineScope result
    }

    private suspend fun bulkLoadTvSeriesData(
        tmdbId: Int,
        seasonNumbers: List<Int>
    ): Map<Int, CityPlexTmdbSeasonDetails?> = coroutineScope {
        val providerCache = getProviderCache()
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}"

        val cachedData = providerCache.getBulkSeasonCache()[cacheKey]
        if (cachedData != null && (System.currentTimeMillis() - cachedData.second) < TMDB_CACHE_DURATION) {
            return@coroutineScope cachedData.first
        }

        val seasonData = CityPlexTmdbHelper.getAllSeasonDetails(tmdbId, seasonNumbers)
        providerCache.getBulkSeasonCache()[cacheKey] = Pair(seasonData, System.currentTimeMillis())

        seasonData.forEach { (seasonNum, seasonDetails) ->
            val individualCacheKey = "$tmdbId:$seasonNum"
            providerCache.getSeasonDetailsCache()[individualCacheKey] = Pair(seasonDetails, System.currentTimeMillis())
        }

        return@coroutineScope seasonData
    }

    // -------------------------------------------------------------------------
    // Main page
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val flatList = getOrBuildFlatList(request)
        val totalItems = flatList.size

        val safePage = maxOf(1, page)
        val startIndex = ((safePage - 1) * itemsPerPage).coerceAtMost(totalItems)
        val endIndex = (startIndex + itemsPerPage).coerceAtMost(totalItems)

        val slice = if (startIndex < endIndex) {
            flatList.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        val home = slice.chunked(batchSize).flatMap { chunk ->
            chunk.map { entry -> async { getPostResult(entry) } }.awaitAll()
        }

        newHomePageResponse(request.name, home, totalItems > endIndex)
    }

    private fun cleanNameForSearch(name: String): String {
        return CityPlexUtils.cleanNameForSearch(name)
    }

    private fun cleanFolderName(name: String): String {
        return CityPlexUtils.cleanFolderName(name)
    }

    private fun isWrestling(url: String): Boolean {
        return url.contains("WWE") || url.contains("AEW")
    }

    private suspend fun getPostResult(entry: FlatEntry): SearchResponse {
        val rawName = entry.name
        val url = entry.url
        val name = if (isWrestling(url)) cleanFolderName(rawName) else cleanNameForSearch(rawName)

        val isAnimeContent = url.contains(animeKeyword)
        val tvType = when {
            isAnimeContent -> TvType.Anime
            containsAnyLoop(url, seriesKeyword) -> TvType.TvSeries
            else -> TvType.Movie
        }

        val posterUrl = try {
            CityPlexUtils.findPosterLight(url, serverForUrl(url).url)
        } catch (e: Exception) {
            null
        }

        return newAnimeSearchResponse(name, url, tvType) {
            addDubStatus(
                dubExist = CityPlexUtils.hasMultiAudio(rawName),
                subExist = false
            )
            if (posterUrl?.isNotEmpty() == true) {
                this.posterUrl = posterUrl
            }
        }
    }

    // -------------------------------------------------------------------------
    // Search (h5ai search is disabled on these servers, so we walk the tree)
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        if (cleanQuery.isBlank()) return emptyList()

        val matches = servers.map { server ->
            runCatching { searchServer(server, cleanQuery) }.getOrElse { emptyList() }
        }.flatten().distinctBy { it.url }

        val scored = matches.mapNotNull { entry ->
            val score = CityPlexUtils.calculateRelevance(entry.name, cleanQuery)
            if (score >= 0.2) entry to score else null
        }.sortedByDescending { it.second }.take(40)

        return coroutineScope {
            scored.chunked(batchSize).flatMap { batch ->
                batch.map { (entry, _) ->
                    async {
                        val rawName = entry.name
                        val name = if (isWrestling(entry.url)) cleanFolderName(rawName) else cleanNameForSearch(rawName)
                        val isAnimeContent = entry.url.contains(animeKeyword)
                        val tvType = when {
                            isAnimeContent -> TvType.Anime
                            containsAnyLoop(entry.url, seriesKeyword) -> TvType.TvSeries
                            else -> TvType.Movie
                        }
                        val posterUrl = try {
                            CityPlexUtils.findPosterLight(entry.url, entry.server.url)
                        } catch (e: Exception) {
                            null
                        }
                        newAnimeSearchResponse(name, entry.url, tvType) {
                            addDubStatus(
                                dubExist = CityPlexUtils.hasMultiAudio(rawName),
                                subExist = false
                            )
                            if (posterUrl?.isNotEmpty() == true) {
                                this.posterUrl = posterUrl
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun searchServer(server: LocalServer, query: String): List<DirEntry> = coroutineScope {
        val roots = serverRoots[server.id] ?: emptyList()
        roots.flatMap { path ->
            runCatching { listFolder(server, path) }.getOrElse { emptyList() }.map { entry ->
                async {
                    if (isGroupingName(entry.name)) {
                        expandEntry(entry, 0)
                            .filter { CityPlexUtils.calculateRelevance(it.name, query) >= 0.2 }
                            .map { DirEntry(it.name, it.url, server) }
                    } else {
                        val relevant = CityPlexUtils.calculateRelevance(entry.name, query) >= 0.2
                        if (relevant) listOf(entry) else emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    private suspend fun findPosterUrl(contentUrl: String): String? {
        val providerCache = getProviderCache()

        providerCache.getFromCache(providerCache.getPosterCache(), contentUrl, POSTER_CACHE_DURATION)
            ?.let { return it as? String }

        val localPoster = try {
            CityPlexUtils.findPoster(contentUrl, serverForUrl(contentUrl).url)
        } catch (e: Exception) {
            null
        }
        providerCache.addToCache(providerCache.getPosterCache(), contentUrl, localPoster)
        return localPoster
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val server = serverForUrl(url)
        val doc = app.get(url).document

        val rawName = URLDecoder.decode(url.split("/").filter { it.isNotEmpty() }.last(), StandardCharsets.UTF_8.toString())
        val name = if (isWrestling(url)) cleanFolderName(rawName) else cleanNameForSearch(rawName)

        var imageLink = findPosterUrl(url) ?: ""

        // Content based classification: a folder containing only sub-folders is
        // a series, a folder with a direct video file is a movie.
        var hasDirectVideo = false
        var hasSubFolders = false
        doc.select("tbody > tr:gt(1)").forEach { row ->
            val a = row.selectFirst("td.fb-n > a") ?: return@forEach
            val img = row.selectFirst("td.fb-i > img")?.attr("alt")
            val fileName = a.text()
            when {
                img == "folder" -> hasSubFolders = true
                CityPlexUtils.isVideoFile(fileName) -> hasDirectVideo = true
                imageLink.isEmpty() && CityPlexUtils.isImageFile(fileName) ->
                    imageLink = server.url + a.attr("href")
            }
        }

        val isAnimeContent = url.contains(animeKeyword)
        val isTvSeries = containsAnyLoop(url, seriesKeyword) || (hasSubFolders && !hasDirectVideo)

        val tmdbData = lazyLoadTmdbData(name, isMovie = !(isTvSeries || isAnimeContent), loadDetails = true)
        var tmdbId: Int? = null
        var plot: String? = null
        var rating: Int? = null
        var year: Int? = null

        if (imageLink.isEmpty()) {
            imageLink = tmdbData?.posterPath?.let { CityPlexTmdbHelper.getPosterUrl(it, isDetail = true) } ?: ""
        }

        if (tmdbData != null) {
            tmdbId = tmdbData.id
            plot = tmdbData.overview
            rating = tmdbData.rating?.times(10)?.toInt()
            tmdbData.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()?.let {
                if (year == null) year = it
            }
        }

        if (year == null) {
            year = extractYear(name)
        }

        if (isTvSeries || isAnimeContent) {
            val imdbId = tmdbId?.let { CityPlexTmdbHelper.getImdbIdFromTmdb(it, isMovie = false) }
            val episodesData = mutableListOf<Episode>()
            val seasonNumbers = mutableListOf<Int>()
            val seasonFolders = mutableListOf<Triple<String, String, Pair<Int?, String?>>>()

            doc.select("tbody > tr:gt(1)").forEach {
                if (it.selectFirst("td.fb-i > img")?.attr("alt") == "folder") {
                    val folderName = it.select("td.fb-n > a").text()
                    val seasonInfo = parseSeasonInfo(folderName)
                    val link = server.url + it.select("td.fb-n > a").attr("href")

                    if (seasonInfo.first != null) {
                        seasonNumbers.add(seasonInfo.first!!)
                        seasonFolders.add(Triple(link, folderName, Pair(seasonInfo.first, null)))
                    } else {
                        seasonNumbers.add(0)
                        seasonFolders.add(Triple(link, folderName, Pair(null, seasonInfo.second)))
                    }
                } else if (imageLink.isEmpty() && CityPlexUtils.isImageFile(it.select("td.fb-n > a").text())) {
                    imageLink = server.url + it.select("td.fb-n > a").attr("href")
                } else {
                    val folderHtml = it.select("td.fb-n > a")
                    val title = folderHtml.text()
                    if (CityPlexUtils.isVideoFile(title)) {
                        val link2 = server.url + folderHtml.attr("href")
                        val episodeNum = episodesData.size + 1
                        episodesData.add(
                            newEpisode(link2) {
                                this.name = title
                                this.season = 1
                                this.episode = episodeNum
                            }
                        )
                    }
                }
            }

            val bulkSeasonData = if (tmdbId != null && seasonNumbers.isNotEmpty()) {
                bulkLoadTvSeriesData(tmdbId, seasonNumbers.distinct())
            } else emptyMap()

            seasonFolders.forEach { (link, folderName, seasonInfo) ->
                val (seasonNum, seasonName) = seasonInfo
                val seasonData = if (seasonNum != null) bulkSeasonData[seasonNum] else null
                seasonExtractorOptimized(link, episodesData, seasonNum ?: 0, seasonData, seasonName)
            }

            if (seasonFolders.isEmpty() && episodesData.isNotEmpty() && tmdbId != null) {
                val seasonData = bulkSeasonData[1]
                episodesData.forEachIndexed { index, episode ->
                    val episodeDetails = CityPlexTmdbHelper.getEpisodeFromSeasonData(seasonData, index + 1)
                    episodesData[index] = newEpisode(episode.data) {
                        this.name = episodeDetails?.name ?: episode.name
                        this.season = 1
                        this.episode = index + 1
                        this.description = episodeDetails?.overview
                        episodeDetails?.stillPath?.let { still ->
                            this.posterUrl = CityPlexTmdbHelper.getStillUrl(still)
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
            var link = ""
            doc.select("tbody > tr:gt(1)").forEach {
                val folderHtml = it.select("td.fb-n > a")
                if (folderHtml.isNotEmpty()) {
                    val fileName = folderHtml.text()
                    if (CityPlexUtils.isVideoFile(fileName)) {
                        link = server.url + folderHtml.attr("href")
                    }
                }
            }

            val movieType = if (isAnimeContent) TvType.AnimeMovie else TvType.Movie
            val imdbId = tmdbId?.let { CityPlexTmdbHelper.getImdbIdFromTmdb(it, isMovie = true) }

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
                    return Pair(seasonNum, null)
                }
            }
        }

        val cleanName = folderName.replace(Regex("[%_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { name ->
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

        return Pair(null, cleanName)
    }

    private suspend fun seasonExtractorOptimized(
        url: String,
        episodesData: MutableList<Episode>,
        seasonNum: Int,
        seasonData: CityPlexTmdbSeasonDetails?,
        seasonName: String? = null
    ) = withContext(Dispatchers.IO) {
        val server = serverForUrl(url)
        val doc = app.get(url).document

        val episodes = doc.select("tbody > tr:gt(1)").mapNotNull {
            val folderHtml = it.select("td.fb-n > a")
            val name = folderHtml.text()
            val link = server.url + folderHtml.attr("href")

            if (!CityPlexUtils.isImageFile(name) && CityPlexUtils.isVideoFile(name)) {
                val episodePattern = Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})")
                val episodeNum = episodePattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                Triple(name, link, if (episodeNum == null && seasonName != null) null else episodeNum)
            } else null
        }

        val sortedEpisodes = episodes.sortedWith(compareBy<Triple<String, String, Int?>> { it.third == null }.thenBy { it.third })
        val finalEpisodes = sortedEpisodes.mapIndexed { index, (name, link, epNum) ->
            Triple(name, link, epNum ?: (index + 1))
        }

        finalEpisodes.forEach { (name, link, epNum) ->
            val episodeDetails = CityPlexTmdbHelper.getEpisodeFromSeasonData(seasonData, epNum)

            newEpisode(link) {
                val baseName = episodeDetails?.name ?: cleanEpisodeName(name)
                this.name = if (seasonName != null) {
                    "$seasonName - $baseName"
                } else {
                    baseName
                }
                this.season = seasonNum
                this.episode = epNum
                this.description = if (seasonName != null) {
                    val seasonDesc = "$seasonName Collection"
                    if (episodeDetails?.overview?.isNotEmpty() == true) {
                        "$seasonDesc\n\n${episodeDetails.overview}"
                    } else {
                        seasonDesc
                    }
                } else {
                    episodeDetails?.overview
                }
                episodeDetails?.stillPath?.let { still ->
                    this.posterUrl = CityPlexTmdbHelper.getStillUrl(still)
                }
            }.also { episodesData.add(it) }
        }
    }

    private fun extractYear(name: String): Int? {
        val yearPattern1 = Regex("\\((\\d{4})\\)")
        val yearPattern2 = Regex("\\(TV Series (\\d{4})-\\d{4}\\)")

        return yearPattern1.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: yearPattern2.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun cleanEpisodeName(name: String): String {
        return name.replace(Regex("S\\d{2}E\\d{2}"), "")
            .replace(Regex("\\d{3,4}p"), "")
            .replace(Regex("WWE"), "")
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
                    return true
                }
            }
        }
        return false
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
}
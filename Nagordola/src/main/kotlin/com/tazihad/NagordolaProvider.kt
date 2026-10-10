package com.tazihad

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

open class NagordolaProvider : MainAPI() {
    override var mainUrl: String
        get() = NagordolaSettingsManager.getBaseUrl()
        set(_) {}

    override var name = "Nagordola"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    private val sectionDefs = linkedMapOf(
        "movies_english.json" to Pair("English Movies", TvType.Movie),
        "movies_hindi.json" to Pair("Hindi Movies", TvType.Movie),
        "movies_bangla.json" to Pair("Bangla Movies", TvType.Movie),
        "movies_tamil.json" to Pair("Tamil Movies", TvType.Movie),
        "movies_telugu.json" to Pair("Telugu Movies", TvType.Movie),
        "movies_hindi_dubbed.json" to Pair("Hindi Dubbed Movies", TvType.Movie),
        "movies_korean.json" to Pair("Korean Movies", TvType.Movie),
        "movies_malayalam.json" to Pair("Malayalam Movies", TvType.Movie),
        "movies_asian.json" to Pair("Asian Movies", TvType.Movie),
        "movies_foreign.json" to Pair("Foreign Movies", TvType.Movie),
        "movies_anime.json" to Pair("Anime Movies", TvType.AnimeMovie),
        "animations_english.json" to Pair("Animation Movies", TvType.AnimeMovie),
        "tvshows_english.json" to Pair("English TV Series", TvType.TvSeries),
        "tvshows_hindi.json" to Pair("Hindi TV Series", TvType.TvSeries),
        "tvshows_korean.json" to Pair("Korean TV Series", TvType.TvSeries),
        "tvshows_bangla.json" to Pair("Bangla TV Series", TvType.TvSeries),
        "tvshows_anime.json" to Pair("Anime TV Series", TvType.Anime),
        "tvshows_foreign.json" to Pair("Foreign TV Series", TvType.TvSeries),
        "tvshows_hindi_dubbed.json" to Pair("Hindi Dubbed TV Series", TvType.TvSeries)
    )

    override val mainPage = mainPageOf(
        "trending_today" to "Top 10 Trending Today",
        *sectionDefs.map { it.key to it.value.first }.toTypedArray()
    )

    private val itemsPerPage = 12

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PreCrawledVideo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PreCrawledMovie(
        @JsonProperty("title") val title: String = "",
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("posterUrl") val posterUrl: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Long? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("isTvSeries") val isTvSeries: Boolean = false,
        @JsonProperty("videos") val videos: List<PreCrawledVideo> = emptyList()
    )

    data class ScrapedEntry(
        val name: String,
        val url: String,
        val isDirectory: Boolean,
        val isVideo: Boolean = false
    )

    private companion object {
        private const val TAG = "NagordolaProvider"
        private val cachedCategories = ConcurrentHashMap<String, List<PreCrawledMovie>>()
        private val movieRegistry = ConcurrentHashMap<String, PreCrawledMovie>()
        private val videoExtRegex = Regex("\\.(mp4|mkv|avi|webm|mov|m4v|flv|ts|m2ts)$", RegexOption.IGNORE_CASE)
        private const val DEFAULT_TMDB_API_KEY = "cdb4d6683a4de1f186e7da86dccdd7f1"
        private const val DEFAULT_POSTER = "https://image.tmdb.org/t/p/w500/peDKpbfsQ4YdWjUllEQrUbGJLQW.jpg"
        private const val DEFAULT_BACKDROP = "https://image.tmdb.org/t/p/w1280/peDKpbfsQ4YdWjUllEQrUbGJLQW.jpg"
        private const val TRENDING_CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 hours
        @Volatile private var cachedTrending: List<SearchResponse>? = null
        @Volatile private var lastTrendingFetchTime = 0L
        private val trendingBackdrops = ConcurrentHashMap<String, String>()
        private val trendingPosters = ConcurrentHashMap<String, String>()
        private val cachedBackdrops = ConcurrentHashMap<Long, String>()
        @Volatile private var resolvedCs3Path: String? = null
    }

    // -------------------------------------------------------------------------
    // Pre-Crawled Database Loader (Local Assets / Resources + Remote Fallbacks)
    // -------------------------------------------------------------------------

    private fun parseMovies(json: String): List<PreCrawledMovie> {
        return try {
            parseJson<Array<PreCrawledMovie>>(json).toList()
        } catch (e1: Exception) {
            try {
                parseJson<List<PreCrawledMovie>>(json)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse movies JSON: ${e2.message}")
                emptyList()
            }
        }
    }

    private fun getCs3Candidates(): List<File> {
        val candidates = mutableListOf<File>()
        resolvedCs3Path?.let { candidates.add(File(it)) }
        this.sourcePlugin?.let { candidates.add(File(it)) }
        NagordolaPlugin.pluginInstance?.filename?.let { candidates.add(File(it)) }

        val ctx = NagordolaPlugin.pluginContext ?: CloudStreamApp.context
        ctx?.let { c ->
            try {
                c.filesDir?.walkTopDown()
                    ?.filter { it.isFile && it.extension.equals("cs3", ignoreCase = true) && it.name.contains("Nagordola", ignoreCase = true) }
                    ?.forEach { candidates.add(it) }
            } catch (_: Exception) {}
        }
        return candidates.distinct()
    }

    private fun loadCategory(fileName: String): List<PreCrawledMovie> {
        cachedCategories[fileName]?.let { return it }

        // 1. Direct ZipFile read from discovered .cs3 plugin package on disk
        for (cs3File in getCs3Candidates()) {
            if (!cs3File.exists() || !cs3File.canRead()) continue
            try {
                ZipFile(cs3File).use { zip ->
                    val entry = zip.getEntry("assets/database/nagordola/$fileName")
                        ?: zip.getEntry("database/nagordola/$fileName")
                    if (entry != null) {
                        val json = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val items = parseMovies(json)
                        if (items.isNotEmpty()) {
                            resolvedCs3Path = cs3File.absolutePath
                            cachedCategories[fileName] = items
                            items.forEachIndexed { idx, m ->
                                movieRegistry["nagordola://$fileName/$idx"] = m
                                movieRegistry["$fileName/$idx"] = m
                            }
                            return items
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ZipFile read from ${cs3File.absolutePath} failed for $fileName: ${e.message}")
            }
        }

        // 2. Try Android AssetManager from context
        try {
            val ctx = NagordolaPlugin.pluginContext ?: CloudStreamApp.context
            val stream = ctx?.assets?.open("assets/database/nagordola/$fileName")
                ?: ctx?.assets?.open("database/nagordola/$fileName")
            if (stream != null) {
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val items = parseMovies(json)
                if (items.isNotEmpty()) {
                    cachedCategories[fileName] = items
                    items.forEachIndexed { idx, m ->
                        movieRegistry["nagordola://$fileName/$idx"] = m
                        movieRegistry["$fileName/$idx"] = m
                    }
                    return items
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "AssetManager load failed for $fileName: ${e.message}")
        }

        // 3. Try ClassLoader resources (bundled in .cs3 zip / jar)
        try {
            val classLoader = NagordolaProvider::class.java.classLoader
            val stream = classLoader?.getResourceAsStream("assets/database/nagordola/$fileName")
                ?: classLoader?.getResourceAsStream("database/nagordola/$fileName")
                ?: NagordolaProvider::class.java.getResourceAsStream("/assets/database/nagordola/$fileName")
                ?: NagordolaProvider::class.java.getResourceAsStream("/database/nagordola/$fileName")
            if (stream != null) {
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val items = parseMovies(json)
                if (items.isNotEmpty()) {
                    cachedCategories[fileName] = items
                    items.forEachIndexed { idx, m ->
                        movieRegistry["nagordola://$fileName/$idx"] = m
                        movieRegistry["$fileName/$idx"] = m
                    }
                    return items
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ClassLoader resource load failed for $fileName: ${e.message}")
        }

        // 4. Fallback: Fast jsDelivr CDN mirrors (unblocked, reliable in BD)
        val cdnUrls = listOf(
            "https://cdn.jsdelivr.net/gh/tazihad/cloudstream@master/Nagordola/src/main/assets/database/nagordola/$fileName",
            "https://cdn.jsdelivr.net/gh/ReduanNurLabid/PotFlix@main/app/src/main/assets/database/nagordola/$fileName",
            "https://raw.githubusercontent.com/ReduanNurLabid/PotFlix/main/app/src/main/assets/database/nagordola/$fileName",
            "https://raw.githubusercontent.com/tazihad/cloudstream/master/Nagordola/src/main/assets/database/nagordola/$fileName"
        )
        for (remoteUrl in cdnUrls) {
            try {
                val response = runBlocking {
                    withTimeoutOrNull(6000L) {
                        app.get(remoteUrl).text
                    }
                }
                if (!response.isNullOrBlank()) {
                    val items = parseMovies(response)
                    if (items.isNotEmpty()) {
                        cachedCategories[fileName] = items
                        items.forEachIndexed { idx, m ->
                            movieRegistry["nagordola://$fileName/$idx"] = m
                            movieRegistry["$fileName/$idx"] = m
                        }
                        return items
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote fallback ($remoteUrl) failed for $fileName: ${e.message}")
            }
        }

        return emptyList()
    }

    private fun toStreamUrl(url: String): String {
        var u = url.trim()
        val base = mainUrl.trimEnd('/')
        if (u.contains("nagordola.com.bd")) {
            if (!u.contains("nagordola.com.bd/d/")) {
                u = u.replace("nagordola.com.bd/", "nagordola.com.bd/d/")
            }
        } else if (u.startsWith(base) && !u.startsWith("$base/d/")) {
            u = u.replace(base, "$base/d")
        }
        return u
    }

    private fun encodeItemData(fileName: String, index: Int, movie: PreCrawledMovie, backdropUrl: String? = null): String {
        val titleEnc = URLEncoder.encode(movie.title, "UTF-8")
        val posterEnc = URLEncoder.encode(movie.posterUrl ?: "", "UTF-8")
        val backdropEnc = URLEncoder.encode(backdropUrl ?: "", "UTF-8")
        val streamEnc = URLEncoder.encode(movie.videos.firstOrNull()?.url ?: "", "UTF-8")
        val tmdb = movie.tmdbId ?: 0
        val isTv = movie.isTvSeries
        val yearEnc = URLEncoder.encode(movie.year ?: "", "UTF-8")
        val rating = movie.rating ?: 0.0

        val fullUrl = "nagordola://$fileName/$index?title=$titleEnc&poster=$posterEnc&backdrop=$backdropEnc&stream=$streamEnc&tmdb=$tmdb&isTv=$isTv&year=$yearEnc&rating=$rating"

        movieRegistry[fullUrl] = movie
        movieRegistry["nagordola://$fileName/$index"] = movie
        movieRegistry["$fileName/$index"] = movie

        return fullUrl
    }

    private fun findMovieByData(data: String): PreCrawledMovie? {
        // 1. Direct registry lookup
        movieRegistry[data]?.let { return it }

        val cleanData = data.substringBefore('?')
        movieRegistry[cleanData]?.let { return it }

        val bareKey = cleanData.removePrefix("nagordola://").trim('/')
        movieRegistry[bareKey]?.let { return it }

        // 2. Index lookup within category file
        if (bareKey.contains('/')) {
            val fileName = bareKey.substringBefore('/')
            val index = bareKey.substringAfter('/').toIntOrNull()
            if (index != null) {
                val list = loadCategory(fileName)
                if (index in list.indices) {
                    val m = list[index]
                    movieRegistry[data] = m
                    movieRegistry[cleanData] = m
                    movieRegistry[bareKey] = m
                    return m
                }
            }
        }

        // 3. Fallback: Parse query parameters
        if (data.contains('?')) {
            val queryPart = data.substringAfter('?')
            val params = queryPart.split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to (if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else "")
            }
            val title = params["title"]?.trim()
            if (!title.isNullOrBlank() && !title.all { it.isDigit() }) {
                val poster = params["poster"]?.ifBlank { null }
                val stream = params["stream"]?.ifBlank { null }
                val tmdbId = params["tmdb"]?.toLongOrNull()?.takeIf { it > 0 }
                val isTv = params["isTv"]?.toBoolean() ?: false
                val year = params["year"]?.ifBlank { null }
                val rating = params["rating"]?.toDoubleOrNull()
                val videos = if (!stream.isNullOrBlank()) listOf(PreCrawledVideo(url = stream, quality = "HD")) else emptyList()
                return PreCrawledMovie(
                    title = title,
                    year = year,
                    posterUrl = poster,
                    tmdbId = tmdbId,
                    rating = rating,
                    isTvSeries = isTv,
                    videos = videos
                )
            }
        }

        // 4. Fallback: search across cached categories by URL or title
        val rawTarget = cleanData.removePrefix("nagordola://")
        for ((_, list) in cachedCategories) {
            val found = list.find { movie ->
                movie.videos.any { it.url == rawTarget || toStreamUrl(it.url ?: "") == rawTarget } || movie.title.equals(rawTarget, ignoreCase = true)
            }
            if (found != null) return found
        }

        return null
    }

    // -------------------------------------------------------------------------
    // Main Page & Pagination
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == "trending_today") {
            if (page > 1) return null
            val trendingItems = getTrendingItems()
            if (trendingItems.isEmpty()) return null
            return newHomePageResponse(request.name, trendingItems, hasNext = false)
        }

        val fileName = request.data
        val def = sectionDefs[fileName]
        val isTv = fileName.startsWith("tvshows") || fileName.contains("anime") && fileName.contains("tv")

        val allItems = withContext(Dispatchers.IO) {
            loadCategory(fileName)
        }

        if (allItems.isEmpty()) return null

        val startIndex = (page - 1) * itemsPerPage
        if (startIndex >= allItems.size) return null

        val pageItems = allItems.drop(startIndex).take(itemsPerPage)
        val results = mutableListOf<SearchResponse>()

        for ((idx, item) in pageItems.withIndex()) {
            val actualIndex = startIndex + idx
            val posterUrl = item.posterUrl?.replace("/w342/", "/w500/") ?: item.posterUrl ?: DEFAULT_POSTER
            val backdropUrl = item.posterUrl?.replace("/w342/", "/w1280/")?.replace("/w500/", "/w1280/") ?: DEFAULT_BACKDROP
            val itemData = encodeItemData(fileName, actualIndex, item, backdropUrl)
            val yearInt = item.year?.take(4)?.toIntOrNull()
            val scoreVal = item.rating?.let { Score.from10(it) }

            if (isTv || item.isTvSeries) {
                results.add(
                    newAnimeSearchResponse(item.title, itemData, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(item.title, itemData, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            }
        }

        val hasNext = (startIndex + itemsPerPage) < allItems.size
        return newHomePageResponse(request.name, results, hasNext)
    }

    private suspend fun getTrendingItems(): List<SearchResponse> {
        val now = System.currentTimeMillis()
        val cached = cachedTrending
        if (cached != null && (now - lastTrendingFetchTime) < TRENDING_CACHE_TTL_MS) {
            return cached
        }

        val items = withContext(Dispatchers.IO) {
            fetchTrendingFromTmdb()
        }

        if (items.isNotEmpty()) {
            cachedTrending = items
            lastTrendingFetchTime = now
            return items
        }

        val fallback = getFallbackTrending()
        if (fallback.isNotEmpty()) {
            cachedTrending = fallback
            lastTrendingFetchTime = now
            return fallback
        }

        return emptyList()
    }

    private suspend fun fetchTrendingFromTmdb(): List<SearchResponse> {
        sectionDefs.keys.forEach { loadCategory(it) }

        val movieByTmdb = mutableMapOf<Long, Triple<String, Int, PreCrawledMovie>>()
        for ((fileName, list) in cachedCategories) {
            for ((idx, movie) in list.withIndex()) {
                val tid = movie.tmdbId
                if (tid != null && !movieByTmdb.containsKey(tid)) {
                    movieByTmdb[tid] = Triple(fileName, idx, movie)
                }
            }
        }

        val apiKey = NagordolaSettingsManager.getApiKey()?.ifBlank { null } ?: DEFAULT_TMDB_API_KEY

        data class TmdbTrendingItem(val id: Long, val posterPath: String?, val backdropPath: String?)
        val trendingItems = mutableListOf<TmdbTrendingItem>()
        val seenIds = mutableSetOf<Long>()

        val endpoints = listOf(
            "trending/all/day",
            "trending/all/week",
            "movie/popular",
            "tv/popular",
            "movie/now_playing"
        )
        for (ep in endpoints) {
            for (p in 1..3) {
                try {
                    val url = "https://api.themoviedb.org/3/$ep?api_key=$apiKey&page=$p"
                    val resp = withTimeoutOrNull(5000L) {
                        app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
                    }
                    if (!resp.isNullOrBlank()) {
                        val root = JSONObject(resp)
                        val results = root.optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                val id = obj.optLong("id")
                                if (id > 0 && seenIds.add(id)) {
                                    val poster = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                                    val backdrop = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                                    trendingItems.add(TmdbTrendingItem(id, poster, backdrop))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed fetching TMDB trending ($ep page $p): ${e.message}")
                }
                if (trendingItems.count { movieByTmdb.containsKey(it.id) } >= 10) break
            }
            if (trendingItems.count { movieByTmdb.containsKey(it.id) } >= 10) break
        }

        val matchedResults = mutableListOf<SearchResponse>()
        val addedKeys = mutableSetOf<String>()

        for (item in trendingItems) {
            val match = movieByTmdb[item.id] ?: continue
            val (fileName, idx, movie) = match
            val key = "$fileName/$idx"
            if (!addedKeys.add(key)) continue

            val posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                ?: movie.posterUrl?.replace("/w342/", "/w500/")
                ?: movie.posterUrl
                ?: DEFAULT_POSTER

            val backdropUrl = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
                ?: posterUrl.replace("/w342/", "/w1280/").replace("/w500/", "/w1280/")

            val itemData = encodeItemData(fileName, idx, movie, backdropUrl)
            val yearInt = movie.year?.take(4)?.toIntOrNull()
            val scoreVal = movie.rating?.let { Score.from10(it) }
            val isTv = movie.isTvSeries || fileName.startsWith("tvshows")

            trendingBackdrops[itemData] = backdropUrl
            trendingBackdrops["nagordola://$fileName/$idx"] = backdropUrl
            trendingBackdrops["$fileName/$idx"] = backdropUrl
            movie.tmdbId?.let { cachedBackdrops[it] = backdropUrl }

            trendingPosters[itemData] = posterUrl
            trendingPosters["nagordola://$fileName/$idx"] = posterUrl
            trendingPosters["$fileName/$idx"] = posterUrl

            if (isTv) {
                matchedResults.add(
                    newAnimeSearchResponse(movie.title, itemData, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            } else {
                matchedResults.add(
                    newMovieSearchResponse(movie.title, itemData, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            }

            if (matchedResults.size >= 10) break
        }

        if (matchedResults.size < 10) {
            val fallback = getFallbackTrending()
            for (fb in fallback) {
                if (matchedResults.none { it.url == fb.url }) {
                    matchedResults.add(fb)
                }
                if (matchedResults.size >= 10) break
            }
        }

        return matchedResults
    }

    private fun getFallbackTrending(): List<SearchResponse> {
        sectionDefs.keys.forEach { loadCategory(it) }

        val candidateList = mutableListOf<Pair<String, Pair<Int, PreCrawledMovie>>>()
        for ((fileName, list) in cachedCategories) {
            for ((idx, movie) in list.withIndex()) {
                val year = movie.year?.take(4)?.toIntOrNull() ?: 0
                val rating = movie.rating ?: 0.0
                if (year >= 2022 && rating >= 7.0 && !movie.posterUrl.isNullOrBlank()) {
                    candidateList.add(fileName to (idx to movie))
                }
            }
        }

        candidateList.sortWith(
            compareByDescending<Pair<String, Pair<Int, PreCrawledMovie>>> { it.second.second.rating ?: 0.0 }
                .thenByDescending { it.second.second.year?.take(4)?.toIntOrNull() ?: 0 }
        )

        val results = mutableListOf<SearchResponse>()
        for ((fileName, pair) in candidateList) {
            val (idx, movie) = pair
            val posterUrl = movie.posterUrl?.replace("/w342/", "/w500/") ?: movie.posterUrl ?: DEFAULT_POSTER
            val backdropUrl = movie.posterUrl?.replace("/w342/", "/w1280/")?.replace("/w500/", "/w1280/") ?: DEFAULT_BACKDROP

            val itemData = encodeItemData(fileName, idx, movie, backdropUrl)
            val yearInt = movie.year?.take(4)?.toIntOrNull()
            val scoreVal = movie.rating?.let { Score.from10(it) }
            val isTv = movie.isTvSeries || fileName.startsWith("tvshows")

            trendingBackdrops[itemData] = backdropUrl
            trendingBackdrops["nagordola://$fileName/$idx"] = backdropUrl
            trendingBackdrops["$fileName/$idx"] = backdropUrl
            movie.tmdbId?.let { cachedBackdrops[it] = backdropUrl }

            trendingPosters[itemData] = posterUrl
            trendingPosters["nagordola://$fileName/$idx"] = posterUrl
            trendingPosters["$fileName/$idx"] = posterUrl

            if (isTv) {
                results.add(
                    newAnimeSearchResponse(movie.title, itemData, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(movie.title, itemData, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.year = yearInt
                        this.score = scoreVal
                    }
                )
            }

            if (results.size >= 10) break
        }

        return results
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val cleanQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        // Load all sections into memory if not loaded
        sectionDefs.keys.forEach { fileName ->
            loadCategory(fileName)
        }

        for ((fileName, list) in cachedCategories) {
            val isTv = fileName.startsWith("tvshows") || fileName.contains("anime") && fileName.contains("tv")
            for ((idx, item) in list.withIndex()) {
                if (item.title.lowercase().contains(cleanQuery)) {
                    val posterUrl = item.posterUrl?.replace("/w342/", "/w500/") ?: item.posterUrl ?: DEFAULT_POSTER
                    val backdropUrl = item.posterUrl?.replace("/w342/", "/w1280/")?.replace("/w500/", "/w1280/") ?: DEFAULT_BACKDROP
                    val itemData = encodeItemData(fileName, idx, item, backdropUrl)
                    val yearInt = item.year?.take(4)?.toIntOrNull()
                    val scoreVal = item.rating?.let { Score.from10(it) }

                    if (isTv || item.isTvSeries) {
                        results.add(
                            newAnimeSearchResponse(item.title, itemData, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.year = yearInt
                                this.score = scoreVal
                            }
                        )
                    } else {
                        results.add(
                            newMovieSearchResponse(item.title, itemData, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.year = yearInt
                                this.score = scoreVal
                            }
                        )
                    }

                    if (results.size >= 50) return@withContext results
                }
            }
        }

        results.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Live AList Directory Scraper (for Series Episodes & Seasons)
    // -------------------------------------------------------------------------

    private suspend fun scrapeAListDirectory(url: String): List<ScrapedEntry> {
        return withTimeoutOrNull(10000L) {
            try {
                val cleanBase = mainUrl.replace(Regex("/d/?$"), "/").trimEnd('/')
                val javaUrl = java.net.URL(url)
                val path = URLDecoder.decode(javaUrl.path, "UTF-8").ifEmpty { "/" }
                val cleanPath = if (path.startsWith("/d/")) path.substring(2) else path

                val jsonBody = JSONObject().apply {
                    put("path", cleanPath)
                    put("password", "")
                    put("page", 1)
                    put("per_page", 1000)
                    put("refresh", false)
                }.toString()

                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val response = app.post(
                    url = "$cleanBase/api/fs/list",
                    requestBody = requestBody,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).text

                val root = JSONObject(response)
                if (root.optInt("code") == 200) {
                    val data = root.optJSONObject("data")
                    val contentArr = data?.optJSONArray("content")
                    if (contentArr != null) {
                        val entries = mutableListOf<ScrapedEntry>()
                        for (i in 0 until contentArr.length()) {
                            val item = contentArr.getJSONObject(i)
                            val name = item.getString("name")
                            val isDir = item.getBoolean("is_dir")
                            val encodedPath = cleanPath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                            val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                            val itemUrl = "${mainUrl.trimEnd('/')}$encodedPath/$encodedName".replace(Regex("(?<!:)//+"), "/")
                            val isVideo = !isDir && name.contains(videoExtRegex)
                            entries.add(ScrapedEntry(name, itemUrl, isDir, isVideo))
                        }
                        return@withTimeoutOrNull entries
                    }
                }
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    private suspend fun fetchBackdropFromTmdb(tmdbId: Long?, isTv: Boolean): String? {
        if (tmdbId == null || tmdbId <= 0) return null
        cachedBackdrops[tmdbId]?.let { return it }
        val apiKey = NagordolaSettingsManager.getApiKey()?.ifBlank { null } ?: DEFAULT_TMDB_API_KEY
        return try {
            val type = if (isTv) "tv" else "movie"
            val resp = withTimeoutOrNull(2500L) {
                app.get("https://api.themoviedb.org/3/$type/$tmdbId?api_key=$apiKey", headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            }
            if (!resp.isNullOrBlank()) {
                val root = JSONObject(resp)
                val path = root.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                if (path != null) {
                    val fullUrl = "https://image.tmdb.org/t/p/w1280$path"
                    cachedBackdrops[tmdbId] = fullUrl
                    fullUrl
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Load Details (Movie & TV Series)
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val movie = findMovieByData(url)
        val cleanUrl = url.substringBefore('?')

        val queryParams = if (url.contains('?')) {
            url.substringAfter('?').split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to (if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else "")
            }
        } else emptyMap()

        val isTv = movie?.isTvSeries == true || queryParams["isTv"]?.toBoolean() == true || url.contains("tvshows") || url.contains("tv-series")

        // STRICT Title Resolution: Never allow numeric digits like "11", "10", "18"
        var title = movie?.title?.trim()
        if (title.isNullOrBlank() || title.all { it.isDigit() }) {
            val qTitle = queryParams["title"]?.trim()
            title = if (!qTitle.isNullOrBlank() && !qTitle.all { it.isDigit() }) {
                qTitle
            } else {
                val bare = cleanUrl.removePrefix("nagordola://").trim('/')
                val fileName = bare.substringBefore('/')
                val indexStr = bare.substringAfter('/', "")
                val indexInt = indexStr.toIntOrNull()
                val catName = sectionDefs[fileName]?.first
                if (!catName.isNullOrBlank()) {
                    if (indexInt != null) "$catName #${indexInt + 1}" else catName
                } else {
                    "Nagordola Featured"
                }
            }
        }

        val yearInt = movie?.year?.take(4)?.toIntOrNull() ?: queryParams["year"]?.take(4)?.toIntOrNull()
        val plot = movie?.overview?.ifBlank { null }
        val rating = (movie?.rating ?: queryParams["rating"]?.toDoubleOrNull())?.let { Score.from10(it) }
        val tmdbId = movie?.tmdbId ?: queryParams["tmdb"]?.toLongOrNull()?.takeIf { it > 0 }

        val poster = trendingPosters[url]
            ?: trendingPosters[cleanUrl]
            ?: movie?.posterUrl?.replace("/w342/", "/w500/")
            ?: movie?.posterUrl
            ?: queryParams["poster"]?.ifBlank { null }
            ?: DEFAULT_POSTER

        val backdrop = trendingBackdrops[url]
            ?: trendingBackdrops[cleanUrl]
            ?: tmdbId?.let { cachedBackdrops[it] }
            ?: fetchBackdropFromTmdb(tmdbId, isTv)
            ?: queryParams["backdrop"]?.ifBlank { null }
            ?: poster.replace("/w342/", "/w1280/").replace("/w500/", "/w1280/")
            ?: DEFAULT_BACKDROP

        val rawStream = movie?.videos?.firstOrNull()?.url
            ?: queryParams["stream"]?.ifBlank { null }
            ?: (if (cleanUrl.startsWith("http")) cleanUrl else null)

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seriesFolderUrl = rawStream ?: ""

            val liveEntries = if (seriesFolderUrl.startsWith("http")) {
                scrapeAListDirectory(seriesFolderUrl)
            } else emptyList()

            val seasonFolders = liveEntries.filter { it.isDirectory }
                .sortedBy { Regex("\\d+").find(it.name)?.value?.toIntOrNull() ?: 0 }

            if (seasonFolders.isNotEmpty()) {
                for (sf in seasonFolders) {
                    val seasonNum = Regex("\\d+").find(sf.name)?.value?.toIntOrNull() ?: 1
                    val seasonEntries = scrapeAListDirectory(sf.url)
                    val epVideos = seasonEntries.filter { it.isVideo }

                    for (ev in epVideos) {
                        val epMatch = Regex("S(\\d{1,2})E(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                            ?: Regex("E(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                            ?: Regex("Episode\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                        val epNum = epMatch?.groupValues?.last()?.toIntOrNull()
                        val streamUrl = toStreamUrl(ev.url)

                        episodes.add(
                            newEpisode(streamUrl) {
                                this.name = if (epNum != null) "Episode $epNum" else ev.name
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } else if (liveEntries.any { it.isVideo }) {
                val directVideos = liveEntries.filter { it.isVideo }
                for (ev in directVideos) {
                    val epMatch = Regex("S(\\d{1,2})E(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                        ?: Regex("E(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                        ?: Regex("Episode\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(ev.name)
                    val epNum = epMatch?.groupValues?.last()?.toIntOrNull()
                    val streamUrl = toStreamUrl(ev.url)

                    episodes.add(
                        newEpisode(streamUrl) {
                            this.name = if (epNum != null) "Episode $epNum" else ev.name
                            this.season = 1
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                val streamUrl = if (seriesFolderUrl.startsWith("http")) {
                    toStreamUrl(seriesFolderUrl)
                } else {
                    "${mainUrl.trimEnd('/')}/d/"
                }
                episodes.add(
                    newEpisode(streamUrl) {
                        this.name = "Play Series"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = yearInt
                this.score = rating
                tmdbId?.let { addTMDbId(it.toString()) }
            }
        } else {
            // Movie
            val streamUrl = if (!rawStream.isNullOrBlank() && rawStream.startsWith("http")) {
                toStreamUrl(rawStream)
            } else {
                url
            }

            return newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = yearInt
                this.score = rating
                tmdbId?.let { addTMDbId(it.toString()) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Load Links (Streams & Subtitles)
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.substringBefore('?')
        val movie = findMovieByData(data)
        val quality = movie?.videos?.firstOrNull()?.quality ?: "HD"

        val queryParams = if (data.contains('?')) {
            data.substringAfter('?').split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to (if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else "")
            }
        } else emptyMap()

        val rawStream = if (cleanData.startsWith("http")) {
            cleanData
        } else {
            movie?.videos?.firstOrNull()?.url
                ?: queryParams["stream"]?.ifBlank { null }
        }

        if (rawStream.isNullOrBlank() || !rawStream.startsWith("http")) {
            return false
        }

        val streamUrl = toStreamUrl(rawStream)

        callback(
            newExtractorLink(
                source = name,
                name = "$name $quality",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$mainUrl/"
                )
            }
        )

        // If multiple video qualities exist in the pre-crawled item, emit all of them
        movie?.videos?.drop(1)?.forEach { v ->
            val vRaw = v.url
            if (!vRaw.isNullOrBlank() && vRaw.startsWith("http")) {
                val vUrl = toStreamUrl(vRaw)
                if (vUrl != streamUrl) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${v.quality ?: "HD"}",
                            url = vUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer" to "$mainUrl/"
                            )
                        }
                    )
                }
            }
        }

        return true
    }
}

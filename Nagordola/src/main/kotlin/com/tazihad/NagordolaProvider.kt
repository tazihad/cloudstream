package com.tazihad

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

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

    private val sectionDefs = mapOf(
        "p/movies/movies-english" to Pair("English Movies", TvType.Movie),
        "p/movies/movies-hindi" to Pair("Hindi Movies", TvType.Movie),
        "p/movies/movies-bangla" to Pair("Bangla Movies", TvType.Movie),
        "p/movies/movies-tamil" to Pair("South Indian Movies", TvType.Movie),
        "p/movies/movies-foreign" to Pair("Foreign Movies", TvType.Movie),
        "p/movies/animations-english" to Pair("Animation Movies", TvType.AnimeMovie),
        "p/tv-series/tvshows-english" to Pair("English TV Series", TvType.TvSeries),
        "p/tv-series/tvshows-korean" to Pair("Korean TV Series", TvType.TvSeries),
        "p/tv-series/tvshows-hindi" to Pair("Hindi TV Series", TvType.TvSeries),
        "p/tv-series/tvshows-bangla" to Pair("Bangla TV Series", TvType.TvSeries),
        "p/tv-series" to Pair("All TV Series", TvType.TvSeries),
        "p/movies" to Pair("All Movies", TvType.Movie),
        "p" to Pair("Nagordola CDN Home", TvType.Movie)
    )

    override val mainPage = mainPageOf(
        *sectionDefs.map { it.key to it.value.first }.toTypedArray()
    )

    private val itemsPerPage = 20
    private val batchSize = 6
    private val sectionFetchTimeoutMs = 15_000L

    private val videoExtRegex = Regex("\\.(mp4|mkv|avi|webm|mov|m4v|flv|ts|m2ts)$", RegexOption.IGNORE_CASE)
    private val subtitleExtRegex = Regex("\\.(srt|vtt|ass|ssa)$", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("(?i)[Ss](\\d{1,2})[Ee][Pp]?(\\d{1,3})|Episode\\s*(\\d{1,3})|Ep\\s*(\\d{1,3})")
    private val yearFolderRegex = Regex("^\\(?(19|20)\\d{2}\\)?$")

    data class AListItem(
        val name: String,
        val size: Long = 0,
        val isDir: Boolean = false,
        val thumb: String? = null
    )

    data class AListFileDetail(
        val name: String? = null,
        val size: Long = 0,
        val isDir: Boolean = false,
        val rawUrl: String? = null,
        val thumb: String? = null
    )

    data class ParsedName(
        val cleanTitle: String,
        val year: Int?,
        val quality: String?,
        val isSeasonFolder: Boolean,
        val seasonNumber: Int?,
        val episodeNumber: Int?
    )

    data class ContentEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val isTvSeries: Boolean,
        val parsed: ParsedName
    )

    private companion object {
        private val sectionCache: MutableMap<String, Pair<List<ContentEntry>, Long>> =
            Collections.synchronizedMap(mutableMapOf())
        private val searchCache: MutableMap<String, Pair<NagordolaTmdbSearchResult?, Long>> =
            Collections.synchronizedMap(mutableMapOf())
        private val seasonBulkCache: MutableMap<String, Pair<Map<Int, NagordolaTmdbSeasonDetails?>, Long>> =
            Collections.synchronizedMap(mutableMapOf())

        private const val CACHE_DURATION = 15 * 60 * 1000L
        private const val TMDB_CACHE_DURATION = 30 * 60 * 1000L
    }

    private fun getFromSearchCache(key: String): NagordolaTmdbSearchResult? {
        synchronized(searchCache) {
            val (value, timestamp) = searchCache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < TMDB_CACHE_DURATION) value else null
        }
    }

    private fun addToSearchCache(key: String, value: NagordolaTmdbSearchResult?) {
        synchronized(searchCache) {
            if (searchCache.size > 150) {
                val now = System.currentTimeMillis()
                searchCache.entries.removeIf { (now - it.value.second) > TMDB_CACHE_DURATION }
                if (searchCache.size > 80) {
                    val sorted = searchCache.entries.sortedBy { it.value.second }
                    sorted.take(sorted.size - 80).forEach { searchCache.remove(it.key) }
                }
            }
            searchCache[key] = Pair(value, System.currentTimeMillis())
        }
    }

    private fun getBulkSeasonCache(key: String): Map<Int, NagordolaTmdbSeasonDetails?>? {
        synchronized(seasonBulkCache) {
            val (value, timestamp) = seasonBulkCache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < TMDB_CACHE_DURATION) value else null
        }
    }

    private fun putBulkSeasonCache(key: String, value: Map<Int, NagordolaTmdbSeasonDetails?>) {
        seasonBulkCache[key] = Pair(value, System.currentTimeMillis())
    }

    // -------------------------------------------------------------------------
    // AList API Helpers
    // -------------------------------------------------------------------------

    private fun normalizePath(rawPath: String): String {
        var p = rawPath.trim().replace("\\", "/")
        if (p.startsWith("http://") || p.startsWith("https://")) {
            p = try {
                val u = java.net.URL(p)
                URLDecoder.decode(u.path, "UTF-8")
            } catch (e: Exception) {
                p.substringAfter(mainUrl).substringAfter("/")
            }
        }
        p = p.trimStart('/')
        if (p.startsWith("d/")) p = p.substring(2)
        return "/" + p.trimStart('/')
    }

    private suspend fun executeAListListCall(targetPath: String, page: Int, perPage: Int): List<AListItem> {
        val cleanBase = mainUrl.replace(Regex("/d/?$"), "/").trimEnd('/')
        val jsonPayload = JSONObject().apply {
            put("path", targetPath)
            put("password", "")
            put("page", page)
            put("per_page", perPage)
            put("refresh", false)
        }.toString()

        val reqBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val response = app.post(
            url = "$cleanBase/api/fs/list",
            requestBody = reqBody,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).text

        val root = JSONObject(response)
        if (root.optInt("code") == 200) {
            val data = root.optJSONObject("data")
            val contentArr = data?.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                val items = mutableListOf<AListItem>()
                for (i in 0 until contentArr.length()) {
                    val obj = contentArr.getJSONObject(i)
                    items.add(
                        AListItem(
                            name = obj.optString("name"),
                            size = obj.optLong("size", 0),
                            isDir = obj.optBoolean("is_dir", false),
                            thumb = obj.optString("thumb", null)
                        )
                    )
                }
                return items
            }
        }
        return emptyList()
    }

    private suspend fun listAListDirectory(rawPath: String, page: Int = 1, perPage: Int = 1000): List<AListItem> {
        return withTimeoutOrNull(sectionFetchTimeoutMs) {
            try {
                val normalized = normalizePath(rawPath)
                // First try the normalized path (e.g. "/p/movies/movies-english")
                val primaryResult = executeAListListCall(normalized, page, perPage)
                if (primaryResult.isNotEmpty()) {
                    return@withTimeoutOrNull primaryResult
                }

                // Fallback 1: try without "/p" prefix (e.g. "/movies/movies-english")
                if (normalized.startsWith("/p/")) {
                    val fallback1 = "/" + normalized.substring(3)
                    val res1 = executeAListListCall(fallback1, page, perPage)
                    if (res1.isNotEmpty()) {
                        return@withTimeoutOrNull res1
                    }
                }

                // Fallback 2: try with "/p/" prefix if not already present
                if (!normalized.startsWith("/p")) {
                    val fallback2 = "/p" + normalized
                    val res2 = executeAListListCall(fallback2, page, perPage)
                    if (res2.isNotEmpty()) {
                        return@withTimeoutOrNull res2
                    }
                }

                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    private suspend fun getAListFile(rawPath: String): AListFileDetail? {
        return withTimeoutOrNull(sectionFetchTimeoutMs) {
            try {
                val normalized = normalizePath(rawPath)
                val cleanBase = mainUrl.replace(Regex("/d/?$"), "/").trimEnd('/')
                val jsonPayload = JSONObject().apply {
                    put("path", normalized)
                    put("password", "")
                }.toString()

                val reqBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val response = app.post(
                    url = "$cleanBase/api/fs/get",
                    requestBody = reqBody,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).text

                val root = JSONObject(response)
                if (root.optInt("code") == 200) {
                    val data = root.optJSONObject("data")
                    if (data != null) {
                        return@withTimeoutOrNull AListFileDetail(
                            name = data.optString("name"),
                            size = data.optLong("size", 0),
                            isDir = data.optBoolean("is_dir", false),
                            rawUrl = data.optString("raw_url", null),
                            thumb = data.optString("thumb", null)
                        )
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getDirectDownloadUrl(path: String): String {
        val normalized = normalizePath(path).trimStart('/')
        val encoded = normalized.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val cleanBase = mainUrl.replace(Regex("/d/?$"), "").trimEnd('/')
        return "$cleanBase/d/$encoded"
    }

    private fun parseMovieName(name: String): ParsedName {
        var clean = name.replace(videoExtRegex, "").trim()
        clean = clean.replace(Regex("^\\d{1,4}\\s*[.\\-]\\s*"), "")

        // Check for season folders
        val seasonMatch = Regex("(?i)Season\\s*(\\d{1,2})|S(\\d{1,2})").find(clean)
        val isSeason = seasonMatch != null
        val seasonNum = seasonMatch?.groupValues?.let { g ->
            g.getOrNull(1)?.toIntOrNull() ?: g.getOrNull(2)?.toIntOrNull()
        }

        // Check for episode number
        val epMatch = episodeRegex.find(clean)
        val epNum = epMatch?.groupValues?.let { g ->
            g.getOrNull(1)?.toIntOrNull() ?: g.getOrNull(2)?.toIntOrNull() ?: g.getOrNull(3)?.toIntOrNull() ?: g.getOrNull(4)?.toIntOrNull()
        }

        // Quality detection
        val qualityMatch = Regex("(?i)(2160p|4k|1080p|720p|480p|HD|WEBRip|BluRay|HDTV)").find(clean)
        val quality = qualityMatch?.value?.uppercase()

        // Year detection: (2024), [2024], or 2024 surrounded by spaces/dots
        val yearMatch = Regex("(?i)[(\\[]?\\b(19\\d{2}|20\\d{2})\\b[\\])]?").find(clean)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Clean title
        var title = clean
        if (year != null) {
            title = title.substringBefore(year.toString()).trim()
        }
        title = title.replace(Regex("(?i)(2160p|4k|1080p|720p|480p|webrip|web-dl|bluray|hdrip|dvdrip|x264|x265|hevc|aac|esub|dual audio|hindi dubbed)"), "")
            .replace(Regex("[\\[\\]()._-]"), " ")
            .trim()

        if (title.isBlank()) {
            title = clean.replace(Regex("[\\[\\]()._-]"), " ").trim()
        }

        return ParsedName(
            cleanTitle = title,
            year = year,
            quality = quality,
            isSeasonFolder = isSeason,
            seasonNumber = seasonNum,
            episodeNumber = epNum
        )
    }

    private suspend fun buildSectionFlatList(sectionPath: String, isTvSeries: Boolean): List<ContentEntry> {
        val cached = sectionCache[sectionPath]
        if (cached != null && (System.currentTimeMillis() - cached.second) < CACHE_DURATION) {
            return cached.first
        }

        val items = listAListDirectory(sectionPath)
        val entries = mutableListOf<ContentEntry>()

        for (item in items) {
            val itemPath = "$sectionPath/${item.name}"
            val parsed = parseMovieName(item.name)

            // If it is a year grouping folder like "(2024)" or "2023", expand its contents
            if (item.isDir && yearFolderRegex.matches(item.name.trim())) {
                val subItems = listAListDirectory(itemPath)
                for (sub in subItems) {
                    val subPath = "$itemPath/${sub.name}"
                    val subParsed = parseMovieName(sub.name)
                    entries.add(
                        ContentEntry(
                            name = sub.name,
                            path = subPath,
                            isDir = sub.isDir,
                            isTvSeries = isTvSeries,
                            parsed = subParsed.copy(year = subParsed.year ?: parsed.year)
                        )
                    )
                }
            } else {
                entries.add(
                    ContentEntry(
                        name = item.name,
                        path = itemPath,
                        isDir = item.isDir,
                        isTvSeries = isTvSeries,
                        parsed = parsed
                    )
                )
            }
        }

        // Sort: newest items / folders first
        val sorted = entries.sortedByDescending { it.parsed.year ?: 0 }
        sectionCache[sectionPath] = Pair(sorted, System.currentTimeMillis())
        return sorted
    }

    // -------------------------------------------------------------------------
    // Main Page & Pagination
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sectionPath = request.data
        val def = sectionDefs[sectionPath]
        val isTv = def?.second == TvType.TvSeries

        val allEntries = buildSectionFlatList(sectionPath, isTv)
        if (allEntries.isEmpty()) return null

        val startIndex = (page - 1) * itemsPerPage
        if (startIndex >= allEntries.size) return null

        val pageEntries = allEntries.drop(startIndex).take(itemsPerPage)

        val results = coroutineScope {
            pageEntries.chunked(batchSize).flatMap { batch ->
                batch.map { entry ->
                    async {
                        val tmdbKey = "${entry.parsed.cleanTitle}_${entry.parsed.year ?: 0}_$isTv"
                        val tmdb = try {
                            getFromSearchCache(tmdbKey) ?: NagordolaTmdbHelper.searchTmdb(
                                title = entry.parsed.cleanTitle,
                                year = entry.parsed.year,
                                isMovie = !isTv
                            ).also { addToSearchCache(tmdbKey, it) }
                        } catch (e: Exception) {
                            null
                        }

                        val posterUrl = tmdb?.posterPath?.let { NagordolaTmdbHelper.getPosterUrl(it, isDetail = false) }

                        if (isTv) {
                            newAnimeSearchResponse(entry.parsed.cleanTitle, entry.path, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.year = entry.parsed.year ?: tmdb?.firstAirDate?.take(4)?.toIntOrNull()
                                this.quality = entry.parsed.quality?.let { com.lagradost.cloudstream3.SearchQuality.valueOf(it) }
                                tmdb?.rating?.let { this.score = Score.from10(it) }
                            }
                        } else {
                            newMovieSearchResponse(entry.parsed.cleanTitle, entry.path, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.year = entry.parsed.year ?: tmdb?.releaseDate?.take(4)?.toIntOrNull()
                                this.quality = entry.parsed.quality?.let { com.lagradost.cloudstream3.SearchQuality.valueOf(it) }
                                tmdb?.rating?.let { this.score = Score.from10(it) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val hasNext = (startIndex + itemsPerPage) < allEntries.size
        return newHomePageResponse(request.name, results, hasNext)
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val allResults = mutableListOf<SearchResponse>()

        coroutineScope {
            val jobs = sectionDefs.keys.take(6).map { sectionPath ->
                async {
                    val isTv = sectionDefs[sectionPath]?.second == TvType.TvSeries
                    val entries = buildSectionFlatList(sectionPath, isTv)
                    entries.filter { it.parsed.cleanTitle.lowercase().contains(cleanQuery) }
                }
            }

            val matchingEntries = jobs.awaitAll().flatten().distinctBy { it.path }

            matchingEntries.take(30).chunked(batchSize).forEach { batch ->
                val batchResults = batch.map { entry ->
                    async {
                        val isTv = entry.isTvSeries
                        val tmdbKey = "${entry.parsed.cleanTitle}_${entry.parsed.year ?: 0}_$isTv"
                        val tmdb = try {
                            getFromSearchCache(tmdbKey) ?: NagordolaTmdbHelper.searchTmdb(
                                title = entry.parsed.cleanTitle,
                                year = entry.parsed.year,
                                isMovie = !isTv
                            ).also { addToSearchCache(tmdbKey, it) }
                        } catch (e: Exception) {
                            null
                        }

                        val posterUrl = tmdb?.posterPath?.let { NagordolaTmdbHelper.getPosterUrl(it, isDetail = false) }

                        if (isTv) {
                            newAnimeSearchResponse(entry.parsed.cleanTitle, entry.path, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.year = entry.parsed.year ?: tmdb?.firstAirDate?.take(4)?.toIntOrNull()
                                tmdb?.rating?.let { this.score = Score.from10(it) }
                            }
                        } else {
                            newMovieSearchResponse(entry.parsed.cleanTitle, entry.path, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.year = entry.parsed.year ?: tmdb?.releaseDate?.take(4)?.toIntOrNull()
                                tmdb?.rating?.let { this.score = Score.from10(it) }
                            }
                        }
                    }
                }.awaitAll()
                allResults.addAll(batchResults)
            }
        }

        return allResults
    }

    // -------------------------------------------------------------------------
    // Load Details (Movie & TV Series)
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val path = normalizePath(url)
        val isTv = path.contains("tv-series") || path.contains("tvshows")
        val parsed = parseMovieName(path.substringAfterLast('/'))

        val tmdb = try {
            NagordolaTmdbHelper.searchTmdb(
                title = parsed.cleanTitle,
                year = parsed.year,
                isMovie = !isTv
            )
        } catch (e: Exception) {
            null
        }

        val tmdbDetails = tmdb?.id?.let {
            try { NagordolaTmdbHelper.getTmdbDetails(it, isMovie = !isTv) } catch (e: Exception) { null }
        }
        val imdbId = tmdb?.id?.let {
            try { NagordolaTmdbHelper.getImdbIdFromTmdb(it, isMovie = !isTv) } catch (e: Exception) { null }
        }

        val posterUrl = (tmdbDetails?.posterPath ?: tmdb?.posterPath)?.let {
            NagordolaTmdbHelper.getPosterUrl(it, isDetail = true)
        }
        val backdropUrl = (tmdbDetails?.backdropPath ?: tmdb?.backdropPath)?.let {
            NagordolaTmdbHelper.getBackdropUrl(it, isDetail = true)
        }
        val rating = (tmdbDetails?.rating ?: tmdb?.rating)?.let { Score.from10(it) }
        val overview = tmdbDetails?.overview ?: tmdb?.overview
        val tags = tmdbDetails?.genres?.mapNotNull { it.name }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val items = listAListDirectory(path)
            val seasonFolders = items.filter { it.isDir && parseMovieName(it.name).isSeasonFolder }

            if (seasonFolders.isNotEmpty()) {
                val seasonNumbers = seasonFolders.mapNotNull { parseMovieName(it.name).seasonNumber }
                val bulkSeasons = if (tmdb?.id != null) {
                    val cacheKey = "${tmdb.id}_${seasonNumbers.joinToString(",")}"
                    getBulkSeasonCache(cacheKey) ?: NagordolaTmdbHelper.getAllSeasonDetails(tmdb.id, seasonNumbers).also {
                        putBulkSeasonCache(cacheKey, it)
                    }
                } else emptyMap()

                for (sf in seasonFolders) {
                    val sNum = parseMovieName(sf.name).seasonNumber ?: 1
                    val epItems = listAListDirectory("$path/${sf.name}").filter {
                        !it.isDir && it.name.contains(videoExtRegex)
                    }

                    for (epItem in epItems) {
                        val epParsed = parseMovieName(epItem.name)
                        val epNum = epParsed.episodeNumber ?: 1
                        val epTmdb = bulkSeasons[sNum]?.let { NagordolaTmdbHelper.getEpisodeFromSeasonData(it, epNum) }

                        val epTitle = epTmdb?.name ?: epParsed.cleanTitle.ifBlank { "Episode $epNum" }
                        val epStill = epTmdb?.stillPath?.let { NagordolaTmdbHelper.getStillUrl(it, isDetail = true) }

                        episodes.add(
                            newEpisode("$path/${sf.name}/${epItem.name}") {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = epNum
                                this.posterUrl = epStill ?: posterUrl
                                this.description = epTmdb?.overview
                                epTmdb?.rating?.let { this.score = Score.from10(it) }
                            }
                        )
                    }
                }
            } else {
                // Video files directly in folder
                val videoItems = items.filter { !it.isDir && it.name.contains(videoExtRegex) }
                for ((idx, vItem) in videoItems.withIndex()) {
                    val epParsed = parseMovieName(vItem.name)
                    val epNum = epParsed.episodeNumber ?: (idx + 1)
                    val sNum = epParsed.seasonNumber ?: 1

                    episodes.add(
                        newEpisode("$path/${vItem.name}") {
                            this.name = epParsed.cleanTitle.ifBlank { "Episode $epNum" }
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(parsed.cleanTitle, url, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = overview
                this.tags = tags
                this.score = rating
                this.year = parsed.year ?: tmdb?.firstAirDate?.take(4)?.toIntOrNull()
                tmdb?.id?.let { addTMDbId(it.toString()) }
                imdbId?.let { addImdbId(it) }
            }
        } else {
            return newMovieLoadResponse(parsed.cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = overview
                this.tags = tags
                this.score = rating
                this.duration = tmdbDetails?.runtime
                this.year = parsed.year ?: tmdb?.releaseDate?.take(4)?.toIntOrNull()
                tmdb?.id?.let { addTMDbId(it.toString()) }
                imdbId?.let { addImdbId(it) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Load Links (Direct Streams & Subtitles)
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = normalizePath(data)

        // Check if data is already a direct video file
        if (clean.contains(videoExtRegex)) {
            resolveAndEmitLink(clean, subtitleCallback, callback)
            return true
        }

        // If data is a directory, list it to find video and subtitle files
        val items = listAListDirectory(clean)
        val videoFiles = items.filter { !it.isDir && it.name.contains(videoExtRegex) }
        val subtitleFiles = items.filter { !it.isDir && it.name.contains(subtitleExtRegex) }

        for (sub in subtitleFiles) {
            val subUrl = getDirectDownloadUrl("$clean/${sub.name}")
            val lang = if (sub.name.contains("ban", ignoreCase = true) || sub.name.contains("bangla", ignoreCase = true)) "Bangla" else "English"
            subtitleCallback(SubtitleFile(lang, subUrl))
        }

        for (v in videoFiles) {
            val vPath = "$clean/${v.name}"
            resolveAndEmitLink(vPath, subtitleCallback, callback)
        }

        return videoFiles.isNotEmpty()
    }

    private suspend fun resolveAndEmitLink(
        filePath: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val detail = getAListFile(filePath)
        val streamUrl = detail?.rawUrl ?: getDirectDownloadUrl(filePath)
        val fileName = filePath.substringAfterLast('/')
        val parsed = parseMovieName(fileName)

        val quality = when {
            fileName.contains("2160p", ignoreCase = true) || fileName.contains("4k", ignoreCase = true) -> "4K"
            fileName.contains("1080p", ignoreCase = true) -> "1080P"
            fileName.contains("720p", ignoreCase = true) -> "720P"
            fileName.contains("480p", ignoreCase = true) -> "480P"
            else -> parsed.quality ?: "HD"
        }

        val cleanBase = mainUrl.replace(Regex("/d/?$"), "").trimEnd('/')
        callback(
            newExtractorLink(
                source = name,
                name = "$name $quality",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$cleanBase/"
                )
            }
        )
    }
}

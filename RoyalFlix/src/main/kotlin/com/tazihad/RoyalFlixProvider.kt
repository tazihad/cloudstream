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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections

open class RoyalFlixProvider : MainAPI() {
    override var mainUrl = "http://royalflix.net"
    override var name = "RoyalFlix"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    // One section per content category. Each section has one or more root
    // directories on the server. The optional mirrorRoots are the disk3
    // "movies/movies" mirror, used as a backup only when the primary roots
    // return no items (e.g. server path unavailable).
    private data class SectionDef(
        val name: String,
        val roots: List<String>,
        val mirrorRoots: List<String> = emptyList(),
        val type: TvType = TvType.Movie
    )

    private val sectionDefs: Map<String, SectionDef> = linkedMapOf(
        "hollywood-new" to SectionDef(
            "Hollywood (New) [2023-26]",
            roots = listOf("Data/disk1/movies/hollywood", "Data/disk6/movies/hollywood"),
            mirrorRoots = listOf("Data/disk3/movies/movies/hollywood")
        ),
        "hollywood-classic" to SectionDef(
            "Hollywood (2019-2021)",
            roots = listOf("Data/disk1/hollywood")
        ),
        "bollywood-new" to SectionDef(
            "Bollywood (New) [2023-24]",
            roots = listOf("Data/disk1/movies/bollywood"),
            mirrorRoots = listOf("Data/disk3/movies/movies/bollywood")
        ),
        "bollywood-classic" to SectionDef(
            "Bollywood (2000-2021)",
            roots = listOf("Data/disk2/bollywood")
        ),
        "hindi-dubbed" to SectionDef(
            "Hindi Dubbed",
            roots = listOf(
                "Data/disk1/movies/hindidub",
                "Data/disk2/movies/hindidub",
                "Data/disk4/movies/hindi%20dubbed",
                "Data/disk6/movies/hindidub"
            ),
            mirrorRoots = listOf("Data/disk3/movies/movies/hindidubbed")
        ),
        "indian-bangla" to SectionDef(
            "Indian Bangla",
            roots = listOf(
                "Data/disk1/movies/indianbangla",
                "Data/disk2/movies/indianbangla",
                "Data/disk4/movies/indianbangla",
                "Data/disk3/movies/indian%20bangla"
            ),
            mirrorRoots = listOf("Data/disk3/movies/movies/indian%20bangla")
        ),
        "bangla" to SectionDef(
            "Bangla",
            roots = listOf("Data/disk6/movies/bangla")
        ),
        "korean" to SectionDef(
            "Korean",
            roots = listOf(
                "Data/disk1/movies/korean",
                "Data/disk2/movies/korean",
                "Data/disk4/movies/korean"
            ),
            mirrorRoots = listOf("Data/disk3/movies/movies/korean")
        ),
        "tamil" to SectionDef(
            "Tamil",
            roots = listOf(
                "Data/disk1/movies/tamil",
                "Data/disk2/movies/tamil",
                "Data/disk3/movies/tamil",
                "Data/disk4/movies/tamil"
            )
        ),
        "animation" to SectionDef(
            "Animation",
            roots = listOf(
                "Data/disk1/movies/animation",
                "Data/disk2/movies/animation",
                "Data/disk4/movies/animation"
            ),
            mirrorRoots = listOf("Data/disk3/movies/movies/animation")
        ),
        "turkish" to SectionDef(
            "Turkish",
            roots = listOf("Data/disk3/movies/Turkish", "Data/disk3/movies/turkish")
        ),
        "tv-series" to SectionDef(
            "TV Series",
            roots = listOf("Data/disk6/tvseries"),
            type = TvType.TvSeries
        )
    )

    override val mainPage = mainPageOf(
        *sectionDefs.map { it.key to it.value.name }.toTypedArray()
    )

    private val itemsPerPage = 12
    private val batchSize = 6

    // Hard cap for a single folder listing request so a slow server can't stall
    // the whole home page (CloudStream wraps all sections in one big timeout).
    private val sectionFetchTimeoutMs = 15_000L
    private val maxCrawlDepth = 6

    private val videoExt = Regex("\\.(mp4|mkv|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("[Ss]\\d{1,2}[Ee][Pp]?(\\d{1,3})")

    private data class RowEntry(val name: String, val url: String, val isFolder: Boolean)
    private data class FlatEntry(val name: String, val url: String)

    private companion object {
        private val flatCache: MutableMap<String, List<FlatEntry>> = Collections.synchronizedMap(mutableMapOf())
        private val searchCache: MutableMap<String, Pair<RoyalFlixTmdbSearchResult?, Long>> =
            Collections.synchronizedMap(mutableMapOf())
        private val seasonBulkCache: MutableMap<String, Pair<Map<Int, RoyalFlixTmdbSeasonDetails?>, Long>> =
            Collections.synchronizedMap(mutableMapOf())

        private const val SEARCH_CACHE_DURATION = 30 * 60 * 1000L // 30 minutes
        private const val TMDB_CACHE_DURATION = 15 * 60 * 1000L // 15 minutes
    }

    private fun getFromSearchCache(key: String): RoyalFlixTmdbSearchResult? {
        synchronized(searchCache) {
            val (value, timestamp) = searchCache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < SEARCH_CACHE_DURATION) value else null
        }
    }

    private fun addToSearchCache(key: String, value: RoyalFlixTmdbSearchResult?) {
        synchronized(searchCache) {
            if (searchCache.size > 100) {
                val currentTime = System.currentTimeMillis()
                searchCache.entries.removeIf { (currentTime - it.value.second) > SEARCH_CACHE_DURATION }
                if (searchCache.size > 50) {
                    val sorted = searchCache.entries.sortedBy { it.value.second }
                    sorted.take(sorted.size - 50).forEach { searchCache.remove(it.key) }
                }
            }
            searchCache[key] = Pair(value, System.currentTimeMillis())
        }
    }

    private fun getBulkSeasonCache(key: String): Map<Int, RoyalFlixTmdbSeasonDetails?>? {
        synchronized(seasonBulkCache) {
            val (value, timestamp) = seasonBulkCache[key] ?: return null
            return if (System.currentTimeMillis() - timestamp < TMDB_CACHE_DURATION) value else null
        }
    }

    private fun putBulkSeasonCache(key: String, value: Map<Int, RoyalFlixTmdbSeasonDetails?>) {
        seasonBulkCache[key] = Pair(value, System.currentTimeMillis())
    }

    // -------------------------------------------------------------------------
    // Autoindex listing helpers (nginx "Index of /..." pages)
    // -------------------------------------------------------------------------

    private fun resolveUrl(base: String, href: String): String {
        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> {
                val baseDir = if (base.endsWith("/")) base else "$base/"
                baseDir + href
            }
        }
    }

    private fun isJunk(name: String): Boolean {
        val lower = name.lowercase()
        return lower in setOf("lost+found", "lostandfound", "wget-log", "parent directory") ||
            name.startsWith(".") ||
            lower.startsWith("wget-log.")
    }

    private suspend fun listFolder(url: String): List<RowEntry> {
        return withTimeoutOrNull(sectionFetchTimeoutMs) {
            runCatching {
                app.get(url).document.select("pre a[href]").mapNotNull { a ->
                    val href = a.attr("href") ?: return@mapNotNull null
                    if (href == ".." || href == "../" || href.isEmpty()) return@mapNotNull null
                    val text = a.text().trim().removeSuffix("/")
                    if (text.isBlank() || text == "Parent Directory") return@mapNotNull null
                    val isFolder = href.endsWith("/")
                    RowEntry(text, resolveUrl(url, href), isFolder)
                }
            }.getOrElse { emptyList() }
        }.orEmpty().distinctBy { it.url }
    }

    // Recursively walks an autoindex tree and returns the "content items":
    // movie folders (folders containing video files) and loose video files.
    // Container folders (year groups, subcategories, season folders for series)
    // are expanded until items are found.
    private suspend fun walkForItems(root: String, depth: Int = 0): List<FlatEntry> = coroutineScope {
        if (depth > maxCrawlDepth) return@coroutineScope emptyList()

        val rows = listFolder(root)
        if (rows.isEmpty()) return@coroutineScope emptyList()

        val folders = rows.filter { it.isFolder && !isJunk(it.name) }
        val videoFiles = rows.filter { !it.isFolder && videoExt.containsMatchIn(it.name) }

        if (videoFiles.isNotEmpty()) {
            if (folders.isEmpty()) {
                // Loose video files directly in the section root -> each is an item.
                if (depth == 0) {
                    return@coroutineScope videoFiles.map { FlatEntry(cleanFileTitle(it.name), it.url) }
                }
                // A leaf movie folder -> the folder itself is the item.
                return@coroutineScope listOf(FlatEntry(folderBaseName(root), root))
            }
            // Has videos AND extra folders (e.g. Extras) -> still a movie item.
            return@coroutineScope listOf(FlatEntry(folderBaseName(root), root))
        }

        if (folders.isEmpty()) return@coroutineScope emptyList()

        // All season-like subfolders (and no direct videos) -> this is a series item.
        val seasonLike = folders.filter { parseSeasonNumber(it.name) != null }
        if (seasonLike.isNotEmpty() && seasonLike.size == folders.size) {
            return@coroutineScope listOf(FlatEntry(folderBaseName(root), root))
        }

        // Generic container (year group / subcategory) -> expand deeper.
        val items = folders.flatMap { sub -> walkForItems(sub.url, depth + 1) }
        return@coroutineScope items.distinctBy { it.url }
    }

    private fun folderBaseName(url: String): String {
        val part = url.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: ""
        return URLDecoder.decode(part, StandardCharsets.UTF_8.toString()).trim()
    }

    private fun cleanFileTitle(name: String): String {
        return name.replace(videoExt, "")
            .replace(Regex("\\.(mkv|mp4|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE), "")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun buildFlatSection(key: String): List<FlatEntry> {
        val def = sectionDefs[key] ?: return emptyList()

        // Primary roots first; fall back to the disk3 mirror only if primary is empty.
        var items = def.roots.flatMap { walkForItems("$mainUrl/${it.trimStart('/')}") }
        if (items.isEmpty() && def.mirrorRoots.isNotEmpty()) {
            items = def.mirrorRoots.flatMap { walkForItems("$mainUrl/${it.trimStart('/')}") }
        }
        return items.distinctBy { it.url }
    }

    private suspend fun getFlatSection(key: String): List<FlatEntry> {
        flatCache[key]?.let { return it }
        val list = buildFlatSection(key)
        if (list.isNotEmpty()) {
            flatCache[key] = list
        }
        return list
    }

    // Cleans a title for TMDb lookups (drops years, quality tags, brackets).
    private fun cleanNameForSearch(name: String): String {
        return name.replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|uhd|hdr|web-?dl|blu-?ray|webrip|hdtv|nordic)\\b"), "")
            .replace(Regex("\\.(mp4|mkv|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Lazy-loaded (and cached) TMDb search result used for poster fallback on
    // the main page and for metadata in the detail view.
    private suspend fun lazyLoadTmdbData(
        name: String,
        isMovie: Boolean,
        loadDetails: Boolean = false
    ): RoyalFlixTmdbSearchResult? = coroutineScope {
        val cleanName = cleanNameForSearch(name)
        val cacheKey = "$cleanName:$isMovie"

        getFromSearchCache(cacheKey)?.let { return@coroutineScope it }

        if (!loadDetails) {
            launch {
                RoyalFlixTmdbHelper.searchTmdb(cleanName, isMovie)?.let { result ->
                    addToSearchCache(cacheKey, result)
                }
            }
            return@coroutineScope null
        }

        val result = RoyalFlixTmdbHelper.searchTmdb(cleanName, isMovie)
        addToSearchCache(cacheKey, result)
        return@coroutineScope result
    }

    // Bulk-load all TMDb season data for a series to minimize API calls.
    private suspend fun bulkLoadTvSeriesData(
        tmdbId: Int,
        seasonNumbers: List<Int>
    ): Map<Int, RoyalFlixTmdbSeasonDetails?> = coroutineScope {
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}"

        getBulkSeasonCache(cacheKey)?.let { return@coroutineScope it }

        val seasonData = RoyalFlixTmdbHelper.getAllSeasonDetails(tmdbId, seasonNumbers)
        putBulkSeasonCache(cacheKey, seasonData)
        return@coroutineScope seasonData
    }

    // -------------------------------------------------------------------------
    // Search responses
    // -------------------------------------------------------------------------

    private suspend fun toSearchResponse(entry: FlatEntry, key: String): SearchResponse {
        val name = entry.name.trim()
        val def = sectionDefs[key]
        val tvType = def?.type ?: TvType.Movie
        val isMovie = tvType == TvType.Movie || tvType == TvType.AnimeMovie

        // TMDb poster only — local folder covers are never used.
        // Use loadDetails=false (fire-and-forget background call) so the main
        // page doesn't block waiting for TMDB. Posters will populate on the
        // next page load once the cache is warm.
        val posterUrl = lazyLoadTmdbData(name, isMovie, loadDetails = false)
            ?.posterPath
            ?.let { RoyalFlixTmdbHelper.getPosterUrl(it) }

        return newAnimeSearchResponse(name, entry.url, tvType) {
            if (posterUrl?.isNotEmpty() == true) {
                this.posterUrl = posterUrl
            }
        }
    }

    // -------------------------------------------------------------------------
    // Main page
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = coroutineScope {
        val flat = getFlatSection(request.data)
        if (flat.isEmpty()) {
            return@coroutineScope null
        }

        val total = flat.size
        val safePage = maxOf(1, page)
        val startIndex = ((safePage - 1) * itemsPerPage).coerceAtMost(total)
        val endIndex = (startIndex + itemsPerPage).coerceAtMost(total)

        val slice = if (startIndex < endIndex) {
            flat.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        val home = slice.chunked(batchSize).flatMap { chunk ->
            chunk.map { entry -> async { toSearchResponse(entry, request.data) } }.awaitAll()
        }

        newHomePageResponse(request.name, home, total > endIndex)
    }

    // -------------------------------------------------------------------------
    // Search (autoindex has no search, so we walk the sections)
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        if (cleanQuery.isBlank()) return emptyList()
        val tokens = cleanQuery.split(" ").filter { it.length > 2 }

        val matches = coroutineScope {
            mainPage.map { page ->
                async {
                    getFlatSection(page.data)
                        .filter { relevance(it.name, cleanQuery, tokens) > 0 }
                        .take(40)
                        .map { it to page.data }
                }
            }.awaitAll().flatten()
        }

        val scored = matches
            .map { (entry, key) -> Triple(entry, key, relevance(entry.name, cleanQuery, tokens)) }
            .sortedByDescending { it.third }
            .take(40)

        return coroutineScope {
            scored.chunked(batchSize).flatMap { chunk ->
                chunk.map { (entry, key, _) -> async { toSearchResponse(entry, key) } }.awaitAll()
            }
        }
    }

    private fun relevance(name: String, query: String, tokens: List<String>): Int {
        val lowerName = name.lowercase()
        var score = 0
        if (lowerName.contains(query.lowercase())) score += 10
        tokens.forEach { token ->
            if (lowerName.contains(token.lowercase())) score += tokens.size + 1
        }
        return score
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val lastSegment = url.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: ""
        val decodedSegment = URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.toString())
        val isDirectFile = videoExt.containsMatchIn(decodedSegment)

        val name = if (isDirectFile) cleanFileTitle(decodedSegment) else decodedSegment.trim()
        val isAnime = url.contains("Anime")
        val isMovieContent = !url.contains("tvseries")

        val rows = if (isDirectFile) emptyList() else listFolder(url)
        val folders = rows.filter { it.isFolder }
        val videos = rows.filter { !it.isFolder && videoExt.containsMatchIn(it.name) }

        val tmdbData = lazyLoadTmdbData(name, isMovie = isMovieContent, loadDetails = true)

        val imageLink = tmdbData?.posterPath?.let { RoyalFlixTmdbHelper.getPosterUrl(it, isDetail = true) }

        var tmdbId: Int? = null
        var plot: String? = null
        var year: Int? = null
        var rating: Double? = null
        var imdbId: String? = null

        if (tmdbData != null) {
            tmdbId = tmdbData.id
            plot = tmdbData.overview
            rating = tmdbData.rating
            year = extractYear(name) ?: tmdbData.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            imdbId = tmdbId?.let { RoyalFlixTmdbHelper.getImdbIdFromTmdb(it, isMovie = isMovieContent) }
        } else {
            year = extractYear(name)
        }

        val directMovieUrl = if (isDirectFile) url else videos.firstOrNull()?.url

        if (isDirectFile) {
            val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
            newMovieLoadResponse(name, url, movieType, url) {
                this.posterUrl = imageLink
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addTMDbId(tmdbId?.toString())
                addImdbId(imdbId)
            }
        } else if (videos.isNotEmpty()) {
            // A direct video file means a movie, BUT if the video names carry an
            // episode pattern, this is a flat (single-folder) series.
            val flatSeries = videos.any { episodeRegex.containsMatchIn(it.name) }
            if (flatSeries) {
                val bulkSeasonData = if (tmdbId != null) {
                    bulkLoadTvSeriesData(tmdbId, listOf(1))
                } else emptyMap()
                val seasonData = bulkSeasonData[1]
                val episodes = videos
                    .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }
                    .mapIndexed { fileIndex, video ->
                        val episodeNum = episodeNumber(video.name) ?: (fileIndex + 1)
                        val episodeDetails = RoyalFlixTmdbHelper.getEpisodeFromSeasonData(seasonData, episodeNum)
                        newEpisode(video.url) {
                            this.name = episodeDetails?.name ?: cleanEpisodeName(video.name)
                            this.season = 1
                            this.episode = episodeNum
                            this.description = episodeDetails?.overview
                            episodeDetails?.stillPath?.let { still ->
                                this.posterUrl = RoyalFlixTmdbHelper.getStillUrl(still)
                            }
                        }
                    }
                val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
                newTvSeriesLoadResponse(name, url, tvType, episodes) {
                    this.posterUrl = imageLink
                    this.plot = plot
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    addTMDbId(tmdbId?.toString())
                    addImdbId(imdbId)
                }
            } else {
                val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
                newMovieLoadResponse(name, url, movieType, directMovieUrl!!) {
                    this.posterUrl = imageLink
                    this.plot = plot
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    addTMDbId(tmdbId?.toString())
                    addImdbId(imdbId)
                }
            }
        } else if (folders.isNotEmpty()) {
            // Season sub-folders mean this is a series.
            val seasonNumbers = folders.mapNotNull { parseSeasonNumber(it.name) }.distinct()
            val bulkSeasonData = if (tmdbId != null && seasonNumbers.isNotEmpty()) {
                bulkLoadTvSeriesData(tmdbId, seasonNumbers)
            } else emptyMap()

            val episodes = buildEpisodes(folders, bulkSeasonData)
            if (episodes.isEmpty()) {
                throw RuntimeException("No episodes found")
            }
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(name, url, tvType, episodes) {
                this.posterUrl = imageLink
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addTMDbId(tmdbId?.toString())
                addImdbId(imdbId)
            }
        } else {
            throw RuntimeException("No playable content found")
        }
    }

    private suspend fun buildEpisodes(
        seasonFolders: List<RowEntry>,
        bulkSeasonData: Map<Int, RoyalFlixTmdbSeasonDetails?> = emptyMap()
    ): List<Episode> = coroutineScope {
        val sortedFolders = seasonFolders.sortedBy { parseSeasonNumber(it.name) ?: Int.MAX_VALUE }
        val episodes = sortedFolders.flatMapIndexed { index, seasonFolder ->
            val seasonNumber = parseSeasonNumber(seasonFolder.name) ?: (index + 1)
            val seasonData = bulkSeasonData[seasonNumber]
            val seasonFiles = listFolder(seasonFolder.url)
            val seasonVideos = seasonFiles
                .filter { videoExt.containsMatchIn(it.name) }
                .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }

            seasonVideos.mapIndexed { fileIndex, video ->
                val episodeNum = episodeNumber(video.name) ?: (fileIndex + 1)
                val episodeDetails = RoyalFlixTmdbHelper.getEpisodeFromSeasonData(seasonData, episodeNum)

                newEpisode(video.url) {
                    this.name = episodeDetails?.name ?: cleanEpisodeName(video.name)
                    this.season = seasonNumber
                    this.episode = episodeNum
                    this.description = episodeDetails?.overview
                    episodeDetails?.stillPath?.let { still ->
                        this.posterUrl = RoyalFlixTmdbHelper.getStillUrl(still)
                    }
                }
            }
        }
        episodes
    }

    private fun extractYear(name: String): Int? {
        return Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    // Extracts a season number from common folder patterns ("Season 1", "S01").
    private fun parseSeasonNumber(folderName: String): Int? {
        val season = Regex("(?i)season\\s*(\\d+)").find(folderName)?.groupValues?.get(1)
        return season?.toIntOrNull()
            ?: Regex("\\b[Ss](\\d{1,2})\\b").find(folderName)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun episodeNumber(fileName: String): Int? {
        return episodeRegex.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun cleanEpisodeName(fileName: String): String {
        return fileName.replace(episodeRegex, "")
            .replace(videoExt, "")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // -------------------------------------------------------------------------
    // Direct file links
    // -------------------------------------------------------------------------

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
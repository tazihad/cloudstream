package com.tazihad

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections

open class CtgHallProvider : MainAPI() {
    override var mainUrl = "https://fs.plus.net.bd"
    override var name = "CTG Hall"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    // Sections whose children are year-group folders (e.g. "2026", "2011",
    // "2001-2010"). They are expanded into a flat list of titles ordered
    // newest-first. Every other section already lists content folders directly.
    private val yearGroupedRoots = setOf("Movies/English/", "Movies/Hindi/")

    override val mainPage = mainPageOf(
        "Movies/English/" to "English Movies",
        "Movies/Hindi/" to "Bollywood Movies",
        "Movies/South-Indian/" to "South Indian Movies",
        "Movies/Indian-Bangla/" to "Indian Bangla Movies",
        "Movies/Asian-Anime/" to "Asian & Anime Movies",
        "Shows/Anime-Shows/" to "Anime",
        "Shows/Tv-Shows/" to "TV Shows",
        "Shows/Indian-Web-Series/" to "Indian Web Series"
    )

    private val itemsPerPage = 12
    private val batchSize = 6

    // Hard cap for a single folder listing request so a slow server can't stall
    // the whole home page (CloudStream wraps all sections in one big timeout).
    private val sectionFetchTimeoutMs = 15_000L
    private val posterFetchTimeoutMs = 3_000L

    private val videoExt = Regex("\\.(mp4|mkv|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE)
    private val imageExt = Regex("\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})")

    private data class RowEntry(val name: String, val url: String, val isFolder: Boolean)
    private data class FlatEntry(val name: String, val url: String)

    private companion object {
        private val flatCache: MutableMap<String, List<FlatEntry>> = Collections.synchronizedMap(mutableMapOf())
        private val posterCache: MutableMap<String, String?> = Collections.synchronizedMap(mutableMapOf())
    }

    // -------------------------------------------------------------------------
    // Folder listing helpers
    // -------------------------------------------------------------------------

    private suspend fun listFolder(url: String, foldersOnly: Boolean): List<RowEntry> {
        return withTimeoutOrNull(sectionFetchTimeoutMs) {
            app.get(url).document.select("tbody > tr:gt(1)").mapNotNull { row ->
                val a = row.selectFirst("td.fb-n > a") ?: return@mapNotNull null
                val name = a.text()
                if (name == "Parent Directory" || name.isBlank()) return@mapNotNull null
                val isFolder = row.selectFirst("td.fb-i > img")?.attr("alt") == "folder"
                if (foldersOnly && !isFolder) return@mapNotNull null
                RowEntry(name, mainUrl + a.attr("href"), isFolder)
            }
        }.orEmpty()
    }

    // Numeric year from a year-group folder name ("2026", "2011", "2001-2010", "1900-2000").
    private fun yearFromName(name: String): Int {
        return Regex("(19|20)\\d{2}").find(name)?.value?.toIntOrNull() ?: 0
    }

    // Flat list of content folders for one section. Year-grouped sections have
    // their year folders expanded (newest year first), everything else is listed
    // directly.
    private suspend fun buildFlatSection(path: String): List<FlatEntry> {
        val rootUrl = "$mainUrl/$path"
        val rows = listFolder(rootUrl, foldersOnly = true)
        val titles = if (path in yearGroupedRoots) {
            coroutineScope {
                rows.sortedByDescending { yearFromName(it.name) }.map { year ->
                    async { listFolder(year.url, foldersOnly = true) }
                }.awaitAll().flatten()
            }
        } else {
            rows
        }
        return titles.map { FlatEntry(it.name, it.url) }
    }

    private suspend fun getFlatSection(path: String): List<FlatEntry> {
        flatCache[path]?.let { return it }
        val list = buildFlatSection(path)
        if (list.isNotEmpty()) {
            flatCache[path] = list
        }
        return list
    }

    // -------------------------------------------------------------------------
    // Posters
    // -------------------------------------------------------------------------

    private fun findPosterFromRows(rows: List<RowEntry>): String? {
        val images = rows.filter { !it.isFolder && imageExt.containsMatchIn(it.name) }
        val lower = { name: String -> name.lowercase() }
        return images.firstOrNull { lower(it.name).contains("poster") }?.url
            ?: images.firstOrNull { lower(it.name).contains("cover") }?.url
            ?: images.firstOrNull { lower(it.name).contains("landscape") }?.url
            ?: images.firstOrNull { lower(it.name).contains("thumb") }?.url
            ?: images.firstOrNull()?.url
    }

    private suspend fun findPosterLight(url: String): String? {
        posterCache[url]?.let { return it }
        val poster = withTimeoutOrNull(posterFetchTimeoutMs) {
            runCatching { findPosterFromRows(listFolder(url, foldersOnly = false)) }.getOrNull()
        } ?: null
        posterCache[url] = poster
        return poster
    }

    // -------------------------------------------------------------------------
    // Search responses
    // -------------------------------------------------------------------------

    private fun sectionTvType(path: String): TvType {
        return when {
            path.contains("Anime-Shows") -> TvType.Anime
            path.contains("Anime") -> TvType.AnimeMovie
            path.contains("Tv-Shows") || path.contains("Web-Series") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private suspend fun toSearchResponse(entry: FlatEntry, path: String): SearchResponse {
        val name = entry.name.trim()
        val posterUrl = findPosterLight(entry.url)
        return newAnimeSearchResponse(name, entry.url, sectionTvType(path)) {
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
    // Search (h5ai search is disabled on this server, so we walk the tree)
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
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }

        val scored = matches
            .map { it to relevance(it.name, cleanQuery, tokens) }
            .sortedByDescending { it.second }
            .take(40)
            .mapNotNull { (entry, _) ->
                val path = mainPage.firstOrNull { entry.url.startsWith("$mainUrl/${it.data}") }?.data ?: ""
                if (path.isNotEmpty()) entry to path else null
            }

        return coroutineScope {
            scored.chunked(batchSize).flatMap { chunk ->
                chunk.map { (entry, path) -> async { toSearchResponse(entry, path) } }.awaitAll()
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
        val rows = listFolder(url, foldersOnly = false)
        val folders = rows.filter { it.isFolder }
        val videos = rows.filter { !it.isFolder && videoExt.containsMatchIn(it.name) }

        val rawName = URLDecoder.decode(
            url.split("/").filter { it.isNotEmpty() }.last(),
            StandardCharsets.UTF_8.toString()
        )
        val name = rawName.trim()
        val isAnime = url.contains("Anime")

        var posterUrl = findPosterFromRows(rows)
        if (posterUrl == null && folders.isNotEmpty()) {
            posterUrl = findPosterLight(folders.first().url)
        }

        if (videos.isNotEmpty()) {
            // A direct video file means this is a movie (even if extra folders
            // like "Extras" exist alongside).
            val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
            newMovieLoadResponse(name, url, movieType, videos.first().url) {
                this.posterUrl = posterUrl
            }
        } else if (folders.isNotEmpty()) {
            // Season sub-folders mean this is a series.
            val episodes = buildEpisodes(folders)
            if (episodes.isEmpty()) {
                throw RuntimeException("No episodes found")
            }
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(name, url, tvType, episodes) {
                this.posterUrl = posterUrl
            }
        } else {
            throw RuntimeException("No playable content found")
        }
    }

    private suspend fun buildEpisodes(seasonFolders: List<RowEntry>): List<Episode> = coroutineScope {
        val sortedFolders = seasonFolders.sortedBy { parseSeasonNumber(it.name) ?: Int.MAX_VALUE }
        val episodes = sortedFolders.flatMapIndexed { index, seasonFolder ->
            val seasonNumber = parseSeasonNumber(seasonFolder.name) ?: (index + 1)
            val seasonFiles = listFolder(seasonFolder.url, foldersOnly = false)
            val seasonVideos = seasonFiles
                .filter { videoExt.containsMatchIn(it.name) }
                .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }

            seasonVideos.mapIndexed { fileIndex, video ->
                newEpisode(video.url) {
                    this.name = cleanEpisodeName(video.name)
                    this.season = seasonNumber
                    this.episode = episodeNumber(video.name) ?: (fileIndex + 1)
                }
            }
        }
        episodes
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
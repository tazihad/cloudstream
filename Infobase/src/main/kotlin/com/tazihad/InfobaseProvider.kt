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

open class InfobaseProvider : MainAPI() {
    override var mainUrl = "http://103.225.94.27"
    override var name = "Infobase"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.TvSeries, TvType.Anime
    )

    private data class SectionDef(
        val name: String,
        val roots: List<String>,
        val type: TvType = TvType.Movie
    )

    // Netflix-style: English → Hindi → Bangla → Asian → Niche
    private val sectionDefs: Map<String, SectionDef> = linkedMapOf(
        "hollywood" to SectionDef(
            "Hollywood",
            roots = listOf(
                "Infobase/hdd-1/English",
                "Infobase/hdd-2/English2.0",
                "Infobase/hdd-3/english",
                "Infobase/hdd-5/English .5"
            )
        ),
        "english-drama" to SectionDef(
            "English Drama",
            roots = listOf(
                "Infobase/hdd-1/english drama",
                "Infobase/hdd-2/english drama",
                "Infobase/hdd-3/English Drama",
                "Infobase/hdd-5/English Drama .5"
            ),
            type = TvType.TvSeries
        ),
        "4k" to SectionDef(
            "4K",
            roots = listOf("Infobase/hdd-1/4K", "Infobase/hdd-2/4k")
        ),
        "horror" to SectionDef(
            "Horror",
            roots = listOf("Infobase/hdd-2/Horror")
        ),
        "bollywood" to SectionDef(
            "Bollywood",
            roots = listOf(
                "Infobase/hdd-1/Hindi",
                "Infobase/hdd-2/hindi2.0",
                "Infobase/hdd-3/hindi",
                "Infobase/hdd-5/hindi.5"
            )
        ),
        "hindi-drama" to SectionDef(
            "Hindi Drama",
            roots = listOf(
                "Infobase/hdd-1/Hindi Drama",
                "Infobase/hdd-2/Hindi Drama 2.0",
                "Infobase/hdd-3/Hindi Drama",
                "Infobase/hdd-5/hindi drama .5"
            ),
            type = TvType.TvSeries
        ),
        "hindi-dubbed" to SectionDef(
            "Hindi Dubbed",
            roots = listOf(
                "Infobase/hdd-1/HINDI DUBBED",
                "Infobase/hdd-2/Hindi Dub2.0",
                "Infobase/hdd-3/Hindi Dub",
                "Infobase/hdd-5/hindi dub.5"
            )
        ),
        "bangla-bd" to SectionDef(
            "Bangla (BD)",
            roots = listOf(
                "Infobase/hdd-1/Bangla(BD)",
                "Infobase/hdd-2/Bangla",
                "Infobase/hdd-3/Bangla",
                "Infobase/hdd-5/Bangla.5"
            )
        ),
        "bangla-drama" to SectionDef(
            "Bangla Drama",
            roots = listOf(
                "Infobase/hdd-1/Bangla Drama",
                "Infobase/hdd-2/bangla drama 2.0",
                "Infobase/hdd-3/Bangla Drama",
                "Infobase/hdd-5/Bangla Drama .5"
            ),
            type = TvType.TvSeries
        ),
        "bangla-kolkata" to SectionDef(
            "Bangla (Kolkata)",
            roots = listOf(
                "Infobase/hdd-1/Bangla(Kolkata)",
                "Infobase/hdd-2/kolkata bangla"
            )
        ),
        "korean" to SectionDef(
            "Korean",
            roots = listOf("Infobase/hdd-1/Korean", "Infobase/hdd-2/Korean")
        ),
        "anime" to SectionDef(
            "Anime",
            roots = listOf("Infobase/hdd-2/Anime"),
            type = TvType.Anime
        ),
        "animation" to SectionDef(
            "Animation",
            roots = listOf("Infobase/hdd-1/Animation/English", "Infobase/hdd-3/Animated")
        ),
        "chinese-japanese" to SectionDef(
            "Chinese / Japanese",
            roots = listOf(
                "Infobase/hdd-1/China",
                "Infobase/hdd-2/Chinese and Japanes",
                "Infobase/hdd-3/Japaness, Korean,Doc,Chainess and Indo"
            )
        ),
        "punjabi" to SectionDef(
            "Punjabi",
            roots = listOf("Infobase/hdd-1/Punjabi", "Infobase/hdd-2/Panjabi")
        ),
        "pakistani" to SectionDef(
            "Pakistani",
            roots = listOf("Infobase/hdd-1/Pakistan ", "Infobase/hdd-2/Pakistani")
        ),
        "documentary" to SectionDef(
            "Documentary",
            roots = listOf("Infobase/hdd-1/Documentary", "Infobase/hdd-2/documentaries")
        )
    )

    override val mainPage = mainPageOf(
        *sectionDefs.map { it.key to it.value.name }.toTypedArray()
    )

    private val itemsPerPage = 12
    private val batchSize = 6
    private val listTimeoutMs = 12_000L

    private val videoExt = Regex("\\.(mp4|mkv|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE)
    // Matches SxxExx and SxxEPxx
    private val episodeRegex = Regex("[Ss]\\d{1,2}[Ee][Pp]?(\\d{1,3})")
    // Year folder: 4 digits or "2025-26" style
    private val yearFolderRegex = Regex("^\\d{4}(-\\d{2})?$")

    private data class RowEntry(val name: String, val url: String, val isFolder: Boolean)
    private data class FlatEntry(val name: String, val url: String)

    private companion object {
        private val flatCache: MutableMap<String, List<FlatEntry>> =
            Collections.synchronizedMap(mutableMapOf())
        private val tmdbCache: MutableMap<String, Pair<InfobaseTmdbSearchResult?, Long>> =
            Collections.synchronizedMap(mutableMapOf())
        private const val TMDB_CACHE_TTL = 30 * 60 * 1000L
    }

    // ── Apache autoindex listing ──────────────────────────────────────────────
    // This server uses Apache <table> autoindex (NOT nginx <pre>).
    // Selector: "table tr td:eq(1) a[href]"

    private fun resolveUrl(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> "$mainUrl$href"
        else -> (if (base.endsWith("/")) base else "$base/") + href
    }

    private fun isJunk(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "parent directory" || lower == "lost+found" || name.startsWith(".")
    }

    private suspend fun listFolder(url: String): List<RowEntry> {
        return withTimeoutOrNull(listTimeoutMs) {
            runCatching {
                app.get(url).document
                    .select("table tr td:eq(1) a[href]")
                    .mapNotNull { a ->
                        val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        // Skip sort/query links and parent dir
                        if (href.startsWith("?") || href == "/" || href == "/Infobase/") return@mapNotNull null
                        val text = a.text().trim().removeSuffix("/")
                        if (text.isBlank() || isJunk(text)) return@mapNotNull null
                        val isFolder = href.endsWith("/")
                        RowEntry(text, resolveUrl(url, href), isFolder)
                    }
            }.getOrElse { emptyList() }
        }.orEmpty().distinctBy { it.url }
    }

    // ── Section building ──────────────────────────────────────────────────────

    private suspend fun buildFlatSection(key: String): List<FlatEntry> {
        val def = sectionDefs[key] ?: return emptyList()
        val isSeries = def.type == TvType.TvSeries || def.type == TvType.Anime

        // Each root is isolated: one failing root won't kill other roots
        val items = def.roots.flatMap { root ->
            runCatching {
                val url = "$mainUrl/${root.trimStart('/')}/"
                val rows = listFolder(url)

                if (isSeries) {
                    // Series section: each subfolder = one show
                    rows.filter { it.isFolder }.map { FlatEntry(it.name, it.url) }
                } else {
                    // Movie section: direct .mp4 files + year-group subfolders
                    val directMovies = rows
                        .filter { !it.isFolder && videoExt.containsMatchIn(it.name) }
                        .map { FlatEntry(cleanFileTitle(it.name), it.url) }

                    val fromFolders = rows.filter { it.isFolder }.flatMap { folder ->
                        runCatching {
                            if (yearFolderRegex.matches(folder.name)) {
                                // Year bucket (e.g. "2025", "2025-26") — expand to get movies inside
                                listFolder(folder.url)
                                    .filter { !it.isFolder && videoExt.containsMatchIn(it.name) }
                                    .map { FlatEntry(cleanFileTitle(it.name), it.url) }
                            } else {
                                // Named folder treated as a single item (old-style movie subfolder)
                                listOf(FlatEntry(folder.name, folder.url))
                            }
                        }.getOrElse { emptyList() }
                    }
                    directMovies + fromFolders
                }
            }.getOrElse { emptyList<FlatEntry>() }  // root-level isolation
        }
        return items.distinctBy { it.url }
    }

    // Section-level isolation: a section throwing returns empty instead of crashing
    private suspend fun getFlatSection(key: String): List<FlatEntry> {
        flatCache[key]?.let { return it }
        val list = runCatching { buildFlatSection(key) }.getOrElse { emptyList() }
        if (list.isNotEmpty()) flatCache[key] = list
        return list
    }

    // ── TMDB helpers ──────────────────────────────────────────────────────────

    private fun cleanNameForSearch(name: String): String {
        return name
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|uhd|hdr|web-?dl|blu-?ray|webrip|hdtv|dvdrip|webhd|completed|season\\s*\\d+|episode\\s*\\d+)\\b"), "")
            .replace(Regex("\\.(mp4|mkv|avi|webm|mov|m4v)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getTmdbFromCache(key: String): InfobaseTmdbSearchResult? {
        val (v, ts) = tmdbCache[key] ?: return null
        return if (System.currentTimeMillis() - ts < TMDB_CACHE_TTL) v else null
    }

    private fun putTmdbCache(key: String, value: InfobaseTmdbSearchResult?) {
        tmdbCache[key] = Pair(value, System.currentTimeMillis())
        if (tmdbCache.size > 200) {
            val cutoff = System.currentTimeMillis() - TMDB_CACHE_TTL
            tmdbCache.entries.removeIf { it.value.second < cutoff }
        }
    }

    // blocking=false → fire-and-forget (home page); blocking=true → await (detail/load)
    private suspend fun fetchTmdb(
        name: String,
        isMovie: Boolean,
        blocking: Boolean = false
    ): InfobaseTmdbSearchResult? = coroutineScope {
        val clean = cleanNameForSearch(name)
        val key = "$clean:$isMovie"
        getTmdbFromCache(key)?.let { return@coroutineScope it }
        if (!blocking) {
            launch { InfobaseTmdbHelper.searchTmdb(clean, isMovie)?.also { putTmdbCache(key, it) } }
            return@coroutineScope null
        }
        val result = InfobaseTmdbHelper.searchTmdb(clean, isMovie)
        putTmdbCache(key, result)
        result
    }

    // ── Title helpers ─────────────────────────────────────────────────────────

    private fun cleanFileTitle(name: String): String {
        return name.replace(videoExt, "")
            .replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|uhd|hdr|web-?dl|blu-?ray|webrip|hdtv|dvdrip|webhd)\\b"), "")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanEpisodeName(fileName: String): String {
        return fileName.replace(episodeRegex, "")
            .replace(videoExt, "")
            .replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|web-?dl|blu-?ray|webrip|hdtv|dvdrip|webhd|esub)\\b"), "")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractYear(name: String): Int? =
        Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun episodeNumber(fileName: String): Int? =
        episodeRegex.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    // ── Search response ───────────────────────────────────────────────────────

    private suspend fun toSearchResponse(entry: FlatEntry, key: String): SearchResponse {
        val def = sectionDefs[key]
        val tvType = def?.type ?: TvType.Movie
        val isMovie = tvType == TvType.Movie || tvType == TvType.AnimeMovie
        // Fire-and-forget on main page: posters populate from cache on next load
        val posterUrl = fetchTmdb(entry.name, isMovie, blocking = false)
            ?.posterPath?.let { InfobaseTmdbHelper.getPosterUrl(it) }
        return newAnimeSearchResponse(entry.name.trim(), entry.url, tvType) {
            if (!posterUrl.isNullOrEmpty()) this.posterUrl = posterUrl
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = coroutineScope {
        // Per-section isolation: a failing section returns null and is skipped by CloudStream
        val flat = runCatching { getFlatSection(request.data) }.getOrElse { return@coroutineScope null }
        if (flat.isEmpty()) return@coroutineScope null

        val total = flat.size
        val startIndex = ((maxOf(1, page) - 1) * itemsPerPage).coerceAtMost(total)
        val endIndex = (startIndex + itemsPerPage).coerceAtMost(total)
        if (startIndex >= endIndex) return@coroutineScope null

        val home = flat.subList(startIndex, endIndex)
            .chunked(batchSize)
            .flatMap { chunk ->
                chunk.map { entry ->
                    async { runCatching { toSearchResponse(entry, request.data) }.getOrNull() }
                }.awaitAll()
            }
            .filterNotNull()

        newHomePageResponse(request.name, home, total > endIndex)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        if (cleanQuery.isBlank()) return emptyList()
        val tokens = cleanQuery.split(" ").filter { it.length > 2 }

        val matches = coroutineScope {
            mainPage.map { page ->
                async {
                    runCatching {
                        getFlatSection(page.data)
                            .filter { relevance(it.name, cleanQuery, tokens) > 0 }
                            .take(30)
                            .map { it to page.data }
                    }.getOrElse { emptyList() }
                }
            }.awaitAll().flatten()
        }

        val scored = matches
            .map { (entry, key) -> Triple(entry, key, relevance(entry.name, cleanQuery, tokens)) }
            .sortedByDescending { it.third }
            .take(40)

        return coroutineScope {
            scored.chunked(batchSize).flatMap { chunk ->
                chunk.map { (entry, key, _) ->
                    async { runCatching { toSearchResponse(entry, key) }.getOrNull() }
                }.awaitAll()
            }.filterNotNull()
        }
    }

    private fun relevance(name: String, query: String, tokens: List<String>): Int {
        val lower = name.lowercase()
        var score = 0
        if (lower.contains(query.lowercase())) score += 10
        tokens.forEach { if (lower.contains(it.lowercase())) score += tokens.size + 1 }
        return score
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val lastSegment = url.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: ""
        val decoded = URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.toString())
        val isDirectFile = videoExt.containsMatchIn(decoded)
        val name = if (isDirectFile) cleanFileTitle(decoded) else decoded.trim()

        // Determine if this is movie or series content from the URL path
        val isMovieSection = sectionDefs.none { (_, def) ->
            (def.type == TvType.TvSeries || def.type == TvType.Anime) &&
                def.roots.any { root -> url.contains(root, ignoreCase = true) }
        }
        val isAnime = url.contains("/Anime/", ignoreCase = true)

        val tmdbData = fetchTmdb(name, isMovieSection, blocking = true)
        val posterUrl = tmdbData?.posterPath?.let { InfobaseTmdbHelper.getPosterUrl(it, isDetail = true) }
        val tmdbId = tmdbData?.id
        val plot = tmdbData?.overview
        val rating = tmdbData?.rating
        val year = extractYear(name) ?: tmdbData?.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val imdbId = tmdbId?.let { InfobaseTmdbHelper.getImdbIdFromTmdb(it, isMovieSection) }

        if (isDirectFile) {
            val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
            newMovieLoadResponse(name, url, movieType, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
                addTMDbId(tmdbId?.toString())
                addImdbId(imdbId)
            }
        } else {
            // Series folder: flat episodes (Infobase puts all episodes directly in the show folder)
            val rows = listFolder(url)
            val videoFiles = rows.filter { !it.isFolder && videoExt.containsMatchIn(it.name) }
                .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }

            if (videoFiles.isNotEmpty()) {
                val seasonData = if (tmdbId != null) InfobaseTmdbHelper.getSeasonDetails(tmdbId, 1) else null
                val episodes = videoFiles.mapIndexed { idx, video ->
                    val epNum = episodeNumber(video.name) ?: (idx + 1)
                    val epDetails = InfobaseTmdbHelper.getEpisodeFromSeasonData(seasonData, epNum)
                    newEpisode(video.url) {
                        this.name = epDetails?.name ?: cleanEpisodeName(video.name)
                        this.season = 1
                        this.episode = epNum
                        this.description = epDetails?.overview
                        epDetails?.stillPath?.let { still -> this.posterUrl = InfobaseTmdbHelper.getStillUrl(still) }
                    }
                }
                val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
                newTvSeriesLoadResponse(name, url, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    addTMDbId(tmdbId?.toString())
                    addImdbId(imdbId)
                }
            } else if (rows.any { it.isFolder }) {
                // Fallback: season subfolders (rare on Infobase, but handle gracefully)
                val allEpisodes = rows.filter { it.isFolder }.flatMapIndexed { sIdx, folder ->
                    val seasonNum = sIdx + 1
                    val seasonData = if (tmdbId != null) InfobaseTmdbHelper.getSeasonDetails(tmdbId, seasonNum) else null
                    listFolder(folder.url)
                        .filter { !it.isFolder && videoExt.containsMatchIn(it.name) }
                        .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }
                        .mapIndexed { eIdx, video ->
                            val epNum = episodeNumber(video.name) ?: (eIdx + 1)
                            val epDetails = InfobaseTmdbHelper.getEpisodeFromSeasonData(seasonData, epNum)
                            newEpisode(video.url) {
                                this.name = epDetails?.name ?: cleanEpisodeName(video.name)
                                this.season = seasonNum
                                this.episode = epNum
                                this.description = epDetails?.overview
                                epDetails?.stillPath?.let { still -> this.posterUrl = InfobaseTmdbHelper.getStillUrl(still) }
                            }
                        }
                }
                if (allEpisodes.isEmpty()) throw RuntimeException("No episodes found at $url")
                val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
                newTvSeriesLoadResponse(name, url, tvType, allEpisodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    addTMDbId(tmdbId?.toString())
                    addImdbId(imdbId)
                }
            } else {
                throw RuntimeException("No playable content found at $url")
            }
        }
    }

    // ── Links ─────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(newExtractorLink(this.name, this.name, url = data, type = ExtractorLinkType.VIDEO))
        return true
    }
}

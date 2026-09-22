package com.tazihad

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*

data class InfobaseTmdbSearchResponse(
    @param:JsonProperty("results") val results: List<InfobaseTmdbSearchResult>? = null
)

data class InfobaseTmdbSearchResult(
    @param:JsonProperty("id") val id: Int? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("poster_path") val posterPath: String? = null,
    @param:JsonProperty("release_date") val releaseDate: String? = null,
    @param:JsonProperty("vote_average") val rating: Double? = null
)

data class InfobaseTmdbEpisodeDetails(
    @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    @param:JsonProperty("still_path") val stillPath: String? = null
)

data class InfobaseTmdbSeasonDetails(
    @param:JsonProperty("episodes") val episodes: List<InfobaseTmdbEpisodeDetails>? = null
)

data class InfobaseTmdbExternalIds(
    @param:JsonProperty("imdb_id") val imdbId: String? = null
)

object InfobaseTmdbHelper {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
    private const val TAG = "InfobaseTmdbHelper"
    private var lastApiCallTime = 0L
    private const val API_CALL_DELAY = 250L

    private fun getApiKey(): String {
        return try { InfobaseSettingsManager.getApiKey() ?: "" } catch (e: Exception) { "" }
    }

    fun getPosterUrl(path: String?, isDetail: Boolean = false): String? {
        if (path == null) return null
        val size = if (isDetail) "w185" else "w92"
        return "$TMDB_IMAGE_BASE/$size$path"
    }

    fun getStillUrl(path: String?): String? {
        if (path == null) return null
        return "$TMDB_IMAGE_BASE/w92$path"
    }

    private fun similarity(s1: String, s2: String): Double {
        val a = s1.lowercase(); val b = s2.lowercase()
        if (b.contains(a) || a.contains(b)) return 1.0
        val w1 = a.split(" ").filter { it.length > 2 }
        val w2 = b.split(" ").filter { it.length > 2 }
        if (w1.isEmpty() || w2.isEmpty()) return 0.0
        val matches = w1.count { w -> w2.any { it.contains(w) || w.contains(it) } }
        return matches.toDouble() / maxOf(w1.size, w2.size)
    }

    private suspend fun apiCall(url: String): String? {
        return try {
            val now = System.currentTimeMillis()
            val wait = API_CALL_DELAY - (now - lastApiCallTime)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val response = app.get(url).text
            lastApiCallTime = System.currentTimeMillis()
            response
        } catch (e: Exception) {
            Log.e(TAG, "API call failed: ${e.message}")
            null
        }
    }

    suspend fun searchTmdb(title: String, isMovie: Boolean = true): InfobaseTmdbSearchResult? {
        if (!InfobaseSettingsManager.isTmdbEnabled()) return null
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) return null

        val type = if (isMovie) "movie" else "tv"
        val clean = title.lowercase()
            .replace(Regex("\\[.*?\\]"), "").replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\d{3,4}p"), "").replace(Regex("\\.(mkv|mp4|avi)"), "")
            .replace(Regex("(?i)\\b(completed|season\\s*\\d+|episode\\s*\\d+)\\b"), "")
            .trim()
        val url = "$TMDB_API/search/$type?api_key=$apiKey&query=${clean.encode()}"

        return try {
            val response = apiCall(url) ?: return null
            parseJson<InfobaseTmdbSearchResponse>(response).results
                ?.maxByOrNull { similarity(clean, it.title ?: it.name ?: "") }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB search failed: ${e.message}")
            null
        }
    }

    suspend fun getImdbIdFromTmdb(tmdbId: Int, isMovie: Boolean): String? {
        if (!InfobaseSettingsManager.isTmdbEnabled()) return null
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) return null
        val type = if (isMovie) "movie" else "tv"
        return try {
            val response = apiCall("$TMDB_API/$type/$tmdbId/external_ids?api_key=$apiKey") ?: return null
            parseJson<InfobaseTmdbExternalIds>(response).imdbId
        } catch (e: Exception) { null }
    }

    suspend fun getSeasonDetails(tmdbId: Int, seasonNumber: Int): InfobaseTmdbSeasonDetails? {
        if (!InfobaseSettingsManager.isTmdbEnabled()) return null
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) return null
        return try {
            val response = apiCall("$TMDB_API/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey") ?: return null
            parseJson(response)
        } catch (e: Exception) { null }
    }

    fun getEpisodeFromSeasonData(seasonData: InfobaseTmdbSeasonDetails?, episodeNumber: Int): InfobaseTmdbEpisodeDetails? {
        return seasonData?.episodes?.find { it.episodeNumber == episodeNumber }
    }

    private fun String.encode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
}

package com.tazihad

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*

// TMDB Data Classes
data class RoyalFlixTmdbSearchResponse(
    @param:JsonProperty("results") val results: List<RoyalFlixTmdbSearchResult>? = null
)

data class RoyalFlixTmdbSearchResult(
    @param:JsonProperty("id") val id: Int? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("poster_path") val posterPath: String? = null,
    @param:JsonProperty("release_date") val releaseDate: String? = null,
    @param:JsonProperty("vote_average") val rating: Double? = null
)

data class RoyalFlixTmdbDetails(
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("poster_path") val posterPath: String? = null,
    @param:JsonProperty("vote_average") val rating: Double? = null,
    @param:JsonProperty("release_date") val releaseDate: String? = null
)

data class RoyalFlixTmdbEpisodeDetails(
    @param:JsonProperty("air_date") val airDate: String? = null,
    @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    @param:JsonProperty("still_path") val stillPath: String? = null,
    @param:JsonProperty("vote_average") val rating: Double? = null
)

data class RoyalFlixTmdbSeasonDetails(
    @param:JsonProperty("episodes") val episodes: List<RoyalFlixTmdbEpisodeDetails>? = null
)

data class RoyalFlixTmdbExternalIds(
    @param:JsonProperty("imdb_id") val imdbId: String? = null
)

object RoyalFlixTmdbHelper {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
    private const val TAG = "RoyalFlixTmdbHelper"
    private var lastApiCallTime = 0L
    private const val API_CALL_DELAY = 250L

    object ImageSizes {
        const val POSTER_SMALL = "w92"
        const val BACKDROP_SMALL = "w300"
        const val STILL_SMALL = "w92"
        const val POSTER_MEDIUM = "w185"
        const val BACKDROP_MEDIUM = "w780"
        const val STILL_MEDIUM = "w185"
        const val POSTER_LARGE = "w342"
        const val BACKDROP_LARGE = "w1280"
        const val STILL_LARGE = "w300"
    }

    private fun getApiKey(): String {
        return try {
            RoyalFlixSettingsManager.getApiKey() ?: return ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    fun getTmdbImageUrl(path: String?, size: String = ImageSizes.POSTER_SMALL): String? {
        if (path == null) return null
        return "$TMDB_IMAGE_BASE_URL/$size$path"
    }

    fun getPosterUrl(path: String?, isDetail: Boolean = false): String? {
        val size = when {
            isDetail -> ImageSizes.POSTER_MEDIUM
            else -> ImageSizes.POSTER_SMALL
        }
        return getTmdbImageUrl(path, size)
    }

    fun getBackdropUrl(path: String?, isDetail: Boolean = false): String? {
        val size = when {
            isDetail -> ImageSizes.BACKDROP_MEDIUM
            else -> ImageSizes.BACKDROP_SMALL
        }
        return getTmdbImageUrl(path, size)
    }

    fun getStillUrl(path: String?, isDetail: Boolean = false): String? {
        val size = when {
            isDetail -> ImageSizes.STILL_MEDIUM
            else -> ImageSizes.STILL_SMALL
        }
        return getTmdbImageUrl(path, size)
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val shorter = if (s1.length < s2.length) s1 else s2
        val longer = if (s1.length < s2.length) s2 else s1

        if (longer.contains(shorter)) return 1.0

        val words1 = s1.split(" ").filter { it.length > 2 }
        val words2 = s2.split(" ").filter { it.length > 2 }

        var matches = 0
        for (word in words1) {
            if (words2.any { it.contains(word) || word.contains(it) }) {
                matches++
            }
        }

        return matches.toDouble() / maxOf(words1.size, words2.size)
    }

    private suspend fun makeApiCall(url: String): String? {
        return try {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastCall = currentTime - lastApiCallTime
            if (timeSinceLastCall < API_CALL_DELAY) {
                delay(API_CALL_DELAY - timeSinceLastCall)
            }

            val response = app.get(url).text
            lastApiCallTime = System.currentTimeMillis()
            response
        } catch (e: Exception) {
            Log.e(TAG, "API call failed: ${e.message}")
            null
        }
    }

    suspend fun searchTmdb(title: String, isMovie: Boolean = true): RoyalFlixTmdbSearchResult? {
        if (!RoyalFlixSettingsManager.isTmdbEnabled()) {
            Log.d(TAG, "TMDB integration is disabled, skipping search")
            return null
        }

        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key set, skipping TMDB search")
            return null
        }

        val type = if (isMovie) "movie" else "tv"
        val cleanTitle = title.lowercase()
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\d{3,4}p"), "")
            .replace(Regex("\\.(mkv|mp4|avi)"), "")
            .trim()

        val url = "$TMDB_API/search/$type?api_key=$apiKey&query=${cleanTitle.encodeUrl()}"

        return try {
            val response = makeApiCall(url) ?: return null
            val results = parseJson<RoyalFlixTmdbSearchResponse>(response)
            results.results?.maxByOrNull { result ->
                val resultTitle = (result.title ?: result.name ?: "").lowercase()
                calculateSimilarity(cleanTitle, resultTitle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching TMDB: ${e.message}")
            null
        }
    }

    suspend fun getTmdbDetails(id: Int, isMovie: Boolean = true): RoyalFlixTmdbDetails? {
        if (!RoyalFlixSettingsManager.isTmdbEnabled()) {
            Log.d(TAG, "TMDB integration is disabled, skipping details")
            return null
        }

        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key set, skipping TMDB details")
            return null
        }

        val type = if (isMovie) "movie" else "tv"
        val url = "$TMDB_API/$type/$id?api_key=$apiKey"

        return try {
            val response = makeApiCall(url) ?: return null
            parseJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting TMDB details: ${e.message}")
            null
        }
    }

    suspend fun getSeasonDetails(
        tmdbId: Int,
        seasonNumber: Int
    ): RoyalFlixTmdbSeasonDetails? {
        if (!RoyalFlixSettingsManager.isTmdbEnabled()) {
            Log.d(TAG, "TMDB integration is disabled, skipping season details")
            return null
        }

        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key set, skipping season details")
            return null
        }

        return try {
            val url = "$TMDB_API/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey"
            val response = makeApiCall(url) ?: return null
            parseJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting season details: ${e.message}")
            null
        }
    }

    suspend fun getImdbIdFromTmdb(
        tmdbId: Int,
        isMovie: Boolean
    ): String? {
        if (!RoyalFlixSettingsManager.isTmdbEnabled()) {
            Log.d(TAG, "TMDB integration is disabled, skipping external IDs")
            return null
        }

        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key set, skipping external IDs")
            return null
        }

        val type = if (isMovie) "movie" else "tv"
        val url = "$TMDB_API/$type/$tmdbId/external_ids?api_key=$apiKey"

        return try {
            val response = makeApiCall(url) ?: return null
            parseJson<RoyalFlixTmdbExternalIds>(response).imdbId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting external IDs: ${e.message}")
            null
        }
    }

    suspend fun getAllSeasonDetails(
        tmdbId: Int,
        seasonNumbers: List<Int>
    ): Map<Int, RoyalFlixTmdbSeasonDetails?> = coroutineScope {
        if (!RoyalFlixSettingsManager.isTmdbEnabled()) {
            Log.d(TAG, "TMDB integration is disabled, skipping bulk season details")
            return@coroutineScope emptyMap()
        }

        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key set, skipping bulk season details")
            return@coroutineScope emptyMap()
        }

        val results = mutableMapOf<Int, RoyalFlixTmdbSeasonDetails?>()

        seasonNumbers.chunked(3).forEach { batch ->
            val batchResults = batch.map { seasonNumber ->
                async {
                    seasonNumber to getSeasonDetails(tmdbId, seasonNumber)
                }
            }.awaitAll().toMap()

            results.putAll(batchResults)
        }

        return@coroutineScope results
    }

    fun getEpisodeFromSeasonData(
        seasonData: RoyalFlixTmdbSeasonDetails?,
        episodeNumber: Int
    ): RoyalFlixTmdbEpisodeDetails? {
        return seasonData?.episodes?.find { it.episodeNumber == episodeNumber }
    }

    fun getEpisodeDetailsFromAnySeason(
        bulkSeasonData: Map<Int, RoyalFlixTmdbSeasonDetails?>,
        seasonNumber: Int,
        episodeNumber: Int
    ): RoyalFlixTmdbEpisodeDetails? {
        return getEpisodeFromSeasonData(bulkSeasonData[seasonNumber], episodeNumber)
    }

    private fun String.encodeUrl(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
    }

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}
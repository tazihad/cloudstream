package com.tazihad

import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object CityPlexUtils {

    private val nameRegex = Regex(""".*/([^/]+)(?:/[^/]*)*$""")

    fun nameFromUrl(href: String): String {
        val hrefDecoded = URLDecoder.decode(href, StandardCharsets.UTF_8.toString())
        val name = nameRegex.find(hrefDecoded)?.groups?.get(1)?.value
        return name.toString()
    }

    fun cleanNameForSearch(name: String): String {
        return name.replace(Regex("\\d{3,4}p.*"), "")
            .replace(Regex("\\.(mkv|mp4|avi|mov)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s*\\([^)]*TV Series[^)]*\\)"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun cleanFolderName(name: String): String {
        return name.replace(Regex("\\d{3,4}p"), "")
            .replace(Regex("\\bHDTV\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[._]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun hasMultiAudio(filename: String): Boolean {
        val multiAudioIndicators = listOf(
            "dual", "multi", "hindi", "english", "tamil", "telugu", "malayalam",
            "kannada", "bengali", "urdu", "punjabi", "gujarati", "marathi",
            "audio", "dubbed", "dub", "lang", "language", "multilang"
        )

        val lowerFilename = filename.lowercase()
        return multiAudioIndicators.any { indicator ->
            lowerFilename.contains(indicator)
        }
    }

    fun isVideoFile(name: String): Boolean {
        return name.lowercase().endsWith(".mkv") ||
            name.lowercase().endsWith(".mp4") ||
            name.lowercase().endsWith(".avi") ||
            name.lowercase().endsWith(".wmv") ||
            name.lowercase().endsWith(".mov")
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".bmp") ||
            lower.endsWith(".gif")
    }

    /**
     * Local poster finder, same priority order as DhakaFlix.
     * Looks for poster/cover file names inside the content folder.
     */
    suspend fun findPoster(contentUrl: String, mainUrl: String): String? {
        try {
            val doc = app.get(contentUrl).document

            val posterPatterns = listOf(
                "poster.jpg", "poster.jpeg", "poster.png",
                "cover.jpg", "cover.jpeg", "cover.png",
                "a_AL_.jpg", "a_AL_.jpeg", "a_AL_.png",
                "thumbnail.jpg", "thumbnail.jpeg", "thumbnail.png",
                "fanart.jpg", "fanart.jpeg", "fanart.png"
            )

            val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")

            doc.select("tbody > tr:gt(1)").forEach { row ->
                val filename = row.select("td.fb-n > a").text().lowercase()
                for (pattern in posterPatterns) {
                    if (filename == pattern) {
                        return mainUrl + row.select("td.fb-n > a").attr("href")
                    }
                }
            }

            doc.select("tbody > tr:gt(1)").forEach { row ->
                val filename = row.select("td.fb-n > a").text().lowercase()
                if (filename.contains("poster") || filename.contains("cover")) {
                    for (ext in imageExtensions) {
                        if (filename.endsWith(ext)) {
                            return mainUrl + row.select("td.fb-n > a").attr("href")
                        }
                    }
                }
            }

            doc.select("tbody > tr:gt(1)").forEach { row ->
                val filename = row.select("td.fb-n > a").text().lowercase()
                for (ext in imageExtensions) {
                    if (filename.endsWith(ext)) {
                        return mainUrl + row.select("td.fb-n > a").attr("href")
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail if we can't load the document
        }
        return null
    }

    suspend fun findPosterLight(contentUrl: String, mainUrl: String): String? {
        return try {
            findPoster(contentUrl, mainUrl)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Relevance scoring used by the folder-walk search.
     */
    fun calculateRelevance(name: String, query: String): Double {
        val cleanName = cleanNameForSearch(name).lowercase()
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim().lowercase()
        if (cleanName.isEmpty() || cleanQuery.isEmpty()) return 0.0
        if (cleanName == cleanQuery) return 1.0

        var score = 0.0
        val queryWords = cleanQuery.split(" ").filter { it.length > 1 }
        if (queryWords.isEmpty()) return 0.0

        var wordMatches = 0
        for (word in queryWords) {
            if (cleanName.contains(word)) wordMatches++
        }
        score += (wordMatches.toDouble() / queryWords.size) * 0.7

        if (cleanName.startsWith(cleanQuery)) score += 0.3
        if (cleanName.contains(cleanQuery)) score += 0.15

        return score
    }
}
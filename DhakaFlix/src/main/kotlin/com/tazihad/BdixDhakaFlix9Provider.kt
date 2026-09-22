package com.tazihad

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

class BdixDhakaFlix9Provider : BdixDhakaFlix14Provider() {
    override var mainUrl = "http://172.16.50.9"
    override var name = "(BDIX) DhakaFlix 9"
    override val tvSeriesKeyword: List<String> =
        listOf("Awards", "WWE", "KOREAN", "Documentary", "Anime")
    override val serverName: String = "DHAKA-FLIX-9"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie,
        TvType.TvSeries
    )
    override val mainPage = mainPageOf(
        "Anime %26 Cartoon TV Series/" to "Anime",
        "Awards %26 TV Shows/%23 AWARDS/" to "Awards",
        "Documentary/" to "Documentaries",
        "Tutorials/" to "Tutorials",
        "WWE %26 AEW Wrestling/WWE Wrestling/" to "WWE",
    )
}
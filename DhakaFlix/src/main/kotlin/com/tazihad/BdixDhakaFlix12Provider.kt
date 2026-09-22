package com.tazihad

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

class BdixDhakaFlix12Provider : BdixDhakaFlix14Provider() {
    override var mainUrl = "http://172.16.50.12"
    override var name = "(BDIX) DhakaFlix 12"
    override val tvSeriesKeyword: List<String> = listOf("TV-WEB-Series")
    override val serverName: String = "DHAKA-FLIX-12"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage= mainPageOf(
        "TV-WEB-Series/" to "TV Series",
    )
}
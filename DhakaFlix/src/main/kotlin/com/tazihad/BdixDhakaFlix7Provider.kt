package com.tazihad

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

class BdixDhakaFlix7Provider : BdixDhakaFlix14Provider()  {
    override var mainUrl = "http://172.16.50.7"
    override var name = "(BDIX) DhakaFlix 7"
    override val tvSeriesKeyword: List<String> = emptyList()
    override val serverName: String = "DHAKA-FLIX-7"
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage= mainPageOf(
        "English Movies/($year)/" to "English Movies 720p",
        "Foreign Language Movies/" to "Foreign Language Movies",
        "Kolkata Bangla Movies/(2022)/" to "Indian Bangla Movie",
    )
}
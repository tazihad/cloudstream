package com.tazihad

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlixHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixHubProvider())
    }
}

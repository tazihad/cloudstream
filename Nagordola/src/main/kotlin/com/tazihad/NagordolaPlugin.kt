package com.tazihad

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NagordolaPlugin : Plugin() {
    private val TAG = "NagordolaPlugin"
    var activity: AppCompatActivity? = null

    companion object {
        var pluginContext: Context? = null
        var pluginInstance: NagordolaPlugin? = null
    }

    override fun load(context: Context) {
        pluginInstance = this
        pluginContext = context
        activity = context as? AppCompatActivity
        registerMainAPI(NagordolaProvider())

        openSettings = { ctx ->
            try {
                val act = ctx as? AppCompatActivity
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    val settingsDialog = NagordolaSettings.newInstance()
                    settingsDialog.show(act.supportFragmentManager, "NagordolaSettings")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing settings dialog: ${e.message}")
            }
        }
    }
}

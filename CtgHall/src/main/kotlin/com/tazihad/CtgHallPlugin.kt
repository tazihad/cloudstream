package com.tazihad

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CtgHallPlugin : Plugin() {
    private val TAG = "CtgHallPlugin"
    var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as AppCompatActivity
        registerMainAPI(CtgHallProvider())

        openSettings = { ctx ->
            try {
                val act = ctx as? AppCompatActivity
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    val settingsDialog = CtgHallSettings.newInstance()
                    settingsDialog.show(act.supportFragmentManager, "CtgHallSettings")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing settings dialog: ${e.message}")
            }
        }
    }
}
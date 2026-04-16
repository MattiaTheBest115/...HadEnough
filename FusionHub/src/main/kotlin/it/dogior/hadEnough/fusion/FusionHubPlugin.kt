package it.dogior.hadEnough.fusion

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FusionHubPlugin : Plugin() {
    companion object {
        const val SETTINGS_TAG = "FusionHubSettings"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(
            FusionStreamingCommunitySource.PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        registerMainAPI(FusionHub(context))
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            FusionHubSettings(this, sharedPref).show(activity.supportFragmentManager, SETTINGS_TAG)
        }
    }
}

package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AnimeUnityHomeSection(
    val key: String,
    val title: String,
) {
    LATEST_EPISODES("latestEpisodes", "Ultimi Episodi"),
    RANDOM("random", "Random"),
    CALENDAR("calendar", "Calendario"),
    ONGOING("ongoing", "In Corso"),
    POPULAR("popular", "Popolari"),
    BEST("best", "I migliori"),
    UPCOMING("upcoming", "In Arrivo");

    companion object {
        fun fromKey(key: String?): AnimeUnityHomeSection? {
            return entries.firstOrNull { it.key == key }
        }

        fun fromTitle(title: String?): AnimeUnityHomeSection? {
            return entries.firstOrNull { it.title == title }
        }
    }
}

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "AnimeUnity"
        const val DEFAULT_MAIN_URL = "https://www.animeunity.so"
        const val PREF_BASE_URL = "baseUrl"
        const val PREF_SECTION_ORDER = "sectionOrder"
        const val PREF_SHOW_LATEST_EPISODES = "showLatestEpisodes"
        const val PREF_SHOW_RANDOM = "showRandom"
        const val PREF_SHOW_CALENDAR = "showCalendar"
        const val PREF_SHOW_ONGOING = "showOngoing"
        const val PREF_SHOW_POPULAR = "showPopular"
        const val PREF_SHOW_BEST = "showBest"
        const val PREF_SHOW_UPCOMING = "showUpcoming"
        const val PREF_SHOW_SCORE = "showScore"
        const val PREF_SHOW_DUB_SUB = "showDubSub"
        const val PREF_SHOW_EPISODE_NUMBER = "showEpisodeNumber"

        fun getConfiguredBaseUrl(sharedPref: SharedPreferences?): String {
            return normalizeBaseUrl(sharedPref?.getString(PREF_BASE_URL, null)) ?: DEFAULT_MAIN_URL
        }

        fun normalizeBaseUrl(rawBaseUrl: String?): String? {
            val trimmedValue = rawBaseUrl?.trim()?.trimEnd('/').takeUnless { it.isNullOrBlank() }
                ?: return null
            val withScheme = if (
                trimmedValue.startsWith("http://") || trimmedValue.startsWith("https://")
            ) {
                trimmedValue
            } else {
                "https://$trimmedValue"
            }

            return withScheme.toHttpUrlOrNull()
                ?.newBuilder()
                ?.encodedPath("/")
                ?.query(null)
                ?.fragment(null)
                ?.build()
                ?.toString()
                ?.trimEnd('/')
        }

        fun getOrderedSections(sharedPref: SharedPreferences?): List<AnimeUnityHomeSection> {
            val storedOrder = sharedPref?.getString(PREF_SECTION_ORDER, null)
            val orderedSections = storedOrder
                ?.split(',')
                ?.mapNotNull { AnimeUnityHomeSection.fromKey(it.trim()) }
                .orEmpty()

            if (orderedSections.isEmpty()) {
                return AnimeUnityHomeSection.entries
            }

            val missingSections = AnimeUnityHomeSection.entries.filterNot { it in orderedSections }
            return (orderedSections + missingSections).distinct()
        }

        fun getSectionEnabledPref(section: AnimeUnityHomeSection): String {
            return when (section) {
                AnimeUnityHomeSection.LATEST_EPISODES -> PREF_SHOW_LATEST_EPISODES
                AnimeUnityHomeSection.RANDOM -> PREF_SHOW_RANDOM
                AnimeUnityHomeSection.CALENDAR -> PREF_SHOW_CALENDAR
                AnimeUnityHomeSection.ONGOING -> PREF_SHOW_ONGOING
                AnimeUnityHomeSection.POPULAR -> PREF_SHOW_POPULAR
                AnimeUnityHomeSection.BEST -> PREF_SHOW_BEST
                AnimeUnityHomeSection.UPCOMING -> PREF_SHOW_UPCOMING
            }
        }

        fun getSectionItemCountPref(section: AnimeUnityHomeSection): String {
            return "sectionItemCount_${section.key}"
        }

        fun getSectionItemCount(sharedPref: SharedPreferences?, section: AnimeUnityHomeSection): Int {
            return sharedPref?.getInt(getSectionItemCountPref(section), 30)?.coerceIn(1, 30) ?: 30
        }
    }

    private var sharedPref: SharedPreferences? = null

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        registerMainAPI(AnimeUnity(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            AnimeUnitySettings(this, sharedPref).show(activity.supportFragmentManager, "AnimeUnitySettings")
        }
    }
}

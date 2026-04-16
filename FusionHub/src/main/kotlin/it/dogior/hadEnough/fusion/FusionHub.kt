package it.dogior.hadEnough.fusion

import android.content.Context
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl

class FusionHub(context: Context) : MainAPI() {
    private val sharedPref = context.getSharedPreferences(
        FusionStreamingCommunitySource.PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val animeUnity = FusionAnimeUnitySource(this)
    private val animeWorld = FusionAnimeWorldSource(context, this)
    private val streamingCommunity = FusionStreamingCommunitySource(context, this)
    private val mergedAnimeByPrimaryUrl = mutableMapOf<String, AnimeMergeEntry>()

    override var mainUrl = FusionStreamingCommunitySource.resolveBaseUrl(
        sharedPref.getString(FusionStreamingCommunitySource.PREF_BASE_URL, null)
    )
    override var name = "Fusion Hub"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
        TvType.Documentary,
    )

    override val mainPage = mainPageOf(
        "anime:ongoing" to "Anime in corso",
        "anime:popular" to "Anime popolari",
        "anime:top" to "Anime top",
        "anime:latest" to "Anime novita",
        "sc:top10" to "Film e serie top 10",
        "sc:trending" to "Film e serie in tendenza",
        "sc:latest" to "Ultime aggiunte",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = when (request.data) {
            "anime:ongoing" -> mergeAnimeSections(
                animeUnity.homeSection("ongoing", page),
                animeWorld.homeSection("ongoing", page),
            )

            "anime:popular" -> mergeAnimeSections(
                animeUnity.homeSection("popular", page),
                animeWorld.homeSection("popular", page),
            )

            "anime:top" -> mergeAnimeSections(
                animeUnity.homeSection("top", page),
                animeWorld.homeSection("top", page),
            )

            "anime:latest" -> mergeAnimeSections(
                animeUnity.homeSection("upcoming", page),
                animeWorld.homeSection("latest", page),
            )

            "sc:top10" -> if (page == 1) streamingCommunity.homeSection(0) else SourcePage(emptyList(), false)
            "sc:trending" -> if (page == 1) streamingCommunity.homeSection(1) else SourcePage(emptyList(), false)
            "sc:latest" -> if (page == 1) streamingCommunity.homeSection(2) else SourcePage(emptyList(), false)
            else -> SourcePage(emptyList(), false)
        }

        return newHomePageResponse(
            HomePageList(request.name, section.items, isHorizontalImages = false),
            section.hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val bundle = searchAll(query, 1)
        return bundle.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val bundle = searchAll(query, page)
        return newSearchResponseList(bundle.items, bundle.hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        return when (providerForUrl(url)) {
            Provider.ANIME_UNITY -> animeUnity.load(url)
            Provider.ANIME_WORLD -> animeWorld.load(url)
            Provider.STREAMING_COMMUNITY -> streamingCommunity.load(url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = parseJson<FusionLinkPayload>(data)
        val seenLinks = mutableSetOf<String>()
        val seenSubtitles = mutableSetOf<String>()

        val dedupedSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            val key = "${subtitle.lang}:${subtitle.url}"
            if (seenSubtitles.add(key)) {
                subtitleCallback(subtitle)
            }
        }

        val dedupedCallback: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) {
                callback(link)
            }
        }

        if (payload.provider !in setOf("animeunity", "animeworld")) {
            return streamingCommunity.loadLinks(data, dedupedSubtitleCallback, dedupedCallback)
        }

        val animePayloads = mutableMapOf<String, String>()
        animePayloads[payload.provider] = data

        if (payload.episodeNumber != null) {
            when (payload.provider) {
                "animeunity" -> {
                    resolveAnimeWorldPayload(payload)?.let { animePayloads["animeworld"] = it }
                }

                "animeworld" -> {
                    resolveAnimeUnityPayload(payload)?.let { animePayloads["animeunity"] = it }
                }
            }
        }

        var loaded = false
        for (provider in animeProviderOrder()) {
            val providerPayload = animePayloads[provider] ?: continue
            when (provider) {
                "animeunity" -> {
                    loaded = animeUnity.loadLinks(
                        providerPayload,
                        dedupedSubtitleCallback,
                        dedupedCallback,
                    ) || loaded
                }

                "animeworld" -> {
                    loaded = animeWorld.loadLinks(
                        providerPayload,
                        dedupedSubtitleCallback,
                        dedupedCallback,
                    ) || loaded
                }
            }
        }

        return loaded
    }

    private suspend fun searchAll(query: String, page: Int): SourcePage {
        val animeUnityResults = if (page == 1) animeUnity.search(query) else emptyList()
        val animeWorldResults = animeWorld.search(query, page)
        val streamingResults = streamingCommunity.search(query, page)

        return SourcePage(
            items = mergeAnimeResults(animeUnityResults, animeWorldResults.items) + streamingResults.items.map(::cloneSearchResponse),
            hasNext = animeWorldResults.hasNext || streamingResults.hasNext,
        )
    }

    private fun mergeAnimeSections(
        animeUnitySection: SourcePage,
        animeWorldSection: SourcePage,
    ): SourcePage {
        return SourcePage(
            items = mergeAnimeResults(animeUnitySection.items, animeWorldSection.items),
            hasNext = animeUnitySection.hasNext || animeWorldSection.hasNext,
        )
    }

    private fun mergeAnimeResults(
        animeUnityResults: List<SearchResponse>,
        animeWorldResults: List<SearchResponse>,
    ): List<SearchResponse> {
        val groups = animeUnityResults.map { mutableListOf(AnimeCandidate(AnimeProviderId.ANIME_UNITY, it)) }
            .toMutableList()

        animeWorldResults.forEach { response ->
            val aliases = animeWorld.aliasesFor(response)
            val group = groups.firstOrNull { candidates ->
                titlesMatch(
                    candidates.flatMap(::aliasesFor),
                    aliases,
                )
            }

            if (group != null) {
                group += AnimeCandidate(AnimeProviderId.ANIME_WORLD, response)
            } else {
                groups += mutableListOf(AnimeCandidate(AnimeProviderId.ANIME_WORLD, response))
            }
        }

        return groups.map(::buildMergedAnimeResponse)
    }

    private fun buildMergedAnimeResponse(candidates: List<AnimeCandidate>): SearchResponse {
        val preferredProvider = preferredAnimeProvider()
        val primary = candidates.firstOrNull { it.provider == preferredProvider }
            ?: candidates.first()
        val secondary = candidates.firstOrNull { it.provider != primary.provider }?.response
        val response = primary.response
        val poster = response.posterUrl ?: secondary?.posterUrl
        val type = response.type ?: secondary?.type ?: TvType.Anime
        val hasDub = candidates.any(::hasDub)
        val hasSub = candidates.any(::hasSub)
        val allAliases = candidates.flatMap(::aliasesFor).distinct()

        if (secondary != null) {
            mergedAnimeByPrimaryUrl[response.url] = AnimeMergeEntry(
                primaryProvider = primary.provider,
                primaryUrl = response.url,
                secondaryProvider = candidates.first { it.response.url == secondary.url }.provider,
                secondaryUrl = secondary.url,
                aliases = allAliases,
            )
        } else {
            mergedAnimeByPrimaryUrl.remove(response.url)
        }

        return newAnimeSearchResponse(response.name, response.url, type) {
            posterUrl = poster
            when {
                hasDub -> addDubStatus(true)
                hasSub -> addDubStatus(false)
            }
        }
    }

    private fun cloneSearchResponse(response: SearchResponse): SearchResponse {
        val type = response.type ?: TvType.Movie
        return when (type) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> {
                newAnimeSearchResponse(response.name, response.url, type) {
                    posterUrl = response.posterUrl
                }
            }

            TvType.TvSeries -> {
                newTvSeriesSearchResponse(response.name, response.url, type) {
                    posterUrl = response.posterUrl
                }
            }

            else -> {
                newMovieSearchResponse(response.name, response.url, type) {
                    posterUrl = response.posterUrl
                }
            }
        }
    }

    private fun aliasesFor(candidate: AnimeCandidate): List<String> {
        return when (candidate.provider) {
            AnimeProviderId.ANIME_UNITY -> animeUnity.aliasesFor(candidate.response)
            AnimeProviderId.ANIME_WORLD -> animeWorld.aliasesFor(candidate.response)
        }
    }

    private fun hasDub(candidate: AnimeCandidate): Boolean {
        return when (candidate.provider) {
            AnimeProviderId.ANIME_UNITY -> animeUnity.hasDub(candidate.response)
            AnimeProviderId.ANIME_WORLD -> animeWorld.hasDub(candidate.response)
        }
    }

    private fun hasSub(candidate: AnimeCandidate): Boolean {
        return when (candidate.provider) {
            AnimeProviderId.ANIME_UNITY -> animeUnity.hasSub(candidate.response)
            AnimeProviderId.ANIME_WORLD -> animeWorld.hasSub(candidate.response)
        }
    }

    private fun providerForUrl(url: String): Provider {
        val lowerUrl = url.lowercase()
        return when {
            "animeunity" in lowerUrl -> Provider.ANIME_UNITY
            "animeworld" in lowerUrl -> Provider.ANIME_WORLD
            lowerUrl.contains(streamingHost()) || "streamingunity" in lowerUrl || "streamingcommunity" in lowerUrl -> {
                Provider.STREAMING_COMMUNITY
            }

            else -> Provider.STREAMING_COMMUNITY
        }
    }

    private fun streamingHost(): String {
        return runCatching { mainUrl.toHttpUrl().host.lowercase() }.getOrDefault("")
    }

    private suspend fun resolveAnimeWorldPayload(payload: FusionLinkPayload): String? {
        val mappedUrl = mergedAnimeByPrimaryUrl[payload.url]
            ?.takeIf { it.secondaryProvider == AnimeProviderId.ANIME_WORLD }
            ?.secondaryUrl
        val showUrl = mappedUrl ?: animeWorld.findMatch(payload.aliases)?.url ?: return null
        return animeWorld.buildEpisodePayload(
            showUrl = showUrl,
            episodeNumber = payload.episodeNumber ?: return null,
            aliases = payload.aliases,
        )
    }

    private suspend fun resolveAnimeUnityPayload(payload: FusionLinkPayload): String? {
        val mappedUrl = mergedAnimeByPrimaryUrl[payload.url]
            ?.takeIf { it.secondaryProvider == AnimeProviderId.ANIME_UNITY }
            ?.secondaryUrl
        val showUrl = mappedUrl ?: animeUnity.findMatch(payload.aliases)?.url ?: return null
        return animeUnity.buildEpisodePayload(
            showUrl = showUrl,
            episodeNumber = payload.episodeNumber ?: return null,
            aliases = payload.aliases,
        )
    }

    private fun preferredAnimeProvider(): AnimeProviderId {
        return when (sharedPref.getString(PREF_ANIME_PROVIDER_PRIORITY, DEFAULT_ANIME_PROVIDER_PRIORITY)) {
            ANIMEWORLD_PRIORITY -> AnimeProviderId.ANIME_WORLD
            else -> AnimeProviderId.ANIME_UNITY
        }
    }

    private fun animeProviderOrder(): List<String> {
        return when (preferredAnimeProvider()) {
            AnimeProviderId.ANIME_WORLD -> listOf("animeworld", "animeunity")
            AnimeProviderId.ANIME_UNITY -> listOf("animeunity", "animeworld")
        }
    }

    private enum class Provider {
        ANIME_UNITY,
        ANIME_WORLD,
        STREAMING_COMMUNITY,
    }

    companion object {
        const val PREF_ANIME_PROVIDER_PRIORITY = "fusionAnimeProviderPriority"
        const val ANIMEUNITY_PRIORITY = "animeunity"
        const val ANIMEWORLD_PRIORITY = "animeworld"
        const val DEFAULT_ANIME_PROVIDER_PRIORITY = ANIMEUNITY_PRIORITY
    }
}

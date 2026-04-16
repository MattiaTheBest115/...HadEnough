package it.dogior.hadEnough.fusion

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class FusionStreamingCommunitySource(
    context: Context,
    private val api: MainAPI,
) {
    private val sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lang = sharedPref.getString("lang", "it") ?: "it"
    private val siteRootUrl = resolveBaseUrl(sharedPref.getString(PREF_BASE_URL, null))
    private val siteHost = siteRootUrl.toHttpUrl().host
    private val cdnHost = resolveCdnHost(siteHost)
    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val mainUrl = siteRootUrl + lang
    private val headers = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to inertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    )

    suspend fun homeSection(index: Int): SourcePage {
        val sections = fetchHomeSections()
        val section = sections.getOrNull(index) ?: return SourcePage(emptyList(), false)
        return SourcePage(section.list, false)
    }

    suspend fun search(query: String, page: Int = 1): SourcePage {
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val response = app.get("$mainUrl/search", params = params).body.string()
        val titles = parseBrowseTitles(response) ?: emptyList()
        val items = buildSearchResponses(titles)
        return SourcePage(items, items.isNotEmpty() && items.size >= 60)
    }

    suspend fun load(url: String): LoadResponse {
        val actualUrl = actualUrl(url)
        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }

        val response = app.get(actualUrl, headers = headers)
        val props = parseJson<ScInertiaResponse>(response.body.string()).props
        val title = props.title ?: error("StreamingCommunity title missing")
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.youtubeUrl() }
        val poster = getPoster(title)

        return if (title.type == "tv") {
            val episodes = getEpisodes(props)
            with(api) {
                newTvSeriesLoadResponse(title.name, actualUrl, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    title.backgroundImageId()?.let { backgroundPosterUrl = "https://$cdnHost/images/$it" }
                    tags = genres
                    this.episodes = episodes
                    this.year = year
                    plot = title.plot
                    title.age?.let { contentRating = "$it+" }
                    recommendations = related?.titles?.let(::buildSearchResponses)
                    title.imdbId?.let { addImdbId(it) }
                    title.tmdbId?.let { addTMDbId(it.toString()) }
                    addActors(title.mainActors?.map { it.name })
                    addScore(title.score)
                    if (!trailers.isNullOrEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
        } else {
            val payload = FusionLinkPayload(
                provider = PROVIDER_ID,
                url = "$mainUrl/iframe/${title.id}&canPlayFHD=1",
                mediaType = "movie",
                tmdbId = title.tmdbId,
            ).serialize()

            with(api) {
                newMovieLoadResponse(title.name, actualUrl, TvType.Movie, payload) {
                    posterUrl = poster
                    title.backgroundImageId()?.let { backgroundPosterUrl = "https://$cdnHost/images/$it" }
                    tags = genres
                    this.year = year
                    plot = title.plot
                    title.age?.let { contentRating = "$it+" }
                    recommendations = related?.titles?.let(::buildSearchResponses)
                    addActors(title.mainActors?.map { it.name })
                    addScore(title.score)
                    title.imdbId?.let { addImdbId(it) }
                    title.tmdbId?.let { addTMDbId(it.toString()) }
                    title.runtime?.let { duration = it }
                    if (!trailers.isNullOrEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = parseJson<FusionLinkPayload>(data)
        val episodeResponse = app.get(actualUrl(payload.url))
        val finalUrl = episodeResponse.okhttpResponse.request.url.toString()
        val iframeSrc = episodeResponse.document.select("iframe").attr("src").ifBlank {
            when {
                finalUrl.contains("vixcloud", ignoreCase = true) -> finalUrl
                finalUrl.contains("vixsrc", ignoreCase = true) -> finalUrl
                else -> ""
            }
        }

        var loaded = false
        if (iframeSrc.isNotBlank()) {
            when {
                iframeSrc.contains("vixsrc", ignoreCase = true) -> {
                    FusionVixSrcExtractor().getUrl(
                        url = iframeSrc,
                        referer = "https://vixsrc.to/",
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    loaded = true
                }

                iframeSrc.contains("vixcloud", ignoreCase = true) -> {
                    FusionVixCloudExtractor("StreamingCommunity", useCloudflare = true).getUrl(
                        url = iframeSrc,
                        referer = siteRootUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    loaded = true
                }

                else -> {
                    loaded = loadExtractor(iframeSrc, siteRootUrl, subtitleCallback, callback)
                }
            }
        }

        val tmdbId = payload.tmdbId
        val vixSrcUrl = when (payload.mediaType) {
            "movie" -> tmdbId?.let { "https://vixsrc.to/movie/$it" }
            else -> {
                val season = payload.seasonNumber
                val episode = payload.episodeNumber
                if (tmdbId != null && season != null && episode != null) {
                    "https://vixsrc.to/tv/$tmdbId/$season/$episode"
                } else {
                    null
                }
            }
        }

        if (vixSrcUrl != null) {
            FusionVixSrcExtractor().getUrl(
                url = vixSrcUrl,
                referer = "https://vixsrc.to/",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
            loaded = true
        }

        return loaded
    }

    private suspend fun fetchHomeSections(): List<HomePageList> {
        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }

        val allSections = mutableListOf<HomePageList>()
        sliderRequestBody.sliders.chunked(6).forEach { chunk ->
            val response = app.post(
                "${siteRootUrl}api/sliders/fetch?lang=$lang",
                requestBody = ScSliderFetchRequestBody(chunk).toRequestBody(),
                headers = sliderHeaders(),
            )

            allSections += parseSliderSections(response.body.string())
        }

        return allSections
    }

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${siteRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        headers["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        decodedXsrfToken = cookieJar["XSRF-TOKEN"]
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: ""

        val page = response.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject.substringAfter("\"version\":\"").substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun sliderHeaders(): Map<String, String> {
        return mapOf(
            "Cookie" to (headers["Cookie"] ?: ""),
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to decodedXsrfToken,
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to siteRootUrl.removeSuffix("/"),
        )
    }

    private fun parseBrowseTitles(payload: String): List<ScTitle>? {
        val jsonPayload = if (isHtml(payload)) {
            val raw = org.jsoup.Jsoup.parse(payload).selectFirst("#app")?.attr("data-page")
            if (raw.isNullOrBlank()) return null
            Parser.unescapeEntities(raw, true)
        } else {
            payload
        }

        return parseJson<ScInertiaResponse>(jsonPayload).props.titles
    }

    private fun parseSliderSections(payload: String): List<HomePageList> {
        if (payload.isBlank() || isHtml(payload)) return emptyList()

        return parseJson<List<ScSlider>>(payload).mapNotNull { slider ->
            val items = buildSearchResponses(slider.titles)
            if (items.isEmpty()) {
                null
            } else {
                HomePageList(
                    name = slider.label.ifBlank { slider.name },
                    list = items,
                    isHorizontalImages = false,
                )
            }
        }
    }

    private fun buildSearchResponses(titles: List<ScTitle>): List<SearchResponse> {
        return titles.filter { it.type == "movie" || it.type == "tv" }.map { title ->
            val url = "$mainUrl/titles/${title.id}-${title.slug}"
            if (title.type == "tv") {
                with(api) {
                    newTvSeriesSearchResponse(title.name, url, TvType.TvSeries) {
                        posterUrl = "https://$cdnHost/images/${title.posterImageId()}"
                    }
                }
            } else {
                with(api) {
                    newMovieSearchResponse(title.name, url, TvType.Movie) {
                        posterUrl = "https://$cdnHost/images/${title.posterImageId()}"
                    }
                }
            }
        }
    }

    private suspend fun getPoster(title: ScTitleProp): String? {
        return if (title.tmdbId != null) {
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val resp = app.get(tmdbUrl).document
            resp.select("img.poster.w-full").attr("srcset").split(", ").lastOrNull()
        } else {
            title.backgroundImageId()?.let { "https://$cdnHost/images/$it" }
        }
    }

    private suspend fun getEpisodes(props: ScProps): List<Episode> {
        val title = props.title ?: return emptyList()
        val result = mutableListOf<Episode>()

        title.seasons?.forEach { season ->
            val responseEpisodes = mutableListOf<ScEpisode>()
            if (season.id == props.loadedSeason?.id) {
                responseEpisodes += props.loadedSeason.episodes.orEmpty()
            } else {
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj = parseJson<ScInertiaResponse>(app.get(url, headers = headers).body.string())
                responseEpisodes += obj.props.loadedSeason?.episodes.orEmpty()
            }

            responseEpisodes.forEach { episode ->
                result += with(api) {
                    newEpisode(
                        FusionLinkPayload(
                            provider = PROVIDER_ID,
                            url = "$mainUrl/iframe/${title.id}?episode_id=${episode.id}&canPlayFHD=1",
                            mediaType = "tv",
                            tmdbId = title.tmdbId,
                            seasonNumber = season.number,
                            episodeNumber = episode.number,
                            aliases = buildAliases(title.name),
                        ).serialize()
                    ) {
                        name = episode.name
                        posterUrl = props.cdnUrl + "/images/" + episode.coverImageId()
                        description = episode.plot
                        this.episode = episode.number
                        this.season = season.number
                        runTime = episode.duration
                    }
                }
            }
        }

        return result
    }

    private fun actualUrl(url: String): String {
        if (url.contains(mainUrl)) return url

        val replacement = if (url.contains("/it/") || url.contains("/en/")) {
            mainUrl.toHttpUrl().host
        } else {
            "${mainUrl.toHttpUrl().host}/$lang"
        }

        return url.replace(url.toHttpUrl().host, replacement)
    }

    private fun isHtml(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://streamingunity.biz/"
        const val PROVIDER_ID = "streamingcommunity"
        const val PREFS_NAME = "StreamingCommunity"
        const val PREF_BASE_URL = "baseUrl"

        fun resolveBaseUrl(rawUrl: String?): String {
            return normalizeBaseUrl(rawUrl) ?: DEFAULT_BASE_URL
        }

        fun normalizeBaseUrl(rawUrl: String?): String? {
            val trimmedValue = rawUrl?.trim().orEmpty()
            if (trimmedValue.isBlank()) return null

            val candidate = if ("://" in trimmedValue) trimmedValue else "https://$trimmedValue"
            return runCatching {
                candidate.toHttpUrl().newBuilder()
                    .encodedPath("/")
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
            }.getOrNull()
        }

        fun resolveCdnHost(siteHost: String): String {
            val fallbackHost = DEFAULT_BASE_URL.toHttpUrl().host
            return if (isIpAddress(siteHost) || siteHost.equals("localhost", ignoreCase = true)) {
                "cdn.$fallbackHost"
            } else {
                "cdn.$siteHost"
            }
        }

        private fun isIpAddress(host: String): Boolean {
            val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
            return ipv4Regex.matches(host) || host.contains(":")
        }

        private val sliderRequestBody = ScSliderFetchRequestBody(
            sliders = listOf(
                ScSliderFetchRequestSlider("top10", null),
                ScSliderFetchRequestSlider("trending", null),
                ScSliderFetchRequestSlider("latest", null),
                ScSliderFetchRequestSlider("upcoming", null),
                ScSliderFetchRequestSlider("genre", "Animation"),
                ScSliderFetchRequestSlider("genre", "Adventure"),
                ScSliderFetchRequestSlider("genre", "Action"),
                ScSliderFetchRequestSlider("genre", "Comedy"),
                ScSliderFetchRequestSlider("genre", "Crime"),
                ScSliderFetchRequestSlider("genre", "Documentary"),
                ScSliderFetchRequestSlider("genre", "Drama"),
                ScSliderFetchRequestSlider("genre", "Family"),
                ScSliderFetchRequestSlider("genre", "Science Fiction"),
                ScSliderFetchRequestSlider("genre", "Fantasy"),
                ScSliderFetchRequestSlider("genre", "Horror"),
                ScSliderFetchRequestSlider("genre", "Reality"),
                ScSliderFetchRequestSlider("genre", "Romance"),
                ScSliderFetchRequestSlider("genre", "Thriller"),
            )
        )
    }
}

private data class ScSliderFetchRequestSlider(
    val name: String,
    val genre: String?,
)

private data class ScSliderFetchRequestBody(
    val sliders: List<ScSliderFetchRequestSlider>,
) {
    fun toRequestBody() = toJson().toRequestBody(
        "application/json;charset=utf-8".toMediaType()
    )
}

private data class ScInertiaResponse(
    @JsonProperty("props") val props: ScProps,
)

private data class ScProps(
    @JsonProperty("cdn_url") val cdnUrl: String,
    @JsonProperty("title") val title: ScTitleProp?,
    @JsonProperty("loadedSeason") val loadedSeason: ScSeason?,
    @JsonProperty("sliders") val sliders: List<ScSlider>?,
    @JsonProperty("titles") val titles: List<ScTitle>?,
)

private data class ScSlider(
    @JsonProperty("name") val name: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("titles") val titles: List<ScTitle>,
)

private data class ScTitle(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("images") val images: List<ScPosterImage>,
) {
    fun posterImageId(): String? = images.firstOrNull { it.type == "poster" }?.filename
}

private data class ScPosterImage(
    @JsonProperty("filename") val filename: String,
    @JsonProperty("type") val type: String,
)

private data class ScSeason(
    @JsonProperty("id") val id: Int,
    @JsonProperty("number") val number: Int,
    @JsonProperty("episodes") val episodes: List<ScEpisode>?,
)

private data class ScEpisode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("number") val number: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("duration") val duration: Int?,
    @JsonProperty("images") val images: List<ScPosterImage>,
) {
    fun coverImageId(): String? = images.firstOrNull { it.type == "cover" }?.filename
}

private data class ScTitleProp(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("score") val score: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("age") val age: Int?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("tmdb_id") val tmdbId: Int?,
    @JsonProperty("imdb_id") val imdbId: String?,
    @JsonProperty("trailers") val trailers: List<ScTrailer>?,
    @JsonProperty("seasons") val seasons: List<ScSeason>?,
    @JsonProperty("images") val images: List<ScPosterImage>,
    @JsonProperty("genres") val genres: List<ScGenre>,
    @JsonProperty("main_actors") val mainActors: List<ScMainActor>?,
) {
    fun backgroundImageId(): String? = images.firstOrNull { it.type == "background" }?.filename
}

private data class ScGenre(
    @JsonProperty("name") val name: String,
)

private data class ScMainActor(
    @JsonProperty("name") val name: String,
)

private data class ScTrailer(
    @JsonProperty("youtube_id") val youtubeId: String?,
) {
    fun youtubeUrl(): String? = youtubeId?.let { "https://www.youtube.com/watch?v=$it" }
}

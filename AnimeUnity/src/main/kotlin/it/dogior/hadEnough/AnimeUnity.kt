package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.random.Random
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

private data class CalendarAnimeEntry(
    val anime: Anime,
    val episodeNumber: Int?,
)

class AnimeUnity(
    private val sharedPref: SharedPreferences?,
) : MainAPI() {
    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
    override var name = "AnimeUnity"
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    private val headers = buildBaseHeaders()
    override val mainPage = buildSectionNamesList()

    private fun buildSectionNamesList() = mainPageOf(
        *AnimeUnityPlugin.getOrderedSections(sharedPref)
            .filter { isSectionEnabled(it) }
            .map { getSectionEndpoint(it) to it.title }
            .toTypedArray()
    )

    private fun buildBaseHeaders(): MutableMap<String, String> {
        return mutableMapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        )
    }

    private fun resetHeadersAndCookies() {
        headers.clear()
        headers.putAll(buildBaseHeaders())
    }

    private fun isSectionEnabled(section: AnimeUnityHomeSection): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.getSectionEnabledPref(section), true) ?: true
    }

    private fun shouldShowScore(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true
    }

    private fun shouldShowDubSub(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true
    }

    private fun shouldShowEpisodeNumber(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true
    }

    private fun getSectionEndpoint(section: AnimeUnityHomeSection): String {
        return when (section) {
            AnimeUnityHomeSection.LATEST_EPISODES -> "$mainUrl/"
            AnimeUnityHomeSection.RANDOM -> "$mainUrl/archivio/"
            AnimeUnityHomeSection.CALENDAR -> "$mainUrl/calendario"
            AnimeUnityHomeSection.ONGOING,
            AnimeUnityHomeSection.POPULAR,
            AnimeUnityHomeSection.BEST,
            AnimeUnityHomeSection.UPCOMING,
            -> "$mainUrl/archivio/"
        }
    }

    private suspend fun setupHeadersAndCookies() {
        val response = app.get("$mainUrl/archivio", headers = headers)

        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies =
            "XSRF-TOKEN=${response.cookies["XSRF-TOKEN"]}; animeunity_session=${response.cookies["animeunity_session"]}"
        headers.putAll(
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to mainUrl,
                "Cookie" to cookies,
            )
        )
    }

    private suspend fun ensureHeadersAndCookies() {
        if (!headers.contains("Cookie")) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }
    }

    private fun Anime.getPreferredTitle(): String {
        return titleIt ?: titleEng ?: title.orEmpty()
    }

    private fun LatestEpisodeAnime.getPreferredTitle(): String {
        return titleIt ?: titleEng ?: title.orEmpty()
    }

    private fun String.withoutDubSuffix(): String {
        return replace(" (ITA)", "")
    }

    private fun getTvType(type: String, episodesCount: Int): TvType {
        return when {
            type == "TV" -> TvType.Anime
            type == "Movie" || episodesCount == 1 -> TvType.AnimeMovie
            else -> TvType.OVA
        }
    }

    private fun buildDisplayTitle(baseTitle: String, episodeNumber: Int?): String {
        val cleanTitle = baseTitle.withoutDubSuffix()
        return if (!shouldShowDubSub() && shouldShowEpisodeNumber() && episodeNumber != null) {
            "$cleanTitle - Ep. $episodeNumber"
        } else {
            cleanTitle
        }
    }

    private fun AnimeSearchResponse.applyCardDisplayState(
        isDubbed: Boolean,
        posterUrl: String?,
        scoreValue: String?,
        episodeNumber: Int?,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                addDubStatus(isDubbed, episodeNumber)
            } else {
                addDubStatus(isDubbed)
            }
        }

        addPoster(posterUrl)
        if (shouldShowScore()) {
            this.score = Score.from(scoreValue, 10)
        }
    }

    private suspend fun buildAnimeSearchResponse(
        baseTitle: String,
        url: String,
        type: TvType,
        isDubbed: Boolean,
        posterUrl: String?,
        scoreValue: String?,
        episodeNumber: Int? = null,
    ): AnimeSearchResponse {
        return newAnimeSearchResponse(
            name = buildDisplayTitle(baseTitle, episodeNumber),
            url = url,
            type = type,
        ).apply {
            applyCardDisplayState(
                isDubbed = isDubbed,
                posterUrl = posterUrl,
                scoreValue = scoreValue,
                episodeNumber = episodeNumber,
            )
        }
    }

    private suspend fun searchResponseBuilder(
        animeList: List<Anime>,
        episodeNumberProvider: (Anime) -> Int? = { null },
    ): List<SearchResponse> {
        return animeList.amap { anime ->
            val title = anime.getPreferredTitle()
            buildAnimeSearchResponse(
                baseTitle = title,
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = getTvType(anime.type, anime.episodesCount),
                isDubbed = anime.dub == 1 || title.contains("(ITA)"),
                posterUrl = getImage(anime.imageUrl, anime.anilistId),
                scoreValue = anime.score,
                episodeNumber = episodeNumberProvider(anime),
            )
        }
    }

    private suspend fun latestEpisodesResponseBuilder(items: List<LatestEpisodeItem>): List<SearchResponse> {
        return items.amap { item ->
            val anime = item.anime
            val title = anime.getPreferredTitle()
            buildAnimeSearchResponse(
                baseTitle = title,
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = getTvType(anime.type, anime.episodesCount),
                isDubbed = anime.dub == 1 || title.contains("(ITA)"),
                posterUrl = getImage(anime.imageUrl, anime.anilistId),
                scoreValue = anime.score,
                episodeNumber = item.number.toIntOrNull(),
            )
        }
    }

    private suspend fun calendarResponseBuilder(entries: List<CalendarAnimeEntry>): List<SearchResponse> {
        return entries.amap { entry ->
            val anime = entry.anime
            val title = anime.getPreferredTitle()
            buildAnimeSearchResponse(
                baseTitle = title,
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = getTvType(anime.type, anime.episodesCount),
                isDubbed = anime.dub == 1 || title.contains("(ITA)"),
                posterUrl = getImage(anime.imageUrl, anime.anilistId),
                scoreValue = anime.score,
                episodeNumber = entry.episodeNumber,
            )
        }
    }

    private fun getImageHost(): String {
        val host = mainUrl.toHttpUrl().host
        return when {
            host.startsWith("img.") -> host
            host.startsWith("www.") -> "img.${host.removePrefix("www.")}"
            else -> "img.$host"
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageHost()}/anime/$fileName"
            } catch (_: Exception) {
            }
        }

        return anilistId?.let { getAnilistPoster(it) }
    }

    private suspend fun getAnilistPoster(anilistId: Int): String {
        val query = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                coverImage {
                    large
                    medium
                }
            }
        }
    """.trimIndent()

        val body = mapOf(
            "query" to query,
            "variables" to """{"id":$anilistId}""",
        )
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        return anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.large ?: coverImage.medium!!
        } ?: throw IllegalStateException("No valid image found")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (AnimeUnityHomeSection.fromTitle(request.name)) {
            AnimeUnityHomeSection.LATEST_EPISODES -> getLatestEpisodesMainPage(page)
            AnimeUnityHomeSection.RANDOM -> getRandomMainPage(page)
            AnimeUnityHomeSection.CALENDAR -> getCalendarMainPage(page, request)
            AnimeUnityHomeSection.ONGOING,
            AnimeUnityHomeSection.POPULAR,
            AnimeUnityHomeSection.BEST,
            AnimeUnityHomeSection.UPCOMING,
            -> getArchiveSectionMainPage(page, request)
            null -> getArchiveSectionMainPage(page, request)
        }
    }

    private suspend fun getArchiveApiResponse(requestData: RequestData): ApiResponse {
        ensureHeadersAndCookies()
        val response = app.post(
            url = "$mainUrl/archivio/get-animes",
            headers = headers,
            requestBody = requestData.toRequestBody(),
        )
        return parseJson(response.text)
    }

    private suspend fun getArchiveSectionMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val section = AnimeUnityHomeSection.fromTitle(request.name)
            ?: return newHomePageResponse(
                HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                false,
            )

        val sectionItemCount = AnimeUnityPlugin.getSectionItemCount(sharedPref, section)
        val requestData = getDataPerHomeSection(section).apply {
            offset = (page - 1) * sectionItemCount
        }

        val responseObject = getArchiveApiResponse(requestData)
        val titles = responseObject.titles.orEmpty()
        val visibleTitles = titles.take(sectionItemCount)
        val renderedResults = searchResponseBuilder(visibleTitles)
        val hasNextPage = responseObject.total > (requestData.offset ?: 0) + visibleTitles.size

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = renderedResults,
                isHorizontalImages = false,
            ),
            hasNextPage,
        )
    }

    private suspend fun getRandomMainPage(page: Int): HomePageResponse {
        val sectionName = AnimeUnityHomeSection.RANDOM.title
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = sectionName, list = emptyList(), isHorizontalImages = false),
                false,
            )
        }

        val sectionItemCount = AnimeUnityPlugin.getSectionItemCount(
            sharedPref,
            AnimeUnityHomeSection.RANDOM,
        )

        val initialResponse = getArchiveApiResponse(RequestData(dubbed = 0, offset = 0))
        val maxOffset = (initialResponse.total - sectionItemCount).coerceAtLeast(0)
        val randomSeed = getDailyRandomSeed(initialResponse.total)
        val randomOffset = if (maxOffset > 0) Random(randomSeed).nextInt(maxOffset + 1) else 0
        val randomResponse = if (randomOffset == 0) {
            initialResponse
        } else {
            getArchiveApiResponse(RequestData(dubbed = 0, offset = randomOffset))
        }

        val randomTitles = randomResponse.titles.orEmpty()
            .shuffled(Random(randomSeed xor 0x4F1BBCDC))
            .take(sectionItemCount)

        return newHomePageResponse(
            HomePageList(
                name = sectionName,
                list = searchResponseBuilder(randomTitles),
                isHorizontalImages = false,
            ),
            false,
        )
    }

    private fun getDailyRandomSeed(total: Int): Int {
        val daySeed = SimpleDateFormat("yyyyDDD", Locale.US).format(Date()).toIntOrNull() ?: 0
        return daySeed xor total xor mainUrl.hashCode()
    }

    private suspend fun getLatestEpisodesMainPage(page: Int): HomePageResponse {
        val sectionName = AnimeUnityHomeSection.LATEST_EPISODES.title
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = sectionName, list = emptyList(), isHorizontalImages = false),
                false,
            )
        }

        val latestEpisodesJson = app.get("$mainUrl/?page=1").document
            .selectFirst("#ultimi-episodi layout-items")
            ?.attr("items-json")
            .orEmpty()

        val latestEpisodes = latestEpisodesJson
            .takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching { parseJson<LatestEpisodesPage>(json).episodes }.getOrDefault(emptyList())
            }
            .orEmpty()
            .take(
                AnimeUnityPlugin.getSectionItemCount(sharedPref, AnimeUnityHomeSection.LATEST_EPISODES)
            )

        return newHomePageResponse(
            HomePageList(
                name = sectionName,
                list = latestEpisodesResponseBuilder(latestEpisodes),
                isHorizontalImages = false,
            ),
            false,
        )
    }

    private suspend fun getCalendarMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "${AnimeUnityHomeSection.CALENDAR.title} ($currentDay)"

        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = calendarTitle, list = emptyList(), isHorizontalImages = false),
                false,
            )
        }

        val sectionItemCount = AnimeUnityPlugin.getSectionItemCount(sharedPref, AnimeUnityHomeSection.CALENDAR)
        val calendarEntries = app.get(request.data).document
            .select("calendario-item")
            .mapNotNull { item ->
                item.attr("a")
                    .takeIf(String::isNotBlank)
                    ?.let { animeJson ->
                        runCatching { parseJson<Anime>(animeJson) }.getOrNull()
                    }
                    ?.let { anime ->
                        CalendarAnimeEntry(
                            anime = anime,
                            episodeNumber = extractCalendarEpisodeNumber(item, anime),
                        )
                    }
            }
            .filter { normalizeDayName(it.anime.day) == normalizeDayName(currentDay) }
            .distinctBy { it.anime.id }
            .take(sectionItemCount)

        return newHomePageResponse(
            HomePageList(
                name = calendarTitle,
                list = calendarResponseBuilder(calendarEntries),
                isHorizontalImages = false,
            ),
            false,
        )
    }

    private fun extractCalendarEpisodeNumber(item: Element, anime: Anime): Int? {
        val attrCandidates = listOf(
            "episode",
            "ep",
            "number",
            "data-episode",
            "data-ep",
            "data-number",
        )

        val attrEpisodeNumber = attrCandidates
            .asSequence()
            .map { candidate -> item.attr(candidate) }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?.toIntOrNull()

        if (attrEpisodeNumber != null) {
            return attrEpisodeNumber
        }

        val labeledEpisodeNumber = Regex("""(?i)\b(?:ep(?:isodio)?\.?\s*)(\d{1,4})\b""")
            .find(item.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (labeledEpisodeNumber != null) {
            return labeledEpisodeNumber
        }

        return anime.episodes
            ?.mapNotNull { episode -> episode.number.toIntOrNull() }
            ?.maxOrNull()
    }

    private fun getCurrentItalianDayName(): String {
        val formatter = SimpleDateFormat("EEEE", Locale.ITALIAN)
        return formatter.format(Date()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString()
        }
    }

    private fun normalizeDayName(dayName: String?): String {
        return Normalizer.normalize(dayName.orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun getDataPerHomeSection(section: AnimeUnityHomeSection): RequestData {
        return when (section) {
            AnimeUnityHomeSection.POPULAR -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
            AnimeUnityHomeSection.UPCOMING -> RequestData(status = Str("In Uscita"), dubbed = 0)
            AnimeUnityHomeSection.BEST -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
            AnimeUnityHomeSection.ONGOING -> RequestData(
                orderBy = Str("Popolarità"),
                status = Str("In Corso"),
                dubbed = 0,
            )
            else -> RequestData(dubbed = 0)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resetHeadersAndCookies()
        setupHeadersAndCookies()

        val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
        val response = app.post(
            url = "$mainUrl/archivio/get-animes",
            headers = headers,
            requestBody = requestBody,
        )

        val responseObject = parseJson<ApiResponse>(response.text)
        return searchResponseBuilder(responseObject.titles.orEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        resetHeadersAndCookies()
        setupHeadersAndCookies()
        val animePage = app.get(url).document

        val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
        val relatedAnime = parseJson<List<Anime>>(relatedAnimeJsonArray)
        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))
        val eps = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEps = videoPlayer.attr("episodes_count").toInt()

        val range = if (totalEps % 120 == 0) {
            totalEps / 120
        } else {
            (totalEps / 120) + 1
        }

        val episodes = eps.map {
            newEpisode("$url/${it.id}") {
                this.episode = it.number.toIntOrNull()
            }
        }.toMutableList()

        if (totalEps > 120) {
            for (index in 2..range) {
                val endRange = if (index == range) totalEps else index * 120
                val infoUrl =
                    "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (index - 1) * 120}&end_range=$endRange"
                val animeInfo = parseJson<AnimeInfo>(app.get(infoUrl).text)
                episodes.addAll(
                    animeInfo.episodes.map {
                        newEpisode("$url/${it.id}") {
                            this.episode = it.number.toIntOrNull()
                        }
                    }
                )
            }
        }

        val title = anime.getPreferredTitle()
        val relatedAnimes = relatedAnime.amap { related ->
            val relatedTitle = related.getPreferredTitle()
            buildAnimeSearchResponse(
                baseTitle = relatedTitle,
                url = "$mainUrl/anime/${related.id}-${related.slug}",
                type = getTvType(related.type, related.episodesCount),
                isDubbed = related.dub == 1 || relatedTitle.contains("(ITA)"),
                posterUrl = getImage(related.imageUrl, related.anilistId),
                scoreValue = related.score,
            )
        }

        return newAnimeLoadResponse(
            name = title.withoutDubSuffix(),
            url = url,
            type = getTvType(anime.type, anime.episodesCount),
        ) {
            this.posterUrl = getImage(anime.imageUrl, anime.anilistId)
            anime.cover?.let { cover ->
                this.backgroundPosterUrl = getBanner(cover)
            }
            this.year = anime.date.toInt()
            addScore(anime.score)
            addDuration("${anime.episodesLength} minuti")

            val dub = if (anime.dub == 1) DubStatus.Dubbed else DubStatus.Subbed
            addEpisodes(dub, episodes)

            addAniListId(anime.anilistId)
            addMalId(anime.malId)
            this.plot = anime.plot
            val languageTag = if (anime.dub == 1 || title.contains("(ITA)")) {
                "\uD83C\uDDEE\uD83C\uDDF9  Italiano"
            } else {
                "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            }
            this.tags = listOf(languageTag) + anime.genres.map { genre ->
                genre.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }
            this.comingSoon = anime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    private fun getBanner(imageUrl: String): String {
        if (imageUrl.isNotEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageHost()}/anime/$fileName"
            } catch (_: Exception) {
            }
        }
        return imageUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val sourceUrl = document.select("video-player").attr("embed_url")
        VixCloudExtractor().getUrl(
            url = sourceUrl,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
        return true
    }
}

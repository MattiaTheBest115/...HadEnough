package it.dogior.hadEnough.fusion

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

internal class FusionAnimeUnitySource(
    private val api: MainAPI,
) {
    private var headers = mutableMapOf(
        "Host" to MAIN_URL.toHttpUrl().host,
        "User-Agent" to USER_AGENT,
    )
    private val metadataByUrl = mutableMapOf<String, AnimeSearchMetadata>()

    suspend fun homeSection(section: String, page: Int): SourcePage {
        if (!headers.contains("Cookie")) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }

        val requestData = requestDataForSection(section).copy(offset = (page - 1) * 30)
        val response = app.post(
            "$MAIN_URL/archivio/get-animes",
            headers = headers,
            requestBody = requestData.toRequestBody(),
        )

        val body = parseJson<AuApiResponse>(response.text)
        val items = buildSearchResponses(body.titles ?: emptyList())
        val hasNext = (requestData.offset ?: 0) < 177 && items.size == 30
        return SourcePage(items, hasNext)
    }

    suspend fun search(query: String): List<SearchResponse> {
        resetHeadersAndCookies()
        setupHeadersAndCookies()

        val response = app.post(
            "$MAIN_URL/archivio/get-animes",
            headers = headers,
            requestBody = AuRequestData(title = query, dubbed = 0).toRequestBody(),
        )

        return buildSearchResponses(parseJson<AuApiResponse>(response.text).titles ?: emptyList())
    }

    suspend fun findMatch(titles: Collection<String>): SearchResponse? {
        val normalizedTargets = titles.map(::normalizeTitle).filter { it.isNotBlank() }
        if (normalizedTargets.isEmpty()) return null

        normalizedTargets
            .sortedByDescending { it.length }
            .forEach { title ->
                val match = search(title).firstOrNull { candidate ->
                    titlesMatch(aliasesFor(candidate), normalizedTargets)
                }
                if (match != null) return match
            }

        return null
    }

    suspend fun buildEpisodePayload(
        showUrl: String,
        episodeNumber: Int,
        aliases: Collection<String>,
    ): String? {
        resetHeadersAndCookies()
        setupHeadersAndCookies()

        val document = app.get(showUrl).document
        val player = document.selectFirst("video-player") ?: return null
        val episodes = parseJson<List<AuEpisode>>(player.attr("episodes"))
        val episode = episodes.firstOrNull { it.number.toIntOrNull() == episodeNumber } ?: return null

        return FusionLinkPayload(
            provider = PROVIDER_ID,
            url = showUrl,
            episodeId = episode.id,
            episodeNumber = episodeNumber,
            aliases = aliases.distinct(),
        ).serialize()
    }

    suspend fun load(url: String): LoadResponse {
        resetHeadersAndCookies()
        setupHeadersAndCookies()

        val animePage = app.get(url).document
        val relatedAnime = parseJson<List<AuAnime>>(animePage.select("layout-items").attr("items-json"))
        val videoPlayer = animePage.selectFirst("video-player")
            ?: error("AnimeUnity video-player not found")

        val anime = parseJson<AuAnime>(videoPlayer.attr("anime"))
        val aliases = listOfNotNull(anime.titleIt, anime.titleEng, anime.title).distinct()
        val episodes = parseJson<List<AuEpisode>>(videoPlayer.attr("episodes")).toMutableList()
        val totalEpisodes = videoPlayer.attr("episodes_count").toInt()

        if (totalEpisodes > 120) {
            val rangeCount = if (totalEpisodes % 120 == 0) totalEpisodes / 120 else (totalEpisodes / 120) + 1
            for (i in 2..rangeCount) {
                val endRange = if (i == rangeCount) totalEpisodes else i * 120
                val infoUrl =
                    "$MAIN_URL/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=$endRange"
                val info = parseJson<AuAnimeInfo>(app.get(infoUrl).text)
                episodes += info.episodes
            }
        }

        val mappedEpisodes = episodes.map { episode ->
            val number = episode.number.toIntOrNull()
            with(api) {
                newEpisode(
                    FusionLinkPayload(
                        provider = PROVIDER_ID,
                        url = url,
                        episodeId = episode.id,
                        episodeNumber = number,
                        aliases = aliases,
                    ).serialize()
                ) {
                    this.episode = number
                }
            }
        }

        val title = (anime.titleIt ?: anime.titleEng ?: anime.title).orEmpty()
        val related = relatedAnime.amap(::toSearchResponse)

        return with(api) {
            newAnimeLoadResponse(
                title.replace(" (ITA)", ""),
                url,
                typeFrom(anime.type, anime.episodesCount),
            ) {
                posterUrl = getImage(anime.imageUrl, anime.anilistId)
                anime.cover?.let { backgroundPosterUrl = getBanner(it) }
                year = anime.date.toIntOrNull()
                addScore(anime.score)
                addDuration("${anime.episodesLength} minuti")
                addEpisodes(if (anime.dub == 1) DubStatus.Dubbed else DubStatus.Subbed, mappedEpisodes)
                addAniListId(anime.anilistId)
                addMalId(anime.malId)
                plot = anime.plot
                val languageTag =
                    if (anime.dub == 1 || title.contains("(ITA)")) "Italiano" else "Giapponese"
                tags = listOf(languageTag) + anime.genres.map { genre ->
                    genre.name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
                comingSoon = anime.status == "In uscita prossimamente"
                recommendations = related
            }
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = parseJson<FusionLinkPayload>(data)
        val episodeId = payload.episodeId ?: return false
        val document = app.get("${payload.url}/$episodeId").document
        val sourceUrl = document.select("video-player").attr("embed_url")
        if (sourceUrl.isBlank()) return false

        FusionVixCloudExtractor("AnimeUnity").getUrl(
            url = sourceUrl,
            referer = MAIN_URL,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
        return true
    }

    private suspend fun setupHeadersAndCookies() {
        val response = app.get("$MAIN_URL/archivio", headers = headers)
        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies =
            "XSRF-TOKEN=${response.cookies["XSRF-TOKEN"]}; animeunity_session=${response.cookies["animeunity_session"]}"

        headers.putAll(
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to MAIN_URL,
                "Cookie" to cookies,
            )
        )
    }

    private fun resetHeadersAndCookies() {
        headers.clear()
        headers["Host"] = MAIN_URL.toHttpUrl().host
        headers["User-Agent"] = USER_AGENT
    }

    private suspend fun buildSearchResponses(animeList: List<AuAnime>): List<SearchResponse> {
        return animeList.amap(::toSearchResponse)
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            runCatching {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://img.animeunity.so/anime/$fileName"
            }
        }

        return anilistId?.let { getAnilistPoster(it) }
    }

    private suspend fun getAnilistPoster(anilistId: Int): String? {
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
        val cover = parseJson<AuAnilistResponse>(response.text).data.media.coverImage
        return cover?.large ?: cover?.medium
    }

    private fun requestDataForSection(section: String): AuRequestData {
        return when (section) {
            "popular" -> AuRequestData(orderBy = AuBooleanOrString.AsString("Popolarita"), dubbed = 0)
            "upcoming" -> AuRequestData(status = AuBooleanOrString.AsString("In Uscita"), dubbed = 0)
            "top" -> AuRequestData(orderBy = AuBooleanOrString.AsString("Valutazione"), dubbed = 0)
            "ongoing" -> AuRequestData(
                orderBy = AuBooleanOrString.AsString("Popolarita"),
                status = AuBooleanOrString.AsString("In Corso"),
                dubbed = 0,
            )

            else -> AuRequestData(dubbed = 0)
        }
    }

    private fun typeFrom(type: String?, episodesCount: Int?): TvType {
        return when {
            type == "Movie" || episodesCount == 1 -> TvType.AnimeMovie
            type == "TV" -> TvType.Anime
            else -> TvType.OVA
        }
    }

    private fun getBanner(imageUrl: String): String {
        return runCatching {
            val fileName = imageUrl.substringAfterLast("/")
            val cdnHost = MAIN_URL.toHttpUrl().host.replace("www", "img")
            "https://$cdnHost/anime/$fileName"
        }.getOrDefault(imageUrl)
    }

    private suspend fun toSearchResponse(anime: AuAnime): SearchResponse {
        val rawTitle = (anime.titleIt ?: anime.titleEng ?: anime.title).orEmpty()
        val title = rawTitle.replace(" (ITA)", "")
        val url = "$MAIN_URL/anime/${anime.id}-${anime.slug}"
        val poster = getImage(anime.imageUrl, anime.anilistId)
        val isDubbed = anime.dub == 1 || rawTitle.contains("(ITA)")
        val response = with(api) {
            newAnimeSearchResponse(
                title,
                url,
                typeFrom(anime.type, anime.episodesCount),
            ) {
                addDubStatus(isDubbed)
                addPoster(poster)
            }
        }

        metadataByUrl[url] = AnimeSearchMetadata(
            aliases = (buildAliases(title, anime.titleIt, anime.titleEng, anime.title, anime.slug) + aliasesOf(response)).distinct(),
            hasDub = isDubbed,
            hasSub = !isDubbed,
        )
        return response
    }

    fun aliasesFor(response: SearchResponse): List<String> {
        return metadataByUrl[response.url]?.aliases ?: aliasesOf(response)
    }

    fun hasDub(response: SearchResponse): Boolean {
        return metadataByUrl[response.url]?.hasDub == true
    }

    fun hasSub(response: SearchResponse): Boolean {
        return metadataByUrl[response.url]?.hasSub == true
    }

    private companion object {
        const val MAIN_URL = "https://www.animeunity.so"
        const val PROVIDER_ID = "animeunity"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
    }
}

private sealed class AuBooleanOrString {
    data class AsBoolean(val value: Boolean) : AuBooleanOrString()
    data class AsString(val value: String) : AuBooleanOrString()

    fun getValue(): Any {
        return when (this) {
            is AsBoolean -> value
            is AsString -> value
        }
    }
}

private data class AuRequestData(
    val title: String = "",
    val type: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    val year: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    val orderBy: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    val status: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    val genres: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    val season: AuBooleanOrString = AuBooleanOrString.AsBoolean(false),
    var offset: Int? = 0,
    val dubbed: Int = 1,
) {
    fun toRequestBody(): RequestBody {
        return JSONObject().apply {
            put("title", title)
            put("type", type.getValue())
            put("year", year.getValue())
            put("order", orderBy.getValue())
            put("status", status.getValue())
            put("genres", genres.getValue())
            put("season", season.getValue())
            put("dubbed", dubbed)
            put("offset", offset)
        }.toString().toRequestBody("application/json;charset=utf-8".toMediaType())
    }
}

private data class AuApiResponse(
    @JsonProperty("records") val titles: List<AuAnime>?,
)

private data class AuAnime(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String?,
    @JsonProperty("imageurl") val imageUrl: String?,
    @JsonProperty("plot") val plot: String,
    @JsonProperty("date") val date: String,
    @JsonProperty("episodes_count") val episodesCount: Int,
    @JsonProperty("episodes_length") val episodesLength: Int,
    @JsonProperty("status") val status: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title_eng") val titleEng: String?,
    @JsonProperty("score") val score: String?,
    @JsonProperty("dub") val dub: Int,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("anilist_id") val anilistId: Int?,
    @JsonProperty("title_it") val titleIt: String?,
    @JsonProperty("mal_id") val malId: Int?,
    @JsonProperty("genres") val genres: List<AuGenre>,
)

private data class AuEpisode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("number") val number: String,
)

private data class AuAnimeInfo(
    @JsonProperty("episodes") val episodes: List<AuEpisode>,
)

private data class AuGenre(
    @JsonProperty("name") val name: String,
)

private data class AuAnilistResponse(
    @JsonProperty("data") val data: AuAnilistData,
)

private data class AuAnilistData(
    @JsonProperty("Media") val media: AuAnilistMedia,
)

private data class AuAnilistMedia(
    @JsonProperty("coverImage") val coverImage: AuAnilistCoverImage?,
)

private data class AuAnilistCoverImage(
    @JsonProperty("medium") val medium: String?,
    @JsonProperty("large") val large: String?,
)

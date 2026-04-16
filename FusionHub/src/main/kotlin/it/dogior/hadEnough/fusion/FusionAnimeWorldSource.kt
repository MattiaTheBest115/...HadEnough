package it.dogior.hadEnough.fusion

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeJSON.stringify
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.text.SimpleDateFormat
import java.util.Locale

internal class FusionAnimeWorldSource(
    context: Context,
    private val api: MainAPI,
) {
    private val mode = resolveMode(context)
    private val cookies = mutableMapOf<String, String>()
    private val headers = mutableMapOf<String, String>()
    private val metadataByUrl = mutableMapOf<String, AnimeSearchMetadata>()

    suspend fun homeSection(section: String, page: Int): SourcePage {
        val url = mainPageUrl(section) ?: return SourcePage(emptyList(), false)
        val pageData = if (page > 1) request("$url&page=$page") else request(url)
        val document = pageData.document
        val list = mutableListOf<SearchResponse>()
        var hasNext = false

        if (section == "top") {
            document.select("div.row .content").forEach { list += it.toSearchResult(true) }
        } else {
            document.select("div.film-list > .item").forEach { list += it.toSearchResult(false) }
            val totalPages = document.select("#paging-form span.total").text().toIntOrNull()
            hasNext = totalPages != null && (page + 1) < totalPages
        }

        return SourcePage(list.filter(::matchesMode), hasNext)
    }

    suspend fun search(query: String, page: Int = 1): SourcePage {
        val pageParam = if (page <= 1) "" else "&page=$page"
        val document = request("$MAIN_URL/filter?sort=0&keyword=${query.trim()}$pageParam").document
        val list = document.select(".film-list > .item").map { it.toSearchResult(false) }
        val totalPages = document.select("#paging-form span.total").text().toIntOrNull()
        val hasNext = totalPages != null && (page + 1) < totalPages
        return SourcePage(list.filter(::matchesMode), hasNext)
    }

    suspend fun findMatch(titles: Collection<String>): SearchResponse? {
        val normalizedTargets = titles.map(::normalizeTitle).filter { it.isNotBlank() }
        if (normalizedTargets.isEmpty()) return null

        normalizedTargets
            .sortedByDescending { it.length }
            .forEach { title ->
                val quick = quickSearch(title)
                val match = quick.firstOrNull { candidate ->
                    titlesMatch(aliasesFor(candidate), normalizedTargets)
                }
                if (match != null) return match
            }

        return null
    }

    fun buildEpisodePayload(
        showUrl: String,
        episodeNumber: Int,
        aliases: Collection<String>,
    ): String {
        return FusionLinkPayload(
            provider = PROVIDER_ID,
            url = showUrl,
            episodeNumber = episodeNumber,
            aliases = aliases.distinct(),
        ).serialize()
    }

    suspend fun load(url: String): LoadResponse {
        val actualUrl = url.replace(Regex("""www\.animeworld\.[^/]+"""), MAIN_URL.toHttpUrl().host)
        val document = request(actualUrl).document

        val widget = document.select("div.widget.info")
        val title = widget.select(".info .title").text().removeSuffix(" (ITA)")
        val otherTitle = widget.select(".info .title").attr("data-jtitle").removeSuffix(" (ITA)")
        val aliases = listOf(title, otherTitle).filter { it.isNotBlank() }
        val description = widget.select(".desc .long").firstOrNull()?.text()
            ?: widget.select(".desc").text()
        val poster = document.select(".thumb img").attr("src")
        val type = getType(widget.select("dd").firstOrNull()?.text())
        val genres = document.select(".meta a[href*=\"/genre/\"]").map { it.text() }
        val rating = widget.select("#average-vote").text()

        val trailerUrl = document.select(".trailer[data-url]").attr("data-url")
        val malId = document.select("#mal-button").attr("href").split('/').last().toIntOrNull()
        val aniListId = document.select("#anilist-button").attr("href").split('/').last().toIntOrNull()

        var dub = false
        var year: Int? = null
        var status: ShowStatus? = null
        var duration: String? = null

        for (meta in document.select(".meta dt, .meta dd")) {
            val text = meta.text()
            if (text.contains("Audio")) {
                dub = meta.nextElementSibling()?.text() == "Italiano"
            } else if (year == null && text.contains("Data")) {
                year = meta.nextElementSibling()?.text()?.split(' ')?.last()?.toIntOrNull()
            } else if (status == null && text.contains("Stato")) {
                status = getStatus(meta.nextElementSibling()?.text())
            } else if (duration == null && text.contains("Durata")) {
                duration = meta.nextElementSibling()?.text()
            }
        }

        duration = duration?.let(::normalizeDuration)
        val episodes = document.select(".widget.servers .server[data-name=\"9\"] .episode").map { item ->
            val number = item.select("a").attr("data-episode-num").toIntOrNull()
            with(api) {
                newEpisode(
                    FusionLinkPayload(
                        provider = PROVIDER_ID,
                        url = actualUrl,
                        episodeNumber = number,
                        aliases = aliases,
                    ).serialize()
                ) {
                    this.episode = number
                }
            }
        }

        val recommendations = document.select(".film-list.interesting .item").map {
            it.toSearchResult(false)
        }.filter(::matchesMode)

        val nextAiringDate = document.select("#next-episode").attr("data-calendar-date")
        val nextAiringTime = document.select("#next-episode").attr("data-calendar-time")
        val nextAiringUnix = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                .parse("${nextAiringDate}T${nextAiringTime}")
                ?.time
                ?.div(1000)
        }.getOrNull()

        return with(api) {
            newAnimeLoadResponse(title, actualUrl, type) {
                engName = title
                japName = otherTitle
                addPoster(poster)
                this.year = year
                addEpisodes(if (dub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
                showStatus = status
                plot = description
                tags = genres
                addMalId(malId)
                addAniListId(aniListId)
                addScore(rating)
                duration?.let { addDuration(it) }
                if (trailerUrl.isNotBlank()) addTrailer(trailerUrl)
                this.recommendations = recommendations
                comingSoon = episodes.isEmpty()
                if (episodes.isNotEmpty() && nextAiringUnix != null && episodes.last().episode != null) {
                    nextAiring = NextAiring(episodes.last().episode!! + 1, nextAiringUnix, null)
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
        val episodeNumber = payload.episodeNumber ?: return false
        val serverElem = request(payload.url).document.select(".widget.servers")
        val episodeElements = serverElem.select("a[data-episode-num=\"$episodeNumber\"]")
        val apiLinks = episodeElements.map {
            "https://www.animeworld.so/api/episode/info?id=${it.attr("data-id")}"
        }
        val apiResults = apiLinks.mapNotNull { tryParseJson<AwEpisodeInfo>(request(it).text) }
        if (apiResults.isEmpty()) return false

        apiResults.amap { info ->
            when {
                info.target.contains("AnimeWorld") -> {
                    callback(
                        newExtractorLink(
                            name = "AnimeWorld",
                            source = "AnimeWorld",
                            url = info.grabber,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            referer = MAIN_URL
                            quality = Qualities.Unknown.value
                        }
                    )
                }

                info.target.contains("listeamed.net") -> {
                    FusionVidguardExtractor().getUrl(info.grabber, null, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(info.grabber, null, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private suspend fun quickSearch(query: String): List<SearchResponse> {
        if (!cookies.containsKey("security")) {
            getSecurityCookie()
        }

        val document = app.post(
            "$MAIN_URL/api/search/v2?keyword=$query",
            referer = MAIN_URL,
            cookies = cookies,
        ).text

        return tryParseJson<AwSearchJson>(document)?.animes?.map { anime ->
            val type = getType(anime.type)
            val dubbed = anime.language == "it"
            rememberSearchResponse(
                response = with(api) {
                    newAnimeSearchResponse(anime.name, "$MAIN_URL/play/${anime.link}.${anime.id}", type) {
                        addDubStatus(dubbed)
                        otherName = anime.otherTitle.removeSuffix(" (ITA)")
                        posterUrl = anime.image
                    }
                },
                aliases = buildAliases(anime.name, anime.otherTitle, anime.link),
                isDubbed = dubbed,
            )
        }?.filter(::matchesMode).orEmpty()
    }

    private suspend fun request(url: String): NiceResponse {
        if (!headers.contains("Cookie")) {
            headers["Cookie"] = getSecurityCookie()
        }
        return app.get(url, headers = headers)
    }

    private suspend fun getSecurityCookie(): String {
        val response = app.get(MAIN_URL)
        val cookie = response.headers["set-cookie"]?.substringBefore(";").orEmpty()
        if (cookie.isNotBlank()) {
            cookies["security"] = cookie
        }
        return cookie
    }

    private fun Element.toSearchResult(isTopPage: Boolean): SearchResponse {
        fun String.parseHref(): String {
            val parts = split('.').toMutableList()
            if (parts.size > 1) {
                parts[1] = parts[1].substringBeforeLast('/')
            }
            return parts.joinToString(".")
        }

        val anchor = selectFirst(if (isTopPage) "a" else "a.name")
            ?: throw ErrorLoadingException("AnimeWorld anchor missing")
        val url = with(api) { fixUrl(anchor.attr("href").parseHref()) }

        val titleText = if (isTopPage) {
            select("div.info > div.main > a").text()
        } else {
            anchor.text()
        }

        val title = titleText.replace(" (ITA)", "")
        val otherTitle = anchor.attr("data-jtitle").replace(" (ITA)", "")
        val poster = if (isTopPage) anchor.select("img").attr("src") else select("a.poster img").attr("src")
        val typeElement = select(if (isTopPage) "div.type" else "div.status")
        val dubbed = typeElement.select(".dub").isNotEmpty()
        val type = when {
            typeElement.select(".movie").isNotEmpty() -> TvType.AnimeMovie
            typeElement.select(".ova").isNotEmpty() -> TvType.OVA
            else -> TvType.Anime
        }

        return rememberSearchResponse(
            response = with(api) {
                newAnimeSearchResponse(title, url, type) {
                    addDubStatus(dubbed)
                    this.otherName = if (otherTitle.isNotBlank()) otherTitle else title
                    this.posterUrl = poster
                }
            },
            aliases = buildAliases(title, otherTitle, url),
            isDubbed = dubbed,
        )
    }

    private fun mainPageUrl(section: String): String? {
        return when (section) {
            "ongoing" -> "$MAIN_URL/filter?status=0${languageParam()}&sort=1"
            "latest" -> "$MAIN_URL/filter?${languageQuery()}sort=1"
            "popular" -> "$MAIN_URL/filter?${languageQuery()}sort=6"
            "top" -> when (mode) {
                Mode.DUB -> "$MAIN_URL/tops/dubbed?sort=1"
                else -> "$MAIN_URL/tops/all?sort=1"
            }

            else -> null
        }
    }

    private fun languageParam(): String {
        return when (mode) {
            Mode.DUB -> "&language=it"
            Mode.SUB -> "&language=jp"
            Mode.CORE -> ""
        }
    }

    private fun languageQuery(): String {
        return when (mode) {
            Mode.DUB -> "language=it&"
            Mode.SUB -> "language=jp&"
            Mode.CORE -> ""
        }
    }

    private fun matchesMode(response: SearchResponse): Boolean {
        val isDubbed = metadataByUrl[response.url]?.hasDub == true
        return when (mode) {
            Mode.DUB -> isDubbed
            Mode.SUB -> !isDubbed
            Mode.CORE -> true
        }
    }

    private fun rememberSearchResponse(
        response: SearchResponse,
        aliases: List<String>,
        isDubbed: Boolean,
    ): SearchResponse {
        metadataByUrl[response.url] = AnimeSearchMetadata(
            aliases = (aliases + aliasesOf(response)).distinct(),
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

    private fun getType(type: String?): TvType {
        return when (type?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when (status?.lowercase()) {
            "finito" -> ShowStatus.Completed
            "in corso" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun normalizeDuration(raw: String): String {
        return when {
            raw.contains("/ep") -> raw.replace("/ep", "").trim()
            raw.contains("h e ") -> {
                val split = raw.split("h e ")
                val hours = split[0].toIntOrNull()?.times(60) ?: 0
                val minutes = split.getOrNull(1)?.removeSuffix(" min")?.toIntOrNull() ?: 0
                "${hours + minutes} min"
            }

            else -> raw
        }
    }

    private fun resolveMode(context: Context): Mode {
        val sharedPref = context.getSharedPreferences("AnimeWorldIT", Context.MODE_PRIVATE)
        val isSplit = sharedPref.getBoolean("isSplit", false)
        val dubEnabled = sharedPref.getBoolean("dubEnabled", false)
        val subEnabled = sharedPref.getBoolean("subEnabled", false)

        return when {
            isSplit && dubEnabled && !subEnabled -> Mode.DUB
            isSplit && subEnabled && !dubEnabled -> Mode.SUB
            else -> Mode.CORE
        }
    }

    private enum class Mode {
        CORE,
        DUB,
        SUB,
    }

    private companion object {
        const val MAIN_URL = "https://www.animeworld.ac"
        const val PROVIDER_ID = "animeworld"
    }
}

private data class AwSearchJson(
    @JsonProperty("animes") val animes: List<AwAnimeJson>,
)

private data class AwAnimeJson(
    @JsonProperty("name") val name: String,
    @JsonProperty("image") val image: String,
    @JsonProperty("link") val link: String,
    @JsonProperty("animeTypeName") val type: String,
    @JsonProperty("language") val language: String,
    @JsonProperty("jtitle") val otherTitle: String,
    @JsonProperty("identifier") val id: String,
)

private data class AwEpisodeInfo(
    @JsonProperty("grabber") val grabber: String,
    @JsonProperty("target") val target: String,
)

private class FusionVidguardExtractor {
    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url).document
        val script = response.selectFirst("script:containsData(eval)")?.data() ?: return
        val decodedScript = runRhino(script)
        val json = tryParseJson<AwSvgObject>(decodedScript) ?: return
        val playlistUrl = decodeSignature(json.stream)

        callback(
            newExtractorLink(
                source = "VidGuard",
                name = "VidGuard",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer ?: "https://listeamed.net/"
                quality = Qualities.Unknown.value
            }
        )
    }

    private fun decodeSignature(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val transformed = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)

        return url.replace(sig, transformed)
    }

    private fun runRhino(script: String): String {
        var result = ""
        val runnable = Runnable {
            val rhino = RhinoContext.enter()
            rhino.initSafeStandardObjects()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)

            try {
                rhino.evaluateString(scope, script, "JavaScript", 1, null)
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    stringify(RhinoContext.getCurrentContext(), scope, svgObject, null, null).toString()
                } else {
                    RhinoContext.toString(svgObject)
                }
            } finally {
                RhinoContext.exit()
            }
        }

        val thread = Thread(ThreadGroup("fusion"), runnable, "fusion-rhino", 3_000_000)
        thread.start()
        thread.join()
        thread.interrupt()
        return result
    }

    private data class AwSvgObject(
        val stream: String,
    )
}

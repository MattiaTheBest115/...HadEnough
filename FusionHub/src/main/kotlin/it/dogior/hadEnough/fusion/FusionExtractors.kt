package it.dogior.hadEnough.fusion

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal class FusionVixCloudExtractor(
    private val displayName: String,
    private val useCloudflare: Boolean = false,
) : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false

    private val headers = mutableMapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "user-agent" to USER_AGENT,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playlistUrl = getPlaylistLink(url)
        callback(
            newExtractorLink(
                source = "VixCloud",
                name = displayName,
                url = playlistUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = headers
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String): JSONObject {
        val document = if (useCloudflare) {
            app.get(url, headers = headers, interceptor = CloudflareKiller()).document
        } else {
            app.get(url, headers = headers).document
        }

        val script = document.select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("VixCloud masterPlaylist not found")

        return JSONObject(sanitizedScript(script))
    }

    private fun sanitizedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""").split(script).drop(1)
        val keys = Regex("""window\.(\w+)\s*=""").findAll(script).map { it.groupValues[1] }.toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }

        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
    }
}

internal class FusionVixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playlistUrl = getPlaylistLink(url, referer ?: "https://vixsrc.to/")
        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer ?: "https://vixsrc.to/"
            }
        )
    }

    private suspend fun getPlaylistLink(url: String, referer: String): String {
        val script = getScript(url, referer)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&token=$token&expires=$expires"
        } else {
            "$playlistUrl?token=$token&expires=$expires"
        }

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String, referer: String): JSONObject {
        val headers = mapOf(
            "Accept" to "*/*",
            "Alt-Used" to url.toHttpUrl().host,
            "Connection" to "keep-alive",
            "Host" to url.toHttpUrl().host,
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )

        val document = app.get(url, headers = headers).document
        val script = document.select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("VixSrc masterPlaylist not found")

        return JSONObject(
            Regex("""window\.(\w+)\s*=""").split(script).drop(1).let { parts ->
                val keys = Regex("""window\.(\w+)\s*=""").findAll(script).map { it.groupValues[1] }.toList()
                val jsonObjects = keys.zip(parts).map { (key, value) ->
                    val cleaned = value
                        .replace(";", "")
                        .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                        .replace(Regex(""",(\s*[}\]])"""), "$1")
                        .trim()
                    "\"$key\": $cleaned"
                }
                "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
            }
        )
    }
}

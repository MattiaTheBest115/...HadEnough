package it.dogior.hadEnough.fusion

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.text.Normalizer
import java.util.Locale

internal data class SourcePage(
    val items: List<SearchResponse>,
    val hasNext: Boolean,
)

internal data class FusionLinkPayload(
    val provider: String,
    val url: String,
    val episodeId: Int? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val mediaType: String? = null,
    val tmdbId: Int? = null,
    val aliases: List<String> = emptyList(),
) {
    fun serialize(): String = this.toJson()
}

internal enum class AnimeProviderId {
    ANIME_UNITY,
    ANIME_WORLD,
}

internal data class AnimeCandidate(
    val provider: AnimeProviderId,
    val response: SearchResponse,
)

internal data class AnimeMergeEntry(
    val primaryProvider: AnimeProviderId,
    val primaryUrl: String,
    val secondaryProvider: AnimeProviderId,
    val secondaryUrl: String,
    val aliases: List<String>,
)

internal data class AnimeSearchMetadata(
    val aliases: List<String>,
    val hasDub: Boolean,
    val hasSub: Boolean,
)

internal fun normalizeTitle(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("\\(ita\\)|\\[ita\\]|sub ita|ita dub|dub".toRegex(), " ")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}

internal fun buildAliases(vararg values: String?): List<String> {
    return values
        .map(::normalizeTitle)
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun aliasFromUrl(url: String): String? {
    val segment = url.substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        .substringAfterLast('/')

    if (segment.isBlank()) return null

    val withoutNumericPrefix = if (
        segment.contains('-') &&
        segment.substringBefore('-').all { it.isDigit() }
    ) {
        segment.substringAfter('-')
    } else {
        segment
    }

    val withoutNumericSuffix = if (
        withoutNumericPrefix.contains('.') &&
        withoutNumericPrefix.substringAfterLast('.').all { it.isDigit() }
    ) {
        withoutNumericPrefix.substringBeforeLast('.')
    } else {
        withoutNumericPrefix
    }

    return withoutNumericSuffix
        .replace('-', ' ')
        .replace('.', ' ')
}

internal fun aliasesOf(response: SearchResponse): List<String> {
    return buildAliases(response.name, aliasFromUrl(response.url))
}

internal fun titlesMatch(first: Collection<String>, second: Collection<String>): Boolean {
    if (first.isEmpty() || second.isEmpty()) return false
    if (first.any(second::contains)) return true

    return first.any { left ->
        second.any { right ->
            val minLength = minOf(left.length, right.length)
            minLength >= 8 && (left.contains(right) || right.contains(left))
        }
    }
}

package org.stypox.dicio.skills.local_music

import org.stypox.dicio.util.StringUtils
import java.text.Normalizer
import java.util.Locale

/**
 * Filename matching for local music: file-name stem only (no ID3), with
 * diacritic-insensitive normalization so Vietnamese titles still match English
 * speech-to-text output.
 */
object LocalMusicMatcher {
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "ogg", "flac", "wav")

    /**
     * Same ceiling OpenSkill uses with [StringUtils.customStringDistance]: reject
     * when the distance is strictly greater than this.
     */
    private const val OPEN_SKILL_MAX_DISTANCE = 5

    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Lowercase, map Vietnamese đ/Đ to d, NFKD + strip combining marks, turn
     * punctuation/underscores/dashes into spaces, then collapse whitespace.
     */
    fun normalizeName(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).replace('đ', 'd')
        val nfkd = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val noMarks = COMBINING_MARKS.replace(nfkd, "")
        return NON_ALNUM.replace(noMarks, " ").trim().replace(WHITESPACE, " ")
    }

    fun fileStem(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0) fileName.substring(0, lastDot) else fileName
    }

    fun isSupportedAudioFile(fileName: String): Boolean {
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot < 0 || lastDot == fileName.lastIndex) return false
        val ext = fileName.substring(lastDot + 1).lowercase(Locale.ROOT)
        return ext in SUPPORTED_EXTENSIONS
    }

    /**
     * Lower is earlier in playlist order. mp3, then m4a, flac, ogg, wav.
     * Anything else sorts after the supported extensions.
     */
    fun extensionRank(fileName: String): Int {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.lastIndex) return UNKNOWN_EXTENSION_RANK
        return when (fileName.substring(dot + 1).lowercase(Locale.ROOT)) {
            "mp3" -> 0
            "m4a" -> 1
            "flac" -> 2
            "ogg" -> 3
            "wav" -> 4
            else -> UNKNOWN_EXTENSION_RANK
        }
    }

    /**
     * Order used when only file names are known: normalized stem, then
     * [extensionRank] (mp3 > m4a > flac > ogg > wav), then the full file name
     * case-insensitively, then the raw name.
     *
     * [LocalMusicPlaylist] uses the content URI as the third key instead of
     * the file name, so two `Intro.mp3` files in different folders stay in a
     * stable path order. Files that share a normalized stem are one voice
     * choice: the first item in this order.
     */
    fun nameOrder(): Comparator<String> =
        compareBy<String>(
            { normalizeName(fileStem(it)) },
            { extensionRank(it) },
        ).thenBy(String.CASE_INSENSITIVE_ORDER) { it }
            .thenBy { it }

    /**
     * Find the best file-name-stem match for [query] among [fileNames].
     * Unsupported extensions are ignored.
     *
     * Candidates whose normalized stems are identical are one choice. The
     * representative is the first in [nameOrder] (playlist order when the
     * caller already sorted that way; equal names keep input order). A
     * [MatchResult.Tie] is returned only when the top two scores are equal
     * and the normalized stems differ. Those two names follow [nameOrder]
     * among the tied scores. "Did you mean Hello or Hello?" is not asked.
     */
    fun findBestMatches(query: String, fileNames: List<String>): MatchResult {
        val queryNorm = normalizeName(query)
        if (queryNorm.isEmpty()) return MatchResult.NoMatch

        val representatives = fileNames
            .filter(::isSupportedAudioFile)
            .sortedWith(nameOrder())
            .distinctBy { normalizeName(fileStem(it)) }

        val scored = representatives.mapNotNull { fileName ->
            val stemNorm = normalizeName(fileStem(fileName))
            if (stemNorm.isEmpty()) return@mapNotNull null
            val score = scoreCandidate(queryNorm, stemNorm) ?: return@mapNotNull null
            ScoredMatch(fileName, stemNorm, score)
        }.sortedWith(
            compareByDescending<ScoredMatch> { it.score }
                .thenBy { extensionRank(it.fileName) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
        )

        if (scored.isEmpty()) return MatchResult.NoMatch
        val top = scored[0]
        if (scored.size >= 2 && scored[1].score == top.score && scored[1].stemNorm != top.stemNorm) {
            return MatchResult.Tie(listOf(top.fileName, scored[1].fileName))
        }
        return MatchResult.Single(top.fileName)
    }

    /**
     * Combined score: exact/substring on compact forms, token Jaccard, then
     * [StringUtils.customStringDistance] (lower distance is better). Returns
     * null when the candidate is not reasonably close.
     *
     * A query whose compact form is shorter than 4 characters matches only a
     * whole token or the whole stem. Longer queries may match a substring
     * that starts at a token boundary (so "love" does not match inside
     * "glove", and "bad guys" still matches "bad guy"). Fuzzy distance and
     * Jaccard are not used for those short queries, so "no" does not match
     * "snow" and "lo" does not match "hello".
     */
    private fun scoreCandidate(queryNorm: String, stemNorm: String): Int? {
        val queryCompact = compact(queryNorm)
        val stemCompact = compact(stemNorm)
        if (queryCompact.isEmpty() || stemCompact.isEmpty()) return null

        val distance = StringUtils.customStringDistance(queryNorm, stemNorm)

        val queryTokens = tokens(queryNorm)
        val stemTokens = tokens(stemNorm)
        val unionSize = queryTokens.union(stemTokens).size
        val jaccardBp = if (unionSize == 0) {
            0
        } else {
            1000 * queryTokens.intersect(stemTokens).size / unionSize
        }

        val exact = queryCompact == stemCompact
        val wholeToken = queryCompact in stemTokens || stemCompact in queryTokens
        val short = queryCompact.length < SHORT_QUERY_COMPACT_LENGTH
        // "no" inside "snow" is not a whole token. Reject before fuzzy distance.
        if (short && !exact && !wholeToken) return null

        val queryInStem = !exact && containsAtTokenBoundary(queryCompact, stemNorm)
        val stemInQuery = !exact && containsAtTokenBoundary(stemCompact, queryNorm)
        val minLen = minOf(queryCompact.length, stemCompact.length)
        val substringOk = (queryInStem || stemInQuery) && minLen >= 2 && !short

        val fuzzyOk = !short &&
            minLen >= 2 &&
            distance <= OPEN_SKILL_MAX_DISTANCE &&
            distance * 5 <= -4 * minLen
        val jaccardOk = !short && jaccardBp >= 500

        if (!exact && !wholeToken && !substringOk && !jaccardOk && !fuzzyOk) return null

        var score = 0
        when {
            exact -> score += 1_000_000
            queryInStem -> score += 500_000 + 1000 * queryCompact.length / stemCompact.length
            stemInQuery -> score += 400_000 + 1000 * stemCompact.length / queryCompact.length
            wholeToken -> score += 450_000
        }
        score += jaccardBp
        score -= distance * 10
        return score
    }

    /**
     * True when [needleCompact] occurs in [haystackNorm] starting at a token.
     * "love" in "glove puppet" starts mid-token and is rejected. "badguy" in
     * "bad guys" starts at the first token and is accepted.
     */
    private fun containsAtTokenBoundary(needleCompact: String, haystackNorm: String): Boolean {
        if (needleCompact.isEmpty()) return false
        val haystackCompact = compact(haystackNorm)
        if (needleCompact.length > haystackCompact.length) return false
        val starts = HashSet<Int>()
        var compactPos = 0
        var atTokenStart = true
        for (ch in haystackNorm) {
            if (ch == ' ') {
                atTokenStart = true
            } else {
                if (atTokenStart) {
                    starts.add(compactPos)
                    atTokenStart = false
                }
                compactPos++
            }
        }
        var from = 0
        while (from <= haystackCompact.length - needleCompact.length) {
            val index = haystackCompact.indexOf(needleCompact, from)
            if (index < 0) return false
            if (index in starts) return true
            from = index + 1
        }
        return false
    }

    private fun compact(normalized: String): String = normalized.replace(" ", "")

    private fun tokens(normalized: String): Set<String> =
        normalized.split(' ').filter { it.isNotEmpty() }.toSet()

    private data class ScoredMatch(
        val fileName: String,
        val stemNorm: String,
        val score: Int,
    )

    private const val SHORT_QUERY_COMPACT_LENGTH = 4
    private const val UNKNOWN_EXTENSION_RANK = 5
}

sealed interface MatchResult {
    data object NoMatch : MatchResult
    data class Single(val name: String) : MatchResult
    data class Tie(val names: List<String>) : MatchResult
}

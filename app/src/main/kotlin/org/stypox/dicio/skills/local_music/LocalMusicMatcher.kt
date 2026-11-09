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
     * Find the best file-name-stem match for [query] among [fileNames].
     * Unsupported extensions are ignored. A [MatchResult.Tie] is returned when
     * the top two remaining candidates have the same score; those two names are
     * in case-insensitive filename order.
     */
    fun findBestMatches(query: String, fileNames: List<String>): MatchResult {
        val queryNorm = normalizeName(query)
        if (queryNorm.isEmpty()) return MatchResult.NoMatch

        val scored = fileNames
            .filter(::isSupportedAudioFile)
            .mapNotNull { fileName ->
                val stemNorm = normalizeName(fileStem(fileName))
                if (stemNorm.isEmpty()) return@mapNotNull null
                val score = scoreCandidate(queryNorm, stemNorm) ?: return@mapNotNull null
                ScoredMatch(fileName, score)
            }
            .sortedWith(
                compareByDescending<ScoredMatch> { it.score }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
            )

        if (scored.isEmpty()) return MatchResult.NoMatch
        val top = scored[0]
        if (scored.size >= 2 && scored[1].score == top.score) {
            return MatchResult.Tie(listOf(top.fileName, scored[1].fileName))
        }
        return MatchResult.Single(top.fileName)
    }

    /**
     * Combined score: exact/substring on compact forms, token Jaccard, then
     * [StringUtils.customStringDistance] (lower distance is better). Returns
     * null when the candidate is not reasonably close.
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
        val queryInStem = !exact && stemCompact.contains(queryCompact)
        val stemInQuery = !exact && queryCompact.contains(stemCompact)
        val minLen = minOf(queryCompact.length, stemCompact.length)

        val accepted = exact ||
            ((queryInStem || stemInQuery) && minLen >= 2) ||
            jaccardBp >= 500 ||
            (
                minLen >= 2 &&
                    distance <= OPEN_SKILL_MAX_DISTANCE &&
                    distance * 5 <= -4 * minLen
                )

        if (!accepted) return null

        var score = 0
        when {
            exact -> score += 1_000_000
            queryInStem -> score += 500_000 + 1000 * queryCompact.length / stemCompact.length
            stemInQuery -> score += 400_000 + 1000 * stemCompact.length / queryCompact.length
        }
        score += jaccardBp
        score -= distance * 10
        return score
    }

    private fun compact(normalized: String): String = normalized.replace(" ", "")

    private fun tokens(normalized: String): Set<String> =
        normalized.split(' ').filter { it.isNotEmpty() }.toSet()

    private data class ScoredMatch(val fileName: String, val score: Int)
}

sealed interface MatchResult {
    data object NoMatch : MatchResult
    data class Single(val name: String) : MatchResult
    data class Tie(val names: List<String>) : MatchResult
}

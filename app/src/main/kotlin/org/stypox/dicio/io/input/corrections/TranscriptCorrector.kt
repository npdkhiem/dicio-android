package org.stypox.dicio.io.input.corrections

import org.stypox.dicio.skills.local_music.LocalMusicMatcher
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rewrites a transcript using the user's corrections, then known words.
 *
 * Comparison uses [LocalMusicMatcher.normalizeName] (case, accents, đ→d,
 * punctuation). Closeness is
 * `100% − (Levenshtein edits ÷ length of the longer normalised string)`.
 * Returns null when [text] is unchanged.
 *
 * Strictness 100 runs corrections only. A normalised window or known word
 * shorter than [MIN_FUZZY_CHARS] characters (spaces ignored) matches only
 * exactly. Work is capped at [WordListFile.MAX_WORDS] known words,
 * [WordListFile.MAX_CORRECTIONS] corrections, and [MAX_INPUT_WORDS] input words.
 */
object TranscriptCorrector {
    const val MAX_INPUT_WORDS = 80
    const val MIN_FUZZY_CHARS = 4
    private const val MAX_WINDOW = 4

    fun correct(
        text: String,
        knownWords: List<String>,
        corrections: List<Pair<String, String>>,
        strictnessPercent: Int,
    ): String? {
        if (text.isEmpty()) return null
        val strictness = strictnessPercent.coerceIn(0, 100)
        val preparedCorrections = prepareCorrections(corrections)
        val known = if (strictness >= 100) null else prepareKnown(knownWords)
        if (preparedCorrections.isEmpty() && known == null) return null
        if (preparedCorrections.isEmpty() && known!!.exact.isEmpty()) return null

        val spans = wordSpans(text)
        if (spans.isEmpty()) return null
        val limit = min(spans.size, MAX_INPUT_WORDS)
        val blocked = BooleanArray(limit)
        val replacements = ArrayList<Rep>()

        if (preparedCorrections.isNotEmpty()) {
            applyCorrections(spans, limit, preparedCorrections, blocked, replacements)
        }
        if (known != null && known.exact.isNotEmpty() && strictness < 100) {
            applyKnownWords(spans, limit, known, strictness, blocked, replacements)
        }
        if (replacements.isEmpty()) return null
        val rewritten = rebuild(text, spans, replacements)
        return if (rewritten == text) null else rewritten
    }

    /**
     * Levenshtein distance. When [maxDist] is set, returns `maxDist + 1` once
     * the distance is known to be greater, without finishing the matrix.
     */
    internal fun levenshteinDistance(a: String, b: String, maxDist: Int = Int.MAX_VALUE): Int {
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)
        return levenshtein(a, b, maxDist, prev, curr)
    }

    private class Span(val start: Int, val end: Int, val norm: String)

    private class Known(val raw: String, val norm: String, val compact: Int, val order: Int)

    private class PreparedKnown(
        val exact: Map<String, Known>,
        val fuzzyByLength: Map<Int, MutableList<Known>>,
    )

    private class Corr(val norms: List<String>, val correct: String, val order: Int)

    private class Rep(val start: Int, val wordCount: Int, val text: String)

    private class Cand(
        val start: Int,
        val wordCount: Int,
        val text: String,
        val closenessNum: Int,
        val closenessDen: Int,
        val order: Int,
    )

    private fun prepareCorrections(corrections: List<Pair<String, String>>): List<Corr> {
        val out = ArrayList<Corr>()
        val seen = HashSet<String>()
        for (pair in corrections) {
            if (out.size >= WordListFile.MAX_CORRECTIONS) break
            val heard = pair.first.trim()
            val correct = pair.second.trim()
            if (heard.isEmpty() || correct.isEmpty()) continue
            val norms = LocalMusicMatcher.normalizeName(heard).split(' ').filter { it.isNotEmpty() }
            if (norms.isEmpty()) continue
            val key = norms.joinToString(" ")
            if (!seen.add(key)) continue
            out.add(Corr(norms, correct, out.size))
        }
        return out
    }

    private fun prepareKnown(knownWords: List<String>): PreparedKnown {
        val exact = HashMap<String, Known>()
        val fuzzy = HashMap<Int, MutableList<Known>>()
        for (raw0 in knownWords) {
            if (exact.size >= WordListFile.MAX_WORDS) break
            val raw = raw0.trim()
            if (raw.isEmpty()) continue
            val norm = LocalMusicMatcher.normalizeName(raw)
            if (norm.isEmpty() || exact.containsKey(norm)) continue
            val compact = compactLength(norm)
            val known = Known(raw, norm, compact, exact.size)
            exact[norm] = known
            if (compact >= MIN_FUZZY_CHARS) {
                fuzzy.getOrPut(norm.length) { ArrayList() }.add(known)
            }
        }
        return PreparedKnown(exact, fuzzy)
    }

    private fun wordSpans(raw: String): List<Span> {
        val spans = ArrayList<Span>()
        val n = raw.length
        var i = 0
        while (i < n) {
            while (i < n && !isWordChar(raw[i])) i++
            if (i >= n) break
            val start = i
            while (i < n && isWordChar(raw[i])) i++
            val norm = LocalMusicMatcher.normalizeName(raw.substring(start, i))
            if (norm.isNotEmpty()) {
                spans.add(Span(start, i, norm))
            }
        }
        return spans
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit()

    private fun applyCorrections(
        spans: List<Span>,
        limit: Int,
        corrections: List<Corr>,
        blocked: BooleanArray,
        replacements: MutableList<Rep>,
    ) {
        val matches = ArrayList<Cand>()
        for (correction in corrections) {
            val len = correction.norms.size
            if (len == 0 || len > limit) continue
            for (start in 0..limit - len) {
                if (!matchesNorms(spans, start, correction.norms)) continue
                matches.add(
                    Cand(
                        start = start,
                        wordCount = len,
                        text = correction.correct,
                        closenessNum = 1,
                        closenessDen = 1,
                        order = correction.order,
                    )
                )
            }
        }
        matches.sortWith(CANDIDATE_ORDER)
        takeNonOverlapping(matches, blocked, replacements)
    }

    private fun matchesNorms(spans: List<Span>, start: Int, norms: List<String>): Boolean {
        for (k in norms.indices) {
            if (spans[start + k].norm != norms[k]) return false
        }
        return true
    }

    private fun applyKnownWords(
        spans: List<Span>,
        limit: Int,
        known: PreparedKnown,
        strictness: Int,
        blocked: BooleanArray,
        replacements: MutableList<Rep>,
    ) {
        var bufferChars = 1
        for (group in known.fuzzyByLength.values) {
            for (candidate in group) {
                if (candidate.norm.length + 1 > bufferChars) {
                    bufferChars = candidate.norm.length + 1
                }
            }
        }
        val prev = IntArray(bufferChars)
        val curr = IntArray(bufferChars)
        val window = StringBuilder()
        val matches = ArrayList<Cand>()
        for (start in 0 until limit) {
            if (blocked[start]) continue
            val maxLen = min(MAX_WINDOW, limit - start)
            for (len in 1..maxLen) {
                if (blocked[start + len - 1]) break
                window.setLength(0)
                for (k in 0 until len) {
                    if (k > 0) window.append(' ')
                    window.append(spans[start + k].norm)
                }
                val windowNorm = window.toString()
                val windowCompact = compactLength(windowNorm)
                val exact = known.exact[windowNorm]
                if (exact != null) {
                    matches.add(
                        Cand(
                            start = start,
                            wordCount = len,
                            text = exact.raw,
                            closenessNum = 1,
                            closenessDen = 1,
                            order = exact.order,
                        )
                    )
                    continue
                }
                if (windowCompact < MIN_FUZZY_CHARS || strictness >= 100) continue
                val best = closestKnown(
                    windowNorm = windowNorm,
                    strictness = strictness,
                    known = known,
                    prev = prev,
                    curr = curr,
                )
                if (best != null) {
                    matches.add(
                        Cand(
                            start = start,
                            wordCount = len,
                            text = best.known.raw,
                            closenessNum = best.longer - best.distance,
                            closenessDen = best.longer,
                            order = best.known.order,
                        )
                    )
                }
            }
        }
        matches.sortWith(CANDIDATE_ORDER)
        takeNonOverlapping(matches, blocked, replacements)
    }

    private class FuzzyHit(val known: Known, val distance: Int, val longer: Int)

    private fun closestKnown(
        windowNorm: String,
        strictness: Int,
        known: PreparedKnown,
        prev: IntArray,
        curr: IntArray,
    ): FuzzyHit? {
        if (strictness <= 0) {
            return closestInRange(
                windowNorm = windowNorm,
                strictness = strictness,
                minLen = 0,
                maxLen = Int.MAX_VALUE,
                known = known,
                prev = prev,
                curr = curr,
            )
        }
        val windowLen = windowNorm.length
        val minLen = (windowLen * strictness + 99) / 100
        val maxLen = (windowLen * 100) / strictness
        return closestInRange(windowNorm, strictness, minLen, maxLen, known, prev, curr)
    }

    private fun closestInRange(
        windowNorm: String,
        strictness: Int,
        minLen: Int,
        maxLen: Int,
        known: PreparedKnown,
        prev: IntArray,
        curr: IntArray,
    ): FuzzyHit? {
        var best: FuzzyHit? = null
        for ((length, group) in known.fuzzyByLength) {
            if (length < minLen || length > maxLen) continue
            for (candidate in group) {
                if (candidate.compact < MIN_FUZZY_CHARS) continue
                val longer = max(windowNorm.length, candidate.norm.length)
                val maxDist = maxDistance(longer, strictness)
                if (abs(windowNorm.length - candidate.norm.length) > maxDist) continue
                val dist = levenshtein(windowNorm, candidate.norm, maxDist, prev, curr)
                if (dist > maxDist) continue
                val hit = FuzzyHit(candidate, dist, longer)
                if (best == null || closer(hit, best)) {
                    best = hit
                }
            }
        }
        return best
    }

    private fun closer(hit: FuzzyHit, best: FuzzyHit): Boolean {
        val left = (hit.longer - hit.distance).toLong() * best.longer
        val right = (best.longer - best.distance).toLong() * hit.longer
        if (left != right) return left > right
        return hit.known.order < best.known.order
    }

    /**
     * Largest edit count that still meets [strictness], for a string of [longer]
     * characters. `100 * (longer - dist) >= strictness * longer`.
     */
    private fun maxDistance(longer: Int, strictness: Int): Int {
        if (longer <= 0) return 0
        if (strictness >= 100) return 0
        if (strictness <= 0) return longer
        return ((100 - strictness) * longer) / 100
    }

    private fun takeNonOverlapping(
        sorted: List<Cand>,
        blocked: BooleanArray,
        replacements: MutableList<Rep>,
    ) {
        for (cand in sorted) {
            var overlaps = false
            for (k in cand.start until cand.start + cand.wordCount) {
                if (blocked[k]) {
                    overlaps = true
                    break
                }
            }
            if (overlaps) continue
            for (k in cand.start until cand.start + cand.wordCount) {
                blocked[k] = true
            }
            replacements.add(Rep(cand.start, cand.wordCount, cand.text))
        }
    }

    private fun rebuild(raw: String, spans: List<Span>, reps: List<Rep>): String {
        val sorted = reps.sortedBy { it.start }
        val sb = StringBuilder(raw.length + 16)
        var cursor = 0
        for (rep in sorted) {
            val start = spans[rep.start].start
            val end = spans[rep.start + rep.wordCount - 1].end
            if (start < cursor) continue
            sb.append(raw, cursor, start)
            sb.append(rep.text)
            cursor = end
        }
        sb.append(raw, cursor, raw.length)
        return sb.toString()
    }

    private fun compactLength(norm: String): Int {
        var count = 0
        for (c in norm) {
            if (c != ' ') count++
        }
        return count
    }

    private fun levenshtein(
        a: String,
        b: String,
        maxDist: Int,
        prev: IntArray,
        curr: IntArray,
    ): Int {
        if (a == b) return 0
        val aLen = a.length
        val bLen = b.length
        if (abs(aLen - bLen) > maxDist) return maxDist + 1
        if (maxDist == 0) return 1
        for (j in 0..bLen) prev[j] = j
        var prevRow = prev
        var currRow = curr
        for (i in 1..aLen) {
            currRow[0] = i
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..bLen) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val value = minOf(prevRow[j] + 1, currRow[j - 1] + 1, prevRow[j - 1] + cost)
                currRow[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > maxDist) return maxDist + 1
            val swap = prevRow
            prevRow = currRow
            currRow = swap
        }
        val dist = prevRow[bLen]
        return if (dist > maxDist) maxDist + 1 else dist
    }

    private val CANDIDATE_ORDER = Comparator<Cand> { a, b ->
        if (a.wordCount != b.wordCount) return@Comparator b.wordCount - a.wordCount
        val left = a.closenessNum.toLong() * b.closenessDen
        val right = b.closenessNum.toLong() * a.closenessDen
        if (left != right) return@Comparator if (left > right) -1 else 1
        if (a.start != b.start) return@Comparator a.start - b.start
        a.order - b.order
    }
}

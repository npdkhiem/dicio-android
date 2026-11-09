package org.stypox.dicio.skills.local_music

import org.dicio.numbers.unit.Number as ParsedNumber
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.util.StringUtils

/**
 * Score helpers for the "Did you mean A or B?" follow-up.
 *
 * Name match runs first. [SongChooserIndex] is high specificity, but it
 * returns [AlwaysWorstScore] when [matchName] hits, so a title wins over a
 * position that happens to use the same words ("one love", "2 become 1",
 * "the one" beside a song called One, "twenty two" beside "22").
 * An index is accepted only when the utterance is nothing but a position:
 * "(the) first|second|… (one|song)", "number N", "option N", a bare number,
 * "(the) last (one|song)" (the final stem), and, with exactly two stems,
 * "(the) other (one|song)", "(the) former" and "(the) latter". Also, and only
 * these wrappers: a leading "play the …" ("play the second one") and a
 * trailing "please" ("second please"). "another one", "uh the first one" and
 * "no the first" are not positions. The number parser is asked only about
 * that remaining phrase, and only if the phrase is entirely one integer.
 * [org.dicio.numbers.parser.param.ExtractNumberParams.parseFirstIfInteger]
 * is not used, so a number buried in a title is not an index.
 *
 * [matchName] compares [LocalMusicMatcher.normalizeName] of both sides, so
 * Vietnamese đ/Đ folds to d the same way library search does. An exact
 * normalized stem is a hit even when it is shorter than the fuzzy cutoff
 * ("Up", "Us"). One leading "the" may be dropped from the utterance
 * ("the one" is "one", "the two" is "two") and that whole-name hit beats a
 * position. A spoken number is also compared after Dicio's number parser has
 * turned number words into digits ("twenty two" is "22", "seven rings" is
 * "7 rings", "nineteen ninety nine" is "1999"). Ordinal words ("first",
 * "second") are left as words, so "the first one" stays a position. An exact
 * or digit name hit beats a position reading. Longer names still use
 * [StringUtils.contactStringDistance] with the same cutoff as
 * [org.stypox.dicio.skills.telephone.ContactChooserName]. An equal distance
 * to both stems is not a choice. A pure position is not fuzzy-matched, so
 * "number one" stays the first option beside "One Love".
 *
 * Stop and pause phrases, including "turn off the music" / "turn the music
 * off" / "switch off …" and a leading or trailing "please", "ok" or "now",
 * match neither skill, so the ranker falls through to the default batch and
 * [org.stypox.dicio.skills.media.MediaSkill] pauses or stops. A title that is
 * itself one of those phrases (I-2, for example "Stop The Song") is not
 * picked by name; "the first one" still plays it.
 */
internal object SongChoice {
    /** ContactChooserName keeps a contact only when the distance is below this. */
    const val NAME_DISTANCE_LIMIT = -7

    private const val MEDIA_NOUN =
        "song|media|track|music|audio|video|movie|stream|film|playback"

    /**
     * Pause lines in `sentences/en/media.yml`, plus "turn|switch off the
     * noun" and "turn|switch the noun off". "playback" is included with the
     * same nouns. Leading and trailing "please|ok|now" are stripped first.
     */
    private val MEDIA_CONTROL = Regex(
        "(?:pause|halt|stop|hold)(?: the)? (?:$MEDIA_NOUN)" +
            "|put(?: the)? (?:$MEDIA_NOUN) on hold" +
            "|stop(?: playing)?(?: (?:the|my))?(?: local)? music" +
            "|(?:turn|switch) off(?: the)? (?:$MEDIA_NOUN)" +
            "|(?:turn|switch)(?: the)? (?:$MEDIA_NOUN) off",
    )

    private val LAST = Regex("(?:the )?last(?: (?:one|song))?")
    private val OTHER = Regex("(?:the )?other(?: (?:one|song))?")
    private val FORMER = Regex("(?:the )?former(?: one)?")
    private val LATTER = Regex("(?:the )?latter(?: one)?")

    fun isMediaControl(input: String): Boolean {
        val text = normalized(input) ?: return false
        if (MEDIA_CONTROL.matches(text)) return true
        val stripped = withoutFillers(text)
        return stripped != text && MEDIA_CONTROL.matches(stripped)
    }

    fun matchName(
        input: String,
        stems: List<String>,
        ctx: SkillContext? = null,
    ): String? {
        if (isMediaControl(input)) return null
        val inputNorm = normalized(input) ?: return null
        if (stems.isEmpty()) return null

        when (val hit = matchExact(inputNorm, stems)) {
            is Hit.One -> return hit.stem
            Hit.Many -> return null
            Hit.None -> Unit
        }
        withoutLeadingThe(inputNorm)?.let { stripped ->
            when (val hit = matchExact(stripped, stems)) {
                is Hit.One -> return hit.stem
                Hit.Many -> return null
                Hit.None -> Unit
            }
        }
        if (ctx != null) {
            when (val hit = matchDigits(ctx, inputNorm, stems)) {
                is Hit.One -> return hit.stem
                Hit.Many -> return null
                Hit.None -> Unit
            }
        }
        // "the second one", "last", and "number one" are positions. Fuzzy
        // distance must not steal them ("number one" must not play One Love).
        if (positionReading(ctx, inputNorm) != null) return null

        val scored = stems.map { stem ->
            val stemNorm = normalized(stem).orEmpty()
            stem to StringUtils.contactStringDistance(inputNorm, stemNorm)
        }
        val best = scored.minBy { it.second }
        if (best.second >= NAME_DISTANCE_LIMIT) return null
        if (scored.count { it.second == best.second } > 1) return null
        return best.first
    }

    fun matchOrdinal(ordinal: Long?, stems: List<String>): String? {
        if (ordinal == null || stems.isEmpty()) return null
        val index = ordinal.toInt()
        if (index < 1 || index > stems.size) return null
        return stems[index - 1]
    }

    /**
     * 1-based index when [input] is only a number or ordinal word, after a
     * leading "the", "number" or "option" and an optional trailing "one" or
     * "song". Position words ("last", "other", "former", "latter") are not
     * numbers; use [strictIndex] for those.
     */
    fun ordinal(ctx: SkillContext, input: String): Long? {
        val text = normalized(input) ?: return null
        val reading = positionReading(ctx, text) ?: return null
        if (isPositionPhrase(reading)) return null
        return numericIndex(ctx, reading)?.takeIf { it >= 1 }
    }

    /**
     * 1-based choice among [optionCount] stems, or null when [input] is not
     * strictly a position. Out-of-range numbers are null so the ranker can
     * fall through ("the third one" with two songs, "three" beside One Love).
     */
    fun strictIndex(ctx: SkillContext, input: String, optionCount: Int): Long? {
        if (optionCount <= 0) return null
        val text = normalized(input) ?: return null
        val reading = positionReading(ctx, text) ?: return null
        positionIndex(reading, optionCount)?.let { return it }
        if (isPositionPhrase(reading)) return null
        val value = numericIndex(ctx, reading) ?: return null
        if (value < 1 || value > optionCount) return null
        return value
    }

    private sealed interface Hit {
        data class One(val stem: String) : Hit
        data object Many : Hit
        data object None : Hit
    }

    private fun normalized(raw: String): String? {
        val text = LocalMusicMatcher.normalizeName(raw)
        return text.ifEmpty { null }
    }

    private fun matchExact(text: String, stems: List<String>): Hit {
        val exact = stems.filter { normalized(it) == text }
        return when (exact.size) {
            0 -> Hit.None
            1 -> Hit.One(exact[0])
            else -> Hit.Many
        }
    }

    /** Drop one leading "the ". "theory" and "the" alone are unchanged. */
    private fun withoutLeadingThe(text: String): String? {
        if (!text.startsWith("the ")) return null
        return text.removePrefix("the ").ifEmpty { null }
    }

    /**
     * Digits for number words in [text], or null when that does not change
     * the phrase. Ordinals ("first", "second") stay words so a position is
     * not rewritten into a digit title. "twenty two" becomes "22".
     */
    private fun numberWordsToDigits(ctx: SkillContext, text: String): String? {
        val formatter = ctx.parserFormatter ?: return null
        val parts = formatter.extractNumber(text)
            .preferOrdinal(true)
            .integerOnly(true)
            .parseMixedWithText()
        if (parts.isEmpty() || parts.none { it is ParsedNumber }) return null
        if (parts.any { it is ParsedNumber && (it.isOrdinal || !it.isInteger) }) return null
        val raw = buildString {
            for (part in parts) {
                when (part) {
                    is ParsedNumber -> append(part.integerValue())
                    is String -> append(part)
                    else -> return null
                }
            }
        }
        val digits = LocalMusicMatcher.normalizeName(raw)
        if (digits.isEmpty() || digits == text) return null
        return digits
    }

    private fun matchDigits(ctx: SkillContext, text: String, stems: List<String>): Hit {
        val forms = ArrayList<String>(2)
        forms.add(text)
        withoutLeadingThe(text)?.let { forms.add(it) }
        for (form in forms) {
            val digits = numberWordsToDigits(ctx, form) ?: continue
            when (val hit = matchExact(digits, stems)) {
                is Hit.One -> return hit
                Hit.Many -> return Hit.Many
                Hit.None -> Unit
            }
        }
        return Hit.None
    }

    private fun isPositionPhrase(text: String): Boolean {
        return LAST.matches(text) || OTHER.matches(text) ||
            FORMER.matches(text) || LATTER.matches(text)
    }

    /**
     * The phrase to read as a position, or null. "play the second one" drops
     * one leading "play " only when "the" follows, and "second please" drops
     * one trailing "please". Nothing else is peeled off.
     */
    private fun positionReading(ctx: SkillContext?, text: String): String? {
        for (candidate in positionCandidates(text)) {
            if (isPositionPhrase(candidate)) return candidate
            if (ctx != null && numericIndex(ctx, candidate) != null) return candidate
        }
        return null
    }

    private fun positionCandidates(text: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(text)
        if (text.endsWith(" please")) out.add(text.removeSuffix(" please"))
        if (text.startsWith("play the ")) out.add(text.removePrefix("play "))
        if (text.startsWith("play the ") && text.endsWith(" please")) {
            out.add(text.removePrefix("play ").removeSuffix(" please"))
        }
        return out.toList()
    }

    /** "last" is the final option. "other" / "former" / "latter" need two options. */
    private fun positionIndex(text: String, optionCount: Int): Long? {
        if (LAST.matches(text)) return optionCount.toLong()
        if (optionCount != 2) return null
        return when {
            OTHER.matches(text) -> 2L
            FORMER.matches(text) -> 1L
            LATTER.matches(text) -> 2L
            else -> null
        }
    }

    /**
     * Parses [text] as one integer after stripping an optional index wrapper.
     * "twenty one" stays 21 because the whole body is parsed before a trailing
     * "one" or "song" is dropped. "one love" and "2 become 1" are not one integer.
     */
    private fun numericIndex(ctx: SkillContext, text: String): Long? {
        val body = indexBody(text) ?: return null
        integerIfWhole(ctx, body)?.let { return it }
        val core = when {
            body.endsWith(" one") -> body.removeSuffix(" one")
            body.endsWith(" song") -> body.removeSuffix(" song")
            else -> return null
        }
        if (core.isEmpty()) return null
        return integerIfWhole(ctx, core)
    }

    private fun indexBody(text: String): String? {
        var rest = text
        if (rest.startsWith("the ")) rest = rest.removePrefix("the ")
        when {
            rest.startsWith("number ") -> rest = rest.removePrefix("number ")
            rest.startsWith("option ") -> rest = rest.removePrefix("option ")
        }
        return rest.ifEmpty { null }
    }

    /** "please stop the music please" is the same control phrase as the middle. */
    private fun withoutFillers(text: String): String {
        var rest = text
        while (rest.isNotEmpty()) {
            rest = when {
                rest.startsWith("please ") -> rest.removePrefix("please ")
                rest.startsWith("ok ") -> rest.removePrefix("ok ")
                rest.startsWith("now ") -> rest.removePrefix("now ")
                rest.endsWith(" please") -> rest.removeSuffix(" please")
                rest.endsWith(" ok") -> rest.removeSuffix(" ok")
                rest.endsWith(" now") -> rest.removeSuffix(" now")
                else -> return rest
            }
        }
        return rest
    }

    private fun integerIfWhole(ctx: SkillContext, text: String): Long? {
        val parts = ctx.parserFormatter
            ?.extractNumber(text)
            ?.preferOrdinal(true)
            ?.integerOnly(true)
            ?.parseMixedWithText()
            ?: return null
        val meaningful = parts.filter { part -> part !is String || part.isNotBlank() }
        val number = meaningful.singleOrNull() as? ParsedNumber ?: return null
        if (!number.isInteger) return null
        return number.integerValue()
    }
}

/**
 * "the first one" / "2" / "the last one" / "play the second one". High
 * specificity so a real position beats a fuzzy name. Returns
 * [AlwaysWorstScore] when [SongChoice.matchName] hits or the utterance is
 * not strictly a position, so the low-specificity name skill (or the default
 * batch) can win.
 */
internal class SongChooserIndex(
    correspondingSkillInfo: SkillInfo,
    private val stems: List<String>,
    private val controller: LocalMusicController,
) : Skill<Int>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Int> {
        if (SongChoice.isMediaControl(input) || SongChoice.matchName(input, stems, ctx) != null) {
            return AlwaysWorstScore to 0
        }
        val index = SongChoice.strictIndex(ctx, input, stems.size)
            ?: return AlwaysWorstScore to 0
        return AlwaysBestScore to index.toInt()
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Int): SkillOutput {
        val stem = stems.getOrNull(inputData - 1)
            ?: return LocalMusicOutput(LocalMusicSpeech.MissingSong)
        return playStem(controller, stem)
    }
}

/**
 * The song name on its own. Low specificity so a strict position still wins
 * when the name does not match, and so an unrelated utterance scores worst
 * and the normal ranker takes over. Stop/pause phrases score worst too,
 * including a title that is itself a stop phrase (I-2).
 */
internal class SongChooserName(
    correspondingSkillInfo: SkillInfo,
    private val stems: List<String>,
    private val controller: LocalMusicController,
) : Skill<String?>(correspondingSkillInfo, Specificity.LOW) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
        if (SongChoice.isMediaControl(input)) return AlwaysWorstScore to null
        val stem = SongChoice.matchName(input, stems, ctx)
        return if (stem == null) AlwaysWorstScore to null else AlwaysBestScore to stem
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String?): SkillOutput {
        val stem = inputData ?: return LocalMusicOutput(LocalMusicSpeech.MissingSong)
        return playStem(controller, stem)
    }
}

private suspend fun playStem(controller: LocalMusicController, stem: String): SkillOutput {
    // playByName(stem) is unique enough once the two stems differ (M2).
    // No follow-up here: a successful choice says "Playing X" and ends.
    val speech = LocalMusicSpeechSelection.forPlayResult(controller.playByName(stem))
    return LocalMusicOutput(speech)
}

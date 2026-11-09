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
 * number that happens to sit inside that title ("one love", "2 become 1").
 * An index is accepted only when the utterance is nothing but a position:
 * "(the) first|second|… (one)", "number N", "option N", a bare number,
 * "(the) last (one)" (the final stem), and, with exactly two stems,
 * "(the) other (one)", "(the) former" and "(the) latter". The number parser
 * is asked only about that remaining phrase, and only if the phrase is
 * entirely one integer. [org.dicio.numbers.parser.param.ExtractNumberParams.parseFirstIfInteger]
 * is not used, so a number buried in a title is not an index.
 *
 * [matchName] compares [LocalMusicMatcher.normalizeName] of both sides, so
 * Vietnamese đ/Đ folds to d the same way library search does. An exact
 * normalized stem is a hit even when it is shorter than the fuzzy cutoff
 * ("Up", "Us"), and it wins over a position ("One" beats the ordinal "one").
 * Longer names still use [StringUtils.contactStringDistance] with the same
 * cutoff as [org.stypox.dicio.skills.telephone.ContactChooserName]. An equal
 * distance to both stems is not a choice. A pure position is not fuzzy-matched,
 * so "number one" stays the first option beside "One Love".
 *
 * Stop and pause phrases from `sentences/en/media.yml` match neither skill,
 * so the ranker falls through to the default batch and [org.stypox.dicio.skills.media.MediaSkill]
 * pauses or stops.
 */
internal object SongChoice {
    /** ContactChooserName keeps a contact only when the distance is below this. */
    const val NAME_DISTANCE_LIMIT = -7

    private const val MEDIA_NOUN =
        "song|media|track|music|audio|video|movie|stream|film"

    /**
     * Pause lines in `sentences/en/media.yml`: "pause|halt|stop|hold the?
     * song|…", "put the? song|… on hold", and "stop (playing)? (the|my)?
     * (local)? music".
     */
    private val MEDIA_CONTROL = Regex(
        "(?:pause|halt|stop|hold)(?: the)? (?:$MEDIA_NOUN)" +
            "|put(?: the)? (?:$MEDIA_NOUN) on hold" +
            "|stop(?: playing)?(?: (?:the|my))?(?: local)? music",
    )

    private val LAST = Regex("(?:the )?last(?: one)?")
    private val OTHER = Regex("(?:the )?other(?: one)?")
    private val FORMER = Regex("(?:the )?former(?: one)?")
    private val LATTER = Regex("(?:the )?latter(?: one)?")

    fun isMediaControl(input: String): Boolean {
        val text = normalized(input) ?: return false
        return MEDIA_CONTROL.matches(text)
    }

    fun matchName(
        input: String,
        stems: List<String>,
        ctx: SkillContext? = null,
    ): String? {
        if (isMediaControl(input)) return null
        val inputNorm = normalized(input) ?: return null
        if (stems.isEmpty()) return null

        val exact = stems.filter { normalized(it) == inputNorm }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) return null
        // "the second one", "last", and "number one" are positions. Fuzzy
        // distance must not steal them ("number one" must not play One Love).
        if (isPositionPhrase(inputNorm)) return null
        if (ctx != null && numericIndex(ctx, inputNorm) != null) return null

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
     * leading "the", "number" or "option" and an optional trailing "one".
     * Position words ("last", "other", "former", "latter") are not numbers;
     * use [strictIndex] for those.
     */
    fun ordinal(ctx: SkillContext, input: String): Long? {
        val text = normalized(input) ?: return null
        if (isPositionPhrase(text)) return null
        return numericIndex(ctx, text)?.takeIf { it >= 1 }
    }

    /**
     * 1-based choice among [optionCount] stems, or null when [input] is not
     * strictly a position. Out-of-range numbers are null so the ranker can
     * fall through ("the third one" with two songs).
     */
    fun strictIndex(ctx: SkillContext, input: String, optionCount: Int): Long? {
        if (optionCount <= 0) return null
        val text = normalized(input) ?: return null
        positionIndex(text, optionCount)?.let { return it }
        if (isPositionPhrase(text)) return null
        val value = numericIndex(ctx, text) ?: return null
        if (value < 1 || value > optionCount) return null
        return value
    }

    private fun normalized(raw: String): String? {
        val text = LocalMusicMatcher.normalizeName(raw)
        return text.ifEmpty { null }
    }

    private fun isPositionPhrase(text: String): Boolean {
        return LAST.matches(text) || OTHER.matches(text) ||
            FORMER.matches(text) || LATTER.matches(text)
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
     * "one" is dropped. "one love" and "2 become 1" are not one integer.
     */
    private fun numericIndex(ctx: SkillContext, text: String): Long? {
        val body = indexBody(text) ?: return null
        integerIfWhole(ctx, body)?.let { return it }
        if (!body.endsWith(" one")) return null
        val core = body.removeSuffix(" one")
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
 * "the first one" / "2" / "the last one". High specificity so a real position
 * beats a fuzzy name. Returns [AlwaysWorstScore] when [SongChoice.matchName]
 * hits or the utterance is not strictly a position, so the low-specificity
 * name skill (or the default batch) can win.
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
 * and the normal ranker takes over. Stop/pause phrases score worst too.
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

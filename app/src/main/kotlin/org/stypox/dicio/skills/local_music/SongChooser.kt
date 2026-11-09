package org.stypox.dicio.skills.local_music

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
 * [matchOrdinal] uses the same [org.dicio.numbers.ParserFormatter] call as
 * [org.stypox.dicio.skills.telephone.ContactChooserIndex]: prefer an ordinal,
 * integers only, first integer in the utterance. "the first one" and "1" are
 * stem A. "the second one" and "2" are stem B.
 *
 * [matchName] uses [StringUtils.contactStringDistance], the same cutoff as
 * [org.stypox.dicio.skills.telephone.ContactChooserName]. An equal distance to
 * both stems is not a choice.
 */
internal object SongChoice {
    /** ContactChooserName keeps a contact only when the distance is below this. */
    const val NAME_DISTANCE_LIMIT = -7

    fun matchName(input: String, stems: List<String>): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || stems.isEmpty()) return null
        val scored = stems.map { stem ->
            stem to StringUtils.contactStringDistance(trimmed, stem)
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

    fun ordinal(ctx: SkillContext, input: String): Long? {
        return ctx.parserFormatter
            ?.extractNumber(input)
            ?.preferOrdinal(true)
            ?.integerOnly(true)
            ?.parseFirstIfInteger()
    }
}

/**
 * "the first one" / "the second one" / "1" / "2". High specificity so it
 * beats [SongChooserName] when both could match.
 */
internal class SongChooserIndex(
    correspondingSkillInfo: SkillInfo,
    private val stems: List<String>,
    private val controller: LocalMusicController,
) : Skill<Int>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Int> {
        val ordinal = SongChoice.ordinal(ctx, input)
        val stem = SongChoice.matchOrdinal(ordinal, stems)
        return if (stem == null) {
            AlwaysWorstScore to 0
        } else {
            AlwaysBestScore to ordinal!!.toInt()
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Int): SkillOutput {
        val stem = stems.getOrNull(inputData - 1)
            ?: return LocalMusicOutput(LocalMusicSpeech.MissingSong)
        return playStem(controller, stem)
    }
}

/**
 * The song name on its own. Low specificity so the ordinal chooser wins when
 * the utterance is "the first one", and so an unrelated utterance scores
 * worst and the normal ranker takes over.
 */
internal class SongChooserName(
    correspondingSkillInfo: SkillInfo,
    private val stems: List<String>,
    private val controller: LocalMusicController,
) : Skill<String?>(correspondingSkillInfo, Specificity.LOW) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
        val stem = SongChoice.matchName(input, stems)
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

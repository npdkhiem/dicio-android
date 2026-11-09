package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class LocalMusicOutput(
    internal val speech: LocalMusicSpeech,
    /** Stem that failed to decode before this reply, or null. Consumed once. */
    internal val couldNotPlay: String? = null,
    /**
     * Set when [speech] is a [LocalMusicSpeech.Tie] and the skill can play
     * the answer. Same idea as telephone passing its yes/no recognizer into
     * [org.stypox.dicio.skills.telephone.TelephoneOutput].
     */
    private val followUp: FollowUp? = null,
) : HeadlineSpeechSkillOutput {

    class FollowUp(
        val skillInfo: SkillInfo,
        val controller: LocalMusicController,
    )

    override fun getSpeechOutput(ctx: SkillContext): String {
        val reply = replyText(ctx, speech)
        val failure = couldNotPlay?.let {
            ctx.getString(R.string.skill_local_music_could_not_play, it)
        }
        return CouldNotPlaySpeech.join(failure, reply)
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val tie = speech as? LocalMusicSpeech.Tie ?: return InteractionPlan.FinishInteraction
        val follow = followUp ?: return InteractionPlan.FinishInteraction
        val stems = listOf(tie.a, tie.b)
        // Index is high specificity and name is low. The index skill scores
        // only a bare position ("the second one", "last", "2") and yields when
        // the utterance names a stem, so "one love" plays One Love. Stop and
        // pause phrases match neither, and the ranker falls through to the
        // normal skills. Neither chooser starts another sub-interaction, so
        // this does not loop.
        return InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(
                SongChooserIndex(follow.skillInfo, stems, follow.controller),
                SongChooserName(follow.skillInfo, stems, follow.controller),
            ),
        )
    }

    private fun replyText(ctx: SkillContext, speech: LocalMusicSpeech): String = when (speech) {
        is LocalMusicSpeech.Playing ->
            ctx.getString(R.string.skill_local_music_playing, speech.name)
        is LocalMusicSpeech.NoMatch ->
            ctx.getString(R.string.skill_local_music_no_match, speech.query)
        is LocalMusicSpeech.Tie ->
            ctx.getString(R.string.skill_local_music_tie, speech.a, speech.b)
        LocalMusicSpeech.NoFolderOrEmpty ->
            ctx.getString(R.string.skill_local_music_no_songs)
        LocalMusicSpeech.PermissionMissing ->
            ctx.getString(R.string.skill_local_music_permission_missing)
        LocalMusicSpeech.AccessLost ->
            ctx.getString(R.string.skill_local_music_access_lost)
        is LocalMusicSpeech.CouldNotPlay ->
            ctx.getString(R.string.skill_local_music_could_not_play, speech.name)
        is LocalMusicSpeech.Repeat -> when (speech.mode) {
            RepeatMode.OFF -> ctx.getString(R.string.skill_local_music_repeat_off)
            RepeatMode.ONE -> ctx.getString(R.string.skill_local_music_repeat_one)
            RepeatMode.ALL -> ctx.getString(R.string.skill_local_music_repeat_all)
        }
        LocalMusicSpeech.Stopped -> ctx.getString(R.string.skill_local_music_stopped)
        LocalMusicSpeech.MissingSong -> ctx.getString(R.string.skill_local_music_missing_song)
    }
}

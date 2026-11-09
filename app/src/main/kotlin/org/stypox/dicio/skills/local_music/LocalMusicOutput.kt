package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class LocalMusicOutput(
    private val action: Action,
) : HeadlineSpeechSkillOutput {
    sealed interface Action {
        data class WouldPlay(val name: String) : Action
        data object WouldStop : Action
        data class WouldSetRepeat(val mode: RepeatMode) : Action
        data object MissingSong : Action
    }

    override fun getSpeechOutput(ctx: SkillContext): String = when (action) {
        is Action.WouldPlay -> ctx.getString(R.string.skill_local_music_would_play, action.name)
        is Action.WouldStop -> ctx.getString(R.string.skill_local_music_would_stop)
        is Action.WouldSetRepeat -> when (action.mode) {
            RepeatMode.ONE -> ctx.getString(R.string.skill_local_music_would_repeat_one)
            RepeatMode.ALL -> ctx.getString(R.string.skill_local_music_would_repeat_all)
            RepeatMode.OFF -> ctx.getString(R.string.skill_local_music_would_repeat_off)
        }
        is Action.MissingSong -> ctx.getString(R.string.skill_local_music_missing_song)
    }
}

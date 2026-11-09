package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class LocalMusicOutput(
    private val speech: LocalMusicSpeech,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (speech) {
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

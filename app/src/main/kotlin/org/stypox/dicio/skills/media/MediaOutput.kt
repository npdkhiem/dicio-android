package org.stypox.dicio.skills.media

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.util.getString

/**
 * What a generic media command actually did. Local-music and MediaSkill share
 * this so the spoken line matches the side effect.
 */
sealed interface PlaybackSpeech {
    data object Playing : PlaybackSpeech
    data object Pausing : PlaybackSpeech
    data object Previous : PlaybackSpeech
    data object Next : PlaybackSpeech
    data object NoSession : PlaybackSpeech
    data object Stopped : PlaybackSpeech
    data object LastSong : PlaybackSpeech
    data object NothingPlaying : PlaybackSpeech

    /** See [org.stypox.dicio.skills.local_music.LocalMusicPlaybackErrors]. */
    data class CouldNotPlay(val name: String) : PlaybackSpeech

    companion object {
        fun from(action: Media?): PlaybackSpeech = when (action) {
            null -> NoSession
            is Media.Play -> Playing
            is Media.Pause -> Pausing
            is Media.Previous -> Previous
            is Media.Next -> Next
        }
    }
}

class MediaOutput(
    private val speech: PlaybackSpeech,
) : HeadlineSpeechSkillOutput {
    constructor(performedAction: Media?) : this(PlaybackSpeech.from(performedAction))

    override fun getSpeechOutput(ctx: SkillContext): String = when (speech) {
        PlaybackSpeech.Playing -> ctx.getString(R.string.skill_media_playing)
        PlaybackSpeech.Pausing -> ctx.getString(R.string.skill_media_pausing)
        PlaybackSpeech.Previous -> ctx.getString(R.string.skill_media_previous)
        PlaybackSpeech.Next -> ctx.getString(R.string.skill_media_next)
        PlaybackSpeech.NoSession -> ctx.getString(R.string.skill_media_no_media_session)
        PlaybackSpeech.Stopped -> ctx.getString(R.string.skill_local_music_stopped)
        PlaybackSpeech.LastSong -> ctx.getString(R.string.skill_local_music_last_song)
        PlaybackSpeech.NothingPlaying -> ctx.getString(R.string.skill_local_music_nothing_playing)
        is PlaybackSpeech.CouldNotPlay ->
            ctx.getString(R.string.skill_local_music_could_not_play, speech.name)
    }
}

package org.stypox.dicio.skills.local_music

import org.stypox.dicio.sentences.Sentences.LocalMusic
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.local_music.LocalMusicPhrases.TrackSkip
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Command

/**
 * Maps a parsed sentence plus the raw utterance onto one shared command.
 * Null means the sentence is not a generic media control (play-by-name or
 * repeat). [MediaSkill][org.stypox.dicio.skills.media.MediaSkill] and
 * [LocalMusicSkill] both use this, then [SharedPlayback], so stop-music and
 * the exact next/previous phrases do the same thing no matter which skill runs.
 */
object LocalMusicCommands {
    fun fromLocal(sentence: LocalMusic, utterance: String): Command? {
        commandFromUtterance(utterance)?.let { return it }
        return when (sentence) {
            is LocalMusic.PlaySong -> skipCommand(LocalMusicPhrases.skipForCapture(sentence.song))
            LocalMusic.Next -> Command.Next
            LocalMusic.Previous -> Command.Previous
            LocalMusic.Stop -> Command.StopMusic
            LocalMusic.RepeatOne,
            LocalMusic.RepeatAll,
            LocalMusic.RepeatOff -> null
        }
    }

    fun fromMedia(sentence: Media, utterance: String): Command {
        commandFromUtterance(utterance)?.let { return it }
        return when (sentence) {
            is Media.Play -> Command.Play
            is Media.Pause -> Command.Pause
            is Media.Next -> Command.Next
            is Media.Previous -> Command.Previous
        }
    }

    private fun commandFromUtterance(utterance: String): Command? {
        skipCommand(LocalMusicPhrases.skipForUtterance(utterance))?.let { return it }
        if (LocalMusicPhrases.isStopMusic(utterance)) return Command.StopMusic
        return null
    }

    private fun skipCommand(skip: TrackSkip?): Command? = when (skip) {
        TrackSkip.Next -> Command.Next
        TrackSkip.Previous -> Command.Previous
        null -> null
    }
}

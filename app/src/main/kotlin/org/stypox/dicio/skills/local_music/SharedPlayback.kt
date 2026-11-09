package org.stypox.dicio.skills.local_music

import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat.getSystemService
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Command
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Decision
import org.stypox.dicio.skills.local_music.LocalMusicRouting.LocalAction
import org.stypox.dicio.skills.media.PlaybackSpeech

/**
 * Runs a generic media command against the local player or by sending the
 * same media-key events MediaSkill used to send. Both skills call this so the
 * side effect and the spoken result stay the same for a phrase.
 */
object SharedPlayback {
    suspend fun perform(
        ctx: SkillContext,
        controller: LocalMusicController,
        command: Command,
    ): PlaybackSpeech {
        val decision = LocalMusicRouting.decide(
            command = command,
            isPlaying = controller.isPlaying,
            hasQueue = controller.hasQueue,
            lastActiveElapsedMillis = controller.lastActiveElapsedMillis,
        )
        return when (decision) {
            is Decision.Local -> performLocal(controller, decision.action)
            is Decision.MediaKey -> {
                if (!dispatchMediaKey(ctx, decision.keyCode)) {
                    PlaybackSpeech.NoSession
                } else {
                    speechForMediaKey(command)
                }
            }
        }
    }

    private suspend fun performLocal(
        controller: LocalMusicController,
        action: LocalAction,
    ): PlaybackSpeech = when (action) {
        LocalAction.Resume -> {
            controller.resume()
            PlaybackSpeech.Playing
        }
        LocalAction.Pause -> {
            controller.pause()
            PlaybackSpeech.Pausing
        }
        LocalAction.Next -> when (controller.next()) {
            NextResult.LastSong -> PlaybackSpeech.LastSong
            NextResult.Advanced -> PlaybackSpeech.Next
        }
        LocalAction.Previous -> {
            controller.previous()
            PlaybackSpeech.Previous
        }
        LocalAction.Stop -> {
            controller.stop()
            PlaybackSpeech.Stopped
        }
    }

    private fun speechForMediaKey(command: Command): PlaybackSpeech = when (command) {
        Command.Play -> PlaybackSpeech.Playing
        // Stop-music on another app is pause's key, so it says the pause line.
        Command.Pause, Command.StopMusic -> PlaybackSpeech.Pausing
        Command.Next -> PlaybackSpeech.Next
        Command.Previous -> PlaybackSpeech.Previous
    }

    private fun dispatchMediaKey(ctx: SkillContext, keyCode: Int): Boolean {
        val audioManager = getSystemService(ctx.android, AudioManager::class.java) ?: return false
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }
}

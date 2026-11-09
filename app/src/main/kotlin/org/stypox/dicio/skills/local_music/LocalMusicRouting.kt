package org.stypox.dicio.skills.local_music

import android.view.KeyEvent

/**
 * Chooses whether a generic media command (play / pause / next / previous /
 * stop-music) should go to the local player or to MediaSkill's key-event path.
 *
 * Local wins if the local player is currently playing, or if it has a queue and
 * was last active within [RECENT_ACTIVITY_WINDOW_MS] (10 minutes). Otherwise
 * the command stays on the media-key path (Spotify and other apps).
 *
 * Stop-music uses the same choice. When the target is local it stops the local
 * player (pause then stop). When the target is another app it sends
 * [KeyEvent.KEYCODE_MEDIA_PAUSE], the same key [Command.Pause] sends.
 */
object LocalMusicRouting {
    const val RECENT_ACTIVITY_WINDOW_MS: Long = 10L * 60L * 1000L

    enum class Target {
        LocalPlayer,
        MediaSkill,
    }

    enum class Command {
        Play,
        Pause,
        Next,
        Previous,
        StopMusic,
    }

    enum class LocalAction {
        Resume,
        Pause,
        Next,
        Previous,
        Stop,
    }

    sealed interface Decision {
        data class Local(val action: LocalAction) : Decision
        data class MediaKey(val keyCode: Int) : Decision
    }

    fun targetForGenericMediaCommand(
        localPlayerHasQueue: Boolean,
        localPlayerIsPlaying: Boolean,
        localSessionRecentlyActiveMillisAgo: Long?,
    ): Target {
        if (localPlayerIsPlaying) return Target.LocalPlayer
        val wasRecentlyActive = localSessionRecentlyActiveMillisAgo != null &&
            localSessionRecentlyActiveMillisAgo <= RECENT_ACTIVITY_WINDOW_MS
        if (localPlayerHasQueue && wasRecentlyActive) return Target.LocalPlayer
        return Target.MediaSkill
    }

    /**
     * @param isPlaying [LocalMusicController.isPlaying]
     * @param hasQueue [LocalMusicController.hasQueue]
     * @param lastActiveElapsedMillis [LocalMusicController.lastActiveElapsedMillis]
     */
    fun decide(
        command: Command,
        isPlaying: Boolean,
        hasQueue: Boolean,
        lastActiveElapsedMillis: Long?,
    ): Decision {
        val local = targetForGenericMediaCommand(
            localPlayerHasQueue = hasQueue,
            localPlayerIsPlaying = isPlaying,
            localSessionRecentlyActiveMillisAgo = lastActiveElapsedMillis,
        ) == Target.LocalPlayer
        return if (local) {
            Decision.Local(
                when (command) {
                    Command.Play -> LocalAction.Resume
                    Command.Pause -> LocalAction.Pause
                    Command.Next -> LocalAction.Next
                    Command.Previous -> LocalAction.Previous
                    Command.StopMusic -> LocalAction.Stop
                }
            )
        } else {
            Decision.MediaKey(
                when (command) {
                    Command.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
                    Command.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    Command.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
                    Command.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    // Same key MediaSkill pause sends today.
                    Command.StopMusic -> KeyEvent.KEYCODE_MEDIA_PAUSE
                }
            )
        }
    }
}

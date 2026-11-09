package org.stypox.dicio.skills.local_music

import android.view.KeyEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Command
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Decision
import org.stypox.dicio.skills.local_music.LocalMusicRouting.LocalAction

/**
 * (player state) x (play, pause, next, previous, stop-music) -> local action
 * or the media key MediaSkill would dispatch.
 */
class LocalMusicRoutingDecideTest : StringSpec({
    data class State(
        val label: String,
        val isPlaying: Boolean,
        val hasQueue: Boolean,
        val lastActiveElapsedMillis: Long?,
        val local: Boolean,
    )

    val window = LocalMusicRouting.RECENT_ACTIVITY_WINDOW_MS
    val states = listOf(
        State("local playing", isPlaying = true, hasQueue = true, lastActiveElapsedMillis = 0L, local = true),
        State("local has recent queue", isPlaying = false, hasQueue = true, lastActiveElapsedMillis = 60_000L, local = true),
        State(
            "local idle >10min",
            isPlaying = false,
            hasQueue = true,
            lastActiveElapsedMillis = window + 1,
            local = false,
        ),
        State("never used", isPlaying = false, hasQueue = false, lastActiveElapsedMillis = null, local = false),
    )

    fun expected(state: State, command: Command): Decision {
        if (state.local) {
            val action = when (command) {
                Command.Play -> LocalAction.Resume
                Command.Pause -> LocalAction.Pause
                Command.Next -> LocalAction.Next
                Command.Previous -> LocalAction.Previous
                Command.StopMusic -> LocalAction.Stop
            }
            return Decision.Local(action)
        }
        val keyCode = when (command) {
            Command.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            Command.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            Command.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            Command.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            Command.StopMusic -> KeyEvent.KEYCODE_MEDIA_PAUSE
        }
        return Decision.MediaKey(keyCode)
    }

    for (state in states) {
        for (command in Command.entries) {
            "${state.label} + $command" {
                LocalMusicRouting.decide(
                    command = command,
                    isPlaying = state.isPlaying,
                    hasQueue = state.hasQueue,
                    lastActiveElapsedMillis = state.lastActiveElapsedMillis,
                ) shouldBe expected(state, command)
            }
        }
    }
})

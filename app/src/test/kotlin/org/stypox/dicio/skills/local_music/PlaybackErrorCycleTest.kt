package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.LocalMusicTransport.ErrorAction

class PlaybackErrorCycleTest : StringSpec({
    fun pass(
        count: Int,
        start: Int,
        readyBeforeEachFailure: Boolean,
    ): List<ErrorAction> {
        val cycle = PlaybackErrorCycle()
        val actions = mutableListOf<ErrorAction>()
        var index = start
        repeat(count + 2) {
            if (readyBeforeEachFailure) {
                cycle.onPlaybackStateChanged(Player.STATE_READY)
            }
            val action = cycle.onError(count, index, RepeatMode.ALL)
            actions += action
            if (action is ErrorAction.Stop) return actions
            index = (action as ErrorAction.SkipTo).index
        }
        return actions
    }

    "repeat-all stops after one full pass when every file fails after ready" {
        val actions = pass(count = 3, start = 0, readyBeforeEachFailure = true)
        actions shouldBe listOf(
            ErrorAction.SkipTo(1),
            ErrorAction.SkipTo(2),
            ErrorAction.Stop,
        )
    }

    "repeat-all from the middle still stops after every index has failed once" {
        val actions = pass(count = 5, start = 2, readyBeforeEachFailure = true)
        actions shouldBe listOf(
            ErrorAction.SkipTo(3),
            ErrorAction.SkipTo(4),
            ErrorAction.SkipTo(0),
            ErrorAction.SkipTo(1),
            ErrorAction.Stop,
        )
    }

    "pre-ready failures still stop after one full pass" {
        pass(count = 3, start = 0, readyBeforeEachFailure = false) shouldBe listOf(
            ErrorAction.SkipTo(1),
            ErrorAction.SkipTo(2),
            ErrorAction.Stop,
        )
        pass(count = 5, start = 2, readyBeforeEachFailure = false) shouldBe listOf(
            ErrorAction.SkipTo(3),
            ErrorAction.SkipTo(4),
            ErrorAction.SkipTo(0),
            ErrorAction.SkipTo(1),
            ErrorAction.Stop,
        )
    }

    "ready and other playback states do not forget a failed index" {
        val cycle = PlaybackErrorCycle()
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_BUFFERING)
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onPlaybackStateChanged(Player.STATE_ENDED)
        cycle.onPlaybackStateChanged(Player.STATE_IDLE)
        // Index 0 already failed. Wrapping from the last item must stop.
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.Stop
    }

    "repeat off on the last item stops in one step" {
        val cycle = PlaybackErrorCycle()
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 2, RepeatMode.OFF) shouldBe ErrorAction.Stop
        cycle.onError(3, 2, RepeatMode.ONE) shouldBe ErrorAction.Stop
    }

    "a single repeat-all item stops" {
        PlaybackErrorCycle().onError(1, 0, RepeatMode.ALL) shouldBe ErrorAction.Stop
    }

    "replacing the playlist forgets failures" {
        val cycle = PlaybackErrorCycle()
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaylistChanged()
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
    }

    "repeat-all keeps playing when one song in the pass is good" {
        val cycle = PlaybackErrorCycle()
        // [bad, good, bad]. The good track reaches READY and the next item starts.
        cycle.onPlaybackStateChanged(Player.STATE_READY, 0)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 2)
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
        // The first file fails again and playback returns to the good one.
        cycle.onPlaybackStateChanged(Player.STATE_READY, 0)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 1)
        // The next corrupt item errors while the player is still READY.
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
    }

    "a good track is confirmed when the next ready has no index" {
        val cycle = PlaybackErrorCycle()
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
    }

    "after a good song a later pass where every file fails still stops" {
        val cycle = PlaybackErrorCycle()
        cycle.onPlaybackStateChanged(Player.STATE_READY, 0)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 2)
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
        // Index 2 already failed in this pass. When 0 and then 1 also fail,
        // the next item is 2, so the pass had no success and playback stops.
        cycle.onPlaybackStateChanged(Player.STATE_READY, 0)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY, 1)
        cycle.onError(3, 1, RepeatMode.ALL) shouldBe ErrorAction.Stop
    }

    "rebuffer on the same index does not forgive a failure" {
        val cycle = PlaybackErrorCycle()
        fun fail(index: Int): ErrorAction {
            cycle.onPlaybackStateChanged(Player.STATE_READY, index)
            cycle.onPlaybackStateChanged(Player.STATE_BUFFERING, index)
            cycle.onPlaybackStateChanged(Player.STATE_READY, index)
            return cycle.onError(3, index, RepeatMode.ALL)
        }
        fail(0) shouldBe ErrorAction.SkipTo(1)
        fail(1) shouldBe ErrorAction.SkipTo(2)
        fail(2) shouldBe ErrorAction.Stop
    }

    "stopping clears the cycle so the next error can skip again" {
        val cycle = PlaybackErrorCycle()
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 1, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(2)
        cycle.onPlaybackStateChanged(Player.STATE_READY)
        cycle.onError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.Stop
        cycle.onError(3, 0, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(1)
    }
})

package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.LocalMusicTransport.ErrorAction
import org.stypox.dicio.skills.local_music.LocalMusicTransport.PlaybackPosition
import org.stypox.dicio.skills.local_music.LocalMusicTransport.SkipDecision

class LocalMusicTransportTest : StringSpec({
    fun next(
        count: Int,
        index: Int,
        position: PlaybackPosition,
        mode: RepeatMode,
    ) = LocalMusicTransport.decideNext(count, index, position, mode)

    fun previous(
        count: Int,
        index: Int,
        position: PlaybackPosition,
        mode: RepeatMode,
    ) = LocalMusicTransport.decidePrevious(count, index, position, mode)

    "empty queue says nothing is playing" {
        next(0, -1, PlaybackPosition.Idle, RepeatMode.OFF) shouldBe SkipDecision.NothingPlaying
        previous(0, 0, PlaybackPosition.Active, RepeatMode.ALL) shouldBe SkipDecision.NothingPlaying
        next(3, -1, PlaybackPosition.Idle, RepeatMode.OFF) shouldBe SkipDecision.NothingPlaying
    }

    "next on the last item stops when repeat is off or one, including after stop" {
        for (mode in listOf(RepeatMode.OFF, RepeatMode.ONE)) {
            for (position in PlaybackPosition.entries) {
                next(2, 1, position, mode) shouldBe SkipDecision.LastSong
            }
        }
    }

    "next on the last item wraps when repeat is all" {
        next(2, 1, PlaybackPosition.Active, RepeatMode.ALL) shouldBe
            SkipDecision.Play(index = 0, prepareAndPlay = false)
        next(1, 0, PlaybackPosition.Active, RepeatMode.ALL) shouldBe
            SkipDecision.Play(index = 0, prepareAndPlay = false)
    }

    "after stop, next and previous prepare and start the target" {
        next(3, 0, PlaybackPosition.Idle, RepeatMode.OFF) shouldBe
            SkipDecision.Play(index = 1, prepareAndPlay = true)
        next(3, 0, PlaybackPosition.Ended, RepeatMode.OFF) shouldBe
            SkipDecision.Play(index = 1, prepareAndPlay = true)
        previous(3, 2, PlaybackPosition.Idle, RepeatMode.OFF) shouldBe
            SkipDecision.Play(index = 1, prepareAndPlay = true)
        previous(3, 0, PlaybackPosition.Ended, RepeatMode.ONE) shouldBe
            SkipDecision.Play(index = 0, prepareAndPlay = true)
    }

    "while playing, next and previous seek without forcing play" {
        next(3, 0, PlaybackPosition.Active, RepeatMode.OFF) shouldBe
            SkipDecision.Play(index = 1, prepareAndPlay = false)
        previous(3, 0, PlaybackPosition.Active, RepeatMode.OFF) shouldBe
            SkipDecision.Play(index = 0, prepareAndPlay = false)
        previous(3, 0, PlaybackPosition.Active, RepeatMode.ALL) shouldBe
            SkipDecision.Play(index = 2, prepareAndPlay = false)
    }

    "player error skips to the next item, wraps only for repeat all, else stops" {
        LocalMusicTransport.decideOnError(3, 0, RepeatMode.OFF) shouldBe ErrorAction.SkipTo(1)
        LocalMusicTransport.decideOnError(3, 0, RepeatMode.ONE) shouldBe ErrorAction.SkipTo(1)
        LocalMusicTransport.decideOnError(3, 2, RepeatMode.OFF) shouldBe ErrorAction.Stop
        LocalMusicTransport.decideOnError(3, 2, RepeatMode.ONE) shouldBe ErrorAction.Stop
        LocalMusicTransport.decideOnError(3, 2, RepeatMode.ALL) shouldBe ErrorAction.SkipTo(0)
        LocalMusicTransport.decideOnError(1, 0, RepeatMode.ALL) shouldBe ErrorAction.Stop
        LocalMusicTransport.decideOnError(
            itemCount = 3,
            currentIndex = 2,
            repeatMode = RepeatMode.ALL,
            failedIndices = setOf(0),
        ) shouldBe ErrorAction.Stop
    }
})

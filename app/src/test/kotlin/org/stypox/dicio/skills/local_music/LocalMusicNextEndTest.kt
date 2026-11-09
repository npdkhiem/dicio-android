package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMusicNextEndTest : StringSpec({
    "next at the end stops when repeat is off or one, and wraps when repeat is all" {
        LocalMusicQueue.nextAtEndOutcome(RepeatMode.OFF) shouldBe NextAtEndOutcome.Stop
        LocalMusicQueue.nextAtEndOutcome(RepeatMode.ONE) shouldBe NextAtEndOutcome.Stop
        LocalMusicQueue.nextAtEndOutcome(RepeatMode.ALL) shouldBe NextAtEndOutcome.Wrap
    }

    "queue next on the last item follows nextAtEndOutcome" {
        for (mode in RepeatMode.entries) {
            val queue = LocalMusicQueue(listOf("a.mp3", "b.mp3"), mode)
            queue.playAt("b.mp3")
            val indexBefore = queue.currentIndex
            val advanced = queue.next()
            when (LocalMusicQueue.nextAtEndOutcome(mode)) {
                NextAtEndOutcome.Stop -> {
                    advanced shouldBe null
                    queue.currentIndex shouldBe indexBefore
                    queue.currentTrack shouldBe "b.mp3"
                }
                NextAtEndOutcome.Wrap -> {
                    advanced shouldBe "a.mp3"
                    queue.currentTrack shouldBe "a.mp3"
                }
            }
        }
    }

    "previous on the first item restarts it when repeat does not wrap" {
        for (mode in listOf(RepeatMode.OFF, RepeatMode.ONE)) {
            val queue = LocalMusicQueue(listOf("a.mp3", "b.mp3"), mode)
            queue.playAt("a.mp3")
            queue.previous() shouldBe "a.mp3"
            queue.currentIndex shouldBe 0
        }
        val wrapping = LocalMusicQueue(listOf("a.mp3", "b.mp3"), RepeatMode.ALL)
        wrapping.playAt("a.mp3")
        wrapping.previous() shouldBe "b.mp3"
    }
})

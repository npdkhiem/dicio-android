package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMusicQueueTest : StringSpec({
    fun queueOf(vararg names: String, mode: RepeatMode = RepeatMode.OFF): LocalMusicQueue =
        LocalMusicQueue(names.toList(), mode)

    "sorts case-insensitively by normalized name" {
        val queue = queueOf("Zebra.mp3", "apple.mp3", "Banana.mp3")
        queue.tracks shouldBe listOf("apple.mp3", "Banana.mp3", "Zebra.mp3")
    }

    "same stem prefers mp3 then m4a then flac then ogg then wav" {
        val queue = queueOf("Hello.wav", "Hello.ogg", "Hello.flac", "Hello.m4a", "Hello.mp3")
        queue.tracks shouldBe listOf(
            "Hello.mp3",
            "Hello.m4a",
            "Hello.flac",
            "Hello.ogg",
            "Hello.wav",
        )
    }

    "sorts by normalized name so Vietnamese đ counts as d" {
        val queue = queueOf("Đi.mp3", "Anh.mp3", "Da.mp3")
        queue.tracks shouldBe listOf("Anh.mp3", "Da.mp3", "Đi.mp3")
    }

    "repeat mode defaults to OFF" {
        LocalMusicQueue(listOf("a.mp3")).repeatMode shouldBe RepeatMode.OFF
    }

    "playAt unknown name returns null and leaves index unchanged" {
        val queue = queueOf("a.mp3", "b.mp3")
        queue.currentIndex shouldBe LocalMusicQueue.NO_TRACK
        queue.playAt("missing.mp3") shouldBe null
        queue.currentIndex shouldBe LocalMusicQueue.NO_TRACK
        queue.playAt("a.mp3") shouldBe "a.mp3"
        queue.playAt("nope.mp3") shouldBe null
        queue.currentTrack shouldBe "a.mp3"
        queue.currentIndex shouldBe 0
    }

    "playAt selects the named track" {
        val queue = queueOf("c.mp3", "a.mp3", "b.mp3")
        queue.playAt("b.mp3") shouldBe "b.mp3"
        queue.currentTrack shouldBe "b.mp3"
    }

    "OFF next at last returns null; previous at first restarts first" {
        val queue = queueOf("a.mp3", "b.mp3", "c.mp3")
        queue.playAt("a.mp3")
        queue.previous() shouldBe "a.mp3"
        queue.currentTrack shouldBe "a.mp3"

        queue.playAt("c.mp3")
        queue.next() shouldBe null
        queue.currentTrack shouldBe "c.mp3"

        queue.playAt("b.mp3")
        queue.next() shouldBe "c.mp3"
        queue.previous() shouldBe "b.mp3"
    }

    "OFF onTrackEnded advances like next, and stops after the last item" {
        val queue = queueOf("a.mp3", "b.mp3")
        queue.playAt("a.mp3")
        queue.onTrackEnded() shouldBe "b.mp3"
        queue.onTrackEnded() shouldBe null
        queue.currentTrack shouldBe "b.mp3"
    }

    "ALL wraps next and previous both ways" {
        val queue = queueOf("a.mp3", "b.mp3", "c.mp3", mode = RepeatMode.ALL)
        queue.playAt("c.mp3")
        queue.next() shouldBe "a.mp3"
        queue.previous() shouldBe "c.mp3"

        queue.playAt("a.mp3")
        queue.previous() shouldBe "c.mp3"
        queue.next() shouldBe "a.mp3"
    }

    "ALL onTrackEnded wraps from last to first" {
        val queue = queueOf("a.mp3", "b.mp3", mode = RepeatMode.ALL)
        queue.playAt("b.mp3")
        queue.onTrackEnded() shouldBe "a.mp3"
        queue.onTrackEnded() shouldBe "b.mp3"
    }

    "ONE onTrackEnded repeats the current item" {
        val queue = queueOf("a.mp3", "b.mp3", "c.mp3", mode = RepeatMode.ONE)
        queue.playAt("b.mp3")
        queue.onTrackEnded() shouldBe "b.mp3"
        queue.currentTrack shouldBe "b.mp3"
        queue.onTrackEnded() shouldBe "b.mp3"
    }

    "ONE explicit next at last returns null like OFF; previous at first restarts first" {
        val queue = queueOf("a.mp3", "b.mp3", "c.mp3", mode = RepeatMode.ONE)
        queue.playAt("c.mp3")
        queue.next() shouldBe null
        queue.currentTrack shouldBe "c.mp3"

        queue.playAt("a.mp3")
        queue.previous() shouldBe "a.mp3"
        queue.currentTrack shouldBe "a.mp3"
    }

    "ONE explicit next and previous still move in the middle of the queue" {
        val queue = queueOf("a.mp3", "b.mp3", "c.mp3", mode = RepeatMode.ONE)
        queue.playAt("a.mp3")
        queue.next() shouldBe "b.mp3"
        queue.next() shouldBe "c.mp3"
        queue.previous() shouldBe "b.mp3"
    }

    "next previous and onTrackEnded without a current track return null" {
        val queue = queueOf("a.mp3", "b.mp3")
        queue.next() shouldBe null
        queue.previous() shouldBe null
        queue.onTrackEnded() shouldBe null
    }

    "empty queue returns null from every navigation method" {
        val queue = LocalMusicQueue(emptyList())
        queue.tracks shouldBe emptyList()
        queue.playAt("a.mp3") shouldBe null
        queue.next() shouldBe null
        queue.previous() shouldBe null
        queue.onTrackEnded() shouldBe null
    }

    "repeatMode can be changed after construction" {
        val queue = queueOf("a.mp3", "b.mp3")
        queue.playAt("b.mp3")
        queue.next() shouldBe null
        queue.repeatMode = RepeatMode.ALL
        queue.next() shouldBe "a.mp3"
    }
})

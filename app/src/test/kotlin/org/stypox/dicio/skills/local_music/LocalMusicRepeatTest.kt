package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class LocalMusicRepeatTest : StringSpec({
    "maps queue repeat modes onto Player constants" {
        LocalMusicRepeat.toPlayerRepeatMode(RepeatMode.OFF) shouldBe Player.REPEAT_MODE_OFF
        LocalMusicRepeat.toPlayerRepeatMode(RepeatMode.ONE) shouldBe Player.REPEAT_MODE_ONE
        LocalMusicRepeat.toPlayerRepeatMode(RepeatMode.ALL) shouldBe Player.REPEAT_MODE_ALL
    }

    "default queue mode is off" {
        LocalMusicQueue(listOf("a.mp3")).repeatMode shouldBe RepeatMode.OFF
        LocalMusicRepeat.toPlayerRepeatMode(RepeatMode.OFF) shouldBe Player.REPEAT_MODE_OFF
    }

    "player repeat constants round-trip" {
        for (mode in RepeatMode.entries) {
            val playerMode = LocalMusicRepeat.toPlayerRepeatMode(mode)
            LocalMusicRepeat.fromPlayerRepeatMode(playerMode) shouldBe mode
        }
        LocalMusicRepeat.fromPlayerRepeatMode(-1) shouldBe RepeatMode.OFF
    }

    "previous moves to the previous item instead of the 3 second restart" {
        LocalMusicRepeat.MAX_SEEK_TO_PREVIOUS_POSITION_MS shouldBe Long.MAX_VALUE
        LocalMusicRepeat.MAX_SEEK_TO_PREVIOUS_POSITION_MS shouldBeGreaterThan 3_000L
    }
})
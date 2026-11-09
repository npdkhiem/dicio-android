package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LocalMusicSpeechTest : StringSpec({
    "playing speaks the file stem" {
        LocalMusicSpeechSelection.forPlayResult(
            PlayByNameResult.Playing("Nơi Này Có Anh.mp3"),
        ) shouldBe LocalMusicSpeech.Playing("Nơi Này Có Anh")
    }

    "no match keeps the query" {
        LocalMusicSpeechSelection.forPlayResult(
            PlayByNameResult.NoMatch("hallo there"),
        ) shouldBe LocalMusicSpeech.NoMatch("hallo there")
    }

    "tie asks with stems and is not a playing result" {
        val speech = LocalMusicSpeechSelection.forPlayResult(
            PlayByNameResult.Tie("Song A.mp3", "Song B.flac"),
        )
        speech shouldBe LocalMusicSpeech.Tie("Song A", "Song B")
        speech.shouldBeInstanceOf<LocalMusicSpeech.Tie>()
    }

    "empty folder and missing permission" {
        LocalMusicSpeechSelection.forPlayResult(PlayByNameResult.NoFolderOrEmpty) shouldBe
            LocalMusicSpeech.NoFolderOrEmpty
        LocalMusicSpeechSelection.forPlayResult(PlayByNameResult.PermissionMissing) shouldBe
            LocalMusicSpeech.PermissionMissing
    }

    "repeat and stop are their own speech" {
        LocalMusicSpeech.Repeat(RepeatMode.OFF).mode shouldBe RepeatMode.OFF
        LocalMusicSpeech.Repeat(RepeatMode.ONE).mode shouldBe RepeatMode.ONE
        LocalMusicSpeech.Repeat(RepeatMode.ALL).mode shouldBe RepeatMode.ALL
        LocalMusicSpeech.Stopped shouldBe LocalMusicSpeech.Stopped
        LocalMusicSpeech.MissingSong shouldBe LocalMusicSpeech.MissingSong
    }
})

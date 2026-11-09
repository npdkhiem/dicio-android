package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMusicPlaybackErrorTest : StringSpec({
    "only a failure of the requested song is reported, once" {
        val report = PlaybackErrorReport()
        report.onFailed("Hello")
        report.consume() shouldBe null

        report.onRequested("Hello")
        report.onFailed("Goodbye")
        report.consume() shouldBe null

        report.onRequested("Hello")
        report.onFailed("Hello")
        report.consume() shouldBe "Hello"
        report.consume() shouldBe null
    }

    "a song that reached ready is no longer the request" {
        val report = PlaybackErrorReport()
        report.onRequested("Hello")
        report.onPlayed("Hello")
        report.onFailed("Hello")
        report.consume() shouldBe null
    }

    "ready of a different stem leaves the request in place" {
        val report = PlaybackErrorReport()
        report.onRequested("Hello")
        report.onPlayed("Goodbye")
        report.onFailed("Hello")
        report.consume() shouldBe "Hello"
    }

    "a failure before the song plays is still reported" {
        val report = PlaybackErrorReport()
        report.onRequested("Hello")
        report.onFailed("Hello")
        report.consume() shouldBe "Hello"
        report.consume() shouldBe null
    }

    "clearing the request drops a later same-name failure" {
        val report = PlaybackErrorReport()
        report.onRequested("Hello")
        report.clearRequested()
        report.onFailed("Hello")
        report.consume() shouldBe null
    }

    "reaching ready does not drop a failure that was already pending" {
        val report = PlaybackErrorReport()
        report.onRequested("Hello")
        report.onFailed("Hello")
        report.onPlayed("Hello")
        report.consume() shouldBe "Hello"
    }

    "revoked saved folder is access lost, not an empty folder" {
        LocalMusicAccess.failureForList(savedTree = true, securityException = true) shouldBe
            PlayByNameResult.AccessLost
        LocalMusicAccess.failureForList(savedTree = false, securityException = true) shouldBe
            PlayByNameResult.PermissionMissing
        LocalMusicAccess.failureForList(savedTree = true, securityException = false) shouldBe
            PlayByNameResult.NoFolderOrEmpty
    }
})

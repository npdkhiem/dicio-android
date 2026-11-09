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

    "revoked saved folder is access lost, not an empty folder" {
        LocalMusicAccess.failureForList(savedTree = true, securityException = true) shouldBe
            PlayByNameResult.AccessLost
        LocalMusicAccess.failureForList(savedTree = false, securityException = true) shouldBe
            PlayByNameResult.PermissionMissing
        LocalMusicAccess.failureForList(savedTree = true, securityException = false) shouldBe
            PlayByNameResult.NoFolderOrEmpty
    }
})

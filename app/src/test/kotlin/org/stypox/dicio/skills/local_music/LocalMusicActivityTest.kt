package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMusicActivityTest : StringSpec({
    "user commands and track changes while playing refresh the window" {
        LocalMusicActivity.effect(LocalMusicActivity.Signal.UserCommand) shouldBe
            LocalMusicActivity.Effect.Refresh
        LocalMusicActivity.effect(LocalMusicActivity.Signal.TrackTransitionWhilePlaying) shouldBe
            LocalMusicActivity.Effect.Refresh
    }

    "permanent audio focus loss clears the window" {
        LocalMusicActivity.effect(LocalMusicActivity.Signal.PermanentAudioFocusLoss) shouldBe
            LocalMusicActivity.Effect.Clear
    }

    "passive playback changes and media-key commands do not refresh the window" {
        LocalMusicActivity.effect(LocalMusicActivity.Signal.PassivePlaybackChange) shouldBe
            LocalMusicActivity.Effect.Keep
        LocalMusicActivity.effect(LocalMusicActivity.Signal.MediaKeyCommand) shouldBe
            LocalMusicActivity.Effect.Keep
    }
})

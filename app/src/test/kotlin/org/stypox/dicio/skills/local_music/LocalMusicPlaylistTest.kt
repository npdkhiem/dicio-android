package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMusicPlaylistTest : StringSpec({
    fun track(name: String, uri: String = name) = LocalMusicPlaylist.Track(name, uri)

    "keeps supported files in LocalMusicQueue order and drops the rest" {
        val input = listOf(
            track("notes.txt", "txt"),
            track("Zebra.mp3", "z"),
            track("apple.mp3", "a"),
            track("voice.aac", "aac"),
            track("Đi.mp3", "di"),
            track("Da.flac", "da"),
            track("clip.mp4", "mp4"),
        )
        val playlist = LocalMusicPlaylist.fromTracks(input)
        val supportedNames = input
            .map { it.displayName }
            .filter(LocalMusicMatcher::isSupportedAudioFile)
        playlist.map { it.displayName } shouldBe LocalMusicQueue(supportedNames).tracks
        playlist.map { it.uri } shouldBe listOf("a", "da", "di", "z")
    }

    "duplicate names keep stable URI order" {
        val playlist = LocalMusicPlaylist.fromTracks(
            listOf(
                track("b.mp3", "b1"),
                track("a.mp3", "a"),
                track("b.mp3", "b2"),
            )
        )
        playlist.map { it.uri } shouldBe listOf("a", "b1", "b2")
        LocalMusicPlaylist.indexOf(playlist, "b.mp3") shouldBe 1
        LocalMusicPlaylist.indexOf(playlist, "missing.mp3") shouldBe -1
    }

    "resolvePlay uses the sorted index" {
        val playlist = LocalMusicPlaylist.fromTracks(
            listOf(track("zeta.mp3"), track("alpha.mp3"), track("beta.mp3"))
        )
        playlist.map { it.displayName } shouldBe listOf("alpha.mp3", "beta.mp3", "zeta.mp3")
        LocalMusicPlaylist.resolve("zeta", playlist.map { it.displayName }) shouldBe
            LocalMusicPlaylist.ResolvedPlay.Play("zeta.mp3", 2)
    }

    "identical stems play the preferred file at its playlist index" {
        val playlist = LocalMusicPlaylist.fromTracks(
            listOf(
                track("Hello.flac", "flac-uri"),
                track("Hello.mp3", "mp3-uri"),
                track("hello.wav", "wav-uri"),
            )
        )
        // mp3 > flac > wav, then that index is the one playback seeks to.
        playlist.map { it.displayName } shouldBe listOf("Hello.mp3", "Hello.flac", "hello.wav")
        LocalMusicPlaylist.resolve("hello", playlist.map { it.displayName }) shouldBe
            LocalMusicPlaylist.ResolvedPlay.Play("Hello.mp3", 0)
    }

    "duplicate names resolve to the first URI, not a tie" {
        val playlist = LocalMusicPlaylist.fromTracks(
            listOf(
                track("Intro.mp3", "content://album-b/Intro.mp3"),
                track("Intro.mp3", "content://album-a/Intro.mp3"),
            )
        )
        playlist.map { it.uri } shouldBe listOf(
            "content://album-a/Intro.mp3",
            "content://album-b/Intro.mp3",
        )
        LocalMusicPlaylist.resolve("intro", playlist.map { it.displayName }) shouldBe
            LocalMusicPlaylist.ResolvedPlay.Play("Intro.mp3", 0)
    }

    "different titles with the same score still tie and do not pick an index" {
        val names = listOf("Hello World.mp3", "Hello There.mp3")
        LocalMusicPlaylist.resolve("hello", names) shouldBe
            LocalMusicPlaylist.ResolvedPlay.Tie("Hello There.mp3", "Hello World.mp3")
    }

    "resolvePlay no match and empty folder" {
        LocalMusicPlaylist.resolve("zzzzzzzzzzzz", listOf("alpha.mp3")) shouldBe
            LocalMusicPlaylist.ResolvedPlay.NoMatch("zzzzzzzzzzzz")
        LocalMusicPlaylist.resolve("hello", emptyList()) shouldBe
            LocalMusicPlaylist.ResolvedPlay.Empty
    }
})

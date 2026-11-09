package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LocalMusicMatcherTest : StringSpec({
    val library = listOf(
        "hello.mp3",
        "Yesterday.mp3",
        "Nơi Này Có Anh.mp3",
        "Đi.mp3",
        "notes.txt",
        "video.mp4",
        "song.aac",
    )

    fun match(query: String, files: List<String> = library): MatchResult =
        LocalMusicMatcher.findBestMatches(query, files)

    "exact stem match" {
        match("hello") shouldBe MatchResult.Single("hello.mp3")
    }

    "case-insensitive match" {
        match("HELLO") shouldBe MatchResult.Single("hello.mp3")
        match("yesterday") shouldBe MatchResult.Single("Yesterday.mp3")
    }

    "misheard hallo matches hello.mp3" {
        match("hallo") shouldBe MatchResult.Single("hello.mp3")
    }

    "split words yes ter day match Yesterday.mp3" {
        match("yes ter day") shouldBe MatchResult.Single("Yesterday.mp3")
    }

    "Vietnamese query matches diacritic filename" {
        match("noi nay co anh") shouldBe MatchResult.Single("Nơi Này Có Anh.mp3")
    }

    "Vietnamese đ maps to d" {
        LocalMusicMatcher.normalizeName("đi") shouldBe "di"
        LocalMusicMatcher.normalizeName("Đi") shouldBe "di"
        match("di") shouldBe MatchResult.Single("Đi.mp3")
        match("đi", listOf("di.mp3")) shouldBe MatchResult.Single("di.mp3")
    }

    "tie when top two scores are equal, deterministic order" {
        val files = listOf("hello.mp3", "hello.flac", "yesterday.ogg")
        match("hello", files) shouldBe MatchResult.Tie(listOf("hello.flac", "hello.mp3"))
    }

    "no match when nothing is reasonably close" {
        match("watermelon") shouldBe MatchResult.NoMatch
        match("zzzznotasong") shouldBe MatchResult.NoMatch
    }

    "empty list yields no match" {
        match("hello", emptyList()) shouldBe MatchResult.NoMatch
    }

    "empty query yields no match" {
        match("   ") shouldBe MatchResult.NoMatch
        match("...") shouldBe MatchResult.NoMatch
    }

    "extension is ignored when matching the stem" {
        match("hello", listOf("hello.WAV")) shouldBe MatchResult.Single("hello.WAV")
        match("yesterday", listOf("Yesterday.FLAC")) shouldBe MatchResult.Single("Yesterday.FLAC")
    }

    "unsupported files are filtered" {
        match("notes") shouldBe MatchResult.NoMatch
        match("video") shouldBe MatchResult.NoMatch
        match("song", listOf("song.aac", "song.mp3")) shouldBe MatchResult.Single("song.mp3")
        match("hello", listOf("hello.txt", "readme.md")) shouldBe MatchResult.NoMatch
    }

    "extra and missing words still match" {
        match("hello there") shouldBe MatchResult.Single("hello.mp3")
        match("anh") shouldBe MatchResult.Single("Nơi Này Có Anh.mp3")
    }

    "punctuation underscores and dashes normalize to spaces" {
        LocalMusicMatcher.normalizeName("Don't_Stop-Me.now") shouldBe "don t stop me now"
        match("dont stop me now", listOf("Don't_Stop-Me.now.mp3")) shouldBe
            MatchResult.Single("Don't_Stop-Me.now.mp3")
    }

    "isSupportedAudioFile accepts mp3 m4a ogg flac wav case-insensitively" {
        LocalMusicMatcher.isSupportedAudioFile("a.mp3") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.MP3") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.m4a") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.M4A") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.ogg") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.OGG") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.flac") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.Flac") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.wav") shouldBe true
        LocalMusicMatcher.isSupportedAudioFile("a.WAV") shouldBe true
    }

    "isSupportedAudioFile rejects other extensions and missing extensions" {
        LocalMusicMatcher.isSupportedAudioFile("a.mp4") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("a.aac") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("a.txt") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("a.mp3.bak") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("mp3") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("a.") shouldBe false
        LocalMusicMatcher.isSupportedAudioFile("") shouldBe false
    }

    "fileStem strips only the last extension" {
        LocalMusicMatcher.fileStem("hello.mp3") shouldBe "hello"
        LocalMusicMatcher.fileStem("Nơi Này Có Anh.MP3") shouldBe "Nơi Này Có Anh"
        LocalMusicMatcher.fileStem("noext") shouldBe "noext"
    }

    "Single result type for a unique winner" {
        match("hello").shouldBeInstanceOf<MatchResult.Single>()
    }
})

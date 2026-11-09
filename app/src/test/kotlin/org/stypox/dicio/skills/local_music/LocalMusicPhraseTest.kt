package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.LocalMusicPhrases.TrackSkip

/**
 * Only an exact normalized phrase is a skip. "next to me", "last christmas",
 * and "the last song of summer" are titles: the leading "the" and any extra
 * word keep them out of [LocalMusicPhrases.skipForCapture].
 */
class LocalMusicPhraseTest : StringSpec({
    val nextCaptures = listOf(
        "next",
        "following",
        "next song",
        "next track",
        "next music",
        "next media",
        "next audio",
        "following song",
        "following track",
        "NEXT SONG",
        "next one",
        "Next-One",
    )
    for (capture in nextCaptures) {
        "capture '$capture' is next" {
            LocalMusicPhrases.skipForCapture(capture) shouldBe TrackSkip.Next
        }
    }

    val previousCaptures = listOf(
        "previous",
        "last",
        "previous song",
        "previous track",
        "last song",
        "last track",
        "last music",
        "last media",
        "last audio",
        "Previous Song",
    )
    for (capture in previousCaptures) {
        "capture '$capture' is previous" {
            LocalMusicPhrases.skipForCapture(capture) shouldBe TrackSkip.Previous
        }
    }

    val titleCaptures = listOf(
        "next to me",
        "last christmas",
        "the last song of summer",
        "last song of summer",
        "the next song",
        "next song please",
        "song next",
        "following one",
        "next one more",
        "",
        "   ",
    )
    for (capture in titleCaptures) {
        "capture '$capture' is a title" {
            LocalMusicPhrases.skipForCapture(capture) shouldBe null
        }
    }

    "null capture is a title" {
        LocalMusicPhrases.skipForCapture(null) shouldBe null
    }

    val nextUtterances = listOf(
        "play the next song",
        "play next song",
        "play the next track",
        "play the following song",
        "play next one",
        "play the next one",
        "play song next",
        "play the song next",
        "play track following",
        "play music next song",
        "play the track next one",
    )
    for (utterance in nextUtterances) {
        "utterance '$utterance' is next" {
            LocalMusicPhrases.skipForUtterance(utterance) shouldBe TrackSkip.Next
        }
    }

    val previousUtterances = listOf(
        "play the previous song",
        "play the last song",
        "play previous",
        "play last song",
        "play song previous",
        "play song last",
        "play the track last song",
        "play music previous",
    )
    for (utterance in previousUtterances) {
        "utterance '$utterance' is previous" {
            LocalMusicPhrases.skipForUtterance(utterance) shouldBe TrackSkip.Previous
        }
    }

    val notExactSkips = listOf(
        "play song next to me",
        "play song last christmas",
        "play song the last song of summer",
        // Not exact. The compiled grammars prefer a title search; see
        // LocalMusicGrammarTest.
        "play the last song of summer",
        "play hello",
        "skip the song",
    )
    for (utterance in notExactSkips) {
        "utterance '$utterance' is not an exact skip" {
            LocalMusicPhrases.skipForUtterance(utterance) shouldBe null
        }
    }

    val stopUtterances = listOf(
        "stop music",
        "stop the music",
        "stop my music",
        "stop playing music",
        "stop local music",
        "stop the local music",
        "stop playing the music",
        "stop playing my music",
        "Stop The Music!",
    )
    for (utterance in stopUtterances) {
        "utterance '$utterance' is stop music" {
            LocalMusicPhrases.isStopMusic(utterance) shouldBe true
        }
    }

    val notStop = listOf(
        "pause the music",
        "stop the song",
        "halt the music",
        "stop listening",
        "stop repeating",
        "play the music",
    )
    for (utterance in notStop) {
        "utterance '$utterance' is not stop music" {
            LocalMusicPhrases.isStopMusic(utterance) shouldBe false
        }
    }
})

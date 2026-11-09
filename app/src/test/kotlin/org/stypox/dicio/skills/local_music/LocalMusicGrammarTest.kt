package org.stypox.dicio.skills.local_music

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.standard.StandardScore
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.LocalMusic
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Command

/**
 * English sentences compiled by the sentences plugin. Both skills' command
 * mapping is checked, because the ranker may pick either one when the scores
 * tie (Media is registered first, and [StandardScore.isBetterThan] is strict).
 *
 * "play the last song of summer" is not an exact skip. MediaSkill's previous
 * sentence still matches "play the last song" with a trailing "of summer", but
 * local play_song scores higher and searches for "last song of summer".
 */
class LocalMusicGrammarTest : StringSpec({
    val mediaData = Sentences.Media["en"].shouldNotBeNull()
    val localData = Sentences.LocalMusic["en"].shouldNotBeNull()

    data class Scored(
        val mediaScore: StandardScore,
        val media: Media,
        val localScore: StandardScore,
        val local: LocalMusic,
        val mediaCommand: Command,
        val localCommand: Command?,
    )

    fun score(input: String): Scored {
        val (mediaScore, media) = mediaData.score(MatchHelper(null, input), input)
        val (localScore, local) = localData.score(MatchHelper(null, input), input)
        return Scored(
            mediaScore,
            media,
            localScore,
            local,
            LocalMusicCommands.fromMedia(media, input),
            LocalMusicCommands.fromLocal(local, input),
        )
    }

    fun assertControl(input: String, command: Command) {
        val scored = score(input)
        withClue(
            "media=${scored.media} (${scored.mediaScore.score()}/${scored.mediaScore.scoreIn01Range()}) " +
                "local=${scored.local} (${scored.localScore.score()}/${scored.localScore.scoreIn01Range()})"
        ) {
            scored.mediaCommand shouldBe command
            scored.localCommand shouldBe command
            maxOf(scored.mediaScore.scoreIn01Range(), scored.localScore.scoreIn01Range())
                .shouldBeGreaterThan(0.85f)
        }
    }

    listOf(
        "play the next song",
        "play next song",
        "play the next track",
        "play the following song",
        "play next one",
        "play song next",
        "play track next song",
    ).forEach { input ->
        "'$input' is next, never play-by-name" { assertControl(input, Command.Next) }
    }

    listOf(
        "play the previous song",
        "play the last song",
        "play song previous",
        "play song last",
    ).forEach { input ->
        "'$input' is previous, never play-by-name" { assertControl(input, Command.Previous) }
    }

    fun assertMediaStop(input: String) {
        val scored = score(input)
        withClue(
            "media=${scored.media} (${scored.mediaScore.score()}/${scored.mediaScore.scoreIn01Range()}) " +
                "local=${scored.local} (${scored.localScore.score()}/${scored.localScore.scoreIn01Range()})"
        ) {
            scored.media.shouldBeInstanceOf<Media.Pause>()
            scored.mediaCommand shouldBe Command.StopMusic
            scored.mediaScore.isBetterThan(scored.localScore).shouldBeTrue()
            scored.mediaScore.scoreIn01Range().shouldBeGreaterThan(0.85f)
        }
    }

    listOf(
        "stop music",
        "stop the music",
        "stop my music",
        "stop playing music",
        "stop playing the music",
        "stop playing my music",
        "stop playing my local music",
        "stop local music",
        "stop the local music",
    ).forEach { input ->
        "'$input' is stop-music and MediaSkill wins" { assertMediaStop(input) }
    }

    "'stop the song' stays pause, not stop-music" {
        val scored = score("stop the song")
        scored.media.shouldBeInstanceOf<Media.Pause>()
        scored.mediaCommand shouldBe Command.Pause
    }

    fun assertTitle(input: String, capture: String) {
        val scored = score(input)
        withClue(
            "media=${scored.media} (${scored.mediaScore.score()}/${scored.mediaScore.scoreIn01Range()}) " +
                "local=${scored.local} (${scored.localScore.score()}/${scored.localScore.scoreIn01Range()})"
        ) {
            scored.localCommand shouldBe null
            val song = scored.local.shouldBeInstanceOf<LocalMusic.PlaySong>().song
            LocalMusicMatcher.normalizeName(song.orEmpty()) shouldBe capture
            LocalMusicPhrases.skipForCapture(song) shouldBe null
            scored.localScore.isBetterThan(scored.mediaScore).shouldBeTrue()
            scored.localScore.scoreIn01Range().shouldBeGreaterThan(0.85f)
        }
    }

    // The captured word is still a skip word. The utterance is a library
    // search, so the command is play-by-name (localCommand == null) and the
    // local score beats MediaSkill's next/previous hit.
    fun assertLibrarySearch(input: String, capture: String) {
        val scored = score(input)
        withClue(
            "media=${scored.media} (${scored.mediaScore.score()}/${scored.mediaScore.scoreIn01Range()}) " +
                "local=${scored.local} (${scored.localScore.score()}/${scored.localScore.scoreIn01Range()})"
        ) {
            scored.localCommand shouldBe null
            val song = scored.local.shouldBeInstanceOf<LocalMusic.PlaySong>().song
            LocalMusicMatcher.normalizeName(song.orEmpty()) shouldBe capture
            LocalMusicPhrases.skipForUtterance(input) shouldBe null
            LocalMusicPhrases.isPlayFromLibrary(input) shouldBe true
            scored.localScore.isBetterThan(scored.mediaScore).shouldBeTrue()
            scored.localScore.scoreIn01Range().shouldBeGreaterThan(0.85f)
        }
    }

    "play next from my music searches for next" {
        assertLibrarySearch("play next from my music", "next")
    }

    "play last from my phone searches for last" {
        assertLibrarySearch("play last from my phone", "last")
    }

    "play previous from my music searches for previous" {
        assertLibrarySearch("play previous from my music", "previous")
    }

    "play following from my phone searches for following" {
        assertLibrarySearch("play following from my phone", "following")
    }

    "from-library play is not a skip even when the capture is a skip word" {
        LocalMusicCommands.fromLocal(
            LocalMusic.PlaySong("next"),
            "play next from my music",
        ) shouldBe null
        LocalMusicCommands.fromLocal(
            LocalMusic.PlaySong("last"),
            "play last from my phone",
        ) shouldBe null
        LocalMusicCommands.fromLocal(
            LocalMusic.PlaySong("next"),
            "play song next",
        ) shouldBe Command.Next
    }

    "play song next to me searches next to me" {
        assertTitle("play song next to me", "next to me")
    }

    "play song last christmas searches last christmas" {
        assertTitle("play song last christmas", "last christmas")
    }

    "play song the last song of summer searches that title" {
        assertTitle("play song the last song of summer", "the last song of summer")
    }

    // Media previous also matches, with a lower score. The ranker therefore
    // searches; only the exact phrase "play the last song" is previous.
    "'play the last song of summer' searches, it is not an exact previous" {
        val scored = score("play the last song of summer")
        withClue(
            "media=${scored.media} (${scored.mediaScore.score()}/${scored.mediaScore.scoreIn01Range()}) " +
                "local=${scored.local} (${scored.localScore.score()}/${scored.localScore.scoreIn01Range()})"
        ) {
            scored.mediaCommand shouldBe Command.Previous
            scored.localCommand shouldBe null
            scored.localScore.isBetterThan(scored.mediaScore).shouldBeTrue()
            val song = scored.local.shouldBeInstanceOf<LocalMusic.PlaySong>().song
            LocalMusicMatcher.normalizeName(song.orEmpty()) shouldBe "last song of summer"
            LocalMusicPhrases.skipForCapture(song) shouldBe null
        }
    }
})

package org.stypox.dicio.skills.local_music

import android.content.Context
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.MockSkillInfo
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.LocalMusic
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Command
import org.stypox.dicio.skills.media.MediaOutput
import org.stypox.dicio.skills.media.MediaSkill
import org.stypox.dicio.skills.media.MediaUtterance
import org.stypox.dicio.skills.media.PlaybackSpeech
import java.util.Locale

/**
 * Context that fails if a reply path touches Android. Play, next-while-playing,
 * and the chooser plan do not need it. [getSpeechOutput] does, so these tests
 * join the English resource lines themselves.
 */
private object UnusedSkillContext : SkillContext {
    override val android: Context get() = error("android context")
    override val locale: Locale get() = error("locale")
    override val sentencesLanguage: String get() = error("language")
    override val parserFormatter: ParserFormatter? get() = error("parser")
    override val speechOutputDevice: SpeechOutputDevice get() = error("speech")
    override val previousOutput: SkillOutput? get() = error("previous")
    override val standardMatchHelper: MatchHelper? get() = error("match")
}

private class RecordingController : LocalMusicController {
    val report = PlaybackErrorReport()
    var playResult: PlayByNameResult? = null
    var nextOutcome: SkipOutcome = SkipOutcome.Advanced
    override var isPlaying: Boolean = false
    override var hasQueue: Boolean = true
    override val lastActiveElapsedMillis: Long? = null
    val played = mutableListOf<String>()

    override suspend fun playByName(query: String): PlayByNameResult {
        played += query
        return playResult ?: PlayByNameResult.Playing("$query.mp3")
    }

    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun next(): SkipOutcome = nextOutcome
    override suspend fun previous(): SkipOutcome = SkipOutcome.Advanced
    override suspend fun stop() = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override fun consumeCouldNotPlay(): String? = report.consume()

    fun failRequested(stem: String) {
        report.onRequested(stem)
        report.onFailed(stem)
    }
}

private fun spoken(failure: String, reply: String): String {
    val line = CouldNotPlaySpeech.join("I couldn't play $failure", reply)
    line.shouldContain("I couldn't play $failure")
    line.shouldContain(reply)
    return line
}

class LocalMusicCouldNotPlaySpeechTest : StringSpec({
    val localData = Sentences.LocalMusic["en"].shouldNotBeNull()
    val mediaData = Sentences.Media["en"].shouldNotBeNull()

    fun localSkill(controller: RecordingController) =
        LocalMusicSkill(MockSkillInfo, localData, controller)

    "a failed song is prepended to the next play reply and consumed once" {
        val controller = RecordingController()
        controller.failRequested("A")
        val skill = localSkill(controller)
        val (_, sentence) = localData.score(MatchHelper(null, "play song B"), "play song B")
        val song = sentence.shouldBeInstanceOf<LocalMusic.PlaySong>()
        song.song?.trim() shouldBe "B"

        val output = skill.generateOutput(
            UnusedSkillContext,
            LocalMusicUtterance(sentence, "play song B"),
        ).shouldBeInstanceOf<LocalMusicOutput>()

        controller.played shouldBe listOf("B")
        output.speech shouldBe LocalMusicSpeech.Playing("B")
        output.couldNotPlay shouldBe "A"
        spoken("A", "Playing B") shouldBe "I couldn't play A. Playing B"
        controller.consumeCouldNotPlay() shouldBe null

        val again = skill.generateOutput(
            UnusedSkillContext,
            LocalMusicUtterance(LocalMusic.PlaySong("C"), "play song C"),
        ).shouldBeInstanceOf<LocalMusicOutput>()
        again.speech shouldBe LocalMusicSpeech.Playing("C")
        again.couldNotPlay shouldBe null
        controller.played shouldBe listOf("B", "C")
    }

    "next keeps the failure line and the next line" {
        val controller = RecordingController().apply {
            isPlaying = true
            failRequested("A")
        }
        val output = localSkill(controller).generateOutput(
            UnusedSkillContext,
            LocalMusicUtterance(LocalMusic.Next, "next"),
        ).shouldBeInstanceOf<MediaOutput>()

        output.speech shouldBe PlaybackSpeech.Next
        output.couldNotPlay shouldBe "A"
        spoken("A", "Next media…") shouldBe "I couldn't play A. Next media…"
    }

    "no match, tie, access lost, and permission missing are not replaced" {
        val cases = listOf(
            PlayByNameResult.NoMatch("B") to LocalMusicSpeech.NoMatch("B"),
            PlayByNameResult.Tie("Hello.mp3", "World.flac") to
                LocalMusicSpeech.Tie("Hello", "World"),
            PlayByNameResult.AccessLost to LocalMusicSpeech.AccessLost,
            PlayByNameResult.PermissionMissing to LocalMusicSpeech.PermissionMissing,
        )
        val replies = listOf(
            "I couldn't find a song matching B",
            "Did you mean Hello or World?",
            "I lost access to your music folder, pick it again in Settings",
            "I need permission to read your music",
        )
        cases.zip(replies).forEach { (resultAndSpeech, reply) ->
            val (result, speech) = resultAndSpeech
            val controller = RecordingController().apply {
                playResult = result
                failRequested("A")
            }
            val output = localSkill(controller).generateOutput(
                UnusedSkillContext,
                LocalMusicUtterance(LocalMusic.PlaySong("B"), "play song B"),
            ).shouldBeInstanceOf<LocalMusicOutput>()
            output.speech shouldBe speech
            output.couldNotPlay shouldBe "A"
            spoken("A", reply) shouldBe "I couldn't play A. $reply"
        }
    }

    "last song and repeat are not replaced" {
        val controller = RecordingController().apply {
            isPlaying = true
            nextOutcome = SkipOutcome.LastSong
            failRequested("A")
        }
        val last = localSkill(controller).generateOutput(
            UnusedSkillContext,
            LocalMusicUtterance(LocalMusic.Next, "next"),
        ).shouldBeInstanceOf<MediaOutput>()
        last.speech shouldBe PlaybackSpeech.LastSong
        last.couldNotPlay shouldBe "A"
        spoken("A", "That was the last song") shouldBe "I couldn't play A. That was the last song"

        val repeatController = RecordingController().apply { failRequested("A") }
        val repeat = localSkill(repeatController).generateOutput(
            UnusedSkillContext,
            LocalMusicUtterance(LocalMusic.RepeatAll, "repeat all"),
        ).shouldBeInstanceOf<LocalMusicOutput>()
        repeat.speech shouldBe LocalMusicSpeech.Repeat(RepeatMode.ALL)
        repeat.couldNotPlay shouldBe "A"
        spoken("A", "Repeat is all") shouldBe "I couldn't play A. Repeat is all"
    }

    "a local pause from MediaSkill keeps both lines" {
        val controller = RecordingController().apply {
            isPlaying = true
            failRequested("A")
        }
        val output = MediaSkill(MockSkillInfo, mediaData, controller).generateOutput(
            UnusedSkillContext,
            MediaUtterance(Media.Pause, "pause"),
        ).shouldBeInstanceOf<MediaOutput>()
        output.speech shouldBe PlaybackSpeech.Pausing
        output.couldNotPlay shouldBe "A"
        spoken("A", "Pausing media…") shouldBe "I couldn't play A. Pausing media…"
    }

    "building a media output directly keeps the failure in front of the pause line" {
        val output = MediaOutput(PlaybackSpeech.Pausing, "A")
        output.speech shouldBe PlaybackSpeech.Pausing
        output.couldNotPlay shouldBe "A"
        spoken("A", "Pausing media…") shouldBe "I couldn't play A. Pausing media…"
    }

    "shared playback does not consume the pending failure" {
        val controller = RecordingController().apply {
            isPlaying = true
            failRequested("A")
        }
        SharedPlayback.perform(UnusedSkillContext, controller, Command.Next) shouldBe
            PlaybackSpeech.Next
        controller.consumeCouldNotPlay() shouldBe "A"
    }

    "a blank failure line leaves the reply alone" {
        CouldNotPlaySpeech.join(null, "Playing B") shouldBe "Playing B"
        CouldNotPlaySpeech.join("   ", "Playing B") shouldBe "Playing B"
        CouldNotPlaySpeech.join("I couldn't play A", "") shouldBe "I couldn't play A"
    }
})

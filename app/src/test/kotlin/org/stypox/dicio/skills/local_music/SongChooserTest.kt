package org.stypox.dicio.skills.local_music

import android.content.Context
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.MockSkill
import org.stypox.dicio.MockSkillInfo
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.sentences.Sentences
import java.util.Locale

private class ParserSkillContext(
    override val parserFormatter: ParserFormatter?,
) : SkillContext {
    override val android: Context get() = error("android context")
    override val locale: Locale = Locale.ENGLISH
    override val sentencesLanguage: String = "en"
    override val speechOutputDevice: SpeechOutputDevice get() = error("speech")
    override val previousOutput: SkillOutput? = null
    override val standardMatchHelper: MatchHelper? = null
}

private class ScriptedController(
    private val onPlay: (String) -> PlayByNameResult,
) : LocalMusicController {
    val played = mutableListOf<String>()

    override suspend fun playByName(query: String): PlayByNameResult {
        played += query
        return onPlay(query)
    }

    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun next(): SkipOutcome = SkipOutcome.Advanced
    override suspend fun previous(): SkipOutcome = SkipOutcome.Advanced
    override suspend fun stop() = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override val hasQueue: Boolean = false
    override val isPlaying: Boolean = false
    override val lastActiveElapsedMillis: Long? = null
}

class SongChooserTest : StringSpec({
    val stems = listOf("Hello", "World")
    val numbers = ParserFormatter(Locale.ENGLISH)
    val parser = ParserSkillContext(numbers)
    val noParser = ParserSkillContext(null)

    "the spoken name picks that stem and an unrelated name scores worst" {
        SongChoice.matchName("Hello", stems) shouldBe "Hello"
        SongChoice.matchName("hello", stems) shouldBe "Hello"
        SongChoice.matchName("World", stems) shouldBe "World"
        SongChoice.matchName("xyzzy", stems).shouldBeNull()
        SongChoice.matchName("banana", stems).shouldBeNull()
        SongChoice.matchName("Hello", listOf("Hello", "Hello")).shouldBeNull()

        val name = SongChooserName(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
        val (helloScore, hello) = name.score(parser, "Hello")
        helloScore shouldBe AlwaysBestScore
        hello shouldBe "Hello"
        val (missScore, miss) = name.score(parser, "xyzzy")
        missScore shouldBe AlwaysWorstScore
        miss.shouldBeNull()
    }

    "first and second pick the two stems" {
        listOf("the first one", "first", "1").forEach { input ->
            val ordinal = SongChoice.ordinal(parser, input)
            ordinal shouldBe 1L
            SongChoice.matchOrdinal(ordinal, stems) shouldBe "Hello"
        }
        listOf("the second one", "second", "2").forEach { input ->
            val ordinal = SongChoice.ordinal(parser, input)
            ordinal shouldBe 2L
            SongChoice.matchOrdinal(ordinal, stems) shouldBe "World"
        }
        SongChoice.matchOrdinal(3L, stems).shouldBeNull()
        SongChoice.ordinal(noParser, "the first one").shouldBeNull()

        val index = SongChooserIndex(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
        val (firstScore, first) = index.score(parser, "the first one")
        firstScore shouldBe AlwaysBestScore
        first shouldBe 1
        val (secondScore, second) = index.score(parser, "the second one")
        secondScore shouldBe AlwaysBestScore
        second shouldBe 2
        val (digitScore, digit) = index.score(parser, "2")
        digitScore shouldBe AlwaysBestScore
        digit shouldBe 2
        val (missScore, miss) = index.score(parser, "xyzzy")
        missScore shouldBe AlwaysWorstScore
        miss shouldBe 0
        index.score(noParser, "the first one").first shouldBe AlwaysWorstScore
    }

    "a tie starts both choosers and a choice says playing without asking again" {
        val controller = ScriptedController { query ->
            if (query == "hello") {
                PlayByNameResult.Tie("Hello.mp3", "World.flac")
            } else {
                PlayByNameResult.Playing("$query.mp3")
            }
        }
        val data = Sentences.LocalMusic["en"].shouldNotBeNull()
        val output = LocalMusicSkill(MockSkillInfo, data, controller).generateOutput(
            noParser,
            LocalMusicUtterance(Sentences.LocalMusic.PlaySong("hello"), "play hello"),
        ).shouldBeInstanceOf<LocalMusicOutput>()

        controller.played shouldBe listOf("hello")
        output.speech shouldBe LocalMusicSpeech.Tie("Hello", "World")
        val plan = output.getInteractionPlan(noParser)
            .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        plan.reopenMicrophone shouldBe true
        plan.nextSkills.size shouldBe 2
        val index = plan.nextSkills[0].shouldBeInstanceOf<SongChooserIndex>()
        val name = plan.nextSkills[1].shouldBeInstanceOf<SongChooserName>()
        index.specificity shouldBe Specificity.HIGH
        name.specificity shouldBe Specificity.LOW

        LocalMusicOutput(LocalMusicSpeech.Tie("Hello", "World"))
            .getInteractionPlan(noParser) shouldBe InteractionPlan.FinishInteraction

        val byName = name.generateOutput(parser, "Hello").shouldBeInstanceOf<LocalMusicOutput>()
        byName.speech shouldBe LocalMusicSpeech.Playing("Hello")
        byName.getInteractionPlan(noParser) shouldBe InteractionPlan.FinishInteraction

        val byIndex = index.generateOutput(parser, 2).shouldBeInstanceOf<LocalMusicOutput>()
        byIndex.speech shouldBe LocalMusicSpeech.Playing("World")
        byIndex.getInteractionPlan(noParser) shouldBe InteractionPlan.FinishInteraction
        controller.played shouldBe listOf("hello", "Hello", "World")

        val normal = MockSkill(Specificity.HIGH, 1.0f)
        fun ranker() = SkillRanker(listOf(normal), MockSkill(Specificity.LOW, 0.0f))

        val missed = ranker().apply { addBatchToTop(plan.nextSkills) }.getBest(parser, "xyzzy")
        missed.shouldNotBeNull()
        missed.skill shouldBeSameInstanceAs normal

        val named = ranker().apply { addBatchToTop(plan.nextSkills) }.getBest(parser, "Hello")
        named.shouldNotBeNull()
        named.skill shouldBeSameInstanceAs name

        val indexed = ranker().apply { addBatchToTop(plan.nextSkills) }.getBest(parser, "the second one")
        indexed.shouldNotBeNull()
        indexed.skill shouldBeSameInstanceAs index
    }
})

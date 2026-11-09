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

    "a title that contains a number plays that title, not the other song" {
        val love = listOf("Hello", "One Love")
        listOf("one love", "the one love song").forEach { answer ->
            SongChoice.ordinal(parser, answer).shouldBeNull()
            SongChoice.strictIndex(parser, answer, love.size).shouldBeNull()
            SongChoice.matchName(answer, love) shouldBe "One Love"
        }
        val spice = listOf("2 Become 1", "Wannabe")
        SongChoice.ordinal(parser, "2 become 1").shouldBeNull()
        SongChoice.matchName("2 become 1", spice) shouldBe "2 Become 1"

        suspend fun played(stems: List<String>, answer: String): String {
            val controller = ScriptedController { PlayByNameResult.Playing("$it.mp3") }
            val index = SongChooserIndex(MockSkillInfo, stems, controller)
            val name = SongChooserName(MockSkillInfo, stems, controller)
            val picked = SkillRanker(listOf(MockSkill(Specificity.HIGH, 1.0f)), MockSkill(Specificity.LOW, 0.0f))
                .apply { addBatchToTop(listOf(index, name)) }
                .getBest(parser, answer)
                .shouldNotBeNull()
            picked.skill shouldBeSameInstanceAs name
            val output = name.generateOutput(parser, SongChoice.matchName(answer, stems))
                .shouldBeInstanceOf<LocalMusicOutput>()
            output.getInteractionPlan(noParser) shouldBe InteractionPlan.FinishInteraction
            controller.played.single()
            return (output.speech as LocalMusicSpeech.Playing).name
        }
        played(love, "one love") shouldBe "One Love"
        played(love, "the one love song") shouldBe "One Love"
        played(spice, "2 become 1") shouldBe "2 Become 1"

        // "one" is only an index when it is not the song's name.
        SongChoice.matchName("one", listOf("Hello", "One")) shouldBe "One"
        SongChoice.matchName("one", love).shouldBeNull()
        SongChoice.ordinal(parser, "one") shouldBe 1L

        // "number one" stays the first option even beside a title that contains it.
        SongChoice.matchName("number one", love, parser).shouldBeNull()
        SongChoice.matchName("number 2", spice, parser).shouldBeNull()
        val helloFirst = SongChooserIndex(
            MockSkillInfo,
            love,
            ScriptedController { PlayByNameResult.Playing("$it.mp3") },
        )
        helloFirst.score(parser, "number one") shouldBe (AlwaysBestScore to 1)
        val wannabe = SongChooserIndex(
            MockSkillInfo,
            spice,
            ScriptedController { PlayByNameResult.Playing("$it.mp3") },
        )
        wannabe.score(parser, "number 2") shouldBe (AlwaysBestScore to 2)
    }

    "last, other, former and latter are positions, and bare ordinals still work" {
        val hello = listOf("Hello World", "Hello Adele")
        listOf("the last one", "last one", "last", "the last").forEach { answer ->
            SongChoice.ordinal(parser, answer).shouldBeNull()
            SongChoice.strictIndex(parser, answer, hello.size) shouldBe 2L
            SongChoice.matchName(answer, hello).shouldBeNull()
        }
        SongChoice.strictIndex(parser, "the last one", 3) shouldBe 3L
        listOf("the other one", "other", "the other").forEach { answer ->
            SongChoice.strictIndex(parser, answer, 2) shouldBe 2L
            SongChoice.strictIndex(parser, answer, 3).shouldBeNull()
        }
        SongChoice.strictIndex(parser, "the former", 2) shouldBe 1L
        SongChoice.strictIndex(parser, "former", 2) shouldBe 1L
        SongChoice.strictIndex(parser, "the latter", 2) shouldBe 2L
        SongChoice.strictIndex(parser, "latter", 2) shouldBe 2L
        SongChoice.strictIndex(parser, "the former", 3).shouldBeNull()
        SongChoice.strictIndex(parser, "the latter", 3).shouldBeNull()

        val positions = mapOf(
            "the first one" to 1L,
            "the second one" to 2L,
            "first" to 1L,
            "second" to 2L,
            "1" to 1L,
            "2" to 2L,
            "number one" to 1L,
            "number 2" to 2L,
            "one" to 1L,
            "two" to 2L,
            "option 2" to 2L,
            "the second" to 2L,
        )
        positions.forEach { (answer, index) ->
            SongChoice.ordinal(parser, answer) shouldBe index
            SongChoice.strictIndex(parser, answer, hello.size) shouldBe index
        }
        SongChoice.strictIndex(parser, "the third one", hello.size).shouldBeNull()
        SongChoice.strictIndex(parser, "3", hello.size).shouldBeNull()
        SongChoice.strictIndex(parser, "0", hello.size).shouldBeNull()
        SongChoice.ordinal(parser, "twenty one") shouldBe 21L

        val controller = ScriptedController { PlayByNameResult.Playing("$it.mp3") }
        val indexSkill = SongChooserIndex(MockSkillInfo, hello, controller)
        val nameSkill = SongChooserName(MockSkillInfo, hello, controller)
        val ranker = SkillRanker(
            listOf(MockSkill(Specificity.HIGH, 1.0f)),
            MockSkill(Specificity.LOW, 0.0f),
        ).apply { addBatchToTop(listOf(indexSkill, nameSkill)) }
        val picked = ranker.getBest(parser, "the last one").shouldNotBeNull()
        picked.skill shouldBeSameInstanceAs indexSkill
        val output = indexSkill.generateOutput(parser, 2).shouldBeInstanceOf<LocalMusicOutput>()
        output.speech shouldBe LocalMusicSpeech.Playing("Hello Adele")
        controller.played shouldBe listOf("Hello Adele")

        val other = SongChooserIndex(MockSkillInfo, hello, ScriptedController { PlayByNameResult.Playing("$it.mp3") })
        other.score(parser, "the other one") shouldBe (AlwaysBestScore to 2)
    }

    "short titles match the whole normalized name" {
        val stems = listOf("Up", "Us")
        SongChoice.matchName("up", stems) shouldBe "Up"
        SongChoice.matchName("US", stems) shouldBe "Us"
        SongChoice.matchName("u", stems).shouldBeNull()
        val name = SongChooserName(MockSkillInfo, stems, ScriptedController { PlayByNameResult.Playing("$it.mp3") })
        val (score, stem) = name.score(parser, "up")
        score shouldBe AlwaysBestScore
        stem shouldBe "Up"
        val output = name.generateOutput(parser, stem).shouldBeInstanceOf<LocalMusicOutput>()
        output.speech shouldBe LocalMusicSpeech.Playing("Up")
    }

    "stop and pause while choosing fall through to the default batch" {
        val stems = listOf("The Sound of Silence", "The Sound of Music")
        val index = SongChooserIndex(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
        val name = SongChooserName(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
        listOf("stop the music", "pause the music").forEach { answer ->
            SongChoice.isMediaControl(answer) shouldBe true
            SongChoice.matchName(answer, stems).shouldBeNull()
            val (nameScore, stem) = name.score(parser, answer)
            nameScore shouldBe AlwaysWorstScore
            stem.shouldBeNull()
            val (indexScore, _) = index.score(parser, answer)
            indexScore shouldBe AlwaysWorstScore
            val normal = MockSkill(Specificity.HIGH, 1.0f)
            val picked = SkillRanker(listOf(normal), MockSkill(Specificity.LOW, 0.0f))
                .apply { addBatchToTop(listOf(index, name)) }
                .getBest(parser, answer)
                .shouldNotBeNull()
            picked.skill shouldBeSameInstanceAs normal
        }
        SongChoice.matchName("play the next song", stems).shouldBeNull()
    }

    "chooser normalization folds d and diacritics on both sides" {
        val rain = listOf("Đêm mưa", "Mưa đêm")
        SongChoice.matchName("dem mua", rain) shouldBe "Đêm mưa"
        SongChoice.matchName("mua dem", rain) shouldBe "Mưa đêm"
        SongChoice.matchName("da lat", listOf("Đà Lạt", "Hello")) shouldBe "Đà Lạt"
    }

    "the one names the song One and beats the position" {
        val helloOne = listOf("Hello", "One")
        SongChoice.matchName("the one", helloOne, parser) shouldBe "One"
        SongChoice.matchName("one", helloOne, parser) shouldBe "One"
        SongChoice.strictIndex(parser, "the one", helloOne.size) shouldBe 1L
        choose(parser, helloOne, "the one") { index, name, _, picked ->
            picked.skill shouldBeSameInstanceAs name
            picked.inputData shouldBe "One"
            index.score(parser, "the one").first shouldBe AlwaysWorstScore
        }
        val played = playChoice(parser, helloOne, "the one")
        played shouldBe "One"

        // Same words, but "the two" is the title Two, not "the second".
        val helloTwo = listOf("Hello", "Two")
        SongChoice.matchName("the two", helloTwo, parser) shouldBe "Two"
        choose(parser, helloTwo, "the two") { _, name, _, picked ->
            picked.skill shouldBeSameInstanceAs name
            picked.inputData shouldBe "Two"
        }
        // Name still wins when the position would be the other song.
        val twoFirst = listOf("Two", "Hello")
        SongChoice.matchName("the two", twoFirst, parser) shouldBe "Two"
        playChoice(parser, twoFirst, "the two") shouldBe "Two"

        val twoOne = listOf("Two", "One")
        SongChoice.matchName("one", twoOne, parser) shouldBe "One"
        SongChoice.matchName("two", twoOne, parser) shouldBe "Two"
        SongChoice.matchName("the one", twoOne, parser) shouldBe "One"
        SongChoice.matchName("the first one", twoOne, parser).shouldBeNull()
        SongChoice.strictIndex(parser, "the first one", twoOne.size) shouldBe 1L
        playChoice(parser, twoOne, "the first one") shouldBe "Two"
        playChoice(parser, twoOne, "the one") shouldBe "One"

        listOf("the first one", "the second one", "number one").forEach { answer ->
            SongChoice.matchName(answer, helloOne, parser).shouldBeNull()
        }
        SongChoice.strictIndex(parser, "the first one", helloOne.size) shouldBe 1L
        SongChoice.strictIndex(parser, "the second one", helloOne.size) shouldBe 2L
        SongChoice.strictIndex(parser, "number one", helloOne.size) shouldBe 1L
        playChoice(parser, helloOne, "the first one") shouldBe "Hello"
        playChoice(parser, helloOne, "number one") shouldBe "Hello"

        val numberOne = listOf("Hello", "Number One")
        SongChoice.matchName("number one", numberOne, parser) shouldBe "Number One"
        playChoice(parser, numberOne, "number one") shouldBe "Number One"
    }

    "turn off and polite stop phrases do not start a song" {
        val tie = listOf("The Sound of Silence", "The Sound of Music")
        val nouns = listOf("music", "song", "track", "playback")
        val controls = nouns.flatMap { noun ->
            listOf(
                "turn off the $noun",
                "turn the $noun off",
                "switch off the $noun",
                "switch the $noun off",
                "please stop the $noun",
                "stop the $noun please",
            )
        } + listOf(
            "ok stop the music",
            "stop the music now",
            "now pause the music",
            "halt the track please",
            "please stop the music please",
            "turn off music",
            "switch music off",
        )
        controls.forEach { answer ->
            SongChoice.isMediaControl(answer) shouldBe true
            SongChoice.matchName(answer, tie, parser).shouldBeNull()
            SongChoice.strictIndex(parser, answer, tie.size).shouldBeNull()
            choose(parser, tie, answer) { _, _, normal, picked ->
                picked.skill shouldBeSameInstanceAs normal
            }
        }
        listOf("turn the music", "stop", "pause", "please", "play the music").forEach { answer ->
            SongChoice.isMediaControl(answer) shouldBe false
        }
    }

    "a title that is a stop phrase is still picked by position" {
        val stems = listOf("Stop The Song", "Hello")
        SongChoice.isMediaControl("stop the song") shouldBe true
        SongChoice.matchName("stop the song", stems, parser).shouldBeNull()
        SongChoice.matchName("Stop The Song", stems, parser).shouldBeNull()
        choose(parser, stems, "stop the song") { _, _, normal, picked ->
            picked.skill shouldBeSameInstanceAs normal
        }
        SongChoice.strictIndex(parser, "the first one", stems.size) shouldBe 1L
        SongChoice.matchName("the first one", stems, parser).shouldBeNull()
        playChoice(parser, stems, "the first one") shouldBe "Stop The Song"
        playChoice(parser, stems, "the second one") shouldBe "Hello"
        playChoice(parser, stems, "number two") shouldBe "Hello"
    }

    "spoken numbers match a title written in digits and beat a position" {
        val twentyTwo = listOf("Hello", "22")
        SongChoice.matchName("twenty two", twentyTwo, parser) shouldBe "22"
        SongChoice.matchName("22", twentyTwo, parser) shouldBe "22"
        SongChoice.ordinal(parser, "twenty two") shouldBe 22L
        playChoice(parser, twentyTwo, "twenty two") shouldBe "22"
        // Position 22 is out of range, so only the name skill can play it.
        choose(parser, twentyTwo, "twenty two") { index, name, _, picked ->
            picked.skill shouldBeSameInstanceAs name
            index.score(parser, "twenty two").first shouldBe AlwaysWorstScore
        }
        // In range, the digit title still beats the other song.
        playChoice(parser, listOf("2", "Hello"), "two") shouldBe "2"
        playChoice(parser, listOf("Hello", "2"), "two") shouldBe "2"

        val year = listOf("Hello", "1999")
        SongChoice.matchName("nineteen ninety nine", year, parser) shouldBe "1999"
        playChoice(parser, year, "nineteen ninety nine") shouldBe "1999"
        choose(parser, year, "nineteen ninety nine") { _, name, _, picked ->
            picked.skill shouldBeSameInstanceAs name
            picked.inputData shouldBe "1999"
        }

        val rings = listOf("Hello", "7 Rings")
        SongChoice.matchName("seven rings", rings, parser) shouldBe "7 Rings"
        SongChoice.matchName("7 rings", rings, parser) shouldBe "7 Rings"
        playChoice(parser, rings, "seven rings") shouldBe "7 Rings"
        playChoice(parser, listOf("7 Rings", "Hello"), "seven rings") shouldBe "7 Rings"

        val spice = listOf("2 Become 1", "Wannabe")
        for (answer in listOf("2 become 1", "two become one", "2 become one", "two become 1")) {
            SongChoice.matchName(answer, spice, parser) shouldBe "2 Become 1"
            playChoice(parser, spice, answer) shouldBe "2 Become 1"
        }

        val love = listOf("Hello", "One Love")
        SongChoice.matchName("three", love, parser).shouldBeNull()
        SongChoice.strictIndex(parser, "three", love.size).shouldBeNull()
        SongChoice.ordinal(parser, "three") shouldBe 3L
        choose(parser, love, "three") { index, name, normal, picked ->
            picked.skill shouldBeSameInstanceAs normal
            index.score(parser, "three").first shouldBe AlwaysWorstScore
            name.score(parser, "three").first shouldBe AlwaysWorstScore
        }
        // "one" is still the first song when it is not the whole title.
        SongChoice.matchName("one", love, parser).shouldBeNull()
        playChoice(parser, love, "one") shouldBe "Hello"
        // The word title still beats the digit reading of the same utterance.
        playChoice(parser, listOf("One", "1"), "one") shouldBe "One"
        playChoice(parser, listOf("One", "1"), "1") shouldBe "1"
    }

    "a few longer position phrases pick a song and the rest still fall through" {
        val hello = listOf("Hello World", "Hello Adele")
        val positions = mapOf(
            "play the second one" to 2L,
            "the second song" to 2L,
            "the first song" to 1L,
            "the last song" to 2L,
            "the other song" to 2L,
            "second please" to 2L,
        )
        for ((answer, index) in positions) {
            SongChoice.matchName(answer, hello, parser).shouldBeNull()
            SongChoice.strictIndex(parser, answer, hello.size) shouldBe index
            playChoice(parser, hello, answer) shouldBe hello[index.toInt() - 1]
        }
        SongChoice.ordinal(parser, "second please") shouldBe 2L
        SongChoice.ordinal(parser, "the second song") shouldBe 2L
        SongChoice.ordinal(parser, "play the second one") shouldBe 2L
        SongChoice.ordinal(parser, "the last song").shouldBeNull()
        SongChoice.ordinal(parser, "the other song").shouldBeNull()
        SongChoice.strictIndex(parser, "the other song", 3).shouldBeNull()
        SongChoice.strictIndex(parser, "the last song", 3) shouldBe 3L
        SongChoice.matchName("the other song", hello, parser).shouldBeNull()

        // A whole-name hit still beats these position shapes.
        val named = listOf("Hello", "Second Song")
        SongChoice.matchName("the second song", named, parser) shouldBe "Second Song"
        playChoice(parser, named, "the second song") shouldBe "Second Song"
        val lastSong = listOf("Hello", "The Last Song")
        SongChoice.matchName("the last song", lastSong, parser) shouldBe "The Last Song"
        playChoice(parser, lastSong, "the last song") shouldBe "The Last Song"

        listOf("another one", "uh the first one", "no the first").forEach { answer ->
            SongChoice.strictIndex(parser, answer, hello.size).shouldBeNull()
            SongChoice.ordinal(parser, answer).shouldBeNull()
            SongChoice.matchName(answer, hello, parser).shouldBeNull()
            choose(parser, hello, answer) { _, _, normal, picked ->
                picked.skill shouldBeSameInstanceAs normal
            }
        }
    }
})

private fun normalSkill() = MockSkill(Specificity.HIGH, 1.0f)

private fun choose(
    parser: SkillContext,
    stems: List<String>,
    answer: String,
    assert: (
        SongChooserIndex,
        SongChooserName,
        MockSkill,
        org.stypox.dicio.eval.SkillWithResult<*>,
    ) -> Unit,
) {
    val index = SongChooserIndex(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
    val name = SongChooserName(MockSkillInfo, stems, ScriptedController { PlayByNameResult.NoMatch(it) })
    val normal = normalSkill()
    val picked = SkillRanker(listOf(normal), MockSkill(Specificity.LOW, 0.0f))
        .apply { addBatchToTop(listOf(index, name)) }
        .getBest(parser, answer)
        .shouldNotBeNull()
    assert(index, name, normal, picked)
}

private suspend fun playChoice(parser: SkillContext, stems: List<String>, answer: String): String {
    val controller = ScriptedController { PlayByNameResult.Playing("$it.mp3") }
    val index = SongChooserIndex(MockSkillInfo, stems, controller)
    val name = SongChooserName(MockSkillInfo, stems, controller)
    val picked = SkillRanker(listOf(normalSkill()), MockSkill(Specificity.LOW, 0.0f))
        .apply { addBatchToTop(listOf(index, name)) }
        .getBest(parser, answer)
        .shouldNotBeNull()
    val output = picked.generateOutput(parser).shouldBeInstanceOf<LocalMusicOutput>()
    output.getInteractionPlan(ParserSkillContext(null)) shouldBe InteractionPlan.FinishInteraction
    return (output.speech as LocalMusicSpeech.Playing).name
}

package org.stypox.dicio.io.input.corrections

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.stypox.dicio.settings.datastore.WordLists

class WordListsRepositoryTest : StringSpec({
    "command words are the fixed recognizer list" {
        WordListsRepository.RECOGNIZER_COMMAND_WORDS shouldBe listOf(
            "play",
            "pause",
            "stop",
            "next",
            "previous",
            "music",
            "song",
            "call",
            "timer",
            "weather",
            "open",
            "search",
            "what",
            "time",
            "lyrics",
            "translate",
            "flashlight",
            "repeat",
            "shuffle",
        )
        WordListsRepository.MAX_RECOGNIZER_HINTS shouldBe 400
    }

    "hints come from commands, then known words, then correction targets, then titles" {
        val hints = WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = listOf("Play", "Hà Nội", "  ", "Snow"),
            correctionTargets = listOf("glow puppet", "Hà Nội", "Khiem"),
            musicTitles = listOf("ha noi", "Yesterday", "Snow", "", "Hello.mp3"),
        )
        hints shouldBe WordListsRepository.RECOGNIZER_COMMAND_WORDS + listOf(
            "Hà Nội",
            "Snow",
            "glow puppet",
            "Khiem",
            "Yesterday",
            "Hello.mp3",
        )
    }

    "music titles keep the caller's order" {
        val hints = WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = emptyList(),
            correctionTargets = emptyList(),
            musicTitles = listOf("b song", "a song"),
        )
        hints shouldBe WordListsRepository.RECOGNIZER_COMMAND_WORDS + listOf("b song", "a song")
    }

    "an off switch returns an empty hint list" {
        WordListsRepository.recognizerHints(
            featureEnabled = false,
            knownWords = listOf("Snow"),
            correctionTargets = listOf("Khiem"),
            musicTitles = listOf("Yesterday"),
        ).shouldBeEmpty()
    }

    "the cap stops the list and a non-positive cap is empty" {
        WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = listOf("Snow"),
            correctionTargets = emptyList(),
            musicTitles = listOf("Yesterday"),
            cap = 3,
        ) shouldBe listOf("play", "pause", "stop")

        val titles = List(500) { "title${it.toString().padStart(3, '0')}" }
        val capped = WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = emptyList(),
            correctionTargets = emptyList(),
            musicTitles = titles,
        )
        capped.size shouldBe WordListsRepository.MAX_RECOGNIZER_HINTS
        val commands = WordListsRepository.RECOGNIZER_COMMAND_WORDS.size
        capped.last() shouldBe titles[WordListsRepository.MAX_RECOGNIZER_HINTS - commands - 1]

        WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = listOf("Snow"),
            correctionTargets = emptyList(),
            musicTitles = listOf("Yesterday"),
            cap = 0,
        ).shouldBeEmpty()
    }

    "stored strictness of 0 means 80 and the slider snaps to steps of 5" {
        WordListsRepository.resolvedStrictness(0) shouldBe 80
        WordListsRepository.resolvedStrictness(80) shouldBe 80
        WordListsRepository.resolvedStrictness(81) shouldBe 80
        WordListsRepository.resolvedStrictness(83) shouldBe 85
        WordListsRepository.resolvedStrictness(59) shouldBe 80
        WordListsRepository.resolvedStrictness(101) shouldBe 80
        WordListsRepository.resolvedStrictness(60) shouldBe 60
        WordListsRepository.resolvedStrictness(100) shouldBe 100
        WordListsRepository.resolvedStrictness(62) shouldBe 60
        WordListsRepository.resolvedStrictness(63) shouldBe 65
    }

    "a missing word list file is enabled, empty, and uses the default strictness" {
        val stored = WordLists.getDefaultInstance()
        stored.disabled shouldBe false
        stored.strictnessPercent shouldBe 0
        stored.knownWordsCount shouldBe 0
        stored.correctionsCount shouldBe 0
        WordListsRepository.resolvedStrictness(stored.strictnessPercent) shouldBe 80
    }
})
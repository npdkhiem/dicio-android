package org.stypox.dicio.io.input.corrections

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis

class TranscriptCorrectorTest : StringSpec({
    fun correct(
        text: String,
        words: List<String> = emptyList(),
        corrections: List<Pair<String, String>> = emptyList(),
        strictness: Int = 80,
    ): String? = TranscriptCorrector.correct(text, words, corrections, strictness)

    "unchanged text and empty input return null" {
        correct("").shouldBeNull()
        correct("play hello").shouldBeNull()
        correct("play hello", words = listOf("hello")).shouldBeNull()
    }

    "corrections replace whole words and keep the user's spelling" {
        correct(
            "play glow puppet",
            corrections = listOf("glow puppet" to "Glove Puppet"),
        ) shouldBe "play Glove Puppet"
        correct(
            "play GLOW PUPPET",
            corrections = listOf("glow puppet" to "Glove Puppet"),
        ) shouldBe "play Glove Puppet"
    }

    "corrections ignore case and accents, including đ" {
        correct(
            "play Ha NỘI",
            corrections = listOf("ha noi" to "Hà Nội"),
        ) shouldBe "play Hà Nội"
        correct(
            "play dang",
            corrections = listOf("dang" to "Đặng"),
        ) shouldBe "play Đặng"
        correct(
            "play ĐẶNG",
            corrections = listOf("Đặng" to "Dang"),
        ) shouldBe "play Dang"
    }

    "longer correction phrases win over shorter ones" {
        correct(
            "visit new york city",
            corrections = listOf(
                "new york" to "NY",
                "new york city" to "NYC",
            ),
        ) shouldBe "visit NYC"
    }

    "a correction does not match inside a longer word" {
        correct(
            "glow",
            corrections = listOf("low" to "LOW"),
        ).shouldBeNull()
    }

    "punctuation and spacing outside the match are kept" {
        correct(
            "play  (glow puppet)!",
            corrections = listOf("glow puppet" to "Glove Puppet"),
        ) shouldBe "play  (Glove Puppet)!"
    }

    "glove pupet versus glove puppet is one edit of twelve and passes at 80" {
        TranscriptCorrector.levenshteinDistance("glove pupet", "glove puppet") shouldBe 1
        correct("play Glove Pupet", words = listOf("Glove Puppet"), strictness = 80) shouldBe
            "play Glove Puppet"
        // 100 * 11 / 12 is 91, so 92 does not pass.
        correct("play Glove Pupet", words = listOf("Glove Puppet"), strictness = 92).shouldBeNull()
    }

    "hello versus yellow is two edits of six and fails at 80" {
        TranscriptCorrector.levenshteinDistance("hello", "yellow") shouldBe 2
        correct("Hello", words = listOf("Yellow"), strictness = 80).shouldBeNull()
        // 100 * 4 / 6 is 66, so 66 passes and 67 does not.
        correct("Hello", words = listOf("Yellow"), strictness = 66) shouldBe "Yellow"
        correct("Hello", words = listOf("Yellow"), strictness = 67).shouldBeNull()
    }

    "hello versus hallo is exactly 80 percent" {
        TranscriptCorrector.levenshteinDistance("hallo", "hello") shouldBe 1
        correct("hallo", words = listOf("hello"), strictness = 79) shouldBe "hello"
        correct("hallo", words = listOf("hello"), strictness = 80) shouldBe "hello"
        correct("hallo", words = listOf("hello"), strictness = 81).shouldBeNull()
    }

    "words under four letters match only exactly" {
        correct("us", words = listOf("up"), strictness = 0).shouldBeNull()
        correct("up", words = listOf("Up"), strictness = 100).shouldBeNull()
        correct("up", words = listOf("Up"), strictness = 80) shouldBe "Up"
        correct("cats", words = listOf("cat"), strictness = 60).shouldBeNull()
        correct("cat", words = listOf("cats"), strictness = 60).shouldBeNull()
        correct("catz", words = listOf("cats"), strictness = 75) shouldBe "cats"
        correct("catz", words = listOf("cats"), strictness = 80).shouldBeNull()
    }

    "strictness 100 applies corrections only" {
        correct(
            "hello helo",
            words = listOf("Hello"),
            corrections = listOf("helo" to "HelloThere"),
            strictness = 100,
        ) shouldBe "hello HelloThere"
        correct("hello", words = listOf("Hello"), strictness = 100).shouldBeNull()
    }

    "a longer window beats a shorter exact match" {
        correct(
            "alpha beta",
            words = listOf("alpha", "alpha bete"),
            strictness = 80,
        ) shouldBe "alpha bete"
    }

    "overlapping matches keep the earlier window" {
        correct(
            "alpha beta gamma",
            words = listOf("Beta Gamma", "Alpha Beta"),
        ) shouldBe "Alpha Beta gamma"
    }

    "an equal closeness tie uses the earlier known word" {
        correct("hallo", words = listOf("hello", "hullo"), strictness = 80) shouldBe "hello"
        correct("hallo", words = listOf("hullo", "hello"), strictness = 80) shouldBe "hullo"
    }

    "the first duplicate spelling is the one inserted" {
        correct("hello", words = listOf("Hello", "HELLO")) shouldBe "Hello"
    }

    "known words keep accents in the inserted text" {
        correct("play ha noi", words = listOf("Hà Nội")) shouldBe "play Hà Nội"
    }

    "corrections are applied before known words and do not overlap them" {
        correct(
            "pleese blue bird",
            words = listOf("please", "blue bird"),
            corrections = listOf("bird" to "Birdy"),
        ) shouldBe "please blue Birdy"
    }

    "several replacements can happen in one sentence" {
        correct(
            "say hello, then world!",
            words = listOf("Hello", "World"),
        ) shouldBe "say Hello, then World!"
    }

    "the same input is corrected the same way twice" {
        val words = listOf("Glove Puppet", "Hello")
        val once = correct("hallo glove pupet", words = words)
        correct("hallo glove pupet", words = words) shouldBe once
        once shouldBe "Hello Glove Puppet"
    }

    "only the first 2000 known words are used" {
        val words = ArrayList<String>(2001)
        words.add("Hello")
        repeat(1999) { words.add("qw${it.toString().padStart(6, '0')}zz") }
        words.add("Uniquezz")
        words.size shouldBe 2001
        correct("hello uniquezz", words = words) shouldBe "Hello uniquezz"
    }

    "only the first 80 input words are corrected" {
        val fillers = List(79) { "plain" }
        val within = (fillers + "uniquezz").joinToString(" ")
        val past = (fillers + "plain" + "uniquezz").joinToString(" ")
        correct(within, words = listOf("Uniquezz")) shouldBe
            (fillers + "Uniquezz").joinToString(" ")
        correct(past, words = listOf("Uniquezz")).shouldBeNull()
    }

    "two thousand known words and a thirty word sentence stay under 500 ms" {
        val words = ArrayList<String>(2000)
        words.add("Glove Puppet")
        repeat(1999) { index ->
            words.add("q" + index.toString().padStart(10, '0'))
        }
        val sentence = MutableList(30) { "alpha" }
        sentence[10] = "glove"
        sentence[11] = "pupet"
        val text = sentence.joinToString(" ")
        correct(text, words = words) // warm up
        val elapsed = measureTimeMillis {
            val corrected = correct(text, words = words)
            corrected shouldBe text.replace("glove pupet", "Glove Puppet")
        }
        elapsed shouldBeLessThan 500
    }
})

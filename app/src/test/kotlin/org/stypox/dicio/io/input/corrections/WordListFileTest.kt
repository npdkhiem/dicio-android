package org.stypox.dicio.io.input.corrections

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class WordListFileTest : StringSpec({
    "parses sections, comments, blanks, arrow, csv, tab, and a bom" {
        val text = "\uFEFF# Dicio word list\r\n" +
            "\n" +
            "[Words]\n" +
            "Hà Nội mùa thu\n" +
            "Glove Puppet\n" +
            "glove puppet\n" +
            "\n" +
            "# a note\n" +
            "Yesterday\n" +
            "x".repeat(201) + "\n" +
            "!!!\n" +
            "[CORRECTIONS]\n" +
            "glow puppet  =>   Glove Puppet\n" +
            "ha noi mua thu => Hà Nội mùa thu\n" +
            "\"key em\",\"Khiem\"\n" +
            "dang\tĐặng\n" +
            "play the snow => play Snow\n" +
            "\"say \"\"hi\"\"\",Hello\n" +
            "glow puppet => Duplicate\n" +
            "not a pair\n" +
            "\"unclosed,quote\n" +
            "a,b,c\n" +
            "y".repeat(201) + "\n" +
            "[unknown]\n" +
            "Orphan word\n"

        val parsed = WordListFile.parse(text)
        parsed.words shouldBe listOf("Hà Nội mùa thu", "Glove Puppet", "Yesterday")
        parsed.corrections shouldBe listOf(
            WordListFile.Correction("glow puppet", "Glove Puppet"),
            WordListFile.Correction("ha noi mua thu", "Hà Nội mùa thu"),
            WordListFile.Correction("key em", "Khiem"),
            WordListFile.Correction("dang", "Đặng"),
            WordListFile.Correction("play the snow", "play Snow"),
            WordListFile.Correction("say \"hi\"", "Hello"),
        )
        parsed.skippedLineNumbers shouldBe listOf(10, 11, 20, 21, 22, 23, 24, 25)
        parsed.wordCount shouldBe 3
        parsed.correctionCount shouldBe 6
        parsed.skippedCount shouldBe 8
    }

    "a line before any section is skipped" {
        val parsed = WordListFile.parse("Hello\n[words]\nSnow\n")
        parsed.words shouldBe listOf("Snow")
        parsed.skippedLineNumbers shouldBe listOf(1)
    }

    "an arrow wins over commas in a correction" {
        val parsed = WordListFile.parse("[corrections]\nsay hi, there => Hello\n")
        parsed.corrections shouldBe listOf(
            WordListFile.Correction("say hi, there", "Hello"),
        )
        parsed.skippedLineNumbers.shouldBeEmpty()
    }

    "accent-insensitive duplicates keep the first spelling" {
        val parsed = WordListFile.parse(
            "[words]\nHello\nHéllo\n[corrections]\nDang => First\nđặng => Second\n",
        )
        parsed.words shouldBe listOf("Hello")
        parsed.corrections shouldBe listOf(WordListFile.Correction("Dang", "First"))
        parsed.skippedLineNumbers.shouldBeEmpty()
    }

    "limits are 2000 words, 2000 corrections, and 200 characters" {
        val words = buildString {
            appendLine("[words]")
            repeat(2001) { appendLine("word${it.toString().padStart(4, '0')}") }
        }
        val parsedWords = WordListFile.parse(words)
        parsedWords.words.size shouldBe 2000
        parsedWords.words.last() shouldBe "word1999"
        parsedWords.skippedLineNumbers shouldBe listOf(2002)

        val corrections = buildString {
            appendLine("[corrections]")
            repeat(2001) { appendLine("heard${it.toString().padStart(4, '0')} => ok") }
        }
        val parsedCorrections = WordListFile.parse(corrections)
        parsedCorrections.corrections.size shouldBe 2000
        parsedCorrections.skippedLineNumbers shouldBe listOf(2002)

        val exact = "z".repeat(200)
        val accepted = WordListFile.parse("[words]\n$exact\n")
        accepted.words shouldBe listOf(exact)
        accepted.skippedLineNumbers.shouldBeEmpty()
    }

    "export writes the header and the arrow form, and round-trips" {
        val words = listOf("Hà Nội mùa thu", "Glove Puppet")
        val corrections = listOf(
            WordListFile.Correction("glow puppet", "Glove Puppet"),
            WordListFile.Correction("key em", "Khiem"),
        )
        val text = WordListFile.export(words, corrections)
        text shouldStartWith "# Dicio word list. Lines starting with # are notes and are ignored.\n"
        text shouldContain "# [words]: one known word or title per line.\n"
        text shouldContain "# [corrections]: what Dicio hears  =>  what it should be\n"
        text shouldContain "glow puppet => Glove Puppet\n"
        text shouldContain "key em => Khiem\n"
        val parsed = WordListFile.parse(text)
        parsed.words shouldBe words
        parsed.corrections shouldBe corrections
        parsed.skippedLineNumbers.shouldBeEmpty()
    }

    "merge keeps the existing spelling and caps the lists" {
        val base = WordListFile.Lists(
            words = listOf("Hello"),
            corrections = listOf(WordListFile.Correction("glow puppet", "Glove Puppet")),
        )
        val extra = WordListFile.Lists(
            words = listOf("Héllo", "Snow"),
            corrections = listOf(
                WordListFile.Correction("glow puppet", "Other"),
                WordListFile.Correction("key em", "Khiem"),
            ),
        )
        WordListFile.merge(base, extra) shouldBe WordListFile.Lists(
            words = listOf("Hello", "Snow"),
            corrections = listOf(
                WordListFile.Correction("glow puppet", "Glove Puppet"),
                WordListFile.Correction("key em", "Khiem"),
            ),
        )

        val full = WordListFile.Lists(
            words = List(WordListFile.MAX_WORDS) { "word$it" },
            corrections = emptyList(),
        )
        val more = WordListFile.Lists(words = listOf("extra"), corrections = emptyList())
        WordListFile.merge(full, more).words.size shouldBe WordListFile.MAX_WORDS
        WordListFile.merge(full, more).words.last() shouldBe "word${WordListFile.MAX_WORDS - 1}"
    }
})

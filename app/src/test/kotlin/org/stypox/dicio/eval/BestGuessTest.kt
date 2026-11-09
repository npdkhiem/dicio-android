package org.stypox.dicio.eval

import android.content.Context
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.MockSkill
import org.stypox.dicio.MockSkillContext
import org.stypox.dicio.MockSkillInfo
import org.stypox.dicio.di.SkillContextInternal
import java.util.Locale

class BestGuessTest : StringSpec({
    fun skill(scores: Map<String, Float>, specificity: Specificity = Specificity.HIGH): Skill<*> {
        return object : Skill<Nothing?>(MockSkillInfo, specificity) {
            override fun score(ctx: SkillContext, input: String): Pair<Score, Nothing?> {
                return Pair(FloatScore(scores[input] ?: 0f), null)
            }

            override suspend fun generateOutput(ctx: SkillContext, inputData: Nothing?): SkillOutput {
                error("not used")
            }
        }
    }

    fun ranker(
        defaultScore: Map<String, Float>,
        conversationScore: Map<String, Float>? = null,
    ): SkillRanker {
        val ranker = SkillRanker(
            listOf(skill(defaultScore)),
            MockSkill(Specificity.LOW, 0f),
        )
        if (conversationScore != null) {
            ranker.addBatchToTop(listOf(skill(conversationScore)))
        }
        return ranker
    }

    "a higher score wins" {
        val candidates = listOf("low", "high")
        val best = selectBestGuess(candidates) {
            ranker(mapOf("low" to 0.90f, "high" to 0.99f)).peekBest(MockSkillContext, it)
        }
        best!!.input shouldBe "high"
        best.scoreIn01Range shouldBe 0.99f
        best.index shouldBe 1
    }

    "a score tie goes to the earlier guess" {
        val best = selectBestGuess(listOf("first", "second")) {
            ranker(mapOf("first" to 0.95f, "second" to 0.95f)).peekBest(MockSkillContext, it)
        }
        best!!.input shouldBe "first"
        best.index shouldBe 0
    }

    "the corrected version is preferred when scores tie" {
        val candidates = buildCandidates(
            utterances = listOf("glow puppet"),
            correctionsEnabled = true,
        ) { utterance ->
            if (utterance == "glow puppet") "Glove Puppet" else null
        }
        candidates shouldBe listOf("Glove Puppet", "glow puppet")
        val best = selectBestGuess(candidates) {
            ranker(mapOf("Glove Puppet" to 0.95f, "glow puppet" to 0.95f))
                .peekBest(MockSkillContext, it)
        }
        best!!.input shouldBe "Glove Puppet"
    }

    "the original wins when the corrected text matches nothing" {
        val candidates = buildCandidates(
            utterances = listOf("glow puppet"),
            correctionsEnabled = true,
        ) { "Glove Puppet" }
        val best = selectBestGuess(candidates) {
            ranker(mapOf("glow puppet" to 0.95f)).peekBest(MockSkillContext, it)
        }
        best!!.input shouldBe "glow puppet"
        best.scoreIn01Range shouldBe 0.95f
    }

    "a conversation batch beats the default batch even with a lower score" {
        val ranker = ranker(
            defaultScore = mapOf("corrected" to 0.99f),
            conversationScore = mapOf("original" to 0.75f),
        )
        val candidates = listOf("corrected", "original")
        val best = selectBestGuess(candidates) { ranker.peekBest(MockSkillContext, it) }
        best!!.input shouldBe "original"
        best.batchLevel shouldBe 0
        best.scoreIn01Range shouldBe 0.75f
        ranker.hasAnyBatches() shouldBe true
        ranker.commitMatch(best.batchLevel)
        ranker.hasAnyBatches() shouldBe true
        ranker.peekBest(MockSkillContext, "original")!!.batchLevel shouldBe 0
    }

    "peeking does not remove batches and the winner is committed once" {
        val ranker = ranker(
            defaultScore = mapOf("only default" to 0.99f),
            conversationScore = mapOf("only default" to 0f),
        )
        val best = selectBestGuess(listOf("only default")) { ranker.peekBest(MockSkillContext, it) }
        best!!.batchLevel shouldBe SkillRanker.DEFAULT_BATCH_LEVEL
        ranker.hasAnyBatches() shouldBe true
        ranker.commitMatch(best.batchLevel)
        ranker.hasAnyBatches() shouldBe false
    }

    "standard match helper is set for each candidate and cleared afterwards" {
        val ctx = object : SkillContextInternal {
            override val android: Context get() = error("android")
            override val locale: Locale get() = error("locale")
            override val sentencesLanguage: String get() = error("language")
            override val parserFormatter: ParserFormatter? get() = error("parser")
            override val speechOutputDevice: SpeechOutputDevice get() = error("speech")
            override var previousOutput: SkillOutput? = null
            override var standardMatchHelper: MatchHelper? = null
        }
        val seen = ArrayList<String?>()
        val scoring = object : Skill<Nothing?>(MockSkillInfo, Specificity.HIGH) {
            override fun score(ctx: SkillContext, input: String): Pair<Score, Nothing?> {
                seen.add(ctx.standardMatchHelper?.userInput)
                return Pair(FloatScore(0.95f), null)
            }

            override suspend fun generateOutput(ctx: SkillContext, inputData: Nothing?): SkillOutput {
                error("not used")
            }
        }
        val ranker = SkillRanker(listOf(scoring), MockSkill(Specificity.LOW, 0f))
        val best = try {
            selectBestGuess(listOf("one", "two")) { input ->
                ctx.standardMatchHelper = MatchHelper(null, input)
                ranker.peekBest(ctx, input)
            }
        } finally {
            ctx.standardMatchHelper = null
        }
        best!!.input shouldBe "one"
        seen shouldBe listOf("one", "two")
        ctx.standardMatchHelper.shouldBeNull()
    }

    "candidates put a different correction first and drop duplicates" {
        buildCandidates(listOf("a", "a", "b"), correctionsEnabled = false) { "X" } shouldBe
            listOf("a", "b")
        buildCandidates(listOf("a", "c"), correctionsEnabled = true) { utterance ->
            utterance.uppercase()
        } shouldBe listOf("A", "a", "C", "c")
        buildCandidates(listOf("a", "b"), correctionsEnabled = true) { utterance ->
            if (utterance == "a") "b" else null
        } shouldBe listOf("b", "a")
        buildCandidates(listOf("a"), correctionsEnabled = true) { "" } shouldBe listOf("a")
        buildCandidates(listOf("a"), correctionsEnabled = true) { "a" } shouldBe listOf("a")
        buildCandidates(listOf("a"), correctionsEnabled = true) { null } shouldBe listOf("a")
    }
})

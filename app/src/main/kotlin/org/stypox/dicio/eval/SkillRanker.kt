package org.stypox.dicio.eval

import org.dicio.skill.skill.Skill
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Specificity
import org.dicio.skill.util.CleanableUp
import java.util.Stack

class SkillRanker(
    defaultSkillBatch: List<Skill<*>>,
    private var fallbackSkill: Skill<*>
) : CleanableUp {

    private class SkillBatch(skills: List<Skill<*>>) {
        // all of the skills by specificity category (high, medium and low)
        private val highSkills: MutableList<Skill<*>> = ArrayList()
        private val mediumSkills: MutableList<Skill<*>> = ArrayList()
        private val lowSkills: MutableList<Skill<*>> = ArrayList()

        init {
            for (skill in skills) {
                when (skill.specificity) {
                    Specificity.HIGH -> highSkills.add(skill)
                    Specificity.MEDIUM -> mediumSkills.add(skill)
                    Specificity.LOW -> lowSkills.add(skill)
                }
            }
        }

        fun getBest(ctx: SkillContext, input: String): SkillWithResult<*>? {
            // first round: considering only high-priority skills
            val bestHigh = getBestForSpecificity(ctx, highSkills, input)
            if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_1) {
                return bestHigh
            }

            // second round: considering both medium- and high-priority skills
            val bestMedium = getBestForSpecificity(ctx, mediumSkills, input)
            if (bestMedium != null && bestMedium.score.scoreIn01Range() > MEDIUM_THRESHOLD_2) {
                return bestMedium
            } else if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_2) {
                return bestHigh
            }

            // third round: all skills are considered
            val bestLow = getBestForSpecificity(ctx, lowSkills, input)
            if (bestLow != null && bestLow.score.scoreIn01Range() > LOW_THRESHOLD_3) {
                return bestLow
            } else if (bestMedium != null && bestMedium.score.scoreIn01Range() > MEDIUM_THRESHOLD_3) {
                return bestMedium
            } else if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_3) {
                return bestHigh
            }

            // nothing was matched
            return null
        }

        companion object {
            private fun getBestForSpecificity(
                ctx: SkillContext,
                skills: List<Skill<*>>,
                input: String,
            ): SkillWithResult<*>? {
                // this ensures that if `skills` is empty and null skill is returned,
                // nothing bad happens since its score cannot be higher than any other float value.
                var bestSkillSoFar: SkillWithResult<*>? = null
                for (skill in skills) {
                    val res = skill.scoreAndWrapResult(ctx, input)
                    if (bestSkillSoFar == null || res.score.isBetterThan(bestSkillSoFar.score)) {
                        bestSkillSoFar = res
                    }
                }
                return bestSkillSoFar
            }
        }
    }

    private var defaultBatch: SkillBatch = SkillBatch(defaultSkillBatch)
    private val batches: Stack<SkillBatch> = Stack()

    fun addBatchToTop(skillBatch: List<Skill<*>>) {
        batches.push(SkillBatch(skillBatch))
    }

    fun hasAnyBatches(): Boolean {
        return batches.isNotEmpty()
    }

    fun removeTopBatch() {
        if (!batches.isEmpty()) {
            batches.pop()
        }
    }

    fun removeAllBatches() {
        batches.removeAllElements()
    }

    /**
     * Best skill for [input] without changing the batch stack.
     * [Peek.batchLevel] is the stack index, or [DEFAULT_BATCH_LEVEL] when only
     * the default batch matched. A higher level is a newer conversation turn.
     */
    fun peekBest(ctx: SkillContext, input: String): Peek? {
        for (i in batches.indices.reversed()) {
            val skillFromBatch = batches[i].getBest(ctx, input)
            if (skillFromBatch != null) {
                return Peek(skillFromBatch, i)
            }
        }
        val skillFromBatch = defaultBatch.getBest(ctx, input) ?: return null
        return Peek(skillFromBatch, DEFAULT_BATCH_LEVEL)
    }

    /**
     * Removes batches the way [getBest] does after a match at [batchLevel].
     * Score every candidate with [peekBest] first, then commit only the winner.
     */
    fun commitMatch(batchLevel: Int) {
        if (batchLevel < 0) {
            removeAllBatches()
            return
        }
        while (batches.size > batchLevel + 1) {
            removeTopBatch()
        }
    }

    fun getBest(
        ctx: SkillContext,
        input: String,
    ): SkillWithResult<*>? {
        val peek = peekBest(ctx, input) ?: return null
        commitMatch(peek.batchLevel)
        return peek.skill
    }

    fun getFallbackSkill(
        ctx: SkillContext,
        input: String,
    ): SkillWithResult<*> {
        return fallbackSkill.scoreAndWrapResult(ctx, input)
    }

    override fun cleanup() {
        batches.clear()
    }

    /**
     * One scored match from [SkillRanker.peekBest]. [batchLevel] is comparable
     * across utterances: higher means "keep the current conversation".
     */
    data class Peek(
        val skill: SkillWithResult<*>,
        val batchLevel: Int,
    )

    companion object {
        /** [Peek.batchLevel] when the match came from the default skill batch. */
        const val DEFAULT_BATCH_LEVEL = -1

        // various thresholds for different specificity categories (high, medium and low)
        // first round
        private const val HIGH_THRESHOLD_1 = 0.85f

        // second round
        private const val MEDIUM_THRESHOLD_2 = 0.90f
        private const val HIGH_THRESHOLD_2 = 0.80f

        // third round
        private const val LOW_THRESHOLD_3 = 0.90f
        private const val MEDIUM_THRESHOLD_3 = 0.80f
        private const val HIGH_THRESHOLD_3 = 0.70f
    }
}

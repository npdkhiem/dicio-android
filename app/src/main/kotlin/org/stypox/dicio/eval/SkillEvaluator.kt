package org.stypox.dicio.eval

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.UserFacingInputException
import org.stypox.dicio.io.input.corrections.WordListsRepository
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import javax.inject.Singleton
import org.dicio.skill.standard.util.MatchHelper

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val wordLists: WordListsRepository,
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val skillRanker: SkillRanker
        get() = skillHandler.skillRanker.value

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state

    // must be kept up to date even when the activity is recreated, for this reason it is `var`
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    override fun processInputEvent(event: InputEvent) {
        scope.launch {
            suspendProcessInputEvent(event)
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.Error -> {
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterances[0].first,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
                evaluateMatchingSkill(event.utterances.map { it.first })
            }
            InputEvent.None -> {
                _state.value = _state.value.copy(pendingQuestion = null)
            }
            is InputEvent.Partial -> {
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        // the next input can be a continuation of the last interaction only if the
                        // last skill invocation provided some skill batches (which are the only way
                        // to continue an interaction/conversation)
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
    }

    private suspend fun evaluateMatchingSkill(utterances: List<String>) {
        val lists = wordLists.current()
        val candidates = buildCandidates(
            utterances = utterances,
            correctionsEnabled = wordLists.correctionsActive(lists),
        ) { utterance ->
            wordLists.correctUtterance(utterance, lists)
        }
        val (chosenInput, chosenSkill) = try {
            val best = selectBestGuess(candidates) { input ->
                skillContext.standardMatchHelper =
                    MatchHelper(skillContext.parserFormatter, input)
                skillRanker.peekBest(skillContext, input)
            }
            if (best != null) {
                skillRanker.commitMatch(best.batchLevel)
                Pair(best.input, best.skill)
            } else {
                val fallbackInput = utterances[0]
                skillContext.standardMatchHelper =
                    MatchHelper(skillContext.parserFormatter, fallbackInput)
                Pair(fallbackInput, skillRanker.getFallbackSkill(skillContext, fallbackInput))
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            return
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses (which may be
            // significant since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        }
        val skillInfo = chosenSkill.skill.correspondingSkillInfo

        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = chosenInput,
                // the skill ranker would have discarded all batches, if the chosen skill was not
                // the continuation of the last interaction (since continuing an
                // interaction/conversation is done through the stack of batches)
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = skillInfo,
            )
        )

        try {
            val permissions = skillInfo.neededPermissions
            if (permissions.isNotEmpty() && !permissionRequester(permissions)) {
                // permissions were not granted, show message
                addInteractionFromPending(MissingPermissionsSkillOutput(skillInfo))
                return
            }

            skillContext.previousOutput =
                _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
            val output = chosenSkill.generateOutput(skillContext)

            val interactionPlan = output.getInteractionPlan(skillContext)
            addInteractionFromPending(output)
            output.getSpeechOutput(skillContext).let {
                if (it.isNotBlank()) {
                    withContext (Dispatchers.Main) {
                        skillContext.speechOutputDevice.speak(it)
                    }
                }
            }

            when (interactionPlan) {
                InteractionPlan.FinishInteraction -> {
                    // current conversation has ended, reset to the default batch of skills
                    skillRanker.removeAllBatches()
                }
                is InteractionPlan.FinishSubInteraction -> {
                    skillRanker.removeTopBatch()
                }
                is InteractionPlan.Continue -> {
                    // nothing to do, just continue with current batches
                }
                is InteractionPlan.StartSubInteraction -> {
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
                is InteractionPlan.ReplaceSubInteraction -> {
                    skillRanker.removeTopBatch()
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
            }

            if (interactionPlan.reopenMicrophone) {
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    sttInputDevice.tryLoad(this::processInputEvent)
                }
            }

        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            return
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        Log.e(TAG, "Error while evaluating skills", throwable)
        addInteractionFromPending(ErrorSkillOutput(throwable, true))
        // Other failures stay silent, as before. A sentence we wrote for the
        // user (no internet, no microphone) is spoken as well as shown.
        val speech = (throwable as? UserFacingInputException)?.message?.trim().orEmpty()
        if (speech.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.Main) {
                skillContext.speechOutputDevice.speak(speech)
            }
        }
    }

    private fun addInteractionFromPending(skillOutput: SkillOutput) {
        val log = _state.value
        val pendingUserInput = log.pendingQuestion?.userInput
        val pendingContinuesLastInteraction = log.pendingQuestion?.continuesLastInteraction
            ?: skillRanker.hasAnyBatches()
        val pendingSkill = log.pendingQuestion?.skillBeingEvaluated
        val questionAnswer = QuestionAnswer(pendingUserInput, skillOutput)

        _state.value = log.copy(
            interactions = log.interactions.toMutableList().also { inters ->
                if (pendingContinuesLastInteraction && inters.isNotEmpty()) {
                    inters[inters.size - 1] = inters[inters.size - 1].let { i -> i.copy(
                        questionsAnswers = i.questionsAnswers.toMutableList()
                            .apply { add(questionAnswer) }
                    ) }
                } else {
                    inters.add(
                        Interaction(
                            skill = pendingSkill,
                            questionsAnswers = listOf(questionAnswer)
                        )
                    )
                }
            },
            pendingQuestion = null,
        )
    }

    companion object {
        val TAG = SkillEvaluator::class.simpleName
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SkillEvaluatorModule {
    @Provides
    @Singleton
    fun provideSkillEvaluator(
        skillContext: SkillContextInternal,
        skillHandler: SkillHandler,
        sttInputDevice: SttInputDeviceWrapper,
        wordLists: WordListsRepository,
    ): SkillEvaluator {
        return SkillEvaluatorImpl(skillContext, skillHandler, sttInputDevice, wordLists)
    }
}

/**
 * Corrected text first (when it differs), then the original utterance.
 * Exact duplicate strings are dropped, keeping the earlier one.
 */
internal fun buildCandidates(
    utterances: List<String>,
    correctionsEnabled: Boolean,
    correct: (String) -> String?,
): List<String> {
    val result = ArrayList<String>(utterances.size)
    val seen = HashSet<String>()
    for (utterance in utterances) {
        if (correctionsEnabled) {
            val corrected = correct(utterance)
            if (!corrected.isNullOrEmpty() && corrected != utterance && seen.add(corrected)) {
                result.add(corrected)
            }
        }
        if (seen.add(utterance)) {
            result.add(utterance)
        }
    }
    return result
}

internal data class BestGuess(
    val index: Int,
    val input: String,
    val batchLevel: Int,
    val scoreIn01Range: Float,
    val skill: SkillWithResult<*>,
)

/**
 * Higher conversation batch, then higher [BestGuess.scoreIn01Range], then the
 * earlier candidate. [peek] is called once per candidate, in order.
 */
internal fun selectBestGuess(
    candidates: List<String>,
    peek: (String) -> SkillRanker.Peek?,
): BestGuess? {
    var best: BestGuess? = null
    for ((index, input) in candidates.withIndex()) {
        val found = peek(input) ?: continue
        val guess = BestGuess(
            index = index,
            input = input,
            batchLevel = found.batchLevel,
            scoreIn01Range = found.skill.score.scoreIn01Range(),
            skill = found.skill,
        )
        if (best == null || guess.beats(best)) {
            best = guess
        }
    }
    return best
}

private fun BestGuess.beats(other: BestGuess): Boolean {
    if (batchLevel != other.batchLevel) return batchLevel > other.batchLevel
    if (scoreIn01Range != other.scoreIn01Range) return scoreIn01Range > other.scoreIn01Range
    return index < other.index
}

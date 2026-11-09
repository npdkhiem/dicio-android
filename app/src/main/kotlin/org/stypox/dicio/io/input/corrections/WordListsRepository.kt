package org.stypox.dicio.io.input.corrections

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.stypox.dicio.settings.datastore.WordLists
import org.stypox.dicio.skills.local_music.LocalMusicMatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cached word lists for [org.stypox.dicio.eval.SkillEvaluator]. The cache is
 * updated from DataStore, so scoring an utterance does not read disk.
 *
 * The next input phase should call [recognizerHints] for
 * `RecognizerIntent.EXTRA_BIASING_STRINGS`. Tune [MAX_RECOGNIZER_HINTS] here.
 * Pass music titles with the most useful ones first. This app does not store
 * play counts, so the caller has to choose that order.
 */
@Singleton
class WordListsRepository @Inject constructor(
    dataStore: DataStore<WordLists>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var snapshot: WordLists = WordLists.getDefaultInstance()

    init {
        scope.launch {
            dataStore.data.collect { snapshot = it }
        }
    }

    fun current(): WordLists = snapshot

    fun correctionsActive(lists: WordLists = snapshot): Boolean {
        return !lists.disabled && (lists.knownWordsCount > 0 || lists.correctionsCount > 0)
    }

    fun correctUtterance(text: String, lists: WordLists = snapshot): String? {
        if (lists.disabled) return null
        if (lists.knownWordsCount == 0 && lists.correctionsCount == 0) return null
        return TranscriptCorrector.correct(
            text = text,
            knownWords = lists.knownWordsList,
            corrections = lists.correctionsList.map { it.heard to it.correct },
            strictnessPercent = resolvedStrictness(lists.strictnessPercent),
        )
    }

    /**
     * Hint strings for the recognizer, in priority order:
     * command words, then known words, then correction targets, then
     * [musicTitles]. Duplicates are dropped case- and accent-insensitively.
     * When the feature is off, the list is empty so a test run can compare
     * "no list" against a run that sends one.
     */
    fun recognizerHints(
        musicTitles: List<String>,
        cap: Int = MAX_RECOGNIZER_HINTS,
    ): List<String> {
        val lists = snapshot
        return recognizerHints(
            featureEnabled = !lists.disabled,
            knownWords = lists.knownWordsList,
            correctionTargets = lists.correctionsList.map { it.correct },
            musicTitles = musicTitles,
            cap = cap,
        )
    }

    companion object {
        const val MAX_RECOGNIZER_HINTS = 400
        const val MIN_STRICTNESS = 60
        const val MAX_STRICTNESS = 100
        const val DEFAULT_STRICTNESS = 80
        const val STRICTNESS_STEP = 5

        val RECOGNIZER_COMMAND_WORDS: List<String> = listOf(
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

        /** Stored 0, or anything outside the slider, means [DEFAULT_STRICTNESS]. */
        fun resolvedStrictness(stored: Int): Int {
            if (stored !in MIN_STRICTNESS..MAX_STRICTNESS) return DEFAULT_STRICTNESS
            return snapStrictness(stored)
        }

        fun snapStrictness(value: Int): Int {
            val clamped = value.coerceIn(MIN_STRICTNESS, MAX_STRICTNESS)
            val snapped = ((clamped + STRICTNESS_STEP / 2) / STRICTNESS_STEP) * STRICTNESS_STEP
            return snapped.coerceIn(MIN_STRICTNESS, MAX_STRICTNESS)
        }

        fun recognizerHints(
            featureEnabled: Boolean,
            knownWords: List<String>,
            correctionTargets: List<String>,
            musicTitles: List<String>,
            cap: Int = MAX_RECOGNIZER_HINTS,
        ): List<String> {
            if (!featureEnabled || cap <= 0) return emptyList()
            val out = ArrayList<String>(minOf(cap, RECOGNIZER_COMMAND_WORDS.size + 8))
            val seen = HashSet<String>()
            fun add(raw: String) {
                if (out.size >= cap) return
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return
                val key = LocalMusicMatcher.normalizeName(trimmed)
                if (key.isEmpty() || !seen.add(key)) return
                out.add(trimmed)
            }
            for (word in RECOGNIZER_COMMAND_WORDS) add(word)
            for (word in knownWords) add(word)
            for (word in correctionTargets) add(word)
            for (title in musicTitles) add(title)
            return out
        }
    }
}

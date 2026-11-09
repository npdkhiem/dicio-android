package org.stypox.dicio.io.input.android_stt

import org.stypox.dicio.io.input.corrections.WordListsRepository
import org.stypox.dicio.skills.local_music.LocalMusicMatcher
import java.util.Locale

/**
 * Pure decisions for the Android speech recognizer: when hints may leave the
 * phone, which strings to send, how music titles are ordered, and the timing
 * log line. Nothing here reads the microphone or the network.
 */
object AndroidSttHints {
    const val DEFAULT_MAX_RESULTS = 5

    /**
     * Biasing strings are a hint Google can upload with an online request.
     * Send them only for a recognizer created with createOnDeviceSpeechRecognizer,
     * and only on API 33+ where EXTRA_BIASING_STRINGS exists.
     * EXTRA_PREFER_OFFLINE on the normal recognizer is not on-device: it still
     * must not receive hints.
     */
    fun shouldSendHints(isOnDevice: Boolean, sdkInt: Int): Boolean {
        return isOnDevice && sdkInt >= 33
    }

    /**
     * The list that would be placed in EXTRA_BIASING_STRINGS.
     * Empty when [shouldSendHints] is false or the word-list feature is off.
     * Capped by [WordListsRepository.MAX_RECOGNIZER_HINTS].
     */
    fun hintsToSend(
        isOnDevice: Boolean,
        sdkInt: Int,
        featureEnabled: Boolean,
        knownWords: List<String>,
        correctionTargets: List<String>,
        musicTitles: List<String>,
    ): List<String> {
        if (!shouldSendHints(isOnDevice, sdkInt)) return emptyList()
        return WordListsRepository.recognizerHints(
            featureEnabled = featureEnabled,
            knownWords = knownWords,
            correctionTargets = correctionTargets,
            musicTitles = musicTitles,
            cap = WordListsRepository.MAX_RECOGNIZER_HINTS,
        )
    }

    /**
     * File names become title stems. [recentFirst] is used only when the caller
     * already has a recent or most-played order. This app does not store play
     * counts, so the cache passes an empty list and the rest are alphabetical.
     * Stems that fold to the same name are kept once.
     */
    fun orderMusicTitles(
        displayNames: List<String>,
        recentFirst: List<String> = emptyList(),
    ): List<String> {
        val byKey = LinkedHashMap<String, String>()
        for (raw in displayNames) {
            val stem = stemOf(raw) ?: continue
            val key = LocalMusicMatcher.normalizeName(stem)
            if (key.isEmpty() || byKey.containsKey(key)) continue
            byKey[key] = stem
        }
        if (byKey.isEmpty()) return emptyList()

        val recent = ArrayList<String>()
        val used = HashSet<String>()
        for (raw in recentFirst) {
            val stem = stemOf(raw) ?: continue
            val key = LocalMusicMatcher.normalizeName(stem)
            val kept = byKey[key] ?: continue
            if (!used.add(key)) continue
            recent.add(kept)
        }
        val rest = byKey
            .filterKeys { it !in used }
            .entries
            .sortedBy { it.key }
            .map { it.value }
        return recent + rest
    }

    /** 0 or a negative stored value means [DEFAULT_MAX_RESULTS]. */
    fun resolvedMaxResults(stored: Int): Int {
        return if (stored <= 0) DEFAULT_MAX_RESULTS else stored
    }

    /**
     * The settings slider counts silent intervals (1 to 7). The recognizer extra
     * wants milliseconds. One interval is one second. Often ignored by the service.
     */
    fun silenceLengthMillis(intervals: Int): Int {
        return intervals.coerceIn(1, 7) * 1000
    }

    /**
     * Language tag for EXTRA_LANGUAGE. Some recognizers reject a language with
     * no country, so an empty country is filled from [localesWithCountry] when
     * one is known. This is the same fallback the external popup uses.
     */
    fun languageTagForRecognizer(locale: Locale, localesWithCountry: List<Locale>): String {
        if (locale.country.isNotEmpty()) return locale.toLanguageTag()
        val withCountry = localesWithCountry.firstOrNull { candidate ->
            candidate.language == locale.language && candidate.country.isNotEmpty()
        }
        return (withCountry ?: locale).toLanguageTag()
    }

    fun engineName(isOnDevice: Boolean, preferOffline: Boolean): String {
        return when {
            isOnDevice -> DicioSttTiming.ENGINE_ON_DEVICE
            preferOffline -> DicioSttTiming.ENGINE_PREFER_OFFLINE
            else -> DicioSttTiming.ENGINE_ONLINE
        }
    }

    private fun stemOf(raw: String): String? {
        val stem = LocalMusicMatcher.fileStem(raw.trim()).trim()
        return stem.takeIf { it.isNotEmpty() }
    }
}

/**
 * One debug line per recognition. The line has counts and timings only.
 * It has no place for the utterance, the hint text, a title, or a package name.
 */
object DicioSttTiming {
    const val TAG = "DicioSttTiming"
    const val ENGINE_ONLINE = "online"
    const val ENGINE_ON_DEVICE = "on-device"
    const val ENGINE_PREFER_OFFLINE = "prefer-offline"

    /** "google" for the two Google recognizer packages, otherwise "other". */
    fun serviceBucket(packageName: String?): String {
        return when (packageName) {
            RecognizerChooser.GOOGLE_TTS_PACKAGE,
            RecognizerChooser.GOOGLE_APP_PACKAGE,
            -> "google"
            else -> "other"
        }
    }

    fun format(
        engine: String,
        hintCount: Int,
        readyMs: Long?,
        beginMs: Long?,
        endToResultsMs: Long?,
        totalMs: Long?,
        resultCount: Int,
        errorCode: Int?,
        serviceBucket: String,
    ): String {
        val error = errorCode?.toString() ?: "none"
        return "engine=$engine hints=$hintCount readyMs=${ms(readyMs)} beginMs=${ms(beginMs)} " +
            "endToResultsMs=${ms(endToResultsMs)} totalMs=${ms(totalMs)} " +
            "results=$resultCount error=$error service=$serviceBucket"
    }

    private fun ms(value: Long?): String {
        return if (value == null || value < 0L) "-" else value.toString()
    }
}

package org.stypox.dicio.io.input.android_stt

import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.LastPartialFallback
import org.stypox.dicio.io.input.UserFacingInputException

/**
 * Maps a recognizer result bundle and error code onto [InputEvent].
 *
 * Error numbers match [android.speech.SpeechRecognizer]. Newer codes are
 * written as numbers so this file does not reference API 31+ fields.
 */
object AndroidSttResults {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    /** API 31. SpeechRecognizer.ERROR_TOO_MANY_REQUESTS */
    const val ERROR_TOO_MANY_REQUESTS = 10
    /** API 31. SpeechRecognizer.ERROR_SERVER_DISCONNECTED */
    const val ERROR_SERVER_DISCONNECTED = 11
    /** API 31. SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED */
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    /** API 31. SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE */
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    /** API 33. SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT */
    const val ERROR_CANNOT_CHECK_SUPPORT = 14
    /** API 34. SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS */
    const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    const val NO_INTERNET_MESSAGE =
        "No internet: Google speech needs a connection, or turn on offline speech"

    const val MICROPHONE_PERMISSION_MESSAGE =
        "Microphone permission was denied. Allow the microphone for Dicio. " +
            "On some Samsung phones this also happens in the background; " +
            "turn on offline speech and try again."

    /**
     * [texts] is RESULTS_RECOGNITION and [scores] is CONFIDENCE_SCORES.
     * A missing score or a negative one (the recognizer uses -1 for unknown)
     * becomes a falling rank: 1.0, 0.9, 0.8, … among the kept lines.
     * Blank lines are dropped. No usable line becomes [InputEvent.None].
     */
    fun finalEvent(texts: List<String>?, scores: FloatArray?): InputEvent {
        if (texts.isNullOrEmpty()) return InputEvent.None
        val utterances = ArrayList<Pair<String, Float>>(texts.size)
        var kept = 0
        for (index in texts.indices) {
            val text = texts[index].trim()
            if (text.isEmpty()) continue
            val given = scores?.getOrNull(index)
            val score = if (given == null || given < 0f) {
                (1.0f - 0.1f * kept).coerceAtLeast(0f)
            } else {
                given
            }
            kept++
            utterances.add(text to score)
        }
        if (utterances.isEmpty()) return InputEvent.None
        return InputEvent.Final(utterances)
    }

    /**
     * Turns a trailing [InputEvent.None] into [InputEvent.Final] when a
     * non-blank partial was heard. One implementation lives in
     * [LastPartialFallback]; this delegates so Android STT and Vosk share it.
     */
    fun withLastPartial(event: InputEvent, lastPartial: String?): InputEvent {
        return LastPartialFallback.withLastPartial(event, lastPartial)
    }

    /**
     * [ERROR_NO_MATCH] and [ERROR_SPEECH_TIMEOUT] are silence, not failures.
     * Every other code is a [UserFacingInputException] with a plain sentence.
     */
    fun errorEvent(errorCode: Int): InputEvent {
        val message = errorMessage(errorCode) ?: return InputEvent.None
        return InputEvent.Error(UserFacingInputException(message))
    }

    fun errorMessage(errorCode: Int): String? {
        return when (errorCode) {
            ERROR_NO_MATCH,
            ERROR_SPEECH_TIMEOUT,
            -> null
            ERROR_NETWORK,
            ERROR_NETWORK_TIMEOUT,
            ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED,
            -> NO_INTERNET_MESSAGE
            ERROR_INSUFFICIENT_PERMISSIONS -> MICROPHONE_PERMISSION_MESSAGE
            ERROR_RECOGNIZER_BUSY ->
                "The speech recognizer is busy. Try again in a moment."
            ERROR_CLIENT ->
                "Speech recognition stopped because of an app error. Try again."
            ERROR_AUDIO ->
                "The microphone could not be read. Try again."
            ERROR_TOO_MANY_REQUESTS ->
                "The speech service is rate-limiting requests. Try again later."
            ERROR_LANGUAGE_NOT_SUPPORTED ->
                "This language is not supported by the speech service."
            ERROR_LANGUAGE_UNAVAILABLE ->
                "This language is not downloaded for offline speech."
            ERROR_CANNOT_CHECK_SUPPORT ->
                "Could not check whether offline speech supports this language."
            ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
                "Could not follow the offline speech download."
            else -> "Speech recognition failed (error $errorCode)."
        }
    }
}

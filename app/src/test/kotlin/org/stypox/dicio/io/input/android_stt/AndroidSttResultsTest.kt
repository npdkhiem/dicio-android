package org.stypox.dicio.io.input.android_stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.UserFacingInputException

class AndroidSttResultsTest : StringSpec({
    "confidence scores are kept and paired with the text" {
        val event = AndroidSttResults.finalEvent(
            listOf("play hello", "play yellow"),
            floatArrayOf(0.8f, 0.4f),
        )
        event shouldBe InputEvent.Final(
            listOf("play hello" to 0.8f, "play yellow" to 0.4f),
        )
    }

    "missing scores become a falling rank" {
        val event = AndroidSttResults.finalEvent(
            listOf("one", "two", "three"),
            null,
        )
        event shouldBe InputEvent.Final(
            listOf(
                "one" to 1.0f,
                "two" to (1.0f - 0.1f),
                "three" to (1.0f - 0.2f),
            ),
        )
    }

    "a negative score is unknown and uses the falling rank of kept lines" {
        val event = AndroidSttResults.finalEvent(
            listOf("one", "two", "three"),
            floatArrayOf(-1f, 0.4f, -1f),
        )
        event shouldBe InputEvent.Final(
            listOf(
                "one" to 1.0f,
                "two" to 0.4f,
                "three" to (1.0f - 0.2f),
            ),
        )
    }

    "blank lines are dropped and do not take a rank" {
        val event = AndroidSttResults.finalEvent(
            listOf("  ", "play snow", "", "next"),
            null,
        )
        event shouldBe InputEvent.Final(
            listOf("play snow" to 1.0f, "next" to (1.0f - 0.1f)),
        )
    }

    "an empty or all-blank result is None" {
        AndroidSttResults.finalEvent(null, null) shouldBe InputEvent.None
        AndroidSttResults.finalEvent(emptyList(), floatArrayOf()) shouldBe InputEvent.None
        AndroidSttResults.finalEvent(listOf("", "  "), floatArrayOf(0.9f, 0.8f)) shouldBe
            InputEvent.None
    }

    "no match and speech timeout are None" {
        AndroidSttResults.errorEvent(AndroidSttResults.ERROR_NO_MATCH) shouldBe InputEvent.None
        AndroidSttResults.errorEvent(AndroidSttResults.ERROR_SPEECH_TIMEOUT) shouldBe
            InputEvent.None
    }

    "network and server errors share the no-internet sentence" {
        val codes = listOf(
            AndroidSttResults.ERROR_NETWORK,
            AndroidSttResults.ERROR_NETWORK_TIMEOUT,
            AndroidSttResults.ERROR_SERVER,
            AndroidSttResults.ERROR_SERVER_DISCONNECTED,
        )
        for (code in codes) {
            val event = AndroidSttResults.errorEvent(code).shouldBeInstanceOf<InputEvent.Error>()
            event.throwable.shouldBeInstanceOf<UserFacingInputException>()
            event.throwable.message shouldBe AndroidSttResults.NO_INTERNET_MESSAGE
        }
    }

    "insufficient permissions explains the microphone and Samsung" {
        val event = AndroidSttResults.errorEvent(AndroidSttResults.ERROR_INSUFFICIENT_PERMISSIONS)
            .shouldBeInstanceOf<InputEvent.Error>()
        event.throwable.message shouldBe AndroidSttResults.MICROPHONE_PERMISSION_MESSAGE
    }

    "other errors are plain sentences" {
        message(AndroidSttResults.ERROR_RECOGNIZER_BUSY) shouldBe
            "The speech recognizer is busy. Try again in a moment."
        message(AndroidSttResults.ERROR_CLIENT) shouldBe
            "Speech recognition stopped because of an app error. Try again."
        message(AndroidSttResults.ERROR_AUDIO) shouldBe
            "The microphone could not be read. Try again."
        message(AndroidSttResults.ERROR_TOO_MANY_REQUESTS) shouldBe
            "The speech service is rate-limiting requests. Try again later."
        message(AndroidSttResults.ERROR_LANGUAGE_NOT_SUPPORTED) shouldBe
            "This language is not supported by the speech service."
        message(AndroidSttResults.ERROR_LANGUAGE_UNAVAILABLE) shouldBe
            "This language is not downloaded for offline speech."
        message(AndroidSttResults.ERROR_CANNOT_CHECK_SUPPORT) shouldBe
            "Could not check whether offline speech supports this language."
        message(AndroidSttResults.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) shouldBe
            "Could not follow the offline speech download."
        message(99) shouldBe "Speech recognition failed (error 99)."
    }
})

private fun message(code: Int): String? {
    val event = AndroidSttResults.errorEvent(code).shouldBeInstanceOf<InputEvent.Error>()
    return event.throwable.message
}

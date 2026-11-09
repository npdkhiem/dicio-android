package org.stypox.dicio.io.input.android_stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.io.input.corrections.WordListsRepository
import java.util.Locale

class AndroidSttHintsTest : StringSpec({
    "hints are sent only to an on-device recognizer on API 33 or newer" {
        AndroidSttHints.shouldSendHints(isOnDevice = true, sdkInt = 33) shouldBe true
        AndroidSttHints.shouldSendHints(isOnDevice = true, sdkInt = 34) shouldBe true
        AndroidSttHints.shouldSendHints(isOnDevice = true, sdkInt = 32) shouldBe false
        AndroidSttHints.shouldSendHints(isOnDevice = true, sdkInt = 31) shouldBe false
        AndroidSttHints.shouldSendHints(isOnDevice = false, sdkInt = 33) shouldBe false
        AndroidSttHints.shouldSendHints(isOnDevice = false, sdkInt = 34) shouldBe false
        AndroidSttHints.shouldSendHints(isOnDevice = false, sdkInt = 21) shouldBe false
    }

    "an online recognizer gets an empty hint list even when words exist" {
        AndroidSttHints.hintsToSend(
            isOnDevice = false,
            sdkInt = 34,
            featureEnabled = true,
            knownWords = listOf("Khiem"),
            correctionTargets = listOf("Glove Puppet"),
            musicTitles = listOf("Yesterday"),
        ).shouldBeEmpty()
    }

    "on-device hints follow the repository order and the feature switch" {
        val hints = AndroidSttHints.hintsToSend(
            isOnDevice = true,
            sdkInt = 33,
            featureEnabled = true,
            knownWords = listOf("Khiem"),
            correctionTargets = listOf("Glove Puppet"),
            musicTitles = listOf("Yesterday"),
        )
        hints shouldBe WordListsRepository.recognizerHints(
            featureEnabled = true,
            knownWords = listOf("Khiem"),
            correctionTargets = listOf("Glove Puppet"),
            musicTitles = listOf("Yesterday"),
        )
        AndroidSttHints.hintsToSend(
            isOnDevice = true,
            sdkInt = 33,
            featureEnabled = false,
            knownWords = listOf("Khiem"),
            correctionTargets = listOf("Glove Puppet"),
            musicTitles = listOf("Yesterday"),
        ).shouldBeEmpty()
    }

    "hint hand-off stays inside the recognizer cap" {
        val titles = (1..500).map { "song $it" }
        val hints = AndroidSttHints.hintsToSend(
            isOnDevice = true,
            sdkInt = 33,
            featureEnabled = true,
            knownWords = emptyList(),
            correctionTargets = emptyList(),
            musicTitles = titles,
        )
        hints.size shouldBe WordListsRepository.MAX_RECOGNIZER_HINTS
    }

    "music titles are alphabetical stems when there is no play history" {
        AndroidSttHints.orderMusicTitles(
            listOf("Yesterday.ogg", "Hello.mp3", "Snow.flac", "Hello.wav", "Hà Nội.mp3", "ha noi.m4a"),
        ) shouldBe listOf("Hà Nội", "Hello", "Snow", "Yesterday")
    }

    "recent titles stay in front when the caller has that order" {
        AndroidSttHints.orderMusicTitles(
            displayNames = listOf("Yesterday.ogg", "Hello.mp3", "Snow.flac"),
            recentFirst = listOf("Snow.flac", "missing.mp3", "Hello.wav"),
        ) shouldBe listOf("Snow", "Hello", "Yesterday")
    }

    "max results and silence use the documented defaults" {
        AndroidSttHints.resolvedMaxResults(0) shouldBe 5
        AndroidSttHints.resolvedMaxResults(-3) shouldBe 5
        AndroidSttHints.resolvedMaxResults(3) shouldBe 3
        AndroidSttHints.silenceLengthMillis(2) shouldBe 2000
        AndroidSttHints.silenceLengthMillis(7) shouldBe 7000
        AndroidSttHints.silenceLengthMillis(0) shouldBe 1000
    }

    "a language with no country borrows one" {
        AndroidSttHints.languageTagForRecognizer(Locale.US, emptyList()) shouldBe "en-US"
        val tag = AndroidSttHints.languageTagForRecognizer(
            Locale.forLanguageTag("en"),
            listOf(Locale.forLanguageTag("fr-FR"), Locale.UK),
        )
        tag shouldBe "en-GB"
        AndroidSttHints.languageTagForRecognizer(Locale.forLanguageTag("vi"), emptyList()) shouldBe "vi"
    }

    "the timing line names the engine and never carries text" {
        AndroidSttHints.engineName(isOnDevice = true, preferOffline = true) shouldBe "on-device"
        AndroidSttHints.engineName(isOnDevice = false, preferOffline = true) shouldBe "prefer-offline"
        AndroidSttHints.engineName(isOnDevice = false, preferOffline = false) shouldBe "online"

        val line = DicioSttTiming.format(
            engine = "on-device",
            hintCount = 42,
            readyMs = 180,
            beginMs = 900,
            endToResultsMs = 240,
            totalMs = 2100,
            resultCount = 3,
            errorCode = null,
            serviceBucket = "google",
        )
        line shouldBe
            "engine=on-device hints=42 readyMs=180 beginMs=900 endToResultsMs=240 " +
            "totalMs=2100 results=3 error=none service=google"
        line.shouldNotContain("play hello")
        line.shouldNotContain("Yesterday")

        DicioSttTiming.format(
            engine = "online",
            hintCount = 0,
            readyMs = null,
            beginMs = null,
            endToResultsMs = null,
            totalMs = 40,
            resultCount = 0,
            errorCode = 2,
            serviceBucket = "other",
        ) shouldBe
            "engine=online hints=0 readyMs=- beginMs=- endToResultsMs=- totalMs=40 results=0 error=2 service=other"
    }

    "the timing service bucket is only google or other" {
        DicioSttTiming.serviceBucket("com.google.android.tts") shouldBe "google"
        DicioSttTiming.serviceBucket("com.google.android.googlequicksearchbox") shouldBe "google"
        DicioSttTiming.serviceBucket("com.samsung.android.bixby.agent") shouldBe "other"
        DicioSttTiming.serviceBucket("org.stypox.dicio.main") shouldBe "other"
        DicioSttTiming.serviceBucket(null) shouldBe "other"
        DicioSttTiming.serviceBucket("") shouldBe "other"
    }
})

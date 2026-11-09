package org.stypox.dicio.io.input.android_stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RecognizerChooserTest : StringSpec({
    val tts = rec("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", "Speech Recognition & Synthesis")
    val ttsSettings = rec("com.google.android.tts", "com.google.android.tts.OtherService", "Other")
    val quick = rec("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService", "Google")
    val samsung = rec("com.samsung.android.bixby.agent", "com.samsung.android.bixby.agent.speech.RecognitionService", "Samsung")
    val dicio = rec("org.stypox.dicio", "org.stypox.dicio.io.input.stt_service.SttService", "Dicio")
    val dicioMain = rec("org.stypox.dicio.main", "org.stypox.dicio.io.input.stt_service.SttService", "Dicio main")

    "a saved service is used when it is still installed" {
        val chosen = RecognizerChooser.choose(
            userChoice = RecognizerChooser.componentFlat(samsung.packageName, samsung.serviceName),
            installed = listOf(tts, quick, samsung, dicio),
            systemDefault = null,
        )
        chosen shouldBe samsung
    }

    "a saved service that was removed falls back to Automatic" {
        val chosen = RecognizerChooser.choose(
            userChoice = "com.missing.example/com.missing.RecognitionService",
            installed = listOf(samsung, tts),
            systemDefault = null,
        )
        chosen shouldBe tts
    }

    "a saved Dicio component is ignored" {
        val chosen = RecognizerChooser.choose(
            userChoice = RecognizerChooser.componentFlat(dicioMain.packageName, dicioMain.serviceName),
            installed = listOf(dicioMain, samsung),
            systemDefault = null,
        )
        chosen shouldBe samsung
    }

    "automatic prefers Google speech synthesis, then the Google app" {
        RecognizerChooser.choose("", listOf(samsung, quick, ttsSettings, tts), null) shouldBe tts
        RecognizerChooser.choose("", listOf(samsung, quick), null) shouldBe quick
        RecognizerChooser.choose("  ", listOf(quick, samsung), null) shouldBe quick
    }

    "automatic uses the system default when Google is missing" {
        val flat = RecognizerChooser.componentFlat(samsung.packageName, samsung.serviceName)
        RecognizerChooser.choose("", listOf(dicio, samsung), flat) shouldBe samsung
    }

    "a short system-default name still matches" {
        val flat = "com.samsung.android.bixby.agent/.speech.RecognitionService"
        val chosen = RecognizerChooser.choose(
            userChoice = "",
            installed = listOf(samsung),
            systemDefault = flat,
        )
        chosen shouldBe samsung
    }

    "a Dicio system default is skipped for the first other service" {
        val dicioFlat = RecognizerChooser.componentFlat(dicio.packageName, dicio.serviceName)
        RecognizerChooser.choose("", listOf(dicio, dicioMain, samsung), dicioFlat) shouldBe samsung
    }

    "an unreadable or unknown system default still prefers Google, else the first other service" {
        // Google app is installed, so it wins over an earlier Samsung entry.
        RecognizerChooser.choose("", listOf(dicioMain, samsung, quick), null) shouldBe quick
        RecognizerChooser.choose("", listOf(samsung, quick), "com.missing/com.missing.Service") shouldBe
            quick
        RecognizerChooser.choose("", listOf(dicioMain, samsung), null) shouldBe samsung
        RecognizerChooser.choose("", listOf(samsung), "com.missing/com.missing.Service") shouldBe
            samsung
    }

    "nothing usable returns null" {
        RecognizerChooser.choose("", emptyList(), null) shouldBe null
        RecognizerChooser.choose("", listOf(dicio, dicioMain), RecognizerChooser.componentFlat(dicio.packageName, dicio.serviceName)) shouldBe
            null
    }

    "the app's own package is excluded even when it does not use the Dicio prefix" {
        val renamed = rec("com.example.assistant", "com.example.assistant.SttService", "Renamed")
        RecognizerChooser.choose(
            userChoice = RecognizerChooser.componentFlat(renamed.packageName, renamed.serviceName),
            installed = listOf(renamed, samsung),
            systemDefault = null,
            ownPackage = renamed.packageName,
        ) shouldBe samsung
        RecognizerChooser.isDicioPackage("org.stypox.dicio.main") shouldBe true
        RecognizerChooser.isDicioPackage("org.stypox.dicio") shouldBe true
        RecognizerChooser.isDicioPackage("com.google.android.tts") shouldBe false
    }
})

private fun rec(packageName: String, serviceName: String, label: String): InstalledRecognizer {
    return InstalledRecognizer(packageName, serviceName, label)
}

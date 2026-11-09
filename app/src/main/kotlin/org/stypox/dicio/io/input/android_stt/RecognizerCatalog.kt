package org.stypox.dicio.io.input.android_stt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Reads installed recognition services. Choosing among them is [RecognizerChooser].
 */
object RecognizerCatalog {
    /** Settings.Secure key for the system voice-input service. Not a public constant. */
    const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    fun listInstalled(context: Context): List<InstalledRecognizer> {
        return try {
            val pm = context.packageManager
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            query(pm, intent).mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                val packageName = service.packageName ?: return@mapNotNull null
                val serviceName = service.name ?: return@mapNotNull null
                if (RecognizerChooser.isExcluded(packageName, context.packageName)) {
                    return@mapNotNull null
                }
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                InstalledRecognizer(packageName, serviceName, label)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Null when the secure setting cannot be read. */
    fun systemDefault(context: Context): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    fun onDeviceAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }

    private fun query(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
}

package org.stypox.dicio.io.speech

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.R
import java.util.Locale

/**
 * While speaking, requests [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]
 * with [AudioAttributes.USAGE_ASSISTANT] (API 26+; stream music on older APIs)
 * and abandons it when the last utterance finishes. Players that handle audio
 * focus, including local music, duck instead of stopping. A denied focus
 * request is ignored and speech still plays.
 *
 * The wake-word service and speech-to-text do not request audio focus, so
 * music keeps playing while Dicio listens.
 */
class AndroidTtsSpeechDevice(private var context: Context, locale: Locale) : SpeechOutputDevice {
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
    private val runnablesWhenFinished: MutableList<Runnable> = ArrayList()
    private var lastUtteranceId = 0
    private val focusLock = Any()
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener {
        // Ducking is best-effort. Do not pause speech or throw if focus changes.
    }
    // AudioFocusRequest (API 26) is kept behind DuckingFocusO so this class
    // still loads on API 21.
    private var focusToken: Any? = null
    private var holdsDuckingFocus = false

    init {
        textToSpeech = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = setLanguage(locale)
                    if (errorCode >= 0) { // errors are -1 or -2
                        initializedCorrectly = true
                        setOnUtteranceProgressListener(object :
                            UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {}
                            override fun onDone(utteranceId: String) {
                                if ("dicio_$lastUtteranceId" == utteranceId) {
                                    // Abandon focus before callbacks so a follow-up speak()
                                    // can request it again.
                                    abandonDuckingFocus()
                                    // run only when the last enqueued utterance is finished
                                    for (runnable in runnablesWhenFinished) {
                                        runnable.run()
                                    }
                                    runnablesWhenFinished.clear()
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                if ("dicio_$lastUtteranceId" == utteranceId) {
                                    abandonDuckingFocus()
                                }
                            }
                        })
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        }
    }

    override fun speak(speechOutput: String) {
        if (initializedCorrectly) {
            requestDuckingFocus()
            lastUtteranceId += 1
            val result = textToSpeech?.speak(
                speechOutput, TextToSpeech.QUEUE_ADD, null,
                "dicio_$lastUtteranceId"
            ) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                abandonDuckingFocus()
            }
        } else {
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
        }
    }

    override fun stopSpeaking() {
        textToSpeech?.stop()
        abandonDuckingFocus()
    }

    override val isSpeaking: Boolean
        get() = textToSpeech?.isSpeaking == true

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        if (isSpeaking) {
            runnablesWhenFinished.add(runnable)
        } else {
            runnable.run()
        }
    }

    override fun cleanup() {
        abandonDuckingFocus()
        textToSpeech?.apply {
            shutdown()
            textToSpeech = null
        }
    }

    /**
     * Best-effort duck. Never throws: if focus is denied, speech continues and
     * music is left as it was.
     */
    private fun requestDuckingFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        try {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val token = DuckingFocusO.request(audioManager, focusChangeListener, focusToken)
                if (token != null) focusToken = token
                token != null
            } else {
                // USAGE_ASSISTANT exists only from API 26. Stream music is what
                // local playback uses, so may-duck still lowers it.
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            synchronized(focusLock) {
                if (granted) holdsDuckingFocus = true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not request audio focus for speech", e)
        }
    }

    private fun abandonDuckingFocus() {
        val shouldAbandon = synchronized(focusLock) {
            val held = holdsDuckingFocus
            holdsDuckingFocus = false
            held
        }
        if (!shouldAbandon) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DuckingFocusO.abandon(audioManager, focusToken)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not abandon audio focus for speech", e)
        }
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}

/**
 * API 26+ audio focus. Loaded only when speech runs on O or newer, so
 * [android.media.AudioFocusRequest] is not resolved on API 21.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private object DuckingFocusO {
    fun request(
        audioManager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
        existing: Any?,
    ): Any? {
        val request = (existing as? android.media.AudioFocusRequest)
            ?: android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(false)
                .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return if (granted) request else null
    }

    fun abandon(audioManager: AudioManager, token: Any?) {
        val request = token as? android.media.AudioFocusRequest ?: return
        audioManager.abandonAudioFocusRequest(request)
    }
}

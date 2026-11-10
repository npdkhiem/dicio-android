package org.stypox.dicio.io.input.android_stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.datastore.core.DataStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.R
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.LastPartialFallback
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.UserFacingInputException
import org.stypox.dicio.io.input.corrections.WordListsRepository
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst

/**
 * Android speech recognition with no popup. The recognizer is created for one
 * utterance and destroyed after the result, the error, stop, or [destroy].
 *
 * [tryLoad] may run on the wake-word thread. Every [SpeechRecognizer] call is
 * posted to the main looper. [onExclusiveMic] is invoked on the calling thread
 * as soon as this device claims the microphone, before [startListening].
 */
class AndroidSttInputDevice(
    private val context: Context,
    private val localeManager: LocaleManager,
    dataStore: DataStore<UserSettings>,
    private val wordLists: WordListsRepository,
    private val musicTitles: MusicTitleCache,
    private val onExclusiveMic: (Boolean) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : SttInputDevice {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = AtomicReference<Session?>(null)
    private val _uiState = MutableStateFlow<SttState>(SttState.NotInitialized)
    override val uiState: StateFlow<SttState> = _uiState

    @Volatile
    private var settingsSnapshot: UserSettings = UserSettings.getDefaultInstance()

    @Volatile
    private var available: Boolean = false

    @Volatile
    private var destroyed: Boolean = false

    private var recognizer: SpeechRecognizer? = null

    init {
        val (firstSettings, nextSettings) = dataStore.data.distinctUntilChangedBlockingFirst()
        settingsSnapshot = firstSettings
        refreshAvailability()
        musicTitles.warmUp()
        scope.launch {
            nextSettings.collect { updated ->
                settingsSnapshot = updated
                refreshAvailability()
            }
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (destroyed) return false
        if (thenStartListeningEventListener == null) {
            // Preload only. Wake-word listens pass a listener and must not scan.
            musicTitles.warmUp()
            refreshAvailability()
            return available
        }
        return requestListen(thenStartListeningEventListener)
    }

    override fun stopListening() {
        mainHandler.post { endCurrent(InputEvent.None, errorCode = null) }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        if (session.get() != null) {
            stopListening()
            return
        }
        if (!available) return
        requestListen(eventListener)
    }

    override suspend fun destroy() {
        destroyed = true
        withContext(Dispatchers.Main.immediate) {
            endCurrent(InputEvent.None, errorCode = null)
            mainHandler.removeCallbacksAndMessages(null)
            _uiState.value = SttState.NotInitialized
        }
        scope.cancel()
    }

    /**
     * Claims the mic on this thread, then starts the recognizer on the main thread.
     * A second call while a session is open is ignored.
     */
    private fun requestListen(listener: (InputEvent) -> Unit): Boolean {
        if (destroyed) return false
        val next = Session(listener)
        if (!session.compareAndSet(null, next)) return true
        setExclusive(true)
        mainHandler.post { begin(next) }
        return true
    }

    private fun begin(owner: Session) {
        if (destroyed || session.get() !== owner) {
            if (session.get() == null) setExclusive(false)
            return
        }
        refreshAvailability()
        if (session.get() !== owner) return
        if (!available) {
            end(
                owner,
                InputEvent.Error(UserFacingInputException(unavailableMessage())),
                errorCode = null,
            )
            return
        }

        val settings = settingsSnapshot
        val preferOffline = settings.androidSttPreferOffline
        val installed = RecognizerCatalog.listInstalled(context)
        val chosen = RecognizerChooser.choose(
            userChoice = settings.androidSttService,
            installed = installed,
            systemDefault = RecognizerCatalog.systemDefault(context),
            ownPackage = context.packageName,
        )
        val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }
        val useOnDevice = preferOffline && onDeviceAvailable
        if (!useOnDevice && chosen == null) {
            available = false
            end(
                owner,
                InputEvent.Error(UserFacingInputException(unavailableMessage())),
                errorCode = null,
            )
            return
        }

        val lists = wordLists.current()
        val hints = AndroidSttHints.hintsToSend(
            isOnDevice = useOnDevice,
            sdkInt = Build.VERSION.SDK_INT,
            featureEnabled = !lists.disabled,
            knownWords = lists.knownWordsList,
            correctionTargets = lists.correctionsList.map { it.correct },
            musicTitles = musicTitles.cachedTitles(),
        )
        // The SDK check has to be here, next to the extra. Lint treats a true
        // sendHints as API 33+, which is the only place EXTRA_BIASING_STRINGS exists.
        val sendHints = hints.isNotEmpty() &&
            useOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        owner.engine = AndroidSttHints.engineName(useOnDevice, preferOffline)
        owner.hintCount = if (sendHints) hints.size else 0
        owner.servicePackage = if (useOnDevice) null else chosen?.packageName

        val languageTag = AndroidSttHints.languageTagForRecognizer(
            localeManager.locale.value,
            Locale.getAvailableLocales().asList(),
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                AndroidSttHints.resolvedMaxResults(settings.androidSttMaxResults),
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                AndroidSttHints.silenceLengthMillis(
                    SttInputDevice.getSttSilenceDurationOrDefault(settings),
                ),
            )
            if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            // Hints stay on the phone: on-device recognizer only, API 33+.
            if (sendHints) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(hints),
                )
            }
        }

        val created = try {
            if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                val component = checkNotNull(chosen)
                SpeechRecognizer.createSpeechRecognizer(
                    context,
                    ComponentName(component.packageName, component.serviceName),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create speech recognizer: ${t.javaClass.simpleName}")
            end(
                owner,
                InputEvent.Error(
                    UserFacingInputException(AndroidSttResults.errorMessage(AndroidSttResults.ERROR_CLIENT)!!),
                ),
                AndroidSttResults.ERROR_CLIENT,
            )
            return
        }
        if (session.get() !== owner) {
            destroyRecognizer(created)
            return
        }
        recognizer = created
        created.setRecognitionListener(Callbacks(owner))
        owner.startedAt = SystemClock.elapsedRealtime()
        owner.started = true
        _uiState.value = SttState.Listening
        try {
            created.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start speech recognizer: ${t.javaClass.simpleName}")
            end(
                owner,
                InputEvent.Error(
                    UserFacingInputException(AndroidSttResults.errorMessage(AndroidSttResults.ERROR_CLIENT)!!),
                ),
                AndroidSttResults.ERROR_CLIENT,
            )
        }
    }

    private fun endCurrent(event: InputEvent?, errorCode: Int?) {
        val owner = session.get() ?: return
        end(owner, event, errorCode)
    }

    /** Drops the recognizer on every exit. Safe to call twice. */
    private fun end(owner: Session, event: InputEvent?, errorCode: Int?) {
        if (!session.compareAndSet(owner, null)) return
        val rec = recognizer
        recognizer = null
        destroyRecognizer(rec)
        setExclusive(false)
        if (!destroyed) {
            _uiState.value = if (available) SttState.Loaded else unavailableState()
        }
        if (event != null) {
            owner.listener(event)
            if (owner.fallback.promoted) {
                owner.resultCount = 1
            }
        }
        logTiming(owner, errorCode)
    }

    private fun destroyRecognizer(rec: SpeechRecognizer?) {
        if (rec == null) return
        try {
            rec.cancel()
        } catch (_: Throwable) {
        }
        try {
            rec.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun logTiming(owner: Session, errorCode: Int?) {
        if (!owner.started || owner.logged) return
        owner.logged = true
        val now = SystemClock.elapsedRealtime()
        // Counts and durations only. No utterance, hint text, title, or package.
        Log.d(
            DicioSttTiming.TAG,
            DicioSttTiming.format(
                engine = owner.engine,
                hintCount = owner.hintCount,
                readyMs = elapsed(owner.readyAt, owner.startedAt),
                beginMs = elapsed(owner.beginAt, owner.startedAt),
                endToResultsMs = elapsed(now, owner.endAt),
                totalMs = elapsed(now, owner.startedAt),
                resultCount = owner.resultCount,
                errorCode = errorCode,
                serviceBucket = DicioSttTiming.serviceBucket(owner.servicePackage),
                partialPromoted = owner.fallback.promoted,
            ),
        )
    }

    private fun elapsed(mark: Long, start: Long): Long? {
        if (mark <= 0L || start <= 0L) return null
        return (mark - start).coerceAtLeast(0L)
    }

    private fun refreshAvailability() {
        val installed = RecognizerCatalog.listInstalled(context)
        val chosen = RecognizerChooser.choose(
            userChoice = settingsSnapshot.androidSttService,
            installed = installed,
            systemDefault = RecognizerCatalog.systemDefault(context),
            ownPackage = context.packageName,
        )
        val recognitionAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
        val onDevice = settingsSnapshot.androidSttPreferOffline &&
            RecognizerCatalog.onDeviceAvailable(context)
        // No service, or the only service is Dicio, and on-device is off.
        available = recognitionAvailable && (chosen != null || onDevice)
        if (session.get() == null && !destroyed) {
            _uiState.value = if (available) SttState.Loaded else unavailableState()
        }
    }

    private fun unavailableState(): SttState = SttState.Unavailable(unavailableMessage())

    private fun unavailableMessage(): String {
        return context.getString(R.string.stt_android_not_available)
    }

    private fun setExclusive(using: Boolean) {
        onExclusiveMic(using)
    }

    private class Session(rawListener: (InputEvent) -> Unit) {
        val fallback = LastPartialFallback(rawListener)
        val listener: (InputEvent) -> Unit = fallback
        var engine: String = DicioSttTiming.ENGINE_ONLINE
        var hintCount: Int = 0
        var servicePackage: String? = null
        var startedAt: Long = 0L
        var readyAt: Long = 0L
        var beginAt: Long = 0L
        var endAt: Long = 0L
        var resultCount: Int = 0
        var started: Boolean = false
        var logged: Boolean = false
    }

    private inner class Callbacks(private val owner: Session) : RecognitionListener {
        private fun active(): Boolean = session.get() === owner

        override fun onReadyForSpeech(params: Bundle?) {
            if (!active()) return
            owner.readyAt = SystemClock.elapsedRealtime()
        }

        override fun onBeginningOfSpeech() {
            if (!active()) return
            owner.beginAt = SystemClock.elapsedRealtime()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!active()) return
            owner.endAt = SystemClock.elapsedRealtime()
        }

        override fun onError(error: Int) {
            if (!active()) return
            val event = AndroidSttResults.errorEvent(error)
            owner.resultCount = 0
            end(owner, event, error)
        }

        override fun onResults(results: Bundle?) {
            if (!active()) return
            val event = AndroidSttResults.finalEvent(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
                results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
            )
            owner.resultCount = (event as? InputEvent.Final)?.utterances?.size ?: 0
            end(owner, event, errorCode = null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active()) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) return
            owner.listener(InputEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "AndroidStt"
    }
}

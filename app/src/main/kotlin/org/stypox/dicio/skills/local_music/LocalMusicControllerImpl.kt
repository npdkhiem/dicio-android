package org.stypox.dicio.skills.local_music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.stypox.dicio.skills.local_music.LocalMusicInfo.Companion.localMusicDataStore
import org.stypox.dicio.util.checkPermissions
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connects to [LocalMusicPlaybackService] with a [MediaController] and keeps
 * [LocalMusicMatcher] / [LocalMusicQueue] free of Android playback types.
 *
 * Storage permission is checked only for the default MediaStore path. A saved
 * SAF tree is listed with its persistable URI permission. The skill still
 * requests storage permission up front; see [LocalMusicInfo].
 */
@Singleton
class LocalMusicControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocalMusicController {
    private val connectionMutex = Mutex()
    private val mainExecutor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }

    private var mediaController: MediaController? = null
    private var releasingController = false

    @Volatile private var connected = false
    @Volatile private var repeatMode: RepeatMode = RepeatMode.OFF
    @Volatile private var hasQueueState = false
    @Volatile private var isPlayingState = false
    @Volatile private var lastActiveElapsedRealtime: Long = NEVER_ACTIVE

    override val hasQueue: Boolean get() = hasQueueState
    override val isPlaying: Boolean get() = isPlayingState
    override val lastActiveElapsedMillis: Long?
        get() {
            val at = lastActiveElapsedRealtime
            if (at == NEVER_ACTIVE) return null
            return (SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
        }

    override fun consumeCouldNotPlay(): String? = LocalMusicPlaybackErrors.consume()

    override suspend fun playByName(query: String): PlayByNameResult {
        val treeUri = context.localMusicDataStore.data.first().musicFolderTreeUri.trim()
        if (treeUri.isEmpty() && !hasStoragePermission()) {
            return PlayByNameResult.PermissionMissing
        }
        if (treeUri.isNotEmpty() && !hasPersistedTreeAccess(treeUri)) {
            return PlayByNameResult.AccessLost
        }
        val files = try {
            withContext(Dispatchers.IO) { LocalMusicLibrary.list(context, treeUri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot list music", e)
            return LocalMusicAccess.failureForList(
                savedTree = treeUri.isNotEmpty(),
                securityException = true,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list music", e)
            return PlayByNameResult.NoFolderOrEmpty
        }
        val playlist = LocalMusicPlaylist.fromTracks(files)
        return when (val resolved = LocalMusicPlaylist.resolve(query, playlist.map { it.displayName })) {
            is LocalMusicPlaylist.ResolvedPlay.Empty -> PlayByNameResult.NoFolderOrEmpty
            is LocalMusicPlaylist.ResolvedPlay.NoMatch -> PlayByNameResult.NoMatch(resolved.query)
            is LocalMusicPlaylist.ResolvedPlay.Tie -> PlayByNameResult.Tie(resolved.a, resolved.b)
            is LocalMusicPlaylist.ResolvedPlay.Play -> {
                startPlayback(playlist, resolved.index)
                PlayByNameResult.Playing(resolved.fileName)
            }
        }
    }

    override suspend fun pause() = withConnected { it.pause() }

    override suspend fun resume() = withConnected {
        if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) {
            it.prepare()
        }
        it.play()
    }

    override suspend fun next(): SkipOutcome = withConnected(SkipOutcome.NothingPlaying) { player ->
        val decision = LocalMusicTransport.decideNext(LocalMusicTransport.snapshotOf(player))
        LocalMusicTransport.apply(player, decision)
        decision.toOutcome()
    }

    override suspend fun previous(): SkipOutcome = withConnected(SkipOutcome.NothingPlaying) { player ->
        val decision = LocalMusicTransport.decidePrevious(LocalMusicTransport.snapshotOf(player))
        LocalMusicTransport.apply(player, decision)
        decision.toOutcome()
    }

    override suspend fun stop() = withConnected { stopPlayer(it) }

    override suspend fun setRepeat(mode: RepeatMode) {
        repeatMode = mode
        withConnected { it.repeatMode = LocalMusicRepeat.toPlayerRepeatMode(mode) }
    }

    private fun hasStoragePermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkPermissions(context, permission)
    }

    /**
     * A revoked SAF grant disappears from the persisted list. Treat that as
     * lost access before listing, which would otherwise look like an empty folder.
     */
    private fun hasPersistedTreeAccess(treeUri: String): Boolean {
        val parsed = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == parsed
        }
    }

    private suspend fun startPlayback(playlist: List<LocalMusicPlaylist.Track>, index: Int) {
        val controller = awaitController()
        val items = playlist.map { track ->
            val title = LocalMusicMatcher.fileStem(track.displayName)
            MediaItem.Builder()
                .setUri(Uri.parse(track.uri))
                .setMediaId(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .build()
                )
                .build()
        }
        withContext(Dispatchers.Main.immediate) {
            controller.setMediaItems(items, index, /* startPositionMs = */ 0L)
            controller.repeatMode = LocalMusicRepeat.toPlayerRepeatMode(repeatMode)
            controller.prepare()
            controller.play()
            val stem = playlist.getOrNull(index)
                ?.let { LocalMusicMatcher.fileStem(it.displayName) }
            if (!stem.isNullOrEmpty()) {
                LocalMusicPlaybackErrors.onRequested(stem)
            }
            applyActivity(LocalMusicActivity.Signal.UserCommand)
            refresh(controller)
        }
    }

    private suspend fun awaitController(): MediaController {
        if (connected) {
            mediaController?.let { return it }
        }
        return withTimeout(CONNECT_TIMEOUT_MS) {
            connectionMutex.withLock {
                val existing = mediaController
                if (connected && existing != null) return@withLock existing
                withContext(Dispatchers.Main.immediate) { connectOnMain() }
            }
        }
    }

    private suspend fun connectOnMain(): MediaController {
        val token = SessionToken(
            context,
            ComponentName(context, LocalMusicPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (releasingController) return
                    if (mediaController === controller) {
                        mediaController = null
                        connected = false
                        hasQueueState = false
                        isPlayingState = false
                    }
                    releasingController = true
                    try {
                        controller.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaController.release failed", e)
                    } finally {
                        releasingController = false
                    }
                }
            })
            .buildAsync()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val controller = future.get()
                    controller.addListener(playerListener)
                    controller.repeatMode = LocalMusicRepeat.toPlayerRepeatMode(repeatMode)
                    mediaController = controller
                    connected = true
                    refresh(controller)
                    if (continuation.isActive) continuation.resume(controller)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }, mainExecutor)
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }

    private fun stopPlayer(player: MediaController) {
        player.pause()
        player.stop()
    }

    private suspend fun withConnected(block: (MediaController) -> Unit) {
        withConnected(Unit, block)
    }

    private suspend fun <T> withConnected(default: T, block: (MediaController) -> T): T {
        if (!connected && mediaController == null) return default
        return withContext(Dispatchers.Main.immediate) {
            val controller = mediaController ?: return@withContext default
            if (!controller.isConnected) {
                dropController(controller)
                return@withContext default
            }
            val result = block(controller)
            applyActivity(LocalMusicActivity.Signal.UserCommand)
            refresh(controller)
            result
        }
    }

    private fun dropController(controller: MediaController) {
        if (mediaController === controller) {
            mediaController = null
            connected = false
            hasQueueState = false
            isPlayingState = false
        }
        if (releasingController) return
        releasingController = true
        try {
            controller.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaController.release failed", e)
        } finally {
            releasingController = false
        }
    }

    private var permanentFocusLoss = false

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            permanentFocusLoss = !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        }

        override fun onEvents(player: Player, events: Player.Events) {
            hasQueueState = player.mediaItemCount > 0
            isPlayingState = player.isPlaying
            val focusLoss = permanentFocusLoss
            permanentFocusLoss = false
            if (focusLoss) {
                // The following IS_PLAYING_CHANGED is the focus-loss pause.
                // Do not refresh the window from it.
                applyActivity(LocalMusicActivity.Signal.PermanentAudioFocusLoss)
                return
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && player.isPlaying) {
                applyActivity(LocalMusicActivity.Signal.TrackTransitionWhilePlaying)
            } else if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                applyActivity(LocalMusicActivity.Signal.PassivePlaybackChange)
            }
        }
    }

    private fun refresh(player: Player) {
        hasQueueState = player.mediaItemCount > 0
        isPlayingState = player.isPlaying
    }

    private fun applyActivity(signal: LocalMusicActivity.Signal) {
        when (LocalMusicActivity.effect(signal)) {
            LocalMusicActivity.Effect.Refresh ->
                lastActiveElapsedRealtime = SystemClock.elapsedRealtime()
            LocalMusicActivity.Effect.Clear ->
                lastActiveElapsedRealtime = NEVER_ACTIVE
            LocalMusicActivity.Effect.Keep -> Unit
        }
    }

    private fun LocalMusicTransport.SkipDecision.toOutcome(): SkipOutcome = when (this) {
        is LocalMusicTransport.SkipDecision.Play -> SkipOutcome.Advanced
        LocalMusicTransport.SkipDecision.LastSong -> SkipOutcome.LastSong
        LocalMusicTransport.SkipDecision.NothingPlaying -> SkipOutcome.NothingPlaying
    }

    companion object {
        private const val TAG = "LocalMusicController"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val NEVER_ACTIVE = Long.MIN_VALUE
    }
}

package org.stypox.dicio.skills.local_music

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.stypox.dicio.MainActivity

/**
 * Foreground media playback for local files. The default Media3 notification
 * (play/pause/next/previous) and audio focus come from [MediaSessionService]
 * plus [ExoPlayer] with [AudioAttributes] usage media / content type music and
 * `handleAudioFocus = true`, so a transient may-duck focus (Dicio TTS) ducks
 * this player automatically.
 *
 * [C.WAKE_MODE_LOCAL] plus the manifest `WAKE_LOCK` permission keep the CPU
 * awake while the screen is off, including across track changes.
 *
 * Repeat mode and previous-seek distance are kept in line with [LocalMusicQueue].
 * The controller builds the playlist (every supported file, queue order) and
 * seeks to the matched index; ExoPlayer then applies the repeat mode at the ends.
 *
 * Notification and headset next/previous go through [EndAwarePlayer], which
 * uses [LocalMusicTransport] — the same rules as the voice path. Next on the
 * last item with repeat off or one stops playback instead of leaving the
 * song running. A player error skips to the next different item (repeat-all
 * wraps) or stops, and is logged. If that file is the one just requested by
 * voice, the next voice reply says it couldn't be played. See
 * [LocalMusicPlaybackErrors].
 */
@OptIn(UnstableApi::class)
class LocalMusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val failedIndices = mutableSetOf<Int>()
    private var errorDepth = 0

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMaxSeekToPreviousPositionMs(LocalMusicRepeat.MAX_SEEK_TO_PREVIOUS_POSITION_MS)
            .build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(errorListener(exoPlayer))

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, EndAwarePlayer(exoPlayer))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun errorListener(player: Player) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                failedIndices.clear()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                failedIndices.clear()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (errorDepth > MAX_ERROR_DEPTH) {
                Log.w(TAG, "Couldn't recover playback; stopping", error)
                player.stop()
                failedIndices.clear()
                return
            }
            errorDepth++
            try {
                recover(player, error)
            } finally {
                errorDepth--
            }
        }
    }

    private fun recover(player: Player, error: PlaybackException) {
        val index = player.currentMediaItemIndex
        val stem = currentStem(player)
        val action = LocalMusicTransport.decideOnError(
            itemCount = player.mediaItemCount,
            currentIndex = index,
            repeatMode = LocalMusicRepeat.fromPlayerRepeatMode(player.repeatMode),
            failedIndices = failedIndices,
        )
        if (index >= 0) failedIndices.add(index)
        LocalMusicPlaybackErrors.onFailed(stem)
        when (action) {
            is LocalMusicTransport.ErrorAction.SkipTo -> {
                Log.w(TAG, "Couldn't play $stem; skipping to index ${action.index}", error)
                player.seekTo(action.index, /* positionMs = */ 0L)
                player.prepare()
                player.play()
            }
            LocalMusicTransport.ErrorAction.Stop -> {
                Log.w(TAG, "Couldn't play $stem; stopping", error)
                player.pause()
                player.stop()
                failedIndices.clear()
            }
        }
    }

    /**
     * Notification and headset next/previous. [Player.COMMAND_SEEK_TO_NEXT]
     * stays available on the last item so the button is delivered, then
     * [LocalMusicTransport] stops instead of no-oping when repeat is off or one.
     */
    @OptIn(UnstableApi::class)
    private class EndAwarePlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToNext() {
            LocalMusicTransport.apply(this, LocalMusicTransport.decideNext(LocalMusicTransport.snapshotOf(this)))
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() {
            LocalMusicTransport.apply(
                this,
                LocalMusicTransport.decidePrevious(LocalMusicTransport.snapshotOf(this)),
            )
        }

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            if (mediaItemCount <= 0) return commands
            return commands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (mediaItemCount > 0 &&
                (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }
    }

    companion object {
        private const val TAG = "LocalMusicPlayback"
        private const val MAX_ERROR_DEPTH = 8

        private fun currentStem(player: Player): String {
            val title = player.mediaMetadata.title?.toString()?.trim().orEmpty()
            if (title.isNotEmpty()) return title
            return "unknown"
        }
    }
}

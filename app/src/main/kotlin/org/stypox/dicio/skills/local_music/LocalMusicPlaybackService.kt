package org.stypox.dicio.skills.local_music

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
 * song running. [EndAwarePlayer] is a [androidx.media3.common.ForwardingSimpleBasePlayer]:
 * a [androidx.media3.common.ForwardingPlayer] reports ExoPlayer's commands to
 * MediaSession unchanged, so Next disappeared on the last song. See that class.
 *
 * A player error skips to the next different item (repeat-all wraps) or stops,
 * and is logged. [PlaybackErrorCycle] does not forget a failure just because
 * the file reached [Player.STATE_READY]; ready-then-error still counts. A later
 * item that actually starts forgives that pass, so one good file keeps
 * repeat-all going. If that file is the one just requested by voice and it
 * never reached READY, the next voice reply says it couldn't be played.
 * Reaching READY clears that request. See [LocalMusicPlaybackErrors].
 */
@OptIn(UnstableApi::class)
class LocalMusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val failureCycle = PlaybackErrorCycle()

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
            failureCycle.onPlaybackStateChanged(
                playbackState,
                player.currentMediaItemIndex,
            )
            if (playbackState == Player.STATE_READY) {
                // The requested song made it out of the decoder. A later
                // same-name file that fails must not be reported as that request.
                LocalMusicPlaybackErrors.onPlayed(currentStem(player))
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                // Also the reset for a brand-new play: setMediaItems replaces
                // the playlist.
                failureCycle.onPlaylistChanged()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(player, error)
        }
    }

    private fun recover(player: Player, error: PlaybackException) {
        val index = player.currentMediaItemIndex
        val stem = currentStem(player)
        val action = failureCycle.onError(
            itemCount = player.mediaItemCount,
            currentIndex = index,
            repeatMode = LocalMusicRepeat.fromPlayerRepeatMode(player.repeatMode),
        )
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
            }
        }
    }

    companion object {
        private const val TAG = "LocalMusicPlayback"

        private fun currentStem(player: Player): String {
            val title = player.mediaMetadata.title?.toString()?.trim().orEmpty()
            if (title.isNotEmpty()) return title
            return "unknown"
        }
    }
}

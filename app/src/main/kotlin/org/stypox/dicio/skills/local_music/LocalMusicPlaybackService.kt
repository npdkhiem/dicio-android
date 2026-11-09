package org.stypox.dicio.skills.local_music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
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
 * Repeat mode and previous-seek distance are kept in line with [LocalMusicQueue].
 * The controller builds the playlist (every supported file, queue order) and
 * seeks to the matched index; ExoPlayer then applies the repeat mode at the ends.
 */
class LocalMusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setMaxSeekToPreviousPositionMs(LocalMusicRepeat.MAX_SEEK_TO_PREVIOUS_POSITION_MS)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
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
}

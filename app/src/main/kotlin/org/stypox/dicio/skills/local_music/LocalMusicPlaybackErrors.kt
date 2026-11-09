package org.stypox.dicio.skills.local_music

/**
 * Remembers when the song the user just asked for fails to decode.
 *
 * [LocalMusicControllerImpl.playByName] records the stem and returns "Playing
 * …" without waiting for ExoPlayer. [LocalMusicPlaybackService] logs the skip
 * (or the stop, when nothing else can be played) and, only if the failed stem
 * is that requested song, stores it here. The next local-music or media voice
 * reply consumes it and says "I couldn't play X" in front of that reply.
 * The command still runs. A later track that fails on its own is only logged.
 *
 * [requested] is cleared when that stem reaches [onPlayed] (the player reported
 * [androidx.media3.common.Player.STATE_READY]). A same-name file that fails
 * afterwards, such as Hello.flac after Hello.mp3 already played, must not be
 * reported as the song the user asked for.
 */
class PlaybackErrorReport {
    private val lock = Any()
    private var requested: String? = null
    private var pending: String? = null

    fun onRequested(stem: String) {
        synchronized(lock) {
            requested = stem
        }
    }

    /**
     * The requested stem made it to READY. Later failures of that stem are
     * not this request.
     */
    fun onPlayed(stem: String) {
        synchronized(lock) {
            if (stem == requested) {
                requested = null
            }
        }
    }

    /** Drops the request without reporting it. */
    fun clearRequested() {
        synchronized(lock) {
            requested = null
        }
    }

    fun onFailed(stem: String) {
        synchronized(lock) {
            if (stem == requested) {
                pending = stem
                requested = null
            }
        }
    }

    fun consume(): String? = synchronized(lock) {
        pending.also { pending = null }
    }
}

/** Process-wide holder. The service and the controller share one player. */
object LocalMusicPlaybackErrors {
    private val report = PlaybackErrorReport()

    fun onRequested(stem: String) = report.onRequested(stem)

    fun onPlayed(stem: String) = report.onPlayed(stem)

    fun clearRequested() = report.clearRequested()

    fun onFailed(stem: String) = report.onFailed(stem)

    fun consume(): String? = report.consume()
}

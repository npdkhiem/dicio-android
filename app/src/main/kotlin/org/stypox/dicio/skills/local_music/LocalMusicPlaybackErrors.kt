package org.stypox.dicio.skills.local_music

/**
 * Remembers when the song the user just asked for fails to decode.
 *
 * [LocalMusicControllerImpl.playByName] records the stem and returns "Playing
 * …" without waiting for ExoPlayer. [LocalMusicPlaybackService] logs the skip
 * (or the stop, when nothing else can be played) and, only if the failed stem
 * is that requested song, stores it here. The next local-music or media voice
 * reply consumes it and says "I couldn't play X". The command still runs.
 * A later track that fails on its own is only logged.
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

    fun onFailed(stem: String) = report.onFailed(stem)

    fun consume(): String? = report.consume()
}

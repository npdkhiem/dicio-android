package org.stypox.dicio.skills.local_music

/**
 * Repeat behaviour for [LocalMusicQueue].
 *
 * Default is [OFF]. Voice commands can set any of the three modes.
 */
enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

/**
 * What an explicit next does when the current item is already the last one.
 *
 * [Stop] is [RepeatMode.OFF] and [RepeatMode.ONE]: do not advance. The
 * controller pauses and stops playback, and the skill says "That was the last
 * song". [Wrap] is [RepeatMode.ALL]: continue at the first item.
 *
 * ExoPlayer's [androidx.media3.common.Player.seekToNext] does nothing at that
 * position when repeat is off, and it navigates repeat-one like off. The
 * controller uses this function instead of relying on that no-op.
 */
enum class NextAtEndOutcome {
    Stop,
    Wrap,
}

/**
 * In-memory playlist: the given file names in [LocalMusicMatcher.nameOrder]
 * (normalized stem, then extension preference mp3 > m4a > flac > ogg > wav,
 * then the full file name), plus a current index and a [RepeatMode].
 * [LocalMusicPlaylist] uses the same stem and extension keys and breaks
 * remaining ties with the content URI, which is the path when two files share
 * a name.
 *
 * Navigation semantics, also used by the local player
 * ([NextAtEndOutcome], [LocalMusicRepeat]):
 * - **OFF** (default): [next] on the last item returns `null` and leaves the
 *   index unchanged. That is [NextAtEndOutcome.Stop]: the controller pauses
 *   and stops, and the skill says "That was the last song". [previous] on the
 *   first item returns the first item so playback restarts it. [onTrackEnded]
 *   is the same as [next].
 * - **ALL**: [next] and [previous] wrap, including [next] on the last item
 *   ([NextAtEndOutcome.Wrap]). [onTrackEnded] is the same as [next].
 * - **ONE**: [onTrackEnded] returns the current item again. Explicit [next] /
 *   [previous] still move and do not wrap. [next] on the last item is the same
 *   stop as **OFF**. [previous] on the first item restarts the first item.
 *
 * Until [playAt] succeeds, there is no current track: [next], [previous] and
 * [onTrackEnded] return `null`.
 */
class LocalMusicQueue(
    fileNames: List<String>,
    var repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val tracks: List<String> = fileNames.sortedWith(LocalMusicMatcher.nameOrder())

    var currentIndex: Int = NO_TRACK
        private set

    val currentTrack: String?
        get() = tracks.getOrNull(currentIndex)

    /**
     * Jump to [name] (exact match against a stored file name) and make it the
     * current track. Returns [name], or `null` if it is not in the queue
     * (index unchanged).
     */
    fun playAt(name: String): String? {
        val index = tracks.indexOf(name)
        if (index < 0) return null
        currentIndex = index
        return tracks[index]
    }

    fun next(): String? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        val atEnd = currentIndex >= tracks.lastIndex
        if (atEnd && nextAtEndOutcome(repeatMode) == NextAtEndOutcome.Stop) {
            // Stay on the last item. The controller stops playback.
            return null
        }
        currentIndex = if (atEnd) 0 else currentIndex + 1
        return tracks[currentIndex]
    }

    fun previous(): String? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        return when (repeatMode) {
            RepeatMode.ALL -> {
                currentIndex = if (currentIndex == 0) tracks.lastIndex else currentIndex - 1
                tracks[currentIndex]
            }
            RepeatMode.OFF, RepeatMode.ONE -> {
                if (currentIndex == 0) {
                    // No previous item: restart the first one. ExoPlayer does
                    // the same when seekToPrevious has nowhere to go.
                    tracks[0]
                } else {
                    currentIndex -= 1
                    tracks[currentIndex]
                }
            }
        }
    }

    /**
     * Advance after a track finishes on its own. See class KDoc for per-mode
     * behaviour.
     */
    fun onTrackEnded(): String? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        return when (repeatMode) {
            RepeatMode.ONE -> tracks[currentIndex]
            RepeatMode.OFF, RepeatMode.ALL -> next()
        }
    }

    companion object {
        const val NO_TRACK: Int = -1

        fun nextAtEndOutcome(repeatMode: RepeatMode): NextAtEndOutcome = when (repeatMode) {
            RepeatMode.OFF, RepeatMode.ONE -> NextAtEndOutcome.Stop
            RepeatMode.ALL -> NextAtEndOutcome.Wrap
        }
    }
}

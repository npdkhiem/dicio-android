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
 * In-memory playlist: the given file names sorted case-insensitively by
 * [LocalMusicMatcher.normalizeName] of the file stem, plus a current index and
 * a [RepeatMode].
 *
 * Navigation semantics:
 * - **OFF** (default): [next] at the last item returns `null` (stop) and leaves
 *   the index unchanged. [previous] at the first item returns the first item
 *   (restart). [onTrackEnded] is the same as [next].
 * - **ALL**: [next] and [previous] wrap. [onTrackEnded] is the same as [next].
 * - **ONE**: [onTrackEnded] returns the current item again. Explicit [next] /
 *   [previous] still move, with the same end-of-list behaviour as **OFF**
 *   ([next] at the last item returns `null`; [previous] at the first item
 *   restarts the first item). Wrapping is not used for explicit skips.
 *
 * Until [playAt] succeeds, there is no current track: [next], [previous] and
 * [onTrackEnded] return `null`.
 */
class LocalMusicQueue(
    fileNames: List<String>,
    var repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val tracks: List<String> = fileNames.sortedWith(
        compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) {
            LocalMusicMatcher.normalizeName(LocalMusicMatcher.fileStem(it))
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }
    )

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
        return when (repeatMode) {
            RepeatMode.ALL -> {
                currentIndex = if (currentIndex >= tracks.lastIndex) 0 else currentIndex + 1
                tracks[currentIndex]
            }
            RepeatMode.OFF, RepeatMode.ONE -> {
                if (currentIndex >= tracks.lastIndex) {
                    null
                } else {
                    currentIndex += 1
                    tracks[currentIndex]
                }
            }
        }
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
    }
}

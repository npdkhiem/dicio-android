package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player

/**
 * Indexes that failed during the current playlist.
 *
 * [Player.STATE_READY] does not clear the set. A file with a valid header can
 * reach READY and then fail; clearing on READY lets repeat-all wrap forever,
 * and [androidx.media3.common.C.WAKE_MODE_LOCAL] keeps the CPU awake until
 * "stop music". The set is cleared when the playlist is replaced (including a
 * brand-new play, which is [Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED])
 * and when [onError] decides to stop after a full failing pass.
 *
 * A re-entry counter used to sit next to this set and did nothing:
 * [Player.Listener.onPlayerError] is asynchronous and is not re-entered.
 */
internal class PlaybackErrorCycle {
    private val failedIndices = mutableSetOf<Int>()

    /**
     * Called for every playback-state change. READY is intentionally ignored
     * so a file that fails after READY stays in [failedIndices].
     */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            // Do not clear failedIndices. See the class KDoc.
            return
        }
    }

    fun onPlaylistChanged() {
        failedIndices.clear()
    }

    /**
     * Records [currentIndex] after deciding, so a later error in this cycle
     * can see it. Stop when the next index already failed (repeat-all would
     * wrap onto it) or when there is nowhere to go.
     */
    fun onError(
        itemCount: Int,
        currentIndex: Int,
        repeatMode: RepeatMode,
    ): LocalMusicTransport.ErrorAction {
        val action = LocalMusicTransport.decideOnError(
            itemCount = itemCount,
            currentIndex = currentIndex,
            repeatMode = repeatMode,
            failedIndices = failedIndices,
        )
        if (currentIndex >= 0) failedIndices.add(currentIndex)
        if (action is LocalMusicTransport.ErrorAction.Stop) {
            failedIndices.clear()
        }
        return action
    }
}

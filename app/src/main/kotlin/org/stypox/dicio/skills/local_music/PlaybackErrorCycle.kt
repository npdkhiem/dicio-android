package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player

/**
 * Indexes that failed since a song last played through.
 *
 * Reaching [Player.STATE_READY] does not by itself forgive a file. A valid
 * header can reach READY and then fail; treating that READY as success lets
 * repeat-all wrap forever, and [androidx.media3.common.C.WAKE_MODE_LOCAL]
 * keeps the CPU awake until "stop music".
 *
 * A song counts as played only when playback moves on without an error for
 * it: another item reaches READY, or [onError] fires for a different index
 * than the one that reached READY (the player stayed READY across the
 * change). That clears [failedIndices]. Repeat-all then keeps going while at
 * least one song plays. It stops when the next index has failed again since
 * that play, which is one full pass with no success. The same-index
 * ready-then-error path still fills the set and stops.
 *
 * Callers that do not know the index (the no-arg [onPlaybackStateChanged])
 * treat two READYs in a row as that move. A second READY for the same known
 * index is a rebuffer and is not a success.
 *
 * The set is also cleared when the playlist is replaced (including a
 * brand-new play, which is [Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED])
 * and when [onError] decides to stop.
 */
internal class PlaybackErrorCycle {
    private val failedIndices = mutableSetOf<Int>()

    /** True after READY until an error, idle, or playlist change. */
    private var pendingReady = false

    /**
     * Item that most recently reached READY, or [UNKNOWN_INDEX] when the
     * caller did not pass one.
     */
    private var pendingReadyIndex = UNKNOWN_INDEX

    /**
     * @param currentIndex item that just changed state, or omit it when the
     * caller does not know. [Player.STATE_IDLE] drops a pending READY without
     * forgiving earlier failures.
     */
    fun onPlaybackStateChanged(playbackState: Int, currentIndex: Int = UNKNOWN_INDEX) {
        if (playbackState == Player.STATE_IDLE) {
            pendingReady = false
            pendingReadyIndex = UNKNOWN_INDEX
            return
        }
        if (playbackState != Player.STATE_READY) return
        if (pendingReady && movedToAnotherItem(currentIndex)) {
            // The item that was READY played through. End the failing pass.
            failedIndices.clear()
        }
        pendingReady = true
        pendingReadyIndex = currentIndex
    }

    fun onPlaylistChanged() {
        failedIndices.clear()
        pendingReady = false
        pendingReadyIndex = UNKNOWN_INDEX
    }

    /**
     * Records [currentIndex] after deciding, so a later error in this cycle
     * can see it. A pending READY for a different item is a song that played;
     * its failures are forgiven before the decision. Stop when the next index
     * already failed since that play (repeat-all would wrap onto it) or when
     * there is nowhere to go.
     */
    fun onError(
        itemCount: Int,
        currentIndex: Int,
        repeatMode: RepeatMode,
    ): LocalMusicTransport.ErrorAction {
        if (pendingReady && playedThroughTo(currentIndex)) {
            failedIndices.clear()
        }
        pendingReady = false
        pendingReadyIndex = UNKNOWN_INDEX
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

    private fun movedToAnotherItem(currentIndex: Int): Boolean {
        val previous = pendingReadyIndex
        val bothUnknown = currentIndex == UNKNOWN_INDEX && previous == UNKNOWN_INDEX
        val bothKnownAndDifferent = currentIndex != UNKNOWN_INDEX &&
            previous != UNKNOWN_INDEX &&
            currentIndex != previous
        return bothUnknown || bothKnownAndDifferent
    }

    /** READY was for a real item, and this error is a later item. */
    private fun playedThroughTo(currentIndex: Int): Boolean {
        return pendingReadyIndex != UNKNOWN_INDEX &&
            currentIndex != UNKNOWN_INDEX &&
            pendingReadyIndex != currentIndex
    }

    private companion object {
        /** Same sentinel as an unknown media-item index. Not a playlist slot. */
        const val UNKNOWN_INDEX = -1
    }
}

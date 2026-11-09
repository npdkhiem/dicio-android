package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player

/**
 * Pure next / previous / player-error decisions shared by the voice controller
 * and by [LocalMusicPlaybackService]'s notification and headset buttons.
 *
 * After [Player.STATE_IDLE] (stop) or [Player.STATE_ENDED], a skip prepares
 * and starts the target item. Next on the last item with repeat off or one
 * stops instead ([NextAtEndOutcome.Stop]) and the skill says "That was the
 * last song". An empty queue is [SkipDecision.NothingPlaying].
 *
 * A player error skips to the next item when there is a different one.
 * Repeat-all wraps. Repeat-one does not replay the broken file. If wrapping
 * would land on an index that already failed in this cycle, playback stops.
 */
object LocalMusicTransport {
    enum class PlaybackPosition {
        /** [Player.STATE_IDLE], including after [Player.stop]. */
        Idle,

        /** [Player.STATE_ENDED]. */
        Ended,

        /** [Player.STATE_READY] or [Player.STATE_BUFFERING]. */
        Active,
    }

    data class Snapshot(
        val itemCount: Int,
        val currentIndex: Int,
        val position: PlaybackPosition,
        val repeatMode: RepeatMode,
    )

    sealed interface SkipDecision {
        /** No items loaded. Say that nothing is playing. */
        data object NothingPlaying : SkipDecision

        /**
         * Already on the last item and repeat is off or one. Stop playback.
         * The voice path says "That was the last song".
         */
        data object LastSong : SkipDecision

        /**
         * Seek to [index]. When [prepareAndPlay] is true the player is idle or
         * ended and must [Player.prepare] then [Player.play]. Otherwise leave
         * play-when-ready as it is (a paused player stays paused).
         */
        data class Play(val index: Int, val prepareAndPlay: Boolean) : SkipDecision
    }

    sealed interface ErrorAction {
        data class SkipTo(val index: Int) : ErrorAction
        data object Stop : ErrorAction
    }

    fun snapshotOf(player: Player): Snapshot = Snapshot(
        itemCount = player.mediaItemCount,
        currentIndex = player.currentMediaItemIndex,
        position = when (player.playbackState) {
            Player.STATE_IDLE -> PlaybackPosition.Idle
            Player.STATE_ENDED -> PlaybackPosition.Ended
            else -> PlaybackPosition.Active
        },
        repeatMode = LocalMusicRepeat.fromPlayerRepeatMode(player.repeatMode),
    )

    fun decideNext(snapshot: Snapshot): SkipDecision = decideNext(
        itemCount = snapshot.itemCount,
        currentIndex = snapshot.currentIndex,
        position = snapshot.position,
        repeatMode = snapshot.repeatMode,
    )

    fun decidePrevious(snapshot: Snapshot): SkipDecision = decidePrevious(
        itemCount = snapshot.itemCount,
        currentIndex = snapshot.currentIndex,
        position = snapshot.position,
        repeatMode = snapshot.repeatMode,
    )

    fun decideNext(
        itemCount: Int,
        currentIndex: Int,
        position: PlaybackPosition,
        repeatMode: RepeatMode,
    ): SkipDecision {
        if (!hasCurrentItem(itemCount, currentIndex)) return SkipDecision.NothingPlaying
        val atLast = currentIndex == itemCount - 1
        if (atLast && LocalMusicQueue.nextAtEndOutcome(repeatMode) == NextAtEndOutcome.Stop) {
            return SkipDecision.LastSong
        }
        val target = if (atLast) 0 else currentIndex + 1
        return SkipDecision.Play(target, prepareAndPlay = position != PlaybackPosition.Active)
    }

    fun decidePrevious(
        itemCount: Int,
        currentIndex: Int,
        position: PlaybackPosition,
        repeatMode: RepeatMode,
    ): SkipDecision {
        if (!hasCurrentItem(itemCount, currentIndex)) return SkipDecision.NothingPlaying
        val target = when (repeatMode) {
            RepeatMode.ALL -> if (currentIndex == 0) itemCount - 1 else currentIndex - 1
            RepeatMode.OFF, RepeatMode.ONE -> if (currentIndex == 0) 0 else currentIndex - 1
        }
        return SkipDecision.Play(target, prepareAndPlay = position != PlaybackPosition.Active)
    }

    /**
     * @param failedIndices indexes that already failed during this cycle.
     * The current index is not treated as failed yet; the caller records it
     * after deciding so a later error can see it.
     */
    fun decideOnError(
        itemCount: Int,
        currentIndex: Int,
        repeatMode: RepeatMode,
        failedIndices: Set<Int> = emptySet(),
    ): ErrorAction {
        if (itemCount <= 0 || currentIndex < 0 || currentIndex >= itemCount) {
            return ErrorAction.Stop
        }
        val next = when (LocalMusicQueue.nextAtEndOutcome(repeatMode)) {
            // Repeat-one is an explicit-skip stop at the end, same as off:
            // do not replay the file that just failed.
            NextAtEndOutcome.Stop ->
                if (currentIndex >= itemCount - 1) -1 else currentIndex + 1
            NextAtEndOutcome.Wrap ->
                if (itemCount <= 1) -1 else (currentIndex + 1) % itemCount
        }
        if (next < 0 || next == currentIndex || next in failedIndices) {
            return ErrorAction.Stop
        }
        return ErrorAction.SkipTo(next)
    }

    fun apply(player: Player, decision: SkipDecision) {
        when (decision) {
            SkipDecision.NothingPlaying -> Unit
            SkipDecision.LastSong -> {
                player.pause()
                player.stop()
            }
            is SkipDecision.Play -> {
                player.seekTo(decision.index, /* positionMs = */ 0L)
                if (decision.prepareAndPlay) {
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private fun hasCurrentItem(itemCount: Int, currentIndex: Int): Boolean =
        itemCount > 0 && currentIndex >= 0 && currentIndex < itemCount
}

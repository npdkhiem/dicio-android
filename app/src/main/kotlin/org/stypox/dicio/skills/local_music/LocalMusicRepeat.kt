package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player

/**
 * Maps [RepeatMode] onto ExoPlayer [Player] repeat constants.
 *
 * ExoPlayer's [Player.REPEAT_MODE_ONE] repeats the current item when it ends on
 * its own, while [Player.seekToNext] / [Player.seekToPrevious] behave like
 * [Player.REPEAT_MODE_OFF]. That matches [LocalMusicQueue]: explicit skips do
 * not wrap in [RepeatMode.ONE], and [LocalMusicQueue.onTrackEnded] repeats.
 * [Player.REPEAT_MODE_ALL] wraps at both ends. [Player.REPEAT_MODE_OFF] does
 * not wrap: next on the last item leaves the index unchanged, and previous on
 * the first item restarts it.
 *
 * [MAX_SEEK_TO_PREVIOUS_POSITION_MS] is [Long.MAX_VALUE] so previous moves to
 * the previous item whenever one exists. ExoPlayer's default of 3 seconds
 * would restart the current item instead. On the first item there is no
 * previous media item, so previous still restarts that item.
 */
object LocalMusicRepeat {
    const val MAX_SEEK_TO_PREVIOUS_POSITION_MS: Long = Long.MAX_VALUE

    fun toPlayerRepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }
}

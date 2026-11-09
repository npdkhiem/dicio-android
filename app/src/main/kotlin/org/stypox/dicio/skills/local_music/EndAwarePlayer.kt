package org.stypox.dicio.skills.local_music

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Notification and headset next/previous.
 *
 * [ForwardingPlayer][androidx.media3.common.ForwardingPlayer] cannot do this.
 * In Media3 1.8.1, `ForwardingPlayer.ForwardingListener.onAvailableCommandsChanged`
 * forwards ExoPlayer's own [Player.Commands] unchanged.
 * `MediaSessionImpl.handleAvailablePlayerCommandsChanged` then pushes that set
 * to Media3 controllers. Overrides of [Player.getAvailableCommands] and
 * [Player.isCommandAvailable] on the wrapper are ignored after a commands
 * change, so on the last song with repeat off or one the controllers drop
 * [Player.COMMAND_SEEK_TO_NEXT] and the notification hides or disables Next.
 * `MediaControllerImplBase.seekToNext()` returns early when command 9 is missing.
 *
 * [ForwardingSimpleBasePlayer] is the player MediaSession actually listens to.
 * [getAvailableCommands] is final and returns [getState]'s commands, and a
 * commands change invalidates that state. Adding the next commands in
 * [getState] is what reaches the session. [handleSeek] still runs for those
 * commands: [androidx.media3.common.BasePlayer.seekToNext] calls `ignoreSeek`
 * when there is no next item, and that calls `seekTo` with
 * [Player.COMMAND_SEEK_TO_NEXT], which [SimpleBasePlayer] delivers here when
 * the command is in the state. Next and previous then use [LocalMusicTransport],
 * the same rules as the voice path (next on the last item with repeat off or
 * one stops).
 */
@OptIn(UnstableApi::class)
internal class EndAwarePlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        val commands = commandsIncludingNext(state.availableCommands, getPlayer().mediaItemCount)
        if (commands == state.availableCommands) return state
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val wrapped = getPlayer()
        when (notificationSeek(seekCommand)) {
            NotificationSeek.Next -> {
                LocalMusicTransport.apply(
                    wrapped,
                    LocalMusicTransport.decideNext(LocalMusicTransport.snapshotOf(wrapped)),
                )
                return Futures.immediateVoidFuture()
            }
            NotificationSeek.Previous -> {
                LocalMusicTransport.apply(
                    wrapped,
                    LocalMusicTransport.decidePrevious(LocalMusicTransport.snapshotOf(wrapped)),
                )
                return Futures.immediateVoidFuture()
            }
            NotificationSeek.Delegate -> Unit
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    internal enum class NotificationSeek {
        Next,
        Previous,
        Delegate,
    }

    companion object {
        /**
         * Command ids a snapshot must advertise whenever the queue is not
         * empty. ExoPlayer drops [Player.COMMAND_SEEK_TO_NEXT] (command 9) on
         * the last item with repeat off or one; this puts it, and
         * [Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM], back. An empty queue is
         * left unchanged.
         *
         * [getState] installs this set through [commandsIncludingNext].
         * [androidx.media3.common.Player.Commands] is backed by
         * `android.util.SparseBooleanArray`, so unit tests assert on this set.
         * That is the set the session receives: [getAvailableCommands] is
         * final and returns [getState]'s commands.
         */
        internal fun commandIdsIncludingNext(
            commandIds: Set<Int>,
            mediaItemCount: Int,
        ): Set<Int> {
            if (mediaItemCount <= 0) return commandIds
            return commandIds +
                Player.COMMAND_SEEK_TO_NEXT +
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        }

        /**
         * [getState] calls this. The ids come from [commandIdsIncludingNext].
         */
        internal fun commandsIncludingNext(
            commands: Player.Commands,
            mediaItemCount: Int,
        ): Player.Commands {
            val current = buildSet {
                for (index in 0 until commands.size()) {
                    add(commands.get(index))
                }
            }
            val merged = commandIdsIncludingNext(current, mediaItemCount)
            if (merged == current) return commands
            val builder = commands.buildUpon()
            for (id in merged - current) {
                builder.add(id)
            }
            return builder.build()
        }

        internal fun notificationSeek(seekCommand: Int): NotificationSeek = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> NotificationSeek.Next
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> NotificationSeek.Previous
            else -> NotificationSeek.Delegate
        }
    }
}

package org.stypox.dicio.skills.local_music

import androidx.media3.common.Player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.EndAwarePlayer.NotificationSeek

/**
 * The id set [EndAwarePlayer.getState] installs. [Player.Commands] needs
 * Android's SparseBooleanArray, so this does not build a MediaSession.
 * Notification drawing stays device-only. Command 9 is
 * [Player.COMMAND_SEEK_TO_NEXT], the one MediaControllerImplBase.seekToNext
 * refuses to send when it is missing.
 */
class EndAwarePlayerTest : StringSpec({
    "command 9 is seek to next" {
        Player.COMMAND_SEEK_TO_NEXT shouldBe 9
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM shouldBe 8
        Player.COMMAND_SEEK_TO_PREVIOUS shouldBe 7
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM shouldBe 6
    }

    "last-item snapshot includes seek to next when the queue is not empty" {
        val lastItemWithoutNext = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_PREVIOUS,
        )
        lastItemWithoutNext.contains(Player.COMMAND_SEEK_TO_NEXT) shouldBe false

        val withOne = EndAwarePlayer.commandIdsIncludingNext(lastItemWithoutNext, mediaItemCount = 1)
        withOne.contains(Player.COMMAND_SEEK_TO_NEXT) shouldBe true
        withOne.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) shouldBe true
        withOne.contains(Player.COMMAND_PLAY_PAUSE) shouldBe true
        withOne.contains(Player.COMMAND_SEEK_TO_PREVIOUS) shouldBe true

        val withMany = EndAwarePlayer.commandIdsIncludingNext(lastItemWithoutNext, mediaItemCount = 4)
        withMany.contains(Player.COMMAND_SEEK_TO_NEXT) shouldBe true
        withMany.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) shouldBe true
    }

    "an empty queue does not gain next" {
        val paused = setOf(Player.COMMAND_PLAY_PAUSE)
        val empty = EndAwarePlayer.commandIdsIncludingNext(paused, mediaItemCount = 0)
        empty.contains(Player.COMMAND_SEEK_TO_NEXT) shouldBe false
        empty.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) shouldBe false
        empty shouldBe paused

        EndAwarePlayer.commandIdsIncludingNext(paused, mediaItemCount = -1) shouldBe paused
    }

    "next and previous seeks stay on the transport" {
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_TO_NEXT) shouldBe NotificationSeek.Next
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) shouldBe
            NotificationSeek.Next
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_TO_PREVIOUS) shouldBe
            NotificationSeek.Previous
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) shouldBe
            NotificationSeek.Previous
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) shouldBe
            NotificationSeek.Delegate
        EndAwarePlayer.notificationSeek(Player.COMMAND_SEEK_TO_MEDIA_ITEM) shouldBe
            NotificationSeek.Delegate
    }
})

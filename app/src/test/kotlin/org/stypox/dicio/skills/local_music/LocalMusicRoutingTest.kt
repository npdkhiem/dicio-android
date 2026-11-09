package org.stypox.dicio.skills.local_music

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.local_music.LocalMusicRouting.Target

class LocalMusicRoutingTest : StringSpec({
    data class Case(
        val hasQueue: Boolean,
        val isPlaying: Boolean,
        val millisAgo: Long?,
        val expected: Target,
        val name: String,
    )

    val tenMinutes = LocalMusicRouting.RECENT_ACTIVITY_WINDOW_MS

    val cases = listOf(
        Case(false, false, null, Target.MediaSkill, "idle with no queue"),
        Case(false, false, 0L, Target.MediaSkill, "recent session but no queue"),
        Case(false, true, null, Target.LocalPlayer, "playing even without a recorded session"),
        Case(false, true, tenMinutes + 1, Target.LocalPlayer, "playing wins over a stale session"),
        Case(true, true, null, Target.LocalPlayer, "playing with a queue"),
        Case(true, false, 0L, Target.LocalPlayer, "paused but just active"),
        Case(true, false, tenMinutes, Target.LocalPlayer, "paused and active exactly 10 minutes ago"),
        Case(true, false, tenMinutes - 1, Target.LocalPlayer, "paused and active inside the window"),
        Case(true, false, tenMinutes + 1, Target.MediaSkill, "queue present but session older than 10 minutes"),
        Case(true, false, null, Target.MediaSkill, "queue present but never active"),
    )

    for (case in cases) {
        case.name {
            LocalMusicRouting.targetForGenericMediaCommand(
                localPlayerHasQueue = case.hasQueue,
                localPlayerIsPlaying = case.isPlaying,
                localSessionRecentlyActiveMillisAgo = case.millisAgo,
            ) shouldBe case.expected
        }
    }
})

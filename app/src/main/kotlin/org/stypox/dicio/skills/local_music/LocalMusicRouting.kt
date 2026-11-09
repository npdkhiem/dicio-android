package org.stypox.dicio.skills.local_music

/**
 * Chooses whether a generic media command (play / pause / next / previous)
 * should go to the local player or to MediaSkill's key-event path.
 *
 * Local wins if the local player is currently playing, or if it has a queue and
 * was last active within [RECENT_ACTIVITY_WINDOW_MS] (10 minutes). Otherwise
 * the command stays on MediaSkill.
 */
object LocalMusicRouting {
    const val RECENT_ACTIVITY_WINDOW_MS: Long = 10L * 60L * 1000L

    enum class Target {
        LocalPlayer,
        MediaSkill,
    }

    fun targetForGenericMediaCommand(
        localPlayerHasQueue: Boolean,
        localPlayerIsPlaying: Boolean,
        localSessionRecentlyActiveMillisAgo: Long?,
    ): Target {
        if (localPlayerIsPlaying) return Target.LocalPlayer
        val wasRecentlyActive = localSessionRecentlyActiveMillisAgo != null &&
            localSessionRecentlyActiveMillisAgo <= RECENT_ACTIVITY_WINDOW_MS
        if (localPlayerHasQueue && wasRecentlyActive) return Target.LocalPlayer
        return Target.MediaSkill
    }
}

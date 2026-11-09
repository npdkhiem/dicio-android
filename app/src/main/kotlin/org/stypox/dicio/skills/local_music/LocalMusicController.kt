package org.stypox.dicio.skills.local_music

/**
 * Playback side effects for [LocalMusicSkill], backed by [LocalMusicPlaybackService].
 *
 * [hasQueue], [isPlaying] and [lastActiveElapsedMillis] are the inputs
 * [LocalMusicRouting] will use. They are safe to read off the main thread.
 */
interface LocalMusicController {
    suspend fun playByName(query: String): PlayByNameResult
    suspend fun pause()
    suspend fun resume()

    /**
     * Next track, using [LocalMusicTransport]. On the last item with repeat
     * off or one, stops playback and returns [SkipOutcome.LastSong]. Repeat
     * all wraps. After stop ([androidx.media3.common.Player.STATE_IDLE] or
     * ended), prepares and starts the target. An empty queue returns
     * [SkipOutcome.NothingPlaying].
     */
    suspend fun next(): SkipOutcome

    /** Previous track. Same idle/ended and empty-queue rules as [next]. */
    suspend fun previous(): SkipOutcome
    suspend fun stop()
    suspend fun setRepeat(mode: RepeatMode)

    val hasQueue: Boolean
    val isPlaying: Boolean

    /**
     * Milliseconds since the local session last refreshed its routing window,
     * or null if it has never been active or the window was cleared.
     *
     * Refreshed only by user commands handled here and by track changes while
     * playing. Cleared on permanent audio-focus loss. Not refreshed by passive
     * play-state changes or by media-key commands. See [LocalMusicActivity].
     */
    val lastActiveElapsedMillis: Long?

    /**
     * Stem of the requested song that failed to play since the previous call,
     * or null. See [LocalMusicPlaybackErrors].
     */
    fun consumeCouldNotPlay(): String? = null
}

sealed interface SkipOutcome {
    /** Moved to another item, wrapped, or restarted the current one. */
    data object Advanced : SkipOutcome

    /**
     * Already on the last item and repeat is off or one. Playback was stopped.
     * The skill says "That was the last song".
     */
    data object LastSong : SkipOutcome

    /** No queue. The skill says nothing is playing. */
    data object NothingPlaying : SkipOutcome
}

sealed interface PlayByNameResult {
    /** [name] is the matched file name, including its extension. */
    data class Playing(val name: String) : PlayByNameResult
    data class NoMatch(val query: String) : PlayByNameResult
    data class Tie(val a: String, val b: String) : PlayByNameResult
    data object NoFolderOrEmpty : PlayByNameResult
    data object PermissionMissing : PlayByNameResult

    /**
     * The saved SAF folder grant is gone. Say to pick the folder again in
     * Settings, not that the folder has no songs.
     */
    data object AccessLost : PlayByNameResult
}

/**
 * Used by Compose previews, which build skills without starting playback.
 */
object PreviewLocalMusicController : LocalMusicController {
    override suspend fun playByName(query: String) = PlayByNameResult.NoFolderOrEmpty
    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun next(): SkipOutcome = SkipOutcome.Advanced
    override suspend fun previous(): SkipOutcome = SkipOutcome.Advanced
    override suspend fun stop() = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override val hasQueue: Boolean = false
    override val isPlaying: Boolean = false
    override val lastActiveElapsedMillis: Long? = null
}

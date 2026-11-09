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
    suspend fun next()
    suspend fun previous()
    suspend fun stop()
    suspend fun setRepeat(mode: RepeatMode)

    val hasQueue: Boolean
    val isPlaying: Boolean

    /**
     * Milliseconds since the local session was last controlled or changed
     * tracks, or null if it has never been active.
     */
    val lastActiveElapsedMillis: Long?
}

sealed interface PlayByNameResult {
    /** [name] is the matched file name, including its extension. */
    data class Playing(val name: String) : PlayByNameResult
    data class NoMatch(val query: String) : PlayByNameResult
    data class Tie(val a: String, val b: String) : PlayByNameResult
    data object NoFolderOrEmpty : PlayByNameResult
    data object PermissionMissing : PlayByNameResult
}

/**
 * Used by Compose previews, which build skills without starting playback.
 */
object PreviewLocalMusicController : LocalMusicController {
    override suspend fun playByName(query: String) = PlayByNameResult.NoFolderOrEmpty
    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun next() = Unit
    override suspend fun previous() = Unit
    override suspend fun stop() = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override val hasQueue: Boolean = false
    override val isPlaying: Boolean = false
    override val lastActiveElapsedMillis: Long? = null
}

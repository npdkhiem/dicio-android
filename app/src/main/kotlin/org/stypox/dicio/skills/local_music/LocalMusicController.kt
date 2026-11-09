package org.stypox.dicio.skills.local_music

/**
 * Playback side effects for [LocalMusicSkill]. Phase A uses
 * [StubLocalMusicController]; later phases wire a Media3-backed implementation.
 */
interface LocalMusicController {
    fun play(name: String)
    fun stop()
    fun setRepeat(mode: RepeatMode)
}

object StubLocalMusicController : LocalMusicController {
    override fun play(name: String) = Unit
    override fun stop() = Unit
    override fun setRepeat(mode: RepeatMode) = Unit
}

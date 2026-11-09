package org.stypox.dicio.skills.local_music

/**
 * What [LocalMusicSkill] should say. File names are reduced to stems so speech
 * does not include the extension. A [PlayByNameResult.Tie] becomes a question
 * and is never a [LocalMusicSpeech.Playing].
 */
sealed interface LocalMusicSpeech {
    data class Playing(val name: String) : LocalMusicSpeech
    data class NoMatch(val query: String) : LocalMusicSpeech
    data class Tie(val a: String, val b: String) : LocalMusicSpeech
    data object NoFolderOrEmpty : LocalMusicSpeech
    data object PermissionMissing : LocalMusicSpeech
    data class Repeat(val mode: RepeatMode) : LocalMusicSpeech
    data object Stopped : LocalMusicSpeech
    data object MissingSong : LocalMusicSpeech
}

object LocalMusicSpeechSelection {
    fun forPlayResult(result: PlayByNameResult): LocalMusicSpeech = when (result) {
        is PlayByNameResult.Playing ->
            LocalMusicSpeech.Playing(LocalMusicMatcher.fileStem(result.name))
        is PlayByNameResult.NoMatch -> LocalMusicSpeech.NoMatch(result.query)
        is PlayByNameResult.Tie -> LocalMusicSpeech.Tie(
            LocalMusicMatcher.fileStem(result.a),
            LocalMusicMatcher.fileStem(result.b),
        )
        PlayByNameResult.NoFolderOrEmpty -> LocalMusicSpeech.NoFolderOrEmpty
        PlayByNameResult.PermissionMissing -> LocalMusicSpeech.PermissionMissing
    }
}

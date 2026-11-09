package org.stypox.dicio.skills.local_music

/**
 * Playlist built from a folder listing. Sorting matches [LocalMusicQueue] so
 * ExoPlayer next/previous at the ends follow the same order and repeat rules.
 */
object LocalMusicPlaylist {
    data class Track(
        val displayName: String,
        /** content:// URI string ExoPlayer can open. */
        val uri: String,
    )

    /**
     * Supported audio files only, in [LocalMusicQueue] order.
     * Duplicate display names keep a stable order and their own URIs.
     */
    fun fromTracks(tracks: List<Track>): List<Track> {
        val supported = tracks.filter { LocalMusicMatcher.isSupportedAudioFile(it.displayName) }
        val order = LocalMusicQueue(supported.map { it.displayName }).tracks
        val remaining = supported.toMutableList()
        return order.map { name ->
            val index = remaining.indexOfFirst { it.displayName == name }
            check(index >= 0) { "Sorted playlist lost $name" }
            remaining.removeAt(index)
        }
    }

    fun indexOf(playlist: List<Track>, displayName: String): Int =
        playlist.indexOfFirst { it.displayName == displayName }

    /**
     * Match [query] against display names that are already in playlist order.
     * A tie does not pick an index: nothing should be played.
     */
    fun resolve(query: String, displayNamesInPlaylistOrder: List<String>): ResolvedPlay {
        if (displayNamesInPlaylistOrder.isEmpty()) return ResolvedPlay.Empty
        return when (val match = LocalMusicMatcher.findBestMatches(query, displayNamesInPlaylistOrder)) {
            MatchResult.NoMatch -> ResolvedPlay.NoMatch(query)
            is MatchResult.Tie -> {
                if (match.names.size < 2) {
                    ResolvedPlay.NoMatch(query)
                } else {
                    ResolvedPlay.Tie(match.names[0], match.names[1])
                }
            }
            is MatchResult.Single -> {
                val index = displayNamesInPlaylistOrder.indexOf(match.name)
                if (index < 0) ResolvedPlay.NoMatch(query)
                else ResolvedPlay.Play(match.name, index)
            }
        }
    }

    sealed interface ResolvedPlay {
        data class Play(val fileName: String, val index: Int) : ResolvedPlay
        data class NoMatch(val query: String) : ResolvedPlay
        data class Tie(val a: String, val b: String) : ResolvedPlay
        data object Empty : ResolvedPlay
    }
}

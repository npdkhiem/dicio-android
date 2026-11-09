package org.stypox.dicio.skills.local_music

/**
 * Playlist built from a folder listing. Sorting matches [LocalMusicQueue] so
 * ExoPlayer next/previous at the ends follow the same order and repeat rules.
 *
 * Order is normalized stem, then extension preference
 * (mp3 > m4a > flac > ogg > wav), then the full content URI. That is O(n log n).
 * Two files with the same normalized stem are one voice choice: the first item
 * here. [resolve] returns that item's index in this list.
 */
object LocalMusicPlaylist {
    data class Track(
        val displayName: String,
        /** content:// URI string ExoPlayer can open. */
        val uri: String,
    )

    /**
     * Supported audio files only, in playlist order (see the class KDoc).
     * Duplicate display names are ordered by URI, not by input position.
     */
    fun fromTracks(tracks: List<Track>): List<Track> {
        return tracks
            .filter { LocalMusicMatcher.isSupportedAudioFile(it.displayName) }
            .sortedWith(trackOrder())
    }

    /**
     * Same stem and extension keys as [LocalMusicMatcher.nameOrder]. The URI
     * is the path tie-break, so `Intro.mp3` in two folders has a stable order.
     */
    fun trackOrder(): Comparator<Track> =
        compareBy<Track>(
            { LocalMusicMatcher.normalizeName(LocalMusicMatcher.fileStem(it.displayName)) },
            { LocalMusicMatcher.extensionRank(it.displayName) },
            { it.uri },
        )

    fun indexOf(playlist: List<Track>, displayName: String): Int =
        playlist.indexOfFirst { it.displayName == displayName }

    /**
     * Match [query] against display names that are already in [fromTracks] order.
     *
     * Identical normalized stems are not a tie: [LocalMusicMatcher] picks the
     * first of them, and the index is that file in this list (the preferred
     * extension, then the first URI). A tie is returned only when the two
     * display stems still differ after normalization, and nothing is played.
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

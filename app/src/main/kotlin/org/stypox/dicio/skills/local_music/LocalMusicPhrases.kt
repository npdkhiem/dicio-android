package org.stypox.dicio.skills.local_music

/**
 * Exact phrase forms for "next / previous" and "stop music".
 *
 * The sentence DSL has no negative lookahead, so [play_song] can still capture
 * a skip word ("play song next") and MediaSkill's pause sentence matches
 * "stop the music". Both skills call these checks so the outcome does not
 * depend on which skill wins the ranker.
 *
 * "play next from my music" / "play last from my phone" are title searches
 * ([isPlayFromLibrary]), not skips. Only the "play (the)? next|following|
 * previous|last (song|track|...)?" forms skip.
 *
 * Stop-music phrases are recognized by MediaSkill (`sentences/en/media.yml`),
 * which asks for no storage permission. [isStopMusic] still maps them to the
 * shared stop command. Repeat phrases stay on the local skill and stay
 * permission-gated.
 *
 * Only the whole normalized string counts. Normalization is
 * [LocalMusicMatcher.normalizeName]: lower case, diacritics removed, punctuation
 * turned into spaces. A title that merely contains a skip word is not a skip:
 * "next to me", "last christmas", and "the last song of summer" stay titles.
 * "the last song of summer" is not previous, because of the leading "the" and
 * the trailing "of summer". The utterance "play the last song of summer" is
 * also not an exact skip: local play_song captures "last song of summer" and
 * outscores MediaSkill's previous hit, so the ranker searches for that title.
 *
 * Next captures: `next` or `following`, optionally followed by one of
 * song|track|music|media|audio, or the exact words `next one`.
 * Previous captures: `previous` or `last`, with the same optional noun.
 * There is no optional leading "the" on a capture ("the next song" is a title).
 */
object LocalMusicPhrases {
    enum class TrackSkip {
        Next,
        Previous,
    }

    private const val NOUN = "(?:song|track|music|media|audio)"

    /** Capture text, not a full "play …" utterance. */
    private val CAPTURE_NEXT = Regex("next one|(?:next|following)(?: $NOUN)?")
    private val CAPTURE_PREVIOUS = Regex("(?:previous|last)(?: $NOUN)?")

    /**
     * Full utterances that must skip even if a skill parsed them as play-by-name.
     * Includes "play the next song" and "play song next" / "play track last song".
     */
    private val UTTERANCE_NEXT = Regex(
        "play(?: the)? (?:next|following)(?: $NOUN| one)?|" +
            "play(?: the)? (?:song|track|music) (?:next|following)(?: $NOUN| one)?"
    )
    private val UTTERANCE_PREVIOUS = Regex(
        "play(?: the)? (?:previous|last)(?: $NOUN)?|" +
            "play(?: the)? (?:song|track|music) (?:previous|last)(?: $NOUN)?"
    )

    /**
     * Same shapes as the stop-music line on MediaSkill's pause sentence in
     * `sentences/en/media.yml`.
     */
    private val STOP_MUSIC = Regex(
        "stop(?: playing)?(?: (?:the|my))?(?: local)? music"
    )

    /**
     * "play <title> from my music|phone". The captured title is a search,
     * even when it is the word next, last, previous, or following.
     */
    private val PLAY_FROM_LIBRARY = Regex("play .+ from my (?:music|phone)")

    fun skipForCapture(capture: String?): TrackSkip? {
        val normalized = normalize(capture) ?: return null
        return when {
            CAPTURE_NEXT.matches(normalized) -> TrackSkip.Next
            CAPTURE_PREVIOUS.matches(normalized) -> TrackSkip.Previous
            else -> null
        }
    }

    fun skipForUtterance(utterance: String?): TrackSkip? {
        val normalized = normalize(utterance) ?: return null
        return when {
            UTTERANCE_NEXT.matches(normalized) -> TrackSkip.Next
            UTTERANCE_PREVIOUS.matches(normalized) -> TrackSkip.Previous
            else -> null
        }
    }

    fun isStopMusic(utterance: String?): Boolean {
        val normalized = normalize(utterance) ?: return false
        return STOP_MUSIC.matches(normalized)
    }

    fun isPlayFromLibrary(utterance: String?): Boolean {
        val normalized = normalize(utterance) ?: return false
        return PLAY_FROM_LIBRARY.matches(normalized)
    }

    private fun normalize(raw: String?): String? {
        if (raw == null) return null
        val normalized = LocalMusicMatcher.normalizeName(raw)
        return normalized.ifEmpty { null }
    }
}

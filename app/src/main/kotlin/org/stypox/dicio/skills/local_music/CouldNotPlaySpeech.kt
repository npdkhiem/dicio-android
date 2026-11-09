package org.stypox.dicio.skills.local_music

/**
 * Joins a pending "I couldn't play X" line with the reply for the command
 * that is running now.
 *
 * The failure used to replace that reply, so "play song B" after a corrupt A
 * started B but only said "I couldn't play A". The same drop hit next,
 * no-match, tie, lost access, and a media-key pause. The name is consumed
 * once, at the skill that builds the output, and both lines are spoken:
 * "I couldn't play A. Playing B".
 *
 * [failureLine] is the resolved `skill_local_music_could_not_play` string.
 */
internal object CouldNotPlaySpeech {
    fun join(failureLine: String?, reply: String): String {
        if (failureLine.isNullOrBlank()) return reply
        if (reply.isBlank()) return failureLine
        return "$failureLine. $reply"
    }
}

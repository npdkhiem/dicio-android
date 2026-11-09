package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.LocalMusic
import org.stypox.dicio.skills.media.MediaOutput

class LocalMusicSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<LocalMusic>,
    private val controller: LocalMusicController,
) : Skill<LocalMusicUtterance>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, LocalMusicUtterance> {
        val (score, sentence) = data.score(ctx, input)
        return score to LocalMusicUtterance(sentence, input)
    }

    override suspend fun generateOutput(
        ctx: SkillContext,
        inputData: LocalMusicUtterance,
    ): SkillOutput {
        // Shared with MediaSkill so stop / next / previous do not depend on
        // which skill the ranker picked.
        // A song the user asked for that failed before it reached READY is
        // prepended to this reply and consumed once. The command still runs.
        // See LocalMusicPlaybackErrors and CouldNotPlaySpeech.
        val couldNotPlay = controller.consumeCouldNotPlay()
        LocalMusicCommands.fromLocal(inputData.sentence, inputData.utterance)?.let { command ->
            val speech = SharedPlayback.perform(ctx, controller, command)
            return MediaOutput(speech, couldNotPlay)
        }
        return when (val sentence = inputData.sentence) {
            is LocalMusic.PlaySong -> playByName(sentence, couldNotPlay)
            LocalMusic.RepeatOne -> {
                controller.setRepeat(RepeatMode.ONE)
                localOutput(LocalMusicSpeech.Repeat(RepeatMode.ONE), couldNotPlay)
            }
            LocalMusic.RepeatAll -> {
                controller.setRepeat(RepeatMode.ALL)
                localOutput(LocalMusicSpeech.Repeat(RepeatMode.ALL), couldNotPlay)
            }
            LocalMusic.RepeatOff -> {
                controller.setRepeat(RepeatMode.OFF)
                localOutput(LocalMusicSpeech.Repeat(RepeatMode.OFF), couldNotPlay)
            }
            LocalMusic.Next, LocalMusic.Previous ->
                error("control sentence was not mapped to a command")
        }
    }

    private suspend fun playByName(
        sentence: LocalMusic.PlaySong,
        couldNotPlay: String?,
    ): SkillOutput {
        val query = sentence.song?.trim().orEmpty()
        if (query.isEmpty()) {
            return localOutput(LocalMusicSpeech.MissingSong, couldNotPlay)
        }
        // A tie returns a question and the controller plays nothing.
        val speech = LocalMusicSpeechSelection.forPlayResult(controller.playByName(query))
        return localOutput(speech, couldNotPlay)
    }

    private fun localOutput(speech: LocalMusicSpeech, couldNotPlay: String?): SkillOutput {
        val followUp = if (speech is LocalMusicSpeech.Tie) {
            LocalMusicOutput.FollowUp(correspondingSkillInfo, controller)
        } else {
            null
        }
        return LocalMusicOutput(
            speech = speech,
            couldNotPlay = couldNotPlay,
            followUp = followUp,
        )
    }
}

data class LocalMusicUtterance(
    val sentence: LocalMusic,
    val utterance: String,
)

package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.LocalMusic
import org.stypox.dicio.skills.media.MediaOutput
import org.stypox.dicio.skills.media.PlaybackSpeech

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
        // A song the user asked for that failed to decode is reported on this
        // reply. The command below still runs. See LocalMusicPlaybackErrors.
        val couldNotPlay = controller.consumeCouldNotPlay()
        LocalMusicCommands.fromLocal(inputData.sentence, inputData.utterance)?.let { command ->
            val speech = SharedPlayback.perform(ctx, controller, command)
            return MediaOutput(couldNotPlay?.let(PlaybackSpeech::CouldNotPlay) ?: speech)
        }
        if (couldNotPlay != null) {
            when (val sentence = inputData.sentence) {
                is LocalMusic.PlaySong -> playByName(sentence)
                LocalMusic.RepeatOne -> controller.setRepeat(RepeatMode.ONE)
                LocalMusic.RepeatAll -> controller.setRepeat(RepeatMode.ALL)
                LocalMusic.RepeatOff -> controller.setRepeat(RepeatMode.OFF)
                LocalMusic.Next, LocalMusic.Previous ->
                    error("control sentence was not mapped to a command")
            }
            return LocalMusicOutput(LocalMusicSpeech.CouldNotPlay(couldNotPlay))
        }
        return when (val sentence = inputData.sentence) {
            is LocalMusic.PlaySong -> playByName(sentence)
            LocalMusic.RepeatOne -> {
                controller.setRepeat(RepeatMode.ONE)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.ONE))
            }
            LocalMusic.RepeatAll -> {
                controller.setRepeat(RepeatMode.ALL)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.ALL))
            }
            LocalMusic.RepeatOff -> {
                controller.setRepeat(RepeatMode.OFF)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.OFF))
            }
            LocalMusic.Next, LocalMusic.Previous ->
                error("control sentence was not mapped to a command")
        }
    }

    private suspend fun playByName(sentence: LocalMusic.PlaySong): SkillOutput {
        val query = sentence.song?.trim().orEmpty()
        if (query.isEmpty()) {
            return LocalMusicOutput(LocalMusicSpeech.MissingSong)
        }
        // A tie returns a question and the controller plays nothing.
        val speech = LocalMusicSpeechSelection.forPlayResult(controller.playByName(query))
        return LocalMusicOutput(speech)
    }
}

data class LocalMusicUtterance(
    val sentence: LocalMusic,
    val utterance: String,
)

package org.stypox.dicio.skills.media

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.local_music.LocalMusicCommands
import org.stypox.dicio.skills.local_music.LocalMusicController
import org.stypox.dicio.skills.local_music.SharedPlayback

class MediaSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Media>,
    private val controller: LocalMusicController,
) : Skill<MediaUtterance>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, MediaUtterance> {
        val (score, sentence) = data.score(ctx, input)
        return score to MediaUtterance(sentence, input)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: MediaUtterance): SkillOutput {
        // Consumed once here. SharedPlayback does not consume it, so a pause
        // aimed at another app still says both "I couldn't play X" and the
        // pause line. See CouldNotPlaySpeech.
        val couldNotPlay = controller.consumeCouldNotPlay()
        val command = LocalMusicCommands.fromMedia(inputData.sentence, inputData.utterance)
        return MediaOutput(SharedPlayback.perform(ctx, controller, command), couldNotPlay)
    }
}

/**
 * [sentence] is the grammar hit. [utterance] is the raw input, because English
 * "stop the music" and "pause the song" are both [Media.Pause], and some
 * next/previous phrases can also score as play.
 */
data class MediaUtterance(
    val sentence: Media,
    val utterance: String,
)

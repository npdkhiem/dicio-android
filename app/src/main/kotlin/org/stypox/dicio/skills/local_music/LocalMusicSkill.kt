package org.stypox.dicio.skills.local_music

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.LocalMusic

class LocalMusicSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<LocalMusic>,
    private val controller: LocalMusicController,
) : StandardRecognizerSkill<LocalMusic>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: LocalMusic): SkillOutput {
        return when (inputData) {
            is LocalMusic.PlaySong -> {
                val query = inputData.song?.trim().orEmpty()
                if (query.isEmpty()) {
                    LocalMusicOutput(LocalMusicSpeech.MissingSong)
                } else {
                    // A tie returns a question and the controller plays nothing.
                    val speech = LocalMusicSpeechSelection.forPlayResult(
                        controller.playByName(query),
                    )
                    LocalMusicOutput(speech)
                }
            }
            is LocalMusic.Stop -> {
                controller.stop()
                LocalMusicOutput(LocalMusicSpeech.Stopped)
            }
            is LocalMusic.RepeatOne -> {
                controller.setRepeat(RepeatMode.ONE)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.ONE))
            }
            is LocalMusic.RepeatAll -> {
                controller.setRepeat(RepeatMode.ALL)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.ALL))
            }
            is LocalMusic.RepeatOff -> {
                controller.setRepeat(RepeatMode.OFF)
                LocalMusicOutput(LocalMusicSpeech.Repeat(RepeatMode.OFF))
            }
        }
    }
}

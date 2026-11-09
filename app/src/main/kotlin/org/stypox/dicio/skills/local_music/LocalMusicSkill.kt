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
    private val controller: LocalMusicController = StubLocalMusicController,
) : StandardRecognizerSkill<LocalMusic>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: LocalMusic): SkillOutput {
        return when (inputData) {
            is LocalMusic.PlaySong -> {
                val name = inputData.song?.trim().orEmpty()
                if (name.isEmpty()) {
                    LocalMusicOutput(LocalMusicOutput.Action.MissingSong)
                } else {
                    controller.play(name)
                    LocalMusicOutput(LocalMusicOutput.Action.WouldPlay(name))
                }
            }
            is LocalMusic.Stop -> {
                controller.stop()
                LocalMusicOutput(LocalMusicOutput.Action.WouldStop)
            }
            is LocalMusic.RepeatOne -> {
                controller.setRepeat(RepeatMode.ONE)
                LocalMusicOutput(LocalMusicOutput.Action.WouldSetRepeat(RepeatMode.ONE))
            }
            is LocalMusic.RepeatAll -> {
                controller.setRepeat(RepeatMode.ALL)
                LocalMusicOutput(LocalMusicOutput.Action.WouldSetRepeat(RepeatMode.ALL))
            }
            is LocalMusic.RepeatOff -> {
                controller.setRepeat(RepeatMode.OFF)
                LocalMusicOutput(LocalMusicOutput.Action.WouldSetRepeat(RepeatMode.OFF))
            }
        }
    }
}

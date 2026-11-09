package org.stypox.dicio.skills.local_music

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SkillSettingsLocalMusicSerializer : Serializer<SkillSettingsLocalMusic> {
    override val defaultValue: SkillSettingsLocalMusic =
        SkillSettingsLocalMusic.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SkillSettingsLocalMusic {
        try {
            return SkillSettingsLocalMusic.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: SkillSettingsLocalMusic, output: OutputStream) {
        t.writeTo(output)
    }
}

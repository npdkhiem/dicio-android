package org.stypox.dicio.settings.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object WordListsSerializer : Serializer<WordLists> {
    override val defaultValue: WordLists = WordLists.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): WordLists {
        try {
            return WordLists.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: WordLists, output: OutputStream) {
        t.writeTo(output)
    }
}

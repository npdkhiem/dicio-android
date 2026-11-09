package org.stypox.dicio.settings.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class WordListsModule {
    @Provides
    @Singleton
    fun provideWordListsDataStore(@ApplicationContext context: Context): DataStore<WordLists> {
        return DataStoreFactory.create(
            serializer = WordListsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler {
                WordListsSerializer.defaultValue
            },
            produceFile = {
                context.dataStoreFile("word_lists.pb")
            },
        )
    }

    companion object {
        fun newDataStoreForPreviews(): DataStore<WordLists> {
            return object : DataStore<WordLists> {
                private val _data = MutableStateFlow(WordListsSerializer.defaultValue)
                override val data: Flow<WordLists> = _data

                override suspend fun updateData(
                    transform: suspend (t: WordLists) -> WordLists
                ): WordLists {
                    val newData = transform(_data.value)
                    _data.value = newData
                    return newData
                }
            }
        }
    }
}

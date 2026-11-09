package org.stypox.dicio.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.dicio.skills.local_music.LocalMusicController
import org.stypox.dicio.skills.local_music.LocalMusicControllerImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class LocalMusicModule {
    @Provides
    @Singleton
    fun provideLocalMusicController(impl: LocalMusicControllerImpl): LocalMusicController = impl
}

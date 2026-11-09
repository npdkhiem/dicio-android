package org.stypox.dicio.io.input.android_stt

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.stypox.dicio.skills.local_music.LocalMusicInfo.Companion.localMusicDataStore
import org.stypox.dicio.skills.local_music.LocalMusicLibrary

/**
 * Titles for recognizer hints. The wake path only reads [cachedTitles].
 * A scan runs in the background, and only when nothing is cached yet.
 * This app does not store play counts, so titles are alphabetical stems.
 */
@Singleton
class MusicTitleCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var titles: List<String>? = null

    private var loading = false

    /** Empty when no index has been loaded yet. Does not scan. */
    fun cachedTitles(): List<String> = titles ?: emptyList()

    /** Remember titles from a scan that already happened. */
    fun rememberDisplayNames(displayNames: List<String>) {
        val ordered = AndroidSttHints.orderMusicTitles(displayNames)
        synchronized(lock) { titles = ordered }
    }

    /**
     * Start one background index if none is loaded. Safe to call often.
     * Does not block, and does not run again while a scan is in flight or
     * after titles are already known.
     */
    fun warmUp() {
        synchronized(lock) {
            if (titles != null || loading) return
            loading = true
        }
        scope.launch {
            try {
                val treeUri = runCatching {
                    context.localMusicDataStore.data.first().musicFolderTreeUri.trim()
                }.getOrDefault("")
                val names = LocalMusicLibrary.list(context, treeUri).map { it.displayName }
                synchronized(lock) {
                    if (titles == null) {
                        titles = AndroidSttHints.orderMusicTitles(names)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.i(TAG, "Music title index not loaded")
            } finally {
                synchronized(lock) { loading = false }
            }
        }
    }

    private companion object {
        const val TAG = "MusicTitleCache"
    }
}

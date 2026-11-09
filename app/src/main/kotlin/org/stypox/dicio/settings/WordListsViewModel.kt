package org.stypox.dicio.settings

import android.app.Application
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.R
import org.stypox.dicio.io.input.corrections.WordListFile
import org.stypox.dicio.io.input.corrections.WordListsRepository
import org.stypox.dicio.settings.datastore.WordLists
import org.stypox.dicio.skills.local_music.LocalMusicMatcher
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
class WordListsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<WordLists>,
) : AndroidViewModel(application) {
    val wordLists = dataStore.data.toStateFlowDistinctBlockingFirst(viewModelScope)
    val snackbarHostState = SnackbarHostState()

    private val _importPrompt = MutableStateFlow<WordListFile.Result?>(null)
    val importPrompt: StateFlow<WordListFile.Result?> = _importPrompt

    private var undoJob: Job? = null

    fun setEnabled(enabled: Boolean) {
        update { it.setDisabled(!enabled) }
    }

    fun setStrictness(percent: Int) {
        update { it.setStrictnessPercent(WordListsRepository.snapStrictness(percent)) }
    }

    fun saveWord(index: Int?, text: String) {
        val trimmed = singleLine(text)
        if (trimmed.isEmpty() || LocalMusicMatcher.normalizeName(trimmed).isEmpty()) return
        viewModelScope.launch {
            val current = dataStore.data.first()
            val key = LocalMusicMatcher.normalizeName(trimmed)
            val duplicate = current.knownWordsList.any { LocalMusicMatcher.normalizeName(it) == key }
            if (index == null && !duplicate && current.knownWordsCount >= WordListFile.MAX_WORDS) {
                showFull()
                return@launch
            }
            dataStore.updateData { lists ->
                val rows = lists.knownWordsList.toMutableList()
                if (index != null && index in rows.indices) {
                    rows[index] = trimmed
                } else {
                    rows.add(trimmed)
                }
                lists.toBuilder()
                    .clearKnownWords()
                    .addAllKnownWords(dedupeWords(rows))
                    .build()
            }
        }
    }

    fun saveCorrection(index: Int?, heard: String, correct: String) {
        val heardTrimmed = singleLine(heard)
        val correctTrimmed = singleLine(correct)
        if (heardTrimmed.isEmpty() || correctTrimmed.isEmpty()) return
        if (LocalMusicMatcher.normalizeName(heardTrimmed).isEmpty()) return
        viewModelScope.launch {
            val current = dataStore.data.first()
            val key = LocalMusicMatcher.normalizeName(heardTrimmed)
            val duplicate = current.correctionsList.any {
                LocalMusicMatcher.normalizeName(it.heard) == key
            }
            if (index == null && !duplicate &&
                current.correctionsCount >= WordListFile.MAX_CORRECTIONS
            ) {
                showFull()
                return@launch
            }
            dataStore.updateData { lists ->
                val rows = lists.correctionsList.map {
                    WordListFile.Correction(it.heard, it.correct)
                }.toMutableList()
                val edited = WordListFile.Correction(heardTrimmed, correctTrimmed)
                if (index != null && index in rows.indices) {
                    rows[index] = edited
                } else {
                    rows.add(edited)
                }
                lists.toBuilder()
                    .clearCorrections()
                    .addAllCorrections(dedupeCorrections(rows).map { it.toProto() })
                    .build()
            }
        }
    }

    fun deleteWord(index: Int) {
        val word = wordLists.value.knownWordsList.getOrNull(index) ?: return
        offerUndo(
            delete = {
                dataStore.updateData { lists ->
                    val rows = lists.knownWordsList.toMutableList()
                    if (rows.getOrNull(index) == word) {
                        rows.removeAt(index)
                    } else {
                        val at = rows.indexOf(word)
                        if (at >= 0) rows.removeAt(at)
                    }
                    lists.toBuilder().clearKnownWords().addAllKnownWords(rows).build()
                }
            },
            undo = {
                dataStore.updateData { lists ->
                    if (lists.knownWordsList.any {
                            LocalMusicMatcher.normalizeName(it) == LocalMusicMatcher.normalizeName(word)
                        }
                    ) {
                        return@updateData lists
                    }
                    val rows = lists.knownWordsList.toMutableList()
                    rows.add(index.coerceIn(0, rows.size), word)
                    lists.toBuilder().clearKnownWords().addAllKnownWords(rows).build()
                }
            },
        )
    }

    fun deleteCorrection(index: Int) {
        val correction = wordLists.value.correctionsList.getOrNull(index) ?: return
        val heard = correction.heard
        val correct = correction.correct
        offerUndo(
            delete = {
                dataStore.updateData { lists ->
                    val rows = lists.correctionsList.toMutableList()
                    if (index in rows.indices &&
                        rows[index].heard == heard &&
                        rows[index].correct == correct
                    ) {
                        rows.removeAt(index)
                    }
                    lists.toBuilder()
                        .clearCorrections()
                        .addAllCorrections(rows)
                        .build()
                }
            },
            undo = {
                dataStore.updateData { lists ->
                    if (lists.correctionsList.any {
                            LocalMusicMatcher.normalizeName(it.heard) ==
                                LocalMusicMatcher.normalizeName(heard)
                        }
                    ) {
                        return@updateData lists
                    }
                    val row = WordLists.Correction.newBuilder()
                        .setHeard(heard)
                        .setCorrect(correct)
                        .build()
                    val rows = lists.correctionsList.toMutableList()
                    rows.add(index.coerceIn(0, rows.size), row)
                    lists.toBuilder().clearCorrections().addAllCorrections(rows).build()
                }
            },
        )
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readUtf8(uri) }
            if (text == null) {
                snackbarHostState.showSnackbar(
                    getApplication<Application>().getString(R.string.word_lists_import_failed)
                )
                return@launch
            }
            _importPrompt.value = WordListFile.parse(text)
        }
    }

    fun dismissImport() {
        _importPrompt.value = null
    }

    fun commitImport(replace: Boolean) {
        val parsed = _importPrompt.value ?: return
        _importPrompt.value = null
        viewModelScope.launch {
            dataStore.updateData { current ->
                val merged = if (replace) {
                    WordListFile.Lists(parsed.words, parsed.corrections)
                } else {
                    WordListFile.merge(
                        WordListFile.Lists(
                            current.knownWordsList,
                            current.correctionsList.map {
                                WordListFile.Correction(it.heard, it.correct)
                            },
                        ),
                        WordListFile.Lists(parsed.words, parsed.corrections),
                    )
                }
                current.toBuilder()
                    .clearKnownWords()
                    .addAllKnownWords(merged.words)
                    .clearCorrections()
                    .addAllCorrections(merged.corrections.map { it.toProto() })
                    .build()
            }
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val lists = dataStore.data.first()
            val text = WordListFile.export(
                lists.knownWordsList,
                lists.correctionsList.map { WordListFile.Correction(it.heard, it.correct) },
            )
            val wrote = withContext(Dispatchers.IO) { writeUtf8(uri, text) }
            if (!wrote) {
                snackbarHostState.showSnackbar(
                    getApplication<Application>().getString(R.string.word_lists_export_failed)
                )
            }
        }
    }

    private fun offerUndo(delete: suspend () -> Unit, undo: suspend () -> Unit) {
        undoJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        val message = getApplication<Application>().getString(R.string.word_lists_deleted)
        val action = getApplication<Application>().getString(R.string.word_lists_undo)
        undoJob = viewModelScope.launch {
            delete()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = action,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                undo()
            }
        }
    }

    private suspend fun showFull() {
        snackbarHostState.showSnackbar(
            getApplication<Application>().getString(
                R.string.word_lists_full,
                WordListFile.MAX_WORDS,
            )
        )
    }

    private fun update(transform: (WordLists.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData { lists ->
                lists.toBuilder().apply(transform).build()
            }
        }
    }

    private fun readUtf8(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes, StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUtf8(uri: Uri, text: String): Boolean {
        return try {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: return false
            stream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun singleLine(text: String): String {
        return text.replace("\n", "").replace("\r", "").trim()
            .take(WordListFile.MAX_LINE_LENGTH)
    }

    private fun dedupeWords(rows: List<String>): List<String> {
        val pos = LinkedHashMap<String, Int>()
        val out = ArrayList<String>()
        for (row in rows) {
            val key = LocalMusicMatcher.normalizeName(row)
            if (key.isEmpty()) continue
            val at = pos[key]
            if (at == null) {
                pos[key] = out.size
                out.add(row)
            } else {
                out[at] = row
            }
        }
        return out.take(WordListFile.MAX_WORDS)
    }

    private fun dedupeCorrections(
        rows: List<WordListFile.Correction>,
    ): List<WordListFile.Correction> {
        val pos = LinkedHashMap<String, Int>()
        val out = ArrayList<WordListFile.Correction>()
        for (row in rows) {
            val key = LocalMusicMatcher.normalizeName(row.heard)
            if (key.isEmpty() || row.correct.isBlank()) continue
            val at = pos[key]
            if (at == null) {
                pos[key] = out.size
                out.add(row)
            } else {
                out[at] = row
            }
        }
        return out.take(WordListFile.MAX_CORRECTIONS)
    }

    private fun WordListFile.Correction.toProto(): WordLists.Correction {
        return WordLists.Correction.newBuilder()
            .setHeard(heard)
            .setCorrect(correct)
            .build()
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }
}

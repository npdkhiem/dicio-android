package org.stypox.dicio.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.stypox.dicio.R
import org.stypox.dicio.io.input.corrections.TranscriptCorrector
import org.stypox.dicio.io.input.corrections.WordListFile
import org.stypox.dicio.io.input.corrections.WordListsRepository
import org.stypox.dicio.settings.datastore.WordLists
import org.stypox.dicio.settings.datastore.WordListsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.ui.theme.AppTheme
import kotlin.math.roundToInt

private const val TAB_WORDS = 0
private const val TAB_CORRECTIONS = 1

@Composable
fun WordListsScreen(
    navigationIcon: @Composable () -> Unit,
    viewModel: WordListsViewModel = hiltViewModel(),
) {
    val lists by viewModel.wordLists.collectAsState()
    val importPrompt by viewModel.importPrompt.collectAsState()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.word_lists_title)) },
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("word_lists_overflow"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.word_lists_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.word_lists_export)) },
                            onClick = {
                                menuOpen = false
                                exportLauncher.launch("dicio-words.txt")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.word_lists_import)) },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(
                                    arrayOf(
                                        "text/*",
                                        "text/plain",
                                        "text/csv",
                                        "text/comma-separated-values",
                                        "application/csv",
                                    )
                                )
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        WordListsContent(
            lists = lists,
            onEnabled = viewModel::setEnabled,
            onStrictness = viewModel::setStrictness,
            onSaveWord = viewModel::saveWord,
            onSaveCorrection = viewModel::saveCorrection,
            onDeleteWord = viewModel::deleteWord,
            onDeleteCorrection = viewModel::deleteCorrection,
            modifier = Modifier.padding(padding),
        )
    }

    importPrompt?.let { parsed ->
        ImportPromptDialog(
            parsed = parsed,
            onReplace = { viewModel.commitImport(replace = true) },
            onAdd = { viewModel.commitImport(replace = false) },
            onCancel = viewModel::dismissImport,
        )
    }
}

@Composable
private fun WordListsContent(
    lists: WordLists,
    onEnabled: (Boolean) -> Unit,
    onStrictness: (Int) -> Unit,
    onSaveWord: (Int?, String) -> Unit,
    onSaveCorrection: (Int?, String, String) -> Unit,
    onDeleteWord: (Int) -> Unit,
    onDeleteCorrection: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !lists.disabled
    val strictness = WordListsRepository.resolvedStrictness(lists.strictnessPercent)
    var tab by rememberSaveable { mutableIntStateOf(TAB_WORDS) }
    var tryText by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    val corrected = if (!enabled || tryText.isEmpty()) {
        tryText
    } else {
        TranscriptCorrector.correct(
            text = tryText,
            knownWords = lists.knownWordsList,
            corrections = lists.correctionsList.map { it.heard to it.correct },
            strictnessPercent = strictness,
        ) ?: tryText
    }

    LazyColumn(modifier = modifier) {
        item {
            SettingsItem(
                modifier = Modifier
                    .clickable { onEnabled(!enabled) }
                    .testTag("word_lists_enabled"),
                title = stringResource(R.string.word_lists_enabled),
                description = stringResource(
                    if (enabled) R.string.word_lists_enabled_on
                    else R.string.word_lists_enabled_off
                ),
                content = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabled,
                    )
                },
            )
        }
        item {
            StrictnessSlider(
                strictness = strictness,
                onStrictness = onStrictness,
            )
        }
        item {
            TryItField(
                text = tryText,
                onText = { tryText = it },
                result = corrected,
            )
        }
        item {
            @OptIn(ExperimentalMaterial3Api::class)
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == TAB_WORDS,
                    onClick = { tab = TAB_WORDS },
                    text = { Text(stringResource(R.string.word_lists_tab_words)) },
                )
                Tab(
                    selected = tab == TAB_CORRECTIONS,
                    onClick = { tab = TAB_CORRECTIONS },
                    text = { Text(stringResource(R.string.word_lists_tab_corrections)) },
                )
            }
        }
        item {
            Button(
                onClick = {
                    editor = if (tab == TAB_WORDS) {
                        EditorTarget.Word(index = null, text = "")
                    } else {
                        EditorTarget.Correction(index = null, heard = "", correct = "")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("word_lists_add"),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(R.string.word_lists_add),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (tab == TAB_WORDS) {
            if (lists.knownWordsCount == 0) {
                item { EmptyLine(stringResource(R.string.word_lists_empty_words)) }
            } else {
                itemsIndexed(lists.knownWordsList) { index, word ->
                    WordRow(
                        text = word,
                        onClick = { editor = EditorTarget.Word(index, word) },
                        onDelete = { onDeleteWord(index) },
                    )
                }
            }
        } else if (lists.correctionsCount == 0) {
            item { EmptyLine(stringResource(R.string.word_lists_empty_corrections)) }
        } else {
            itemsIndexed(lists.correctionsList) { index, correction ->
                CorrectionRow(
                    heard = correction.heard,
                    correct = correction.correct,
                    onClick = {
                        editor = EditorTarget.Correction(
                            index,
                            correction.heard,
                            correction.correct,
                        )
                    },
                    onDelete = { onDeleteCorrection(index) },
                )
            }
        }
    }

    editor?.let { target ->
        EditorDialog(
            target = target,
            onDismiss = { editor = null },
            onSaveWord = { index, text ->
                onSaveWord(index, text)
                editor = null
            },
            onSaveCorrection = { index, heard, correct ->
                onSaveCorrection(index, heard, correct)
                editor = null
            },
        )
    }
}

@Composable
private fun StrictnessSlider(
    strictness: Int,
    onStrictness: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("word_lists_strictness"),
    ) {
        Text(
            text = stringResource(R.string.word_lists_strictness),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.word_lists_strictness_value, strictness),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = strictness.toFloat(),
            onValueChange = { raw ->
                val snapped = WordListsRepository.snapStrictness(raw.roundToInt())
                if (snapped != strictness) onStrictness(snapped)
            },
            valueRange = WordListsRepository.MIN_STRICTNESS.toFloat()..
                WordListsRepository.MAX_STRICTNESS.toFloat(),
            steps = (WordListsRepository.MAX_STRICTNESS - WordListsRepository.MIN_STRICTNESS) /
                WordListsRepository.STRICTNESS_STEP - 1,
        )
        Text(
            text = stringResource(R.string.word_lists_strictness_help),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TryItField(
    text: String,
    onText: (String) -> Unit,
    result: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("word_lists_try"),
            label = { Text(stringResource(R.string.word_lists_try)) },
            placeholder = { Text(stringResource(R.string.word_lists_try_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 2,
        )
        Text(
            text = stringResource(R.string.word_lists_try_result),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = result.ifEmpty { " " },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun WordRow(
    text: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.word_lists_delete),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun CorrectionRow(
    heard: String,
    correct: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        ) {
            Text(text = heard, style = MaterialTheme.typography.bodyLarge)
            Text(text = correct, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.word_lists_delete),
            )
        }
    }
    HorizontalDivider()
}

private sealed interface EditorTarget {
    val index: Int?

    data class Word(override val index: Int?, val text: String) : EditorTarget
    data class Correction(
        override val index: Int?,
        val heard: String,
        val correct: String,
    ) : EditorTarget
}

@Composable
private fun EditorDialog(
    target: EditorTarget,
    onDismiss: () -> Unit,
    onSaveWord: (Int?, String) -> Unit,
    onSaveCorrection: (Int?, String, String) -> Unit,
) {
    when (target) {
        is EditorTarget.Word -> {
            var text by rememberSaveable(target.index, target.text) { mutableStateOf(target.text) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        stringResource(
                            if (target.index == null) R.string.word_lists_add_word
                            else R.string.word_lists_edit_word
                        )
                    )
                },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = limitLine(it) },
                        label = { Text(stringResource(R.string.word_lists_word_label)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onSaveWord(target.index, text) },
                    ) {
                        Text(
                            stringResource(R.string.word_lists_save),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.word_lists_cancel),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
            )
        }
        is EditorTarget.Correction -> {
            var heard by rememberSaveable(target.index, target.heard) {
                mutableStateOf(target.heard)
            }
            var correct by rememberSaveable(target.index, target.correct) {
                mutableStateOf(target.correct)
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        stringResource(
                            if (target.index == null) R.string.word_lists_add_correction
                            else R.string.word_lists_edit_correction
                        )
                    )
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = heard,
                            onValueChange = { heard = limitLine(it) },
                            label = { Text(stringResource(R.string.word_lists_heard_label)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = correct,
                            onValueChange = { correct = limitLine(it) },
                            label = { Text(stringResource(R.string.word_lists_correct_label)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onSaveCorrection(target.index, heard, correct) },
                    ) {
                        Text(
                            stringResource(R.string.word_lists_save),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.word_lists_cancel),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ImportPromptDialog(
    parsed: WordListFile.Result,
    onReplace: () -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val skipped = parsed.skippedLineNumbers
    val shown = if (skipped.size <= 12) {
        skipped.joinToString(", ")
    } else {
        skipped.take(12).joinToString(", ") + "…"
    }
    val summary = when (skipped.size) {
        0 -> stringResource(
            R.string.word_lists_import_summary_none,
            parsed.wordCount,
            parsed.correctionCount,
        )
        1 -> stringResource(
            R.string.word_lists_import_summary_one,
            parsed.wordCount,
            parsed.correctionCount,
            shown,
        )
        else -> stringResource(
            R.string.word_lists_import_summary_many,
            parsed.wordCount,
            parsed.correctionCount,
            skipped.size,
            shown,
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.word_lists_import_title)) },
        text = { Text(summary, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onReplace) {
                    Text(
                        stringResource(R.string.word_lists_import_replace),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = onAdd) {
                    Text(
                        stringResource(R.string.word_lists_import_add),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(
                        stringResource(R.string.word_lists_cancel),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
    )
}

private fun limitLine(value: String): String {
    return value.replace("\n", "").replace("\r", "").take(WordListFile.MAX_LINE_LENGTH)
}

@Preview
@Composable
private fun WordListsScreenPreview() {
    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            WordListsScreen(
                navigationIcon = {},
                viewModel = WordListsViewModel(
                    application = Application(),
                    dataStore = newDataStoreForPreviews(),
                ),
            )
        }
    }
}

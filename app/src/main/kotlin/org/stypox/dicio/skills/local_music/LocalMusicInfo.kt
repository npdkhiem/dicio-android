package org.stypox.dicio.skills.local_music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.util.PERMISSION_READ_EXTERNAL_STORAGE
import org.stypox.dicio.util.PERMISSION_READ_MEDIA_AUDIO

/**
 * Storage permission is always requested: [PERMISSION_READ_MEDIA_AUDIO] on API 33+
 * and [PERMISSION_READ_EXTERNAL_STORAGE] below. A SAF folder does not strictly
 * need it, but [neededPermissions] is a static list checked before every run
 * (the same shape as [org.stypox.dicio.skills.telephone.TelephoneInfo]), so the
 * permission cannot depend on whether a tree URI is saved. The controller only
 * enforces it for the default MediaStore Music/ path.
 *
 * The chosen SAF folder is not scanned recursively. The default Music library
 * does include subfolders. See [LocalMusicLibrary].
 */
class LocalMusicInfo(
    private val controller: LocalMusicController,
) : SkillInfo("local_music") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_local_music)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_local_music)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.QueueMusic)

    override val neededPermissions: List<Permission> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(PERMISSION_READ_MEDIA_AUDIO)
        } else {
            listOf(PERMISSION_READ_EXTERNAL_STORAGE)
        }

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.LocalMusic[ctx.sentencesLanguage] ?: return null
        return LocalMusicSkill(this, data, controller)
    }

    override val renderSettings: @Composable () -> Unit get() = @Composable {
        val context = LocalContext.current
        val dataStore = context.localMusicDataStore
        val data by dataStore.data.collectAsState(SkillSettingsLocalMusicSerializer.defaultValue)
        val scope = rememberCoroutineScope()
        val treeUri = data.musicFolderTreeUri
        val pickedName = remember(treeUri) {
            if (treeUri.isEmpty()) null else LocalMusicLibrary.displayNameOfTree(context, treeUri)
        }
        val folderLabel = when {
            treeUri.isEmpty() -> stringResource(R.string.pref_local_music_folder_default)
            pickedName != null -> pickedName
            else -> stringResource(R.string.pref_local_music_folder_unknown)
        }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val persisted = try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist music folder permission", e)
                false
            }
            if (!persisted) return@rememberLauncherForActivityResult
            scope.launch {
                val previous = context.localMusicDataStore.data.first().musicFolderTreeUri
                if (previous.isNotEmpty() && previous != uri.toString()) {
                    releaseTreePermission(context, previous)
                }
                context.localMusicDataStore.updateData { settings ->
                    settings.toBuilder()
                        .setMusicFolderTreeUri(uri.toString())
                        .build()
                }
            }
        }

        Column {
            SettingsItem(
                title = stringResource(R.string.pref_local_music_folder),
                description = folderLabel,
            )
            SettingsItem(
                title = stringResource(R.string.pref_local_music_choose_folder),
                description = stringResource(R.string.pref_local_music_folder_note),
                modifier = Modifier.clickable {
                    val initial = treeUri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                    launcher.launch(initial)
                },
            )
            SettingsItem(
                title = stringResource(R.string.pref_local_music_use_default_folder),
                modifier = Modifier.clickable {
                    scope.launch {
                        val previous = context.localMusicDataStore.data.first().musicFolderTreeUri
                        if (previous.isNotEmpty()) {
                            releaseTreePermission(context, previous)
                        }
                        context.localMusicDataStore.updateData { settings ->
                            settings.toBuilder()
                                .setMusicFolderTreeUri("")
                                .build()
                        }
                    }
                },
            )
        }
    }

    companion object {
        private const val TAG = "LocalMusicInfo"

        // DataStore keeps a single store per file name. No Hilt needed, same as weather.
        internal val Context.localMusicDataStore by dataStore(
            fileName = "skill_settings_local_music.pb",
            serializer = SkillSettingsLocalMusicSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler {
                SkillSettingsLocalMusicSerializer.defaultValue
            },
        )

        private fun releaseTreePermission(context: Context, uriString: String) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not release music folder permission", e)
            }
        }
    }
}

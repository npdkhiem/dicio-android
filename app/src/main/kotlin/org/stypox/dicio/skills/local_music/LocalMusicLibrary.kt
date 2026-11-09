package org.stypox.dicio.skills.local_music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

/**
 * Lists audio files for the local music skill.
 *
 * SAF tree (non-blank [treeUri]): direct children of that folder only.
 * [DocumentFile.listFiles] is not recursive, so songs in subfolders are
 * ignored. That is also what the settings screen tells the user.
 *
 * Default (blank URI): the public Music library via MediaStore. On API 29+
 * the selection is `RELATIVE_PATH LIKE 'Music/%'`, which includes subfolders.
 * On older APIs the selection is the `DATA` path under
 * [Environment.DIRECTORY_MUSIC], which also includes subfolders. Names are
 * [MediaStore.Audio.Media.DISPLAY_NAME], then filtered with
 * [LocalMusicMatcher.isSupportedAudioFile].
 */
object LocalMusicLibrary {
    fun list(context: Context, treeUri: String): List<LocalMusicPlaylist.Track> {
        return if (treeUri.isBlank()) {
            listMediaStore(context)
        } else {
            listTree(context, Uri.parse(treeUri))
        }
    }

    /**
     * Human-readable folder name for a persisted tree URI, or null when [treeUri]
     * is blank or the name cannot be read.
     */
    fun displayNameOfTree(context: Context, treeUri: String): String? {
        if (treeUri.isBlank()) return null
        val uri = Uri.parse(treeUri)
        val fromDocument = runCatching {
            DocumentFile.fromTreeUri(context, uri)?.name
        }.getOrNull()
        if (!fromDocument.isNullOrBlank()) return fromDocument
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val raw = documentId
            ?.substringAfter(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return raw?.let(Uri::decode)
    }

    private fun listTree(context: Context, treeUri: Uri): List<LocalMusicPlaylist.Track> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (!root.exists() || !root.isDirectory) return emptyList()
        // listFiles() returns only the immediate children.
        return root.listFiles().mapNotNull { child ->
            if (!child.isFile) return@mapNotNull null
            val name = child.name ?: return@mapNotNull null
            if (!LocalMusicMatcher.isSupportedAudioFile(name)) return@mapNotNull null
            LocalMusicPlaylist.Track(displayName = name, uri = child.uri.toString())
        }
    }

    private fun listMediaStore(context: Context): List<LocalMusicPlaylist.Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val (selection, selectionArgs) = musicSelection()
        val tracks = mutableListOf<LocalMusicPlaylist.Track>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!LocalMusicMatcher.isSupportedAudioFile(name)) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                tracks.add(LocalMusicPlaylist.Track(displayName = name, uri = uri.toString()))
            }
        }
        return tracks
    }

    private fun musicSelection(): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Music/%")
        } else {
            @Suppress("DEPRECATION")
            val musicPath = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC,
            ).absolutePath
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("$musicPath/%")
        }
    }
}

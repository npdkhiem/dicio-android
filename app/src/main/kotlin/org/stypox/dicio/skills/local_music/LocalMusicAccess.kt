package org.stypox.dicio.skills.local_music

/**
 * How a failed folder listing is reported.
 *
 * A saved SAF tree that throws [SecurityException], or whose URI is no longer
 * in the persisted permission list, is [PlayByNameResult.AccessLost]: the
 * grant was revoked. Say to pick the folder again in Settings. A security
 * failure with no saved tree is the storage-permission path. Any other
 * listing failure stays "no songs".
 */
object LocalMusicAccess {
    fun failureForList(savedTree: Boolean, securityException: Boolean): PlayByNameResult =
        when {
            savedTree && securityException -> PlayByNameResult.AccessLost
            securityException -> PlayByNameResult.PermissionMissing
            else -> PlayByNameResult.NoFolderOrEmpty
        }
}

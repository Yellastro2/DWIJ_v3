package com.yellastrodev.dwij.navigation

/** Платформенные действия shared-route локальной медиатеки. */
interface LocalLibraryPlatform {
    fun startLocalLibrarySync()

    fun openPlayer()

    fun openPlaylist(playlistId: String)

    fun closeScreen()

    fun showTrackHideFailed()
}

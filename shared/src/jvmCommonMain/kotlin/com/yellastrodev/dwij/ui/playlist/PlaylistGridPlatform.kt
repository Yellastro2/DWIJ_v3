package com.yellastrodev.dwij.ui.playlist

/**
 * Системные возможности, необходимые shared-route плейлистов.
 *
 * Контракт не содержит навигацию: открытие экранов и возврат принадлежат общему NavHost.
 */
interface PlaylistGridPlatform {
    fun hasLocalMusicAccess(): Boolean

    suspend fun requestLocalMusicAccess(): Boolean

    fun startLocalLibrarySync()
}

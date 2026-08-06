package com.yellastrodev.dwij.ui.playlist

/**
 * Только те действия экрана плейлистов, которые исполняются конкретной платформой.
 *
 * Экран сам решает, когда запросить доступ, запустить синхронизацию или открыть плейлист.
 * Платформа лишь выполняет принятое экраном решение.
 */
interface PlaylistGridPlatform {
    fun hasLocalMusicAccess(): Boolean

    suspend fun requestLocalMusicAccess(): Boolean

    fun startLocalLibrarySync()

    fun openYandexPlaylist(playlistId: String)

    fun openLocalPlaylist(playlistId: String)

    fun closeScreen()
}
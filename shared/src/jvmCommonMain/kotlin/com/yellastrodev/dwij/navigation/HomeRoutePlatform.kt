package com.yellastrodev.dwij.navigation

/**
 * Платформенные действия домашнего маршрута для локальной медиатеки.
 *
 * Shared-route самостоятельно решает, когда менять источник. Платформа только
 * проверяет и запрашивает разрешение, а затем запускает синхронизацию.
 */
interface HomeRoutePlatform {
    fun hasLocalMusicAccess(): Boolean

    suspend fun requestLocalMusicAccess(): Boolean

    fun startLocalLibrarySync()
}

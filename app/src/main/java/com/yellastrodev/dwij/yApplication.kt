package com.yellastrodev.dwij

import android.app.Application

import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.AndroidPlayerServiceRegistry

/**
 * Тонкая Android-точка сборки приложения.
 *
 * Общий граф, YamApiClient, восстановление сессии и запуск общей логики
 * находятся в shared. Здесь остаётся только создание Android-адаптеров.
 */
class yApplication : Application() {

    val playerServiceRegistry =
        AndroidPlayerServiceRegistry()

    val component: DwijComponent by lazy {
        AndroidDwijComponentFactory(
            application = this,
            playerServiceRegistry =
                playerServiceRegistry,
        ).create()
    }

    override fun onCreate() {
        super.onCreate()
        component.start()
    }
}

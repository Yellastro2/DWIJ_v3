package com.yellastrodev.dwij

import android.app.Application

import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.AndroidPlayerServiceRegistry
import com.yellastrodev.dwij.util.AppSessionLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Тонкая Android-точка сборки приложения.
 *
 * Общий граф, YamApiClient, восстановление сессии и запуск общей логики
 * находятся в shared. Здесь остаётся только создание Android-адаптеров.
 */
class yApplication : Application() {

    private val applicationScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO,
        )

    val playerServiceRegistry =
        AndroidPlayerServiceRegistry()

    val component: DwijComponent by lazy {
        AndroidDwijComponentFactory(
            application = this,
            playerServiceRegistry =
                playerServiceRegistry,
        ).create()
    }

    /** Запускает журнал процесса до инициализации общего графа приложения. */
    override fun onCreate() {
        super.onCreate()
        AppSessionLogStore.start(
            this,
            applicationScope,
        )
        component.start()
    }
}

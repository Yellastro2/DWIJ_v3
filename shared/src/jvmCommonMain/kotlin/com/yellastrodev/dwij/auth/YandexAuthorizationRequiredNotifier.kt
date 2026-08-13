package com.yellastrodev.dwij.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Передаёт корню приложения запрос показать повторную авторизацию Яндекс Музыки. */
class YandexAuthorizationRequiredNotifier {

    private val mutableEvents =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
        )

    val events: SharedFlow<Unit> =
        mutableEvents.asSharedFlow()

    fun notifyRequired() {
        mutableEvents.tryEmit(Unit)
    }
}

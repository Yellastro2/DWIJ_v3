package com.yellastrodev.dwij.desktop

import com.yellastrodev.yamusicsdk.YamLogger

/**
 * Пишет desktop-логи в консоль и файл текущей сессии.
 */
class YamLoggerDesktop(
    private val sessionLogStore: DesktopSessionLogStore,
) : YamLogger {

    override fun info(
        tag: String,
        message: String,
    ) {
        sessionLogStore.write(
            level = "I",
            tag = tag,
            message = message,
        )
    }

    override fun debug(
        tag: String,
        message: String,
    ) {
        sessionLogStore.write(
            level = "D",
            tag = tag,
            message = message,
        )
    }

    override fun warning(
        tag: String,
        message: String,
    ) {
        sessionLogStore.write(
            level = "W",
            tag = tag,
            message = message,
        )
    }

    override fun error(
        tag: String,
        message: String,
        cause: Throwable?,
    ) {
        sessionLogStore.write(
            level = "E",
            tag = tag,
            message = message,
            cause = cause,
        )
    }
}

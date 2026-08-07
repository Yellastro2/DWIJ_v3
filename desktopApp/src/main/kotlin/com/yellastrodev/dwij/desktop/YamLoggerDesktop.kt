package com.yellastrodev.dwij.desktop

import com.yellastrodev.yandexmusiclib.YamLogger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Простой stdout/stderr logger для desktop-прототипа.
 */
class YamLoggerDesktop : YamLogger {

    override fun info(
        tag: String,
        message: String,
    ) {
        printLine(
            level = "I",
            tag = tag,
            message = message,
        )
    }

    override fun debug(
        tag: String,
        message: String,
    ) {
        printLine(
            level = "D",
            tag = tag,
            message = message,
        )
    }

    override fun warning(
        tag: String,
        message: String,
    ) {
        printLine(
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
        System.err.println(
            format(
                level = "E",
                tag = tag,
                message = message,
            ),
        )

        cause?.printStackTrace(
            System.err,
        )
    }

    private fun printLine(
        level: String,
        tag: String,
        message: String,
    ) {
        println(
            format(
                level = level,
                tag = tag,
                message = message,
            ),
        )
    }

    private fun format(
        level: String,
        tag: String,
        message: String,
    ): String =
        "${LocalDateTime.now().format(TIME_FORMAT)} $level/$tag: $message"

    private companion object {
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern(
                "HH:mm:ss.SSS",
            )
    }
}

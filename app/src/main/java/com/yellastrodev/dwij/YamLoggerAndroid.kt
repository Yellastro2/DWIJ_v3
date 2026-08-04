package com.yellastrodev.dwij

import android.util.Log
import com.yellastrodev.dwij.utils.DwLogger
import com.yellastrodev.yandexmusiclib.YamLogger

class YamLoggerAndroid : YamLogger, DwLogger {

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun warning(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun error(
        tag: String,
        message: String,
        cause: Throwable?
    ) {
        if (cause == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, cause)
        }
    }
}
package com.yellastrodev.yandexmusiclib

interface YamLogger {
    fun info(tag: String, message: String)
    fun debug(tag: String, message: String)
    fun warning(tag: String, message: String)
    fun error(tag: String, message: String, cause: Throwable? = null)
}

object NoOpYamLogger : YamLogger {
    override fun info(tag: String, message: String) = Unit
    override fun debug(tag: String, message: String) = Unit
    override fun warning(tag: String, message: String) = Unit
    override fun error(tag: String, message: String, cause: Throwable?) = Unit
}
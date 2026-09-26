package com.yellastrodev.dwij.playback

import kotlinx.coroutines.flow.StateFlow

/** Платформенное объявление HTTP-пульта через DNS-SD; имя сервиса не является DNS-именем хоста. */
interface HttpMediaServiceAdvertiser {
    /** Фактическое имя после разрешения конфликтов; null до регистрации и после остановки. */
    val serviceName: StateFlow<String?>

    /** Объявляет сервис асинхронно, используя открытый порт и доступный локальный адрес. */
    fun start(port: Int, address: String?)

    /** Снимает объявление асинхронно, не задерживая интерфейс настроек. */
    fun stop()

    companion object {
        const val SERVICE_NAME = "DWIJ"
        const val SERVICE_TYPE = "_http._tcp."
    }
}

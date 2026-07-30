package com.yellastrodev.yandexmusiclib.network

/**
 * Единый тип результата для запросов Яндекс Музыки.
 */
sealed interface YamResult<out T> {
    data class Success<T>(val value: T) : YamResult<T>
    data class Failure(val error: YamError) : YamResult<Nothing>
}

/**
 * Ошибки транспортного слоя и декодирования ответов Яндекс Музыки.
 */
sealed interface YamError {
    data object Unauthorized : YamError
    data object NoInternet : YamError
    data object Timeout : YamError

    data class Http(
        val statusCode: Int,
        val code: String? = null,
        val description: String? = null
    ) : YamError

    data class InvalidResponse(val cause: Throwable) : YamError
    data class Network(val cause: Throwable) : YamError
}

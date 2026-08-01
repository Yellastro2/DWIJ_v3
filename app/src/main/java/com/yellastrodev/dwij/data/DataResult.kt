package com.yellastrodev.dwij.data

import com.yellastrodev.yandexmusiclib.network.YamError

/** Результат app-data слоя, не привязанный к конкретному поставщику музыки. */
sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
}

/** Ошибки, которые репозитории приложения могут вернуть вызывающему слою. */
sealed interface DataError {
    data object Unauthorized : DataError
    data object NoInternet : DataError
    data object Timeout : DataError

    data class NotFound(
        val entity: String,
        val id: String
    ) : DataError

    data class InvalidData(
        val message: String,
        val cause: Throwable? = null
    ) : DataError

    data class Remote(
        val statusCode: Int,
        val code: String? = null,
        val description: String? = null
    ) : DataError

    data class Network(val cause: Throwable) : DataError
    data class Storage(val cause: Throwable) : DataError
    data class Unknown(val cause: Throwable) : DataError
}

inline fun <T, R> DataResult<T>.map(
    transform: (T) -> R
): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(value))
    is DataResult.Failure -> this
}

fun YamError.toDataError(): DataError = when (this) {
    YamError.Unauthorized -> DataError.Unauthorized
    YamError.NoInternet -> DataError.NoInternet
    YamError.Timeout -> DataError.Timeout
    is YamError.Http -> DataError.Remote(
        statusCode = statusCode,
        code = code,
        description = description
    )
    is YamError.InvalidResponse -> DataError.InvalidData(
        message = cause.message ?: "Некорректный ответ удалённого источника",
        cause = cause
    )
    is YamError.Network -> DataError.Network(cause)
}

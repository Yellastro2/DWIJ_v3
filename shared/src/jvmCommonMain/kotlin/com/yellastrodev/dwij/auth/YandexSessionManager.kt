package com.yellastrodev.dwij.auth

import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamLogger
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult

/**
 * Единственная shared-точка владения YamApiClient и авторизацией.
 *
 * Восстанавливает сохранённую сессию, при необходимости запрашивает uid,
 * обновляет живой клиент после входа и очищает его после выхода.
 */
class YandexSessionManager private constructor(
    private val store: YandexSessionStore,
    val client: YamApiClient,
    private val logger: YamLogger,
) {

    fun currentLogin(): String? {
        val session = store.read()
            ?: return null

        if (session.accessToken.isBlank()) {
            return null
        }

        return session.login
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_LOGIN
    }

    fun save(session: YandexSession) {
        store.write(session)

        client.updateAuthorization(
            token = session.accessToken,
            userId = session.userId.orEmpty(),
            login = session.login.orEmpty(),
        )

        logger.info(
            TAG,
            "[save] Авторизация Яндекс Музыки обновлена",
        )
    }

    fun clear() {
        store.clear()
        client.clearAuthorization()

        logger.info(
            TAG,
            "[clear] Авторизация Яндекс Музыки очищена",
        )
    }

    companion object {
        private const val TAG = "YandexSessionManager"
        private const val DEFAULT_LOGIN = "nologin"

        /**
         * Создаёт клиент из сохранённой сессии.
         *
         * Старые установки без сохранённого uid автоматически дополняются
         * через account/status. При ошибке создаётся неавторизованный клиент,
         * а сохранённая сессия остаётся для следующей попытки запуска.
         */
        suspend fun create(
            store: YandexSessionStore,
            logger: YamLogger,
        ): YandexSessionManager {
            val saved = store.read()

            if (
                saved == null ||
                saved.accessToken.isBlank()
            ) {
                logger.info(
                    TAG,
                    "[create] Сохранённая авторизация отсутствует",
                )

                return YandexSessionManager(
                    store = store,
                    client = emptyClient(logger),
                    logger = logger,
                )
            }

            val savedUserId =
                saved.userId
                    ?.takeIf(String::isNotBlank)

            if (savedUserId != null) {
                return YandexSessionManager(
                    store = store,
                    client = YamApiClient(
                        accessToken = saved.accessToken,
                        userId = savedUserId,
                        logger = logger,
                    ),
                    logger = logger,
                )
            }

            val bootstrapClient = YamApiClient(
                accessToken = saved.accessToken,
                userId = "",
                logger = logger,
            )

            return when (
                val statusResult =
                    bootstrapClient.accountStatus()
            ) {
                is YamResult.Success -> {
                    val account =
                        statusResult.value.account

                    val resolvedUserId =
                        account
                            ?.uid
                            ?.toString()
                            ?.takeIf(String::isNotBlank)

                    if (resolvedUserId == null) {
                        logger.error(
                            TAG,
                            "[create] В account/status отсутствует uid",
                        )

                        YandexSessionManager(
                            store = store,
                            client = emptyClient(logger),
                            logger = logger,
                        )
                    } else {
                        val restoredSession = saved.copy(
                            userId = resolvedUserId,
                            login =
                                account.login
                                    ?.takeIf(String::isNotBlank)
                                    ?: saved.login,
                        )

                        store.write(restoredSession)

                        logger.info(
                            TAG,
                            "[create] Старая сессия дополнена uid",
                        )

                        YandexSessionManager(
                            store = store,
                            client = YamApiClient(
                                accessToken =
                                    restoredSession.accessToken,
                                userId = resolvedUserId,
                                logger = logger,
                            ),
                            logger = logger,
                        )
                    }
                }

                is YamResult.Failure -> {
                    logger.error(
                        TAG,
                        "[create] Не удалось восстановить авторизацию: " +
                            statusResult.error.safeName(),
                    )

                    YandexSessionManager(
                        store = store,
                        client = emptyClient(logger),
                        logger = logger,
                    )
                }
            }
        }

        private fun emptyClient(
            logger: YamLogger,
        ): YamApiClient =
            YamApiClient(
                accessToken = "",
                userId = "",
                logger = logger,
            )

        private fun YamError.safeName(): String =
            when (this) {
                YamError.Unauthorized ->
                    "Unauthorized"

                YamError.NoInternet ->
                    "NoInternet"

                YamError.Timeout ->
                    "Timeout"

                is YamError.Http ->
                    "Http($statusCode)"

                is YamError.InvalidResponse ->
                    "InvalidResponse"

                is YamError.Network ->
                    "Network"
            }
    }
}

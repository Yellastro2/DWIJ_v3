package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.YA_ID
import com.yellastrodev.dwij.YA_LOGIN
import com.yellastrodev.dwij.YA_REFRESH_TOKEN
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.YA_TOKEN_EXPIRES_AT
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.auth.YandexSessionStore

/**
 * Прежнее plaintext-хранилище авторизации Яндекс Музыки.
 *
 * Сохранено только для переноса существующих установок в защищённое
 * платформенное хранилище и не должно использоваться для новых записей.
 */
class StoredYandexSessionStore(
    private val storage: LocalKeyValueStore,
) : YandexSessionStore {

    override fun read(): YandexSession? {
        val accessToken =
            storage
                .getString(YA_TOKEN)
                ?.takeIf(String::isNotBlank)
                ?: return null

        return YandexSession(
            accessToken = accessToken,
            refreshToken =
                storage.getString(
                    YA_REFRESH_TOKEN,
                ),
            expiresAtMillis =
                storage.getLong(
                    YA_TOKEN_EXPIRES_AT,
                ),
            login =
                storage.getString(
                    YA_LOGIN,
                ),
            userId =
                storage.getString(
                    YA_ID,
                ),
        )
    }

    override fun write(
        session: YandexSession,
    ) {
        storage.edit {
            putString(
                YA_TOKEN,
                session.accessToken,
            )

            val refreshToken =
                session.refreshToken

            if (refreshToken == null) {
                remove(YA_REFRESH_TOKEN)
            } else {
                putString(
                    YA_REFRESH_TOKEN,
                    refreshToken,
                )
            }

            val expiresAtMillis =
                session.expiresAtMillis

            if (expiresAtMillis == null) {
                remove(YA_TOKEN_EXPIRES_AT)
            } else {
                putLong(
                    YA_TOKEN_EXPIRES_AT,
                    expiresAtMillis,
                )
            }

            val login =
                session.login

            if (login == null) {
                remove(YA_LOGIN)
            } else {
                putString(
                    YA_LOGIN,
                    login,
                )
            }

            val userId =
                session.userId

            if (userId == null) {
                remove(YA_ID)
            } else {
                putString(
                    YA_ID,
                    userId,
                )
            }
        }
    }

    override fun clear() {
        storage.edit {
            remove(YA_TOKEN)
            remove(YA_REFRESH_TOKEN)
            remove(YA_TOKEN_EXPIRES_AT)
            remove(YA_LOGIN)
            remove(YA_ID)
        }
    }
}

package com.yellastrodev.dwij

import android.content.Context
import android.preference.PreferenceManager
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.auth.YandexSessionStore

/** Shared-сессия Яндекс Музыки поверх Android SharedPreferences. */
@Suppress("DEPRECATION")
class AndroidYandexSessionStore(
    context: Context,
) : YandexSessionStore {

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(
            context.applicationContext,
        )

    override fun read(): YandexSession? {
        val accessToken =
            preferences
                .getString(
                    YA_TOKEN,
                    null,
                )
                ?.takeIf(String::isNotBlank)
                ?: return null

        return YandexSession(
            accessToken = accessToken,
            refreshToken =
                preferences.getString(
                    YA_REFRESH_TOKEN,
                    null,
                ),
            expiresAtMillis =
                preferences
                    .takeIf {
                        it.contains(
                            YA_TOKEN_EXPIRES_AT,
                        )
                    }
                    ?.getLong(
                        YA_TOKEN_EXPIRES_AT,
                        0L,
                    ),
            login =
                preferences.getString(
                    YA_LOGIN,
                    null,
                ),
            userId =
                preferences.getString(
                    YA_ID,
                    null,
                ),
        )
    }

    override fun write(session: YandexSession) {
        val refreshToken = session.refreshToken
        val expiresAtMillis = session.expiresAtMillis
        val login = session.login
        val userId = session.userId

        val editor =
            preferences.edit()
                .putString(
                    YA_TOKEN,
                    session.accessToken,
                )

        if (refreshToken == null) {
            editor.remove(YA_REFRESH_TOKEN)
        } else {
            editor.putString(
                YA_REFRESH_TOKEN,
                refreshToken,
            )
        }

        if (expiresAtMillis == null) {
            editor.remove(YA_TOKEN_EXPIRES_AT)
        } else {
            editor.putLong(
                YA_TOKEN_EXPIRES_AT,
                expiresAtMillis,
            )
        }

        if (login == null) {
            editor.remove(YA_LOGIN)
        } else {
            editor.putString(
                YA_LOGIN,
                login,
            )
        }

        if (userId == null) {
            editor.remove(YA_ID)
        } else {
            editor.putString(
                YA_ID,
                userId,
            )
        }

        editor.apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(YA_TOKEN)
            .remove(YA_REFRESH_TOKEN)
            .remove(YA_TOKEN_EXPIRES_AT)
            .remove(YA_LOGIN)
            .remove(YA_ID)
            .apply()
    }
}

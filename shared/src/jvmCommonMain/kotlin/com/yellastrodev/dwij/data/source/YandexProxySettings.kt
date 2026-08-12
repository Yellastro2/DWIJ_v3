package com.yellastrodev.dwij.storage

import com.yellastrodev.yamusicsdk.network.YamProxyConfig
import com.yellastrodev.yamusicsdk.network.YamProxyType
import java.net.URI

/**
 * Хранит адрес и состояние HTTP-прокси Яндекс Музыки.
 *
 * Адрес сохраняется обычной строкой, включая учётные данные, если они указаны.
 */
class YandexProxySettings(
    private val storage: LocalKeyValueStore,
) {

    var url: String
        get() =
            storage
                .getString(KEY_URL)
                .orEmpty()

        set(value) {
            val normalized =
                value.trim()

            storage.edit {
                if (normalized.isEmpty()) {
                    remove(KEY_URL)
                } else {
                    putString(
                        KEY_URL,
                        normalized,
                    )
                }
            }
        }

    var enabled: Boolean
        get() =
            storage.getBoolean(KEY_ENABLED)
                ?: false

        set(value) {
            storage.edit {
                putBoolean(
                    KEY_ENABLED,
                    value,
                )
            }
        }

    /** Возвращает активный конфиг либо null для выключенного/некорректного прокси. */
    fun activeConfigOrNull(): YamProxyConfig? =
        if (enabled) {
            parseConfig(url)
        } else {
            null
        }

    /** Разбирает строку вида `http://[user:password@]host:port`. */
    fun parseConfig(
        value: String,
    ): YamProxyConfig? {
        val normalized =
            value.trim()

        if (normalized.isEmpty()) {
            return null
        }

        val uri =
            runCatching {
                URI(normalized)
            }.getOrNull()
                ?: return null

        if (
            !uri.scheme.equals(
                HTTP_SCHEME,
                ignoreCase = true,
            ) ||
            uri.host.isNullOrBlank() ||
            uri.port !in MIN_PORT..MAX_PORT ||
            (!uri.path.isNullOrEmpty() && uri.path != "/") ||
            uri.query != null ||
            uri.fragment != null
        ) {
            return null
        }

        val userInfo =
            uri.userInfo

        val username =
            userInfo
                ?.substringBefore(':')
                ?.takeIf(String::isNotEmpty)

        if (userInfo != null && username == null) {
            return null
        }

        val password =
            userInfo
                ?.takeIf { ':' in it }
                ?.substringAfter(':')

        return runCatching {
            YamProxyConfig(
                host = uri.host,
                port = uri.port,
                type = YamProxyType.HTTP,
                username = username,
                password = password,
            )
        }.getOrNull()
    }

    private companion object {
        const val KEY_URL =
            "yandex_proxy_url"

        const val KEY_ENABLED =
            "yandex_proxy_enabled"

        const val HTTP_SCHEME =
            "http"

        const val MIN_PORT =
            1

        const val MAX_PORT =
            65_535
    }
}

package com.yellastrodev.dwij.storage

import com.yellastrodev.yamusicsdk.network.YamProxyConfig
import com.yellastrodev.yamusicsdk.network.YamProxyType
import java.net.URI

/**
 * Хранит выбранный тип, отдельные адреса HTTP/SOCKS5 и состояние прокси Яндекс Музыки.
 *
 * Адрес сохраняется обычной строкой, включая учётные данные, если они указаны.
 */
class YandexProxySettings(
    private val storage: LocalKeyValueStore,
) {

    var selectedType: YamProxyType
        get() =
            storage
                .getString(KEY_SELECTED_TYPE)
                ?.let(::typeFromStorage)
                ?: YamProxyType.HTTP

        set(value) {
            storage.edit {
                putString(
                    KEY_SELECTED_TYPE,
                    value.storageValue,
                )
            }
        }

    /** Адрес выбранного режима. Старый HTTP-ключ сохраняется для миграции без потери данных. */
    var url: String
        get() = urlFor(selectedType)
        set(value) {
            setUrl(
                type = selectedType,
                value = value,
            )
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
            parseConfig(
                value = url,
                type = selectedType,
            )
        } else {
            null
        }

    /** Возвращает последнее сохранённое значение для указанного режима. */
    fun urlFor(
        type: YamProxyType,
    ): String =
        storage
            .getString(type.urlStorageKey)
            .orEmpty()

    /** Сохраняет адрес независимо от адреса второго режима. */
    fun setUrl(
        type: YamProxyType,
        value: String,
    ) {
        val normalized =
            value.trim()

        storage.edit {
            if (normalized.isEmpty()) {
                remove(type.urlStorageKey)
            } else {
                putString(
                    type.urlStorageKey,
                    normalized,
                )
            }
        }
    }

    /**
     * Выбирает режим и отключает активный прокси, если его сохранённый адрес некорректен.
     * Возвращает разобранный конфиг независимо от текущего состояния [enabled].
     */
    fun selectType(
        type: YamProxyType,
    ): YamProxyConfig? {
        selectedType =
            type

        val config =
            parseConfig(
                value = urlFor(type),
                type = type,
            )

        if (enabled && config == null) {
            enabled =
                false
        }

        return config
    }

    /** Разбирает строку выбранного режима: HTTP или SOCKS5. */
    fun parseConfig(
        value: String,
        type: YamProxyType = selectedType,
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
                type.uriScheme,
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
                type = type,
                username = username,
                password = password,
            )
        }.getOrNull()
    }

    private companion object {
        const val KEY_URL =
            "yandex_proxy_url"

        const val KEY_SOCKS5_URL =
            "yandex_proxy_socks5_url"

        const val KEY_SELECTED_TYPE =
            "yandex_proxy_type"

        const val KEY_ENABLED =
            "yandex_proxy_enabled"

        const val MIN_PORT =
            1

        const val MAX_PORT =
            65_535

        val YamProxyType.urlStorageKey: String
            get() =
                when (this) {
                    YamProxyType.HTTP -> KEY_URL
                    YamProxyType.SOCKS -> KEY_SOCKS5_URL
                }

        val YamProxyType.uriScheme: String
            get() =
                when (this) {
                    YamProxyType.HTTP -> "http"
                    YamProxyType.SOCKS -> "socks5"
                }

        val YamProxyType.storageValue: String
            get() = uriScheme

        fun typeFromStorage(
            value: String,
        ): YamProxyType? =
            when {
                value.equals("http", ignoreCase = true) ->
                    YamProxyType.HTTP
                value.equals("socks5", ignoreCase = true) ||
                    value.equals("socks", ignoreCase = true) ->
                    YamProxyType.SOCKS
                else ->
                    null
            }
    }
}

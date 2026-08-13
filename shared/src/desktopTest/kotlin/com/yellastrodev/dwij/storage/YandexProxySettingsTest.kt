package com.yellastrodev.dwij.storage

import com.yellastrodev.yamusicsdk.network.YamProxyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YandexProxySettingsTest {

    @Test
    fun `старый HTTP адрес остаётся доступен после обновления`() {
        val settings = YandexProxySettings(
            FakeLocalKeyValueStore(
                strings = mutableMapOf(
                    "yandex_proxy_url" to "http://proxy.example:8080",
                ),
            ),
        )

        assertEquals(YamProxyType.HTTP, settings.selectedType)
        assertEquals("http://proxy.example:8080", settings.url)
    }

    @Test
    fun `HTTP и SOCKS5 сохраняются независимо`() {
        val settings = YandexProxySettings(FakeLocalKeyValueStore())

        settings.setUrl(YamProxyType.HTTP, "http://http.example:8080")
        settings.setUrl(
            YamProxyType.SOCKS,
            "socks5://user:password@socks.example:1080",
        )

        assertEquals(
            "http://http.example:8080",
            settings.urlFor(YamProxyType.HTTP),
        )
        assertEquals(
            "socks5://user:password@socks.example:1080",
            settings.urlFor(YamProxyType.SOCKS),
        )
    }

    @Test
    fun `SOCKS5 строка создаёт SOCKS конфиг`() {
        val settings = YandexProxySettings(FakeLocalKeyValueStore())

        val config = settings.parseConfig(
            value = "socks5://user:password@socks.example:1080",
            type = YamProxyType.SOCKS,
        )

        assertEquals(YamProxyType.SOCKS, config?.type)
        assertEquals("socks.example", config?.host)
        assertEquals(1_080, config?.port)
        assertEquals("user", config?.username)
        assertEquals("password", config?.password)
    }

    @Test
    fun `схема другого режима отклоняется`() {
        val settings = YandexProxySettings(FakeLocalKeyValueStore())

        assertNull(
            settings.parseConfig(
                value = "http://proxy.example:8080",
                type = YamProxyType.SOCKS,
            ),
        )
    }

    @Test
    fun `переход на режим с некорректным адресом выключает прокси`() {
        val settings = YandexProxySettings(FakeLocalKeyValueStore())
        settings.setUrl(YamProxyType.HTTP, "http://proxy.example:8080")
        settings.enabled = true

        val selectedConfig = settings.selectType(YamProxyType.SOCKS)

        assertNull(selectedConfig)
        assertEquals(YamProxyType.SOCKS, settings.selectedType)
        assertFalse(settings.enabled)
    }

    @Test
    fun `переход на валидный режим сохраняет включённое состояние`() {
        val settings = YandexProxySettings(FakeLocalKeyValueStore())
        settings.setUrl(
            YamProxyType.SOCKS,
            "socks5://socks.example:1080",
        )
        settings.enabled = true

        val selectedConfig = settings.selectType(YamProxyType.SOCKS)

        assertEquals(YamProxyType.SOCKS, selectedConfig?.type)
        assertTrue(settings.enabled)
    }

    private class FakeLocalKeyValueStore(
        private val strings: MutableMap<String, String> = mutableMapOf(),
    ) : LocalKeyValueStore {
        private val longs = mutableMapOf<String, Long>()
        private val booleans = mutableMapOf<String, Boolean>()

        override fun getString(key: String): String? = strings[key]

        override fun getLong(key: String): Long? = longs[key]

        override fun getBoolean(key: String): Boolean? = booleans[key]

        override fun edit(
            block: LocalKeyValueStore.Editor.() -> Unit,
        ) {
            block(
                object : LocalKeyValueStore.Editor {
                    override fun putString(key: String, value: String) {
                        strings[key] = value
                    }

                    override fun putLong(key: String, value: Long) {
                        longs[key] = value
                    }

                    override fun putBoolean(key: String, value: Boolean) {
                        booleans[key] = value
                    }

                    override fun remove(key: String) {
                        strings -= key
                        longs -= key
                        booleans -= key
                    }
                },
            )
        }
    }
}

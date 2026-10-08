package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.RadialMenuCollection
import com.yellastrodev.dwij.RadialMenuTarget
import com.yellastrodev.dwij.defaultRadialPrimaryTarget
import com.yellastrodev.dwij.data.entities.MusicSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Проверяет восстановление назначений после перезапуска и сохранность неподдерживаемой записи. */
class RadialMenuSettingsStoreTest {
    /** Все виды назначений и источники плейлистов восстанавливаются в прежних позициях. */
    @Test
    fun `назначения переживают создание нового хранилища`() {
        val storage = MemoryStorage()
        val settings = RadialMenuSettingsStore(storage)
        val expected = listOf(
            RadialMenuTarget.Playlist(MusicSource.LOCAL, "local-playlist"),
            RadialMenuTarget.Playlist(MusicSource.YANDEX, "playlist-uuid", "Мой плейлист"),
            RadialMenuTarget.Playlist(MusicSource.VK, "123_456"),
            RadialMenuTarget.YandexWave("artist:123", "Волна артиста"),
            RadialMenuTarget.Collection(RadialMenuCollection.MIXED_RECOMMENDATIONS),
        )
        expected.forEachIndexed { index, target -> settings.setTarget(index, target) }

        assertEquals(expected, settings.targets.value)
        assertEquals(expected, RadialMenuSettingsStore(storage).targets.value)
    }

    /** Старые назначения без подписей остаются читаемыми после добавления title. */
    @Test
    fun `версия один без подписей продолжает читаться`() {
        val storage = MemoryStorage()
        val raw = """{"version":1,"targets":[
            {"type":"playlist","source":"LOCAL","key":"local-playlist"},
            {"type":"yandex_wave","seed":"user:onyourwave"},
            {"type":"collection","kind":"YANDEX_LIKED"},
            {"type":"collection","kind":"VK_RECOMMENDATIONS"},
            {"type":"collection","kind":"VK_ALL_TRACKS"}
        ]}"""
        storage.edit { putString("radial_menu_targets", raw) }
        val settings = RadialMenuSettingsStore(storage)
        val targets = settings.targets.value
        assertEquals(RadialMenuTarget.Playlist(MusicSource.LOCAL, "local-playlist"), targets[0])
        assertEquals(RadialMenuTarget.YandexWave("user:onyourwave"), targets[1])
        assertEquals(defaultRadialPrimaryTarget(), settings.primaryTarget.value)
        assertEquals(raw, storage.getString("radial_menu_targets"))
    }

    /** Изменение центра не сдвигает сектора и переживает последующую запись сектора. */
    @Test
    fun `центральное действие сохраняется независимо от секторов`() {
        val storage = MemoryStorage()
        val settings = RadialMenuSettingsStore(storage)
        val sectors = settings.targets.value
        val primary = RadialMenuTarget.Playlist(MusicSource.VK, "123_456", "Мой плейлист")
        settings.setPrimaryTarget(primary)
        assertEquals(sectors, settings.targets.value)
        assertEquals(primary, RadialMenuSettingsStore(storage).primaryTarget.value)
        val replacement = RadialMenuTarget.Collection(RadialMenuCollection.YANDEX_LIKED)
        settings.setTarget(0, replacement)
        val restored = RadialMenuSettingsStore(storage)
        assertEquals(primary, restored.primaryTarget.value)
        assertEquals(replacement, restored.targets.value[0])
        assertEquals(sectors.drop(1), restored.targets.value.drop(1))
        settings.reset()
        assertEquals(sectors, RadialMenuSettingsStore(storage).targets.value)
        assertEquals(defaultRadialPrimaryTarget(), RadialMenuSettingsStore(storage).primaryTarget.value)
    }

    /** Чтение повреждённого JSON или будущего формата не уничтожает исходную запись. */
    @Test
    fun `неподдерживаемая запись остаётся до явного изменения`() {
        val defaults = RadialMenuSettingsStore(MemoryStorage()).targets.value
        listOf(
            "broken json",
            "{\"version\":2,\"targets\":[]}",
            "{\"version\":1,\"targets\":[]}",
            "{\"version\":1,\"targets\":[{}, {}, {}, {}, {}]}",
        ).forEach { raw ->
            val storage = MemoryStorage()
            storage.edit { putString("radial_menu_targets", raw) }
            assertEquals(defaults, RadialMenuSettingsStore(storage).targets.value)
            assertEquals(raw, storage.getString("radial_menu_targets"))
        }
    }

    /** Неверный сектор не меняет настройки; явный сброс восстанавливает сохранённые дефолты. */
    @Test
    fun `проверка индекса и сброс сохраняют пять секторов`() {
        val storage = MemoryStorage()
        val settings = RadialMenuSettingsStore(storage)
        val defaults = settings.targets.value
        assertFailsWith<IllegalArgumentException> {
            settings.setTarget(5, RadialMenuTarget.YandexWave("user:onyourwave"))
        }
        assertEquals(defaults, settings.targets.value)
        settings.setTarget(0, RadialMenuTarget.YandexWave("user:onyourwave"))
        settings.reset()
        assertEquals(defaults, RadialMenuSettingsStore(storage).targets.value)
    }

    /** Подменяет платформенный key-value backend для проверки постоянного строкового формата. */
    private class MemoryStorage : LocalKeyValueStore, LocalKeyValueStore.Editor {
        private val values = mutableMapOf<String, Any>()
        /** Возвращает сохранённую строку. */
        override fun getString(key: String): String? = values[key] as? String
        /** Возвращает сохранённое число. */
        override fun getLong(key: String): Long? = values[key] as? Long
        /** Возвращает сохранённый флаг. */
        override fun getBoolean(key: String): Boolean? = values[key] as? Boolean
        /** Выполняет изменения в памяти. */
        override fun edit(block: LocalKeyValueStore.Editor.() -> Unit) { block(this) }
        /** Сохраняет строку. */
        override fun putString(key: String, value: String) { values[key] = value }
        /** Сохраняет число. */
        override fun putLong(key: String, value: Long) { values[key] = value }
        /** Сохраняет флаг. */
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        /** Удаляет значение. */
        override fun remove(key: String) { values.remove(key) }
    }
}

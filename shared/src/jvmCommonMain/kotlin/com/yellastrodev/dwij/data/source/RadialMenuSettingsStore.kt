package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.RADIAL_MENU_TARGET_COUNT
import com.yellastrodev.dwij.RadialMenuCollection
import com.yellastrodev.dwij.RadialMenuTarget
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.defaultRadialMenuTargets
import com.yellastrodev.dwij.defaultRadialPrimaryTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Хранит от нуля до пяти секторов и действие центральной кнопки одним JSON в платформенном хранилище.
 * Позиция в списке соответствует сектору. Главная и экран просмотра используют общий StateFlow.
 * Отсутствующий, повреждённый или неподдерживаемый формат даёт дефолты в памяти,
 * но исходная запись не перезаписывается до явного изменения назначений.
 * Изменения выполняются последовательно владельцем хранилища на UI-потоке.
 */
class RadialMenuSettingsStore(private val storage: LocalKeyValueStore) {
    private val initial = load()
    private val mutableTargets = MutableStateFlow(initial.first)
    private val mutablePrimaryTarget = MutableStateFlow(initial.second)
    val targets: StateFlow<List<RadialMenuTarget>> = mutableTargets.asStateFlow()
    val primaryTarget: StateFlow<RadialMenuTarget> = mutablePrimaryTarget.asStateFlow()

    /** Меняет действие короткого нажатия при пустой очереди, сохраняя сектора. */
    fun setPrimaryTarget(target: RadialMenuTarget) {
        save(mutableTargets.value, target)
    }

    /** Меняет одно назначение; сохранение выполняется до публикации нового состояния. */
    fun setTarget(index: Int, target: RadialMenuTarget) {
        require(index in mutableTargets.value.indices) { "Неверный индекс сектора: $index" }
        val updated = mutableTargets.value.toMutableList().apply { this[index] = target }
        save(updated)
    }

    /** Добавляет выбранное назначение в конец, сохраняя остальные сектора и центр. */
    fun addTarget(target: RadialMenuTarget) {
        require(mutableTargets.value.size < RADIAL_MENU_TARGET_COUNT) { "Достигнут предел секторов" }
        save(mutableTargets.value + target)
    }

    /** Удаляет сектор и сдвигает последующие назначения, сохраняя центральное действие. */
    fun removeTarget(index: Int) {
        require(index in mutableTargets.value.indices) { "Неверный индекс сектора: $index" }
        save(mutableTargets.value.filterIndexed { position, _ -> position != index })
    }

    /** Восстанавливает пять секторов и личную ЯМ-волну для центральной кнопки. */
    fun reset() {
        save(defaultRadialMenuTargets(), defaultRadialPrimaryTarget())
    }

    /** Старый JSON без primary сохраняет сектора и получает личную волну для центра. */
    private fun load(): Pair<List<RadialMenuTarget>, RadialMenuTarget> {
        val defaults = defaultRadialMenuTargets() to defaultRadialPrimaryTarget()
        val saved = storage.getString(KEY) ?: return defaults
        return try {
            val root = Json.parseToJsonElement(saved).jsonObject
            require(root.getValue("version").jsonPrimitive.int == VERSION)
            val entries = root.getValue("targets").jsonArray
            require(entries.size <= RADIAL_MENU_TARGET_COUNT)
            entries.map { decodeTarget(it.jsonObject) } to
                (root["primary"]?.let { decodeTarget(it.jsonObject) } ?: defaultRadialPrimaryTarget())
        } catch (_: IllegalArgumentException) {
            defaults
        } catch (_: NoSuchElementException) {
            defaults
        }
    }

    /** Записывает центр и все сектора одной операцией до публикации нового состояния. */
    private fun save(targets: List<RadialMenuTarget>, primary: RadialMenuTarget = mutablePrimaryTarget.value) {
        val json = buildJsonObject {
            put("version", VERSION)
            put("targets", buildJsonArray { targets.forEach { add(encodeTarget(it)) } })
            put("primary", encodeTarget(primary))
        }
        storage.edit { putString(KEY, json.toString()) }
        mutableTargets.value = targets.toList()
        mutablePrimaryTarget.value = primary
    }

    /** Кодирует устойчивый тип назначения и минимальные параметры его восстановления. */
    private fun encodeTarget(target: RadialMenuTarget): JsonObject = buildJsonObject {
        when (target) {
            is RadialMenuTarget.Collection -> {
                put("type", "collection")
                put("kind", target.kind.name)
            }
            is RadialMenuTarget.Playlist -> {
                put("type", "playlist")
                put("source", target.source.name)
                put("key", target.key)
                target.title?.let { put("title", it) }
            }
            is RadialMenuTarget.YandexWave -> {
                put("type", "yandex_wave")
                put("seed", target.seed)
                target.title?.let { put("title", it) }
            }
        }
    }

    /** Восстанавливает назначение; неизвестный тип или enum отклоняет всю сохранённую запись. */
    private fun decodeTarget(json: JsonObject): RadialMenuTarget = when (json.string("type")) {
        "collection" -> RadialMenuTarget.Collection(RadialMenuCollection.valueOf(json.string("kind")))
        "playlist" -> RadialMenuTarget.Playlist(
            source = MusicSource.valueOf(json.string("source")),
            key = json.string("key"),
            title = json["title"]?.jsonPrimitive?.contentOrNull,
        )
        "yandex_wave" -> RadialMenuTarget.YandexWave(json.string("seed"), json["title"]?.jsonPrimitive?.contentOrNull)
        else -> throw IllegalArgumentException("Неизвестный тип назначения радиального меню")
    }

    /** Читает обязательную JSON-строку без преобразования чисел и null в идентификаторы. */
    private fun JsonObject.string(key: String): String {
        val value = getValue(key).jsonPrimitive
        require(value.isString)
        return value.content
    }

    private companion object {
        const val KEY = "radial_menu_targets"
        const val VERSION = 1
    }
}

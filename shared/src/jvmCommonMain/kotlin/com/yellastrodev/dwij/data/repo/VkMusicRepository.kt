package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.storage.ProtectedSessionPayloadStore
import com.yellastrodev.vkmusicsdk.VkApiClient
import com.yellastrodev.vkmusicsdk.VkApiException
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkHlsRelay
import com.yellastrodev.yamusicsdk.YamLogger
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Сессия, поиск и временный одиночный трек VK. Токен сохраняется только в защищённом
 * платформенном payload; VK не записывается в таблицы локальной/Яндекс-фонотеки.
 */
class VkMusicRepository(
    private val store: ProtectedSessionPayloadStore,
    private val logger: YamLogger,
) : Closeable {
    private val mutex = Mutex()
    private var client: VkApiClient? = null
    private var relay: VkHlsRelay? = null
    private val mutableAuthorized = MutableStateFlow(false)
    val authorized = mutableAuthorized.asStateFlow()

    /** Восстанавливает зашифрованную сессию; недоступное хранилище оставляет VK без входа. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val token = store.read()?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
                client = token?.let { VkApiClient(it) }
                mutableAuthorized.value = client != null
            } catch (_: Exception) {
                logger.warning(TAG, "[restore] Не удалось восстановить защищённую сессию VK")
            }
        }
    }

    /** Проверяет музыкальный доступ до атомарной замены сохранённой сессии. */
    suspend fun authorize(token: String): DataResult<Unit> = operation("authorize") {
        mutex.withLock {
            val candidate = VkApiClient(token)
            try {
                candidate.validateMusicAccess()
                store.write(token.toByteArray(Charsets.UTF_8))
                val old = client
                client = candidate
                mutableAuthorized.value = true
                old?.close()
            } catch (error: Exception) {
                candidate.close()
                throw error
            }
        }
    }

    /** Удаляет только VK-сессию, сохраняя состояние Яндекс Музыки. */
    suspend fun logout(): DataResult<Unit> = operation("logout") {
        mutex.withLock {
            store.clear()
            client?.close()
            client = null
            mutableAuthorized.value = false
        }
    }

    /** Ищет аудио текущей сессии; code=5 сбрасывает признак принятой авторизации. */
    suspend fun search(query: String): DataResult<List<VkAudio>> = operation("search") {
        val active = client ?: throw VkApiException(5)
        active.search(query).items
    }

    /** Получает свежий URL и передаёт один VK-инстанс в обычную очередь общего плеера. */
    suspend fun play(audio: VkAudio, player: PlayerRepository): DataResult<Unit> = operation("play") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val fresh = active.getById(listOf(audio.requestId)).firstOrNull { it.fullId == audio.fullId }
                ?: throw IllegalStateException("VK не вернул выбранный трек")
            require(fresh.url.isNotBlank()) { "VK не предоставил аудио для этого трека" }
            require(java.net.URI(fresh.url).scheme == "https") { "VK не предоставил HTTPS-аудио" }
            val nextRelay = if (java.net.URI(fresh.url).path.endsWith(".m3u8", ignoreCase = true)) {
                VkHlsRelay().also { it.onError { message -> logger.warning(TAG, message) } }
            } else null
            try {
                val uri = nextRelay?.open(fresh.url) ?: fresh.url
                val id = "vk:${fresh.fullId}"
                val song = Song(
                    id = id, title = fresh.title,
                    artists = fresh.artistNames.mapIndexed { index, name -> Artist("$id:artist:$index", name) },
                    albums = fresh.album?.let { listOf(Album("$id:album", it.title)) }.orEmpty(),
                    durationMs = fresh.duration * 1000, coverUri = fresh.coverUrl,
                    instances = listOf(TrackInstance.Vk(id, fresh, uri)), preferredInstanceId = id,
                    hasPendingMatchCandidate = false, isLocalOnlyInLibrary = false, isLiked = false,
                )
                player.playQueue(listOf(song), 0, VkSearchTracklist())
                relay?.close()
                relay = nextRelay
            } catch (error: Exception) {
                nextRelay?.close()
                throw error
            }
        }
    }

    /** Преобразует ошибки VK в общий контракт, не логируя token, callback или signed URL. */
    private suspend fun <T> operation(name: String, block: suspend () -> T): DataResult<T> =
        withContext(Dispatchers.IO) {
            try {
                DataResult.Success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: VkApiException) {
                logger.warning(TAG, "[$name] VK API вернул код ${error.code}")
                if (error.code == 5) mutableAuthorized.value = false
                DataResult.Failure(if (error.code == 5) DataError.Unauthorized
                    else DataError.Remote(200, error.code.toString(), "VK API: ${error.code}"))
            } catch (error: Exception) {
                logger.warning(TAG, "[$name] Ошибка VK: ${error.javaClass.simpleName}")
                DataResult.Failure(DataError.InvalidData("Не удалось выполнить запрос VK. Повторите попытку"))
            }
        }

    /** Освобождает relay после перехода к другому источнику; проверяет актуальное состояние под mutex. */
    suspend fun releaseInactivePlayback(player: PlayerRepository) {
        mutex.withLock {
            if (player.currentPlaybackTrack.value?.source != MusicSource.VK) {
                relay?.close()
                relay = null
            }
        }
    }

    /** Освобождает сессию и текущий relay вместе с графом приложения. */
    override fun close() { client?.close(); relay?.close() }

    private companion object { const val TAG = "VkMusicRepository" }
}

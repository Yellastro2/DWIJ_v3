package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.data.cache.VkLocalStorage
import java.io.File
import com.yellastrodev.dwij.storage.ProtectedSessionPayloadStore
import com.yellastrodev.vkmusicsdk.VkApiClient
import com.yellastrodev.vkmusicsdk.VkApiException
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkHlsRelay
import com.yellastrodev.vkmusicsdk.VkAudioCache
import com.yellastrodev.vkmusicsdk.VkPlaylist
import com.yellastrodev.vkmusicsdk.VkPlaylistPermissions
import com.yellastrodev.yamusicsdk.YamLogger
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update

/** Зарезервированный маршрут личной коллекции VK. */
const val VK_MY_TRACKS = "0_-1"
/** Зарезервированный маршрут объединения личной коллекции и всех плейлистов VK. */
const val VK_ALL_TRACKS = "0_-2"

/**
 * Сессия, поиск, личная коллекция, плейлисты и временные очереди VK. Токен сохраняется только в защищённом
 * платформенном payload; VK не записывается в таблицы локальной/Яндекс-фонотеки.
 * Аудио проходит через relay с необязательным постоянным кешем приложения.
 * Явное сохранение публикует независимый offline-bundle вне LRU.
 */
class VkMusicRepository(
    private val store: ProtectedSessionPayloadStore,
    private val logger: YamLogger,
) : Closeable {
    private val mutex = Mutex()
    private var client: VkApiClient? = null
    @Volatile private var relay: VkHlsRelay? = null
    @Volatile private var audioCache: VkAudioCache? = null
    private var localStorage: VkLocalStorage? = null
    private val downloadMutex = Mutex()
    private val downloadMetadata = java.util.concurrent.ConcurrentHashMap<String, VkAudio>()
    private val mutableLocalDownloads = MutableStateFlow<Map<String, LocalTrackDownloadProgress>>(emptyMap())
    val localDownloads = mutableLocalDownloads.asStateFlow()
    private val mutableLocalStorageRevision = MutableStateFlow(0L)
    val localStorageRevision = mutableLocalStorageRevision.asStateFlow()
    private val mutableSavedPlaylists = MutableStateFlow<List<VkPlaylist>>(emptyList())
    val savedPlaylists = mutableSavedPlaylists.asStateFlow()

    /** Подключает постоянный каталог и доступные после перезапуска снимки плейлистов. */
    fun useLocalStorage(storage: VkLocalStorage) {
        localStorage = storage
        mutableSavedPlaylists.value = storage.playlists().map { it.playlist }
        mutablePlaylists.value = mutableSavedPlaylists.value.filter { it.id >= 0 }
    }

    /** Проверяет только постоянный bundle, не обычный кеш. */
    fun isSavedLocally(id: String): Boolean = localStorage?.readyFile(id.removePrefix("vk:")) != null

    /** Сохраняет access_key выбранного аудио только в памяти, не включая его в Intent, ID или логи. */
    fun rememberDownloadAudio(audio: VkAudio) { downloadMetadata[audio.fullId] = audio }

    /** Размер постоянных файлов VK для общего раздела настроек. */
    fun localStorageSizeBytes(): Long = localStorage?.sizeBytes() ?: 0L

    /** Очищает постоянные файлы и снимки, дождавшись текущей загрузки. */
    suspend fun clearLocalStorage(): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            (localStorage?.clear() ?: true).also {
                mutableSavedPlaylists.value = localStorage?.playlists().orEmpty().map { it.playlist }
                mutableLocalStorageRevision.update { it + 1 }
            }
        }
    }

    /** Сохраняет порядок плейлиста для offline-открытия до постановки отдельных треков в очередь. */
    suspend fun rememberLocalPlaylist(playlist: VkPlaylist, tracks: List<VkAudio>) = withContext(Dispatchers.IO) {
        requireNotNull(localStorage).rememberPlaylist(playlist, tracks)
        tracks.forEach(::rememberDownloadAudio)
        mutableSavedPlaylists.value = requireNotNull(localStorage).playlists().map { it.playlist }
        mutableLocalStorageRevision.update { it + 1 }
    }

    /** Загружает один VK-трек через общий кеш, публикует bundle и сообщает прогресс платформенной очереди. */
    suspend fun saveLocally(id: String, onProgress: (LocalTrackDownloadProgress) -> Unit = {}): DataResult<File> =
        operation("saveVkLocally") {
            downloadMutex.withLock {
                val fullId = id.removePrefix("vk:")
                require(fullId.matches(Regex("-?[0-9]+_[0-9]+")))
                val storage = requireNotNull(localStorage)
                storage.readyFile(fullId)?.let { return@withLock it }
                val key = "vk:$fullId"
                val context = currentCoroutineContext()
                /** Контролирует отмену между сегментами и обновляет общий формат уведомлений. */
                fun progress(bytes: Long, total: Long?) {
                    context.ensureActive()
                    val value = LocalTrackDownloadProgress(key, bytes, total)
                    mutableLocalDownloads.update { it + (key to value) }
                    onProgress(value)
                }
                try {
                    progress(0, null)
                    val active = client ?: throw VkApiException(5)
                    val audio = active.getById(listOf(downloadMetadata[fullId]?.requestId ?: fullId)).firstOrNull { it.fullId == fullId }
                        ?: throw IllegalStateException("VK не вернул трек")
                    createRelay().use { downloader -> storage.save(audio, downloader, ::progress) }.also {
                        mutableLocalStorageRevision.update { it + 1 }
                        logger.info(TAG, "[saveVkLocally] Трек VK сохранён для воспроизведения без сети")
                    }
                } finally { mutableLocalDownloads.update { it - key } }
            }
        }

    /** Подключает общее хранилище до первого воспроизведения; SDK не зависит от файлов приложения. */
    fun useAudioCache(cache: VkAudioCache) { audioCache = cache }

    /** Создаёт relay с общим кешем и диагностикой без подписанных URL. */
    private fun createRelay(): VkHlsRelay = VkHlsRelay().also {
        it.useAudioCache(audioCache)
        it.onError { message -> logger.warning(TAG, message) }
        it.onDiagnostic { message -> logger.debug(TAG, message) }
    }

    /** Отменяет чтения старой попытки desktop, сохраняя очередь и отдельные фоновые загрузки. */
    fun cancelPendingPlaybackRequests() { relay?.cancelPendingRequests() }

    /** Ограничивает ленивый VK API-запрос восемью секундами; отмена runBlocking отменяет транспорт SDK. */
    private fun resolvePlaybackUrl(active: VkApiClient?, audio: VkAudio): String = runBlocking {
        kotlinx.coroutines.withTimeout(8_000L) { freshAudio(active ?: throw VkApiException(5), audio).url }
    }
    private val mutableAuthorized = MutableStateFlow(false)
    val authorized = mutableAuthorized.asStateFlow()
    private val mutablePlaylists = MutableStateFlow<List<VkPlaylist>>(emptyList())
    val playlists = mutablePlaylists.asStateFlow()
    private val mutableUserId = MutableStateFlow<Long?>(null)
    val userId = mutableUserId.asStateFlow()
    private val mutableSessionRevision = MutableStateFlow(0L)
    val sessionRevision = mutableSessionRevision.asStateFlow()
    private val mutablePlaylistTracks = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val playlistTracks = mutablePlaylistTracks.asStateFlow()
    private val mutableMembershipRevision = MutableStateFlow(0L)
    val membershipRevision = mutableMembershipRevision.asStateFlow()
    private val mutableMyTracks = MutableStateFlow<List<VkAudio>>(emptyList())
    val myTracks = mutableMyTracks.asStateFlow()
    private val mutableMyTracksLoaded = MutableStateFlow(false)
    val myTracksLoaded = mutableMyTracksLoaded.asStateFlow()
    private val myTrackAliases = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Сопоставляет исходный трек с подтверждённым audio.add личным экземпляром без сравнения названий. */
    fun isInMyTracks(audio: VkAudio): Boolean = myTrackInstance(audio) != null

    /** Находит только точный source-id или серверную связь ID, полученную при добавлении. */
    private fun myTrackInstance(audio: VkAudio): VkAudio? {
        val id = myTrackAliases.value[audio.fullId] ?: audio.fullId
        return mutableMyTracks.value.firstOrNull { it.fullId == id ||
            it.releaseAudioId?.takeIf(String::isNotBlank)?.let { release -> release == audio.fullId || release == audio.releaseAudioId } == true ||
            audio.releaseAudioId == it.fullId }
    }

    /** Обновляет коллекцию атомарно; ошибочная загрузка не публикует пустую фонотеку. Вызывается под mutex. */
    private suspend fun loadMyTracks(active: VkApiClient, owner: Long) {
        myTrackAliases.value = localStorage?.myTrackAliases(owner).orEmpty() + myTrackAliases.value
        mutableMyTracks.value = active.getMyTracks(owner)
        mutableMyTracksLoaded.value = true
    }

    /** Загружает статус лайков один раз за ревизию, независимо от плейлистов. */
    suspend fun refreshMyTracks(): DataResult<Unit> = operation("refreshMyTracks") {
        mutex.withLock {
            if (!mutableMyTracksLoaded.value) {
                val active = client ?: throw VkApiException(5)
                loadMyTracks(active, currentUserId(active))
            }
        }
    }

    /** Добавляет/удаляет из «Моих треков» после проверки личного экземпляра и подтверждения VK. */
    suspend fun setInMyTracks(audio: VkAudio, added: Boolean): DataResult<Unit> = operation("setInMyTracks") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val owner = currentUserId(active)
            if (!mutableMyTracksLoaded.value) loadMyTracks(active, owner)
            val existing = myTrackInstance(audio)
            if (added && existing == null) {
                val own = active.addToMyTracks(audio, owner)
                val previousId = myTrackAliases.value[audio.fullId] ?: audio.fullId
                myTrackAliases.value = myTrackAliases.value.mapValues { (_, id) ->
                    if (id == previousId) own.fullId else id
                } + (audio.fullId to own.fullId)
                mutableMyTracks.value = listOf(own) + mutableMyTracks.value.filterNot { it.fullId == own.fullId }
            } else if (!added && existing != null) {
                active.removeFromMyTracks(existing)
                mutableMyTracks.value = mutableMyTracks.value.filterNot { it.fullId == existing.fullId }
                mutablePlaylistTracks.value = emptyMap()
            }
            try { localStorage?.rememberMyTrackAliases(owner, myTrackAliases.value) }
            catch (error: Exception) {
                logger.warning(TAG, "[setInMyTracks] Не удалось сохранить связь ID: ${error.javaClass.simpleName}")
            }
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[setInMyTracks] Коллекция VK обновлена: добавление=$added")
        }
    }

    /** Загружает коллекцию либо объединение с плейлистами; сбой списка не выдаётся за полную фонотеку. */
    private suspend fun getCollection(fullId: String): DataResult<VkPlaylistContent> = operation("getVkCollection") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val owner = currentUserId(active)
            loadMyTracks(active, owner)
            val tracks = mutableMyTracks.value.toMutableList()
            if (fullId == VK_ALL_TRACKS) {
                val lists = active.getPlaylists(owner)
                mutablePlaylists.value = (lists + mutableSavedPlaylists.value.filter { it.id >= 0 }).distinctBy { it.fullId }
                for (playlist in lists) {
                    val items = active.getPlaylistTracks(playlist)
                    tracks.addAll(items)
                    mutablePlaylistTracks.value = mutablePlaylistTracks.value + (playlist.fullId to items.map { it.fullId }.toSet())
                }
            }
            val unique = distinctVkLibraryTracks(tracks, mutableMyTracks.value, myTrackAliases.value)
            VkPlaylistContent(VkPlaylist(id = if (fullId == VK_MY_TRACKS) -1 else -2, ownerId = 0,
                title = if (fullId == VK_MY_TRACKS) "Мои треки" else "Все треки",
                count = unique.size, permissions = VkPlaylistPermissions(edit = false, delete = false)), unique)
        }
    }

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
                mutablePlaylists.value = emptyList()
                mutablePlaylistTracks.value = emptyMap()
                mutableMembershipRevision.value += 1
                mutableUserId.value = null
                mutableMyTracks.value = emptyList()
                mutableMyTracksLoaded.value = false
                myTrackAliases.value = emptyMap()
                mutableSessionRevision.value += 1
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
            mutablePlaylists.value = emptyList()
            mutablePlaylistTracks.value = emptyMap()
            mutableMembershipRevision.value += 1
            mutableUserId.value = null
            mutableMyTracks.value = emptyList()
            mutableMyTracksLoaded.value = false
            myTrackAliases.value = emptyMap()
            mutableSessionRevision.value += 1
        }
    }

    /** Ищет в VK; при сетевом отказе возвращает совпадения среди постоянно сохранённых треков. */
    suspend fun search(query: String): DataResult<List<VkAudio>> {
        val result = operation("search") {
            val active = client ?: throw VkApiException(5)
            active.search(query).items
        }
        if (result is DataResult.Failure) {
            val local = withContext(Dispatchers.IO) { localStorage?.audios().orEmpty().filter {
                "${it.title} ${it.artistNames.joinToString(" ")}".contains(query.trim(), ignoreCase = true)
            } }
            if (local.isNotEmpty()) return DataResult.Success(local)
        }
        return result
    }

    /** Загружает полную сетку текущего пользователя; прошлый успешный список сохраняется при ошибке сети. */
    suspend fun refreshPlaylists(): DataResult<Unit> = operation("refreshPlaylists") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val owner = currentUserId(active)
            mutablePlaylists.value = (active.getPlaylists(owner) + mutableSavedPlaylists.value.filter { it.id >= 0 }).distinctBy { it.fullId }
            mutableMyTracksLoaded.value = false
            mutablePlaylistTracks.value = emptyMap()
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[refreshPlaylists] Загружено плейлистов VK: ${mutablePlaylists.value.size}")
        }
    }

    /** Получает идентификатор аккаунта один раз на сессию; вызывается под mutex. */
    private suspend fun currentUserId(active: VkApiClient): Long = mutableUserId.value
        ?: active.getCurrentUserId().also { mutableUserId.value = it }

    /** Получает актуальный состав либо сохранённый снимок; preferLocal позволяет открыть его сразу без VK. */
    suspend fun getPlaylist(fullId: String, preferLocal: Boolean = false): DataResult<VkPlaylistContent> {
        val saved = withContext(Dispatchers.IO) { localStorage?.playlists()?.firstOrNull { it.playlist.fullId == fullId } }
        if (preferLocal && saved != null && fullId != VK_MY_TRACKS && fullId != VK_ALL_TRACKS)
            return DataResult.Success(VkPlaylistContent(saved.playlist, saved.tracks))
        if (fullId == VK_MY_TRACKS || fullId == VK_ALL_TRACKS) {
            val result = getCollection(fullId)
            return if (result is DataResult.Failure && saved != null)
                DataResult.Success(VkPlaylistContent(saved.playlist, saved.tracks)) else result
        }
        val result = operation("getPlaylist") {
            mutex.withLock {
                val active = client ?: throw VkApiException(5)
                val owner = currentUserId(active)
                if (saved != null) {
                    // Постоянный снимок не содержит access_key; обновление получает его из сетки заново.
                    mutablePlaylists.value = (active.getPlaylists(owner) + mutableSavedPlaylists.value.filter { it.id >= 0 }).distinctBy { it.fullId }
                }
                // После восстановления маршрута сначала получаем ключи доступа из личной сетки.
                val known = mutablePlaylists.value.firstOrNull { it.fullId == fullId }
                    ?: active.getPlaylists(owner).also { mutablePlaylists.value = it }
                        .firstOrNull { it.fullId == fullId }
                    ?: throw IllegalArgumentException("Плейлист недоступен")
                val playlist = active.getPlaylistById(known.ownerId, known.id, known.accessKey).let {
                    it.copy(accessKey = it.accessKey ?: known.accessKey,
                        original = it.original ?: known.original, permissions = it.permissions ?: known.permissions,
                        photo = it.photo ?: known.photo, thumbs = it.thumbs.ifEmpty { known.thumbs })
                }
                val tracks = active.getPlaylistTracks(playlist)
                mutablePlaylistTracks.value = mutablePlaylistTracks.value + (fullId to tracks.map { it.fullId }.toSet())
                VkPlaylistContent(playlist, tracks)
            }
        }
        if (result is DataResult.Failure) {
            saved?.let {
                return DataResult.Success(VkPlaylistContent(it.playlist, it.tracks))
            }
        }
        return result
    }

    /** Создаёт свой плейлист и сразу помещает подтверждённую сервером запись в сетку. */
    suspend fun createPlaylist(title: String): DataResult<VkPlaylist> = operation("createPlaylist") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val playlist = active.createPlaylist(currentUserId(active), title)
            mutablePlaylists.value = listOf(playlist) + mutablePlaylists.value.filterNot { it.fullId == playlist.fullId }
            mutablePlaylistTracks.value = mutablePlaylistTracks.value + (playlist.fullId to emptySet())
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[createPlaylist] Плейлист VK создан")
            playlist
        }
    }

    /** Удаляет только свой редактируемый плейлист; сетку меняет после подтверждения VK. */
    suspend fun deletePlaylist(playlist: VkPlaylist): DataResult<Unit> = operation("deletePlaylist") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            require(playlist.canDelete(currentUserId(active)))
            active.deletePlaylist(playlist)
            mutablePlaylists.value = mutablePlaylists.value.filterNot { it.fullId == playlist.fullId }
            mutablePlaylistTracks.value = mutablePlaylistTracks.value - playlist.fullId
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[deletePlaylist] Плейлист VK удалён")
        }
    }

    /** Получает выбранное аудио для отдельного VK-сценария добавления в плейлист. */
    suspend fun getAudio(requestId: String): DataResult<VkAudio> = operation("getAudio") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            active.getById(listOf(requestId)).firstOrNull()
                ?: throw IllegalArgumentException("Трек недоступен")
        }
    }

    /** Добавляет трек только в свой плейлист, не заменяя source-id на Яндекс-id. */
    suspend fun addToPlaylist(playlist: VkPlaylist, audio: VkAudio): DataResult<Unit> = operation("addToPlaylist") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            require(playlist.canEdit(currentUserId(active)))
            active.addToPlaylist(playlist, audio)
            mutablePlaylistTracks.value = mutablePlaylistTracks.value - playlist.fullId
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[addToPlaylist] Трек добавлен в плейлист VK")
        }
    }

    /** Удаляет трек из своего плейлиста; личную коллекцию и текущий плеер не изменяет. */
    suspend fun removeFromPlaylist(playlist: VkPlaylist, audio: VkAudio): DataResult<Unit> = operation("removeFromPlaylist") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            require(playlist.canEdit(currentUserId(active)))
            active.removeFromPlaylist(playlist, audio)
            mutablePlaylistTracks.value = mutablePlaylistTracks.value - playlist.fullId
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[removeFromPlaylist] Трек удалён из плейлиста VK")
        }
    }

    /** Дополняет кэш состава для меток плеера; успешно загруженный плейлист повторно не сканируется на каждом треке. */
    suspend fun refreshPlaylistMemberships(): DataResult<Unit> = operation("refreshPlaylistMemberships") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            if (mutableUserId.value == null || mutablePlaylists.value.isEmpty()) {
                mutablePlaylists.value = active.getPlaylists(currentUserId(active))
            }
            for (playlist in mutablePlaylists.value) {
                if (playlist.fullId in mutablePlaylistTracks.value) continue
                try {
                    val tracks = active.getPlaylistTracks(playlist)
                    mutablePlaylistTracks.value = mutablePlaylistTracks.value + (playlist.fullId to tracks.map { it.fullId }.toSet())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: VkApiException) {
                    if (error.code == 5) throw error
                    logger.warning(TAG, "[refreshPlaylistMemberships] Состав плейлиста VK недоступен: код ${error.code}")
                } catch (error: Exception) {
                    logger.warning(TAG, "[refreshPlaylistMemberships] Не удалось прочитать состав VK: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    /** Передаёт полный состав; сохранённые bundle открываются без VK, остальные URL разрешаются лениво. */
    suspend fun playPlaylist(
        playlist: VkPlaylist, tracks: List<VkAudio>, startIndex: Int, player: PlayerRepository,
    ): DataResult<Unit> = operation("playPlaylist") {
        mutex.withLock {
            require(tracks.isNotEmpty() && startIndex in tracks.indices)
            val active = client
            val selected = localStorage?.audio(tracks[startIndex].fullId)
                ?: freshAudio(active ?: throw VkApiException(5), tracks[startIndex])
            val savedFormats = localStorage?.playlists()?.firstOrNull { it.playlist.fullId == playlist.fullId }?.hls.orEmpty()
            val nextRelay = createRelay()
            try {
                val songs = tracks.mapIndexed { index, audio ->
                    val metadata = if (index == startIndex) selected else audio
                    localStorage?.readyFile(audio.fullId)?.let { file ->
                        return@mapIndexed vkSong(localStorage?.audio(audio.fullId) ?: metadata, nextRelay.openSavedAudio(file))
                    }
                    val isHls = if (metadata.url.isBlank()) savedFormats[audio.fullId] ?: true
                        else java.net.URI(metadata.url).path.endsWith(".m3u8", ignoreCase = true)
                    val uri = nextRelay.openDeferredAudio(isHls, selected.url.takeIf { index == startIndex }, audio.fullId,
                        savedAudio = { localStorage?.readyFile(audio.fullId) }) {
                        // Callback вызывается HTTP-worker relay; не берёт mutex репозитория.
                        try { resolvePlaybackUrl(active, audio) }
                        catch (error: VkApiException) {
                            logger.warning(TAG, "[resolveQueueAudio] VK API вернул код ${error.code}")
                            throw error
                        }
                    }
                    vkSong(metadata, uri)
                }
                mutablePlaylistTracks.value = mutablePlaylistTracks.value + (playlist.fullId to tracks.map { it.fullId }.toSet())
                player.playQueue(songs, startIndex, VkPlaylistTracklist(playlist))
                relay?.close()
                relay = nextRelay
                logger.info(TAG, "[playPlaylist] Очередь VK передана плееру: треков=${songs.size}, позиция=$startIndex")
            } catch (error: Exception) {
                nextRelay.close()
                throw error
            }
        }
    }

    /** Получает и проверяет свежую ссылку конкретного составного source-id. */
    private suspend fun freshAudio(active: VkApiClient, audio: VkAudio): VkAudio {
        val fresh = active.getById(listOf(audio.requestId)).firstOrNull { it.fullId == audio.fullId }
            ?: throw IllegalStateException("VK не вернул выбранный трек")
        require(fresh.url.isNotBlank() && java.net.URI(fresh.url).scheme == "https") { "VK не предоставил HTTPS-аудио" }
        return fresh
    }

    /** Собирает временную Song с готовым прямым либо ленивым loopback URI; в Room она не сохраняется. */
    private fun vkSong(audio: VkAudio, uri: String): Song {
        val id = "vk:${audio.fullId}"
        return Song(
            id = id, title = audio.title,
            artists = audio.artistNames.mapIndexed { index, name -> Artist("$id:artist:$index", name) },
            albums = audio.album?.let { listOf(Album("$id:album", it.title)) }.orEmpty(),
            durationMs = audio.duration * 1000, coverUri = audio.coverUrl,
            instances = listOf(TrackInstance.Vk(id, audio, uri)), preferredInstanceId = id,
            hasPendingMatchCandidate = false, isLocalOnlyInLibrary = false, isLiked = false,
        )
    }

    /** Предпочитает постоянный offline-bundle; иначе получает свежий URL для кеширующего relay. */
    suspend fun play(audio: VkAudio, player: PlayerRepository): DataResult<Unit> = operation("play") {
        mutex.withLock {
            val active = client
            val saved = localStorage?.readyFile(audio.fullId)
            val fresh = if (saved != null) localStorage?.audio(audio.fullId) ?: audio
                else freshAudio(active ?: throw VkApiException(5), audio)
            val nextRelay = createRelay()
            try {
                val isHls = java.net.URI(fresh.url).path.endsWith(".m3u8", ignoreCase = true)
                val uri = if (saved != null) nextRelay.openSavedAudio(saved)
                    else nextRelay.openDeferredAudio(isHls, fresh.url, fresh.fullId,
                        savedAudio = { localStorage?.readyFile(audio.fullId) }) {
                        resolvePlaybackUrl(active, audio)
                    }
                val song = vkSong(fresh, uri)
                player.playQueue(listOf(song), 0, VkSearchTracklist())
                relay?.close()
                relay = nextRelay
            } catch (error: Exception) {
                nextRelay.close()
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

/** Актуальные метаданные и состав VK-плейлиста без записи в Room. */
data class VkPlaylistContent(val playlist: VkPlaylist, val tracks: List<VkAudio>)

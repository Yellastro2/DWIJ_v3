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
import com.yellastrodev.vkmusicsdk.VkWebSession
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
import com.yellastrodev.dwij.data.dao.VkLibraryDao
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** Зарезервированный маршрут личной коллекции VK. */
const val VK_MY_TRACKS = "0_-1"
/** Зарезервированный маршрут объединения личной коллекции и всех плейлистов VK. */
const val VK_ALL_TRACKS = "0_-2"

/**
 * Сессия и коллекции VK; Room-кеш аккаунта доступен до сети, Song собирается общим индексом.
 * OAuth-токен либо браузерные cookies с обновляемым токеном сохраняются только в защищённом payload.
 * Временные URI создаются при подготовке очереди; диагностический запрет относится только к OAuth.
 * Аудио проходит через relay с необязательным постоянным кешем приложения.
 * Desktop может получать MP3 HLS без TS-обёртки; формат кеша и offline-bundle сохраняется.
 * Явное сохранение публикует независимый offline-bundle вне LRU.
 * Сборка очередей использует отдельный mutex и не ожидает сетевого обновления фонотеки.
 * Конечные рекомендации загружаются по нажатию независимо от обновления личной фонотеки.
 */
class VkMusicRepository(
    private val store: ProtectedSessionPayloadStore,
    private val logger: YamLogger,
    private val songs: SongRepository,
    private val libraryDao: VkLibraryDao,
    private val mp3HlsSegments: Boolean = false,
) : Closeable {
    private val mutex = Mutex()
    private val queueMutex = Mutex()
    private val sessionStorageLock = Any()
    @Volatile private var client: VkApiClient? = null
    @Volatile private var relay: VkHlsRelay? = null
    @Volatile private var audioCache: VkAudioCache? = null
    private var localStorage: VkLocalStorage? = null
    private val downloadMutex = Mutex()
    private val downloadMetadata = java.util.concurrent.ConcurrentHashMap<String, VkAudio>()
    private val playbackLock = Any()
    private val playbackUris = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedLibrary: VkLibraryEntity? = null
    private var playlistsKnown = false
    private var playlistsFresh = false
    private val refreshedMemberships = mutableSetOf<String>()
    private var playlistOrder = emptyMap<String, List<String>>()
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

    /** Импортирует отсутствующие offline-метаданные, не перезаписывая более свежие записи Room. */
    suspend fun indexSavedTracks() = withContext(Dispatchers.IO) {
        val storage = localStorage ?: return@withContext
        val tracks = (storage.audios() + storage.playlists().flatMap { it.tracks }).distinctBy(VkAudio::fullId)
        val formats = tracks.mapNotNull { audio -> storage.readyFile(audio.fullId)?.let {
            audio.fullId to (it.extension == "m3u8")
        } }.toMap()
        songs.registerVkTracks(tracks, onlyIfMissing = true, formatOverrides = formats)
    }

    /** Сохраняет долговечную метадату, а access_key оставляет в памяти для будущего getById. */
    private suspend fun registerTracks(tracks: List<VkAudio>) {
        tracks.forEach(::rememberDownloadAudio)
        songs.registerVkTracks(tracks)
    }

    /** Сохраняет сетку и порядок с повторами; неизвестные коллекции не превращает в пустые. */
    private suspend fun persistLibrary(owner: Long, tracks: List<VkAudio> = emptyList()) {
        try {
            registerTracks(tracks)
            val previous = libraryDao.library(owner)
            val snapshot = VkLibraryEntity(
                accountId = owner,
                myTracksJson = if (mutableMyTracksLoaded.value) json.encodeToString(mutableMyTracks.value.map { it.fullId })
                    else previous?.myTracksJson,
                aliasesJson = json.encodeToString(myTrackAliases.value),
                playlistTracksJson = json.encodeToString(playlistOrder),
                playlistsJson = if (playlistsKnown) json.encodeToString(mutablePlaylists.value.map {
                    it.copy(accessKey = null, original = it.original?.copy(accessKey = null))
                }) else previous?.playlistsJson,
            )
            libraryDao.upsertLibrary(snapshot)
            cachedLibrary = snapshot
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Подтверждённая сервером мутация не превращается в ошибку VK при проблеме локального диска.
            logger.warning(TAG, "[persistVkLibrary] Не удалось сохранить снимок коллекций: ${error.javaClass.simpleName}")
        }
    }

    /** Восстанавливает кеш только аккаунта, привязанного к защищённой сессии, без запросов к VK. */
    private suspend fun restoreLibrary(owner: Long) {
        try {
            val snapshot = libraryDao.library(owner) ?: return
            val aliases = snapshot.aliases()
            val order = snapshot.playlistTracks()
            val playlists = snapshot.playlists()
            val myTracks = snapshot.myTrackIds()?.let { ids ->
                val records = libraryDao.tracks(ids.distinct()).associateBy { it.fullId }
                ids.mapNotNull { records[it]?.audio() }
            }
            cachedLibrary = snapshot
            myTrackAliases.value = aliases
            playlistOrder = order
            mutablePlaylistTracks.value = order.mapValues { it.value.toSet() }
            playlists?.let {
                mutablePlaylists.value = it
                playlistsKnown = true
            }
            if (myTracks != null) mutableMyTracks.value = myTracks
            // Снимок нужен для отображения; статус лайка перед мутацией подтверждаем сервером.
            mutableMyTracksLoaded.value = false
            logger.debug(TAG, "[restoreLibrary] Кеш VK восстановлен: плейлистов=${playlists?.size ?: 0}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warning(TAG, "[restoreLibrary] Не удалось прочитать кеш VK: ${error.javaClass.simpleName}")
        }
    }

    /** Учитывает и подтверждённую пустую сетку, чтобы её обновление не блокировало экран. */
    fun hasCachedPlaylists(): Boolean = cachedLibrary?.playlistsJson != null || mutablePlaylists.value.isNotEmpty()

    /** Читает состав из памяти/Room без сетевого mutex; сохраняет порядок и повторяющиеся позиции. */
    suspend fun cachedPlaylist(fullId: String): VkPlaylistContent? = withContext(Dispatchers.IO) {
        try {
            val snapshot = cachedLibrary
            if (snapshot != null && snapshot.accountId == mutableUserId.value) {
                val ids = when (fullId) {
                    VK_MY_TRACKS -> snapshot.myTrackIds()
                    VK_ALL_TRACKS -> {
                        val playlists = snapshot.playlists()
                        val myIds = snapshot.myTrackIds()
                        val contents = snapshot.playlistTracks()
                        if (playlists != null && myIds != null && playlists.all { it.fullId in contents })
                            myIds + playlists.flatMap { contents.getValue(it.fullId) }
                        else null // Частичный снимок нельзя выдавать за всю фонотеку.
                    }
                    else -> snapshot.playlistTracks()[fullId]
                }
                if (ids != null) {
                    val records = libraryDao.tracks(ids.distinct()).associateBy { it.fullId }
                    if (ids.all { it in records }) {
                        val tracks = ids.map { records.getValue(it).audio() }
                        val content = if (fullId == VK_ALL_TRACKS) distinctVkLibraryTracks(tracks,
                            snapshot.myTrackIds().orEmpty().map { records.getValue(it).audio() }, snapshot.aliases()) else tracks
                        val playlist = if (fullId == VK_MY_TRACKS || fullId == VK_ALL_TRACKS)
                            VkPlaylist(if (fullId == VK_MY_TRACKS) -1 else -2, 0,
                                title = if (fullId == VK_MY_TRACKS) "Мои треки" else "Все треки", count = content.size,
                                permissions = VkPlaylistPermissions(edit = false, delete = false))
                        else mutablePlaylists.value.firstOrNull { it.fullId == fullId }
                        if (playlist != null) return@withContext VkPlaylistContent(playlist, content)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warning(TAG, "[cachedPlaylist] Кеш состава VK недоступен: ${error.javaClass.simpleName}")
        }
        localStorage?.playlists()?.firstOrNull { it.playlist.fullId == fullId }
            ?.let { VkPlaylistContent(it.playlist, it.tracks) }
    }

    /** Запоминает успешный состав; кеш меток и экран используют один порядок source-ID. */
    private fun rememberPlaylistTracks(fullId: String, tracks: List<VkAudio>) {
        playlistOrder = playlistOrder + (fullId to tracks.map { it.fullId })
        mutablePlaylistTracks.value = mutablePlaylistTracks.value + (fullId to tracks.map { it.fullId }.toSet())
        refreshedMemberships.add(fullId)
    }

    /** Инвалидирует только изменённый список, чтобы следующий просмотр получил подтверждённый состав. */
    private fun invalidatePlaylist(fullId: String) {
        playlistOrder = playlistOrder - fullId
        mutablePlaylistTracks.value = mutablePlaylistTracks.value - fullId
        refreshedMemberships.remove(fullId)
    }

    /** Сбрасывает аккаунтный кеш при смене сессии; строки других аккаунтов в Room остаются. */
    private fun clearLibraryCache() {
        cachedLibrary = null
        playlistsKnown = false
        playlistsFresh = false
        playlistOrder = emptyMap()
        refreshedMemberships.clear()
    }

    /** Хранит OAuth либо полный web-снимок и профиль в защищённом payload под общим lock. */
    private fun saveSession(token: String, owner: Long, profile: VkAccountProfile? = mutableAccountProfile.value,
        webSession: VkWebSession? = client?.webSession) = synchronized(sessionStorageLock) {
        store.write(buildJsonObject {
            put("token", token)
            put("accountId", owner)
            put("authMethod", if (webSession == null) "marusya" else "web")
            webSession?.let {
                put("p", it.p)
                put("remixsid", it.remixsid)
                put("userAgent", it.userAgent)
                put("expiresAt", it.expiresAt)
            }
            profile?.takeIf { it.id == owner }?.let {
                put("firstName", it.firstName)
                put("lastName", it.lastName)
            }
        }.toString().toByteArray(Charsets.UTF_8))
    }

    /** Читает профиль владельца токена через уже существующий транспорт SDK. */
    private suspend fun readAccountProfile(active: VkApiClient): VkAccountProfile {
        val user = active.request("users.get").jsonArray.first().jsonObject
        return VkAccountProfile(user.getValue("id").jsonPrimitive.long,
            user["first_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            user["last_name"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** Дополняет сессию именем, сохраняя web-cookies; диагностический OAuth не обращается к API. */
    suspend fun loadAccountProfile() = withContext(Dispatchers.IO) {
        if (authorizationOnly) return@withContext
        mutex.withLock {
            if (mutableAccountProfile.value?.displayName?.isNotBlank() == true) return@withLock
            val active = client ?: return@withLock
            try {
                val profile = readAccountProfile(active)
                val payload = store.read()?.toString(Charsets.UTF_8) ?: return@withLock
                val token = if (payload.startsWith("{")) json.parseToJsonElement(payload).jsonObject
                    .getValue("token").jsonPrimitive.content else payload
                saveSession(token, profile.id, profile)
                mutableAccountProfile.value = profile
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                logger.warning(TAG, "[loadAccountProfile] Не удалось загрузить или сохранить имя аккаунта VK")
            }
        }
    }

    /** Создаёт lazy URI только для выбранного источника; сеть запускается при чтении relay, а не сборке Song. */
    fun playbackUri(instance: TrackInstance.Vk): String? = synchronized(playbackLock) {
        if (authorizationOnly) return@synchronized null
        val audio = instance.track
        val metadata = downloadMetadata[audio.fullId] ?: audio
        val saved = localStorage?.readyFile(audio.fullId)
        if (saved == null && client == null) return@synchronized null
        // При появлении/удалении offline-файла выбирается новый корень, а не устаревший saved URI.
        val isHls = if (saved != null) saved.extension == "m3u8" else
            metadata.url.takeIf(String::isNotBlank)?.let {
                java.net.URI(it).path.endsWith(".m3u8", ignoreCase = true)
            } ?: instance.isHls
        val key = "${audio.fullId}:$isHls:${saved?.absolutePath ?: "online"}"
        playbackUris[key]?.let { return@synchronized it }
        val activeRelay = relay ?: createRelay().also { relay = it }
        val uri = if (saved != null) activeRelay.openSavedAudio(saved) else {
            activeRelay.openDeferredAudio(isHls, null, audio.fullId,
                savedAudio = { localStorage?.readyFile(audio.fullId) }) {
                resolvePlaybackUrl(client, downloadMetadata[audio.fullId] ?: metadata)
            }
        }
        playbackUris[key] = uri
        uri
    }

    /** Проверяет только постоянный bundle, не обычный кеш. */
    fun isSavedLocally(id: String): Boolean = localStorage?.readyFile(id.removePrefix("vk:")) != null

    /** Обновляет метадату в памяти, сохраняя известный access_key при передаче очищенной Room-записи. */
    fun rememberDownloadAudio(audio: VkAudio) {
        downloadMetadata.compute(audio.fullId) { _, previous ->
            audio.copy(accessKey = audio.accessKey ?: previous?.accessKey,
                url = audio.url.ifBlank { previous?.url.orEmpty() })
        }
    }

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
        registerTracks(tracks)
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
                storage.readyFile(fullId)?.let { file ->
                    storage.audio(fullId)?.let { registerTracks(listOf(it)) }
                    return@withLock file
                }
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
                    registerTracks(listOf(audio))
                    createRelay().use { downloader -> storage.save(audio, downloader, ::progress) }.also {
                        mutableLocalStorageRevision.update { it + 1 }
                        logger.info(TAG, "[saveVkLocally] Трек VK сохранён для воспроизведения без сети")
                    }
                } finally { mutableLocalDownloads.update { it - key } }
            }
        }

    /** Подключает общее хранилище до первого воспроизведения; SDK не зависит от файлов приложения. */
    fun useAudioCache(cache: VkAudioCache) { audioCache = cache }

    /** Создаёт relay с платформенным HLS-режимом, общим кешем и безопасной диагностикой. */
    private fun createRelay(): VkHlsRelay = VkHlsRelay(mp3HlsSegments).also {
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
    private val mutableBrowserSession = MutableStateFlow(false)
    /** Позволяет UI отличать обычную браузерную сессию от диагностического OAuth. */
    val browserSession = mutableBrowserSession.asStateFlow()
    /** OAuth-эксперимент не отключает новый браузерный способ входа. */
    val authorizationOnly: Boolean get() = AUTHORIZATION_ONLY && !mutableBrowserSession.value
    private val mutablePlaylists = MutableStateFlow<List<VkPlaylist>>(emptyList())
    val playlists = mutablePlaylists.asStateFlow()
    private val mutableUserId = MutableStateFlow<Long?>(null)
    private val mutableAccountProfile = MutableStateFlow<VkAccountProfile?>(null)
    val accountProfile = mutableAccountProfile.asStateFlow()
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

    /** Идентификаторы одной VK-записи: точный ID, release ID и подтверждённый сервером alias. */
    fun audioIdentityIds(audio: VkAudio): Set<String> = buildSet {
        add(audio.fullId)
        audio.releaseAudioId?.takeIf(String::isNotBlank)?.let(::add)
        myTrackAliases.value[audio.fullId]?.let(::add)
    }

    /** Находит только точный source-id или серверную связь ID, полученную при добавлении. */
    private fun myTrackInstance(audio: VkAudio): VkAudio? {
        val id = myTrackAliases.value[audio.fullId] ?: audio.fullId
        return mutableMyTracks.value.firstOrNull { it.fullId == id ||
            it.releaseAudioId?.takeIf(String::isNotBlank)?.let { release -> release == audio.fullId || release == audio.releaseAudioId } == true ||
            audio.releaseAudioId == it.fullId }
    }

    /** Обновляет коллекцию атомарно; ошибочная загрузка не публикует пустую фонотеку. Вызывается под mutex. */
    private suspend fun loadMyTracks(active: VkApiClient, owner: Long) {
        val saved = libraryDao.library(owner)
        myTrackAliases.value = (saved?.aliases() ?: localStorage?.myTrackAliases(owner).orEmpty()) + myTrackAliases.value
        mutableMyTracks.value = active.getMyTracks(owner)
        mutableMyTracksLoaded.value = true
        persistLibrary(owner, mutableMyTracks.value)
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
            registerTracks(listOf(audio))
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
                playlistOrder = emptyMap()
                refreshedMemberships.clear()
            }
            persistLibrary(owner, mutableMyTracks.value)
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
                playlistsKnown = true
                playlistsFresh = true
                for (playlist in lists) {
                    val items = active.getPlaylistTracks(playlist)
                    registerTracks(items)
                    tracks.addAll(items)
                    rememberPlaylistTracks(playlist.fullId, items)
                }
            }
            val unique = distinctVkLibraryTracks(tracks, mutableMyTracks.value, myTrackAliases.value)
            persistLibrary(owner)
            VkPlaylistContent(VkPlaylist(id = if (fullId == VK_MY_TRACKS) -1 else -2, ownerId = 0,
                title = if (fullId == VK_MY_TRACKS) "Мои треки" else "Все треки",
                count = unique.size, permissions = VkPlaylistPermissions(edit = false, delete = false)), unique)
        }
    }

    /** Восстанавливает OAuth или web-сессию и кеш без сети; истёкший web-токен обновится перед первым запросом. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val payload = store.read()?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
                val session = payload?.takeIf { it.startsWith("{") }?.let { json.parseToJsonElement(it).jsonObject }
                val token = if (session != null) session["token"]?.jsonPrimitive?.contentOrNull else payload
                val webSession = session?.takeIf { it["authMethod"]?.jsonPrimitive?.contentOrNull == "web" }?.let {
                    VkWebSession(p = it.getValue("p").jsonPrimitive.content,
                        remixsid = it.getValue("remixsid").jsonPrimitive.content,
                        userAgent = it.getValue("userAgent").jsonPrimitive.content,
                        accessToken = token.orEmpty(), expiresAt = it.getValue("expiresAt").jsonPrimitive.long,
                        userId = it["accountId"]?.jsonPrimitive?.longOrNull)
                }
                client = if (webSession != null) createWebClient(webSession)
                    else token?.let { VkApiClient(it, minRequestIntervalMs = 1_000, requestsEnabled = !AUTHORIZATION_ONLY) }
                mutableBrowserSession.value = webSession != null
                session?.get("accountId")?.jsonPrimitive?.longOrNull?.let { owner ->
                    mutableUserId.value = owner
                    mutableAccountProfile.value = VkAccountProfile(owner,
                        session["firstName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        session["lastName"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    restoreLibrary(owner)
                }
                mutableAuthorized.value = client != null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                logger.warning(TAG, "[restore] Не удалось восстановить защищённую сессию VK")
            }
        }
    }

    /** В диагностическом режиме сохраняет только OAuth-токен; обычно проверяет музыкальный доступ и профиль. */
    suspend fun authorize(token: String): DataResult<Unit> = operation("authorize") {
        mutex.withLock {
            val candidate = VkApiClient(token, minRequestIntervalMs = 1_000, requestsEnabled = !AUTHORIZATION_ONLY)
            try {
                val profile = if (AUTHORIZATION_ONLY) null else {
                    candidate.validateMusicAccess()
                    readAccountProfile(candidate)
                }
                val owner = profile?.id
                synchronized(sessionStorageLock) {
                    if (owner != null) saveSession(token, owner, profile, webSession = null)
                    else store.write(token.toByteArray(Charsets.UTF_8))
                    adoptSession(candidate, profile)
                }
                owner?.let { restoreLibrary(it) }
            } catch (error: Exception) {
                candidate.close()
                throw error
            }
        }
    }

    /** Проверяет браузерный токен, музыку и профиль; прежняя сессия заменяется только после успеха. */
    suspend fun authorizeWebSession(session: VkWebSession): DataResult<Unit> = operation("authorizeWebSession") {
        mutex.withLock {
            logger.info(TAG, "[authorizeWebSession] Проверяем браузерную сессию, музыкальный доступ и профиль VK")
            val candidate = createWebClient(session)
            try {
                candidate.authenticateWebSession()
                candidate.validateMusicAccess()
                val profile = readAccountProfile(candidate)
                synchronized(sessionStorageLock) {
                    val updated = checkNotNull(candidate.webSession)
                    if (updated.userId != null && updated.userId != profile.id) throw VkApiException(5)
                    saveSession(updated.accessToken, profile.id, profile, updated)
                    adoptSession(candidate, profile)
                }
                restoreLibrary(profile.id)
                logger.info(TAG, "[authorizeWebSession] Браузерная авторизация VK сохранена; музыкальный доступ проверен")
            } catch (error: Exception) {
                candidate.close()
                throw error
            }
        }
    }

    /** Создаёт web-клиент; обновления устаревшего клиента не перезаписывают новую сессию или выход. */
    private fun createWebClient(session: VkWebSession): VkApiClient {
        lateinit var created: VkApiClient
        created = VkApiClient(session.accessToken, apiBase = "https://api.vk.ru/method/", version = "5.282",
            minRequestIntervalMs = 1_000, webSession = session, onWebSessionUpdated = { updated ->
                synchronized(sessionStorageLock) {
                    if (client === created) {
                        val owner = mutableUserId.value ?: updated.userId
                            ?: error("VK не вернул владельца браузерной сессии")
                        saveSession(updated.accessToken, owner, webSession = updated)
                        logger.info(TAG, "[refreshWebSession] Веб-токен VK обновлён и сохранён")
                    }
                }
            })
        return created
    }

    /** Публикует уже сохранённую сессию и сбрасывает только аккаунтные кеши прежнего входа. */
    private fun adoptSession(candidate: VkApiClient, profile: VkAccountProfile?) {
        val old = client
        client = candidate
        mutableBrowserSession.value = candidate.webSession != null
        mutableAccountProfile.value = profile
        mutablePlaylists.value = emptyList()
        clearLibraryCache()
        mutablePlaylistTracks.value = emptyMap()
        mutableMembershipRevision.value += 1
        mutableUserId.value = profile?.id
        mutableMyTracks.value = emptyList()
        mutableMyTracksLoaded.value = false
        myTrackAliases.value = emptyMap()
        downloadMetadata.clear()
        resetPlayback()
        mutableSessionRevision.value += 1
        mutableAuthorized.value = true
        old?.close()
    }

    /** Удаляет только VK-сессию, сохраняя состояние Яндекс Музыки. */
    suspend fun logout(): DataResult<Unit> = operation("logout") {
        mutex.withLock {
            synchronized(sessionStorageLock) {
                store.clear()
                client?.close()
                client = null
                mutableBrowserSession.value = false
            }
            mutableAuthorized.value = false
            mutablePlaylists.value = emptyList()
            clearLibraryCache()
            mutablePlaylistTracks.value = emptyMap()
            mutableMembershipRevision.value += 1
            mutableUserId.value = null
            mutableAccountProfile.value = null
            mutableMyTracks.value = emptyList()
            mutableMyTracksLoaded.value = false
            myTrackAliases.value = emptyMap()
            downloadMetadata.clear()
            resetPlayback()
            mutableSessionRevision.value += 1
        }
    }

    /** Ищет в VK; при сетевом отказе возвращает совпадения среди постоянно сохранённых треков. */
    suspend fun search(query: String): DataResult<List<VkAudio>> {
        val result = operation("search") {
            val active = client ?: throw VkApiException(5)
            active.search(query).items.also { it.forEach(::rememberDownloadAudio) }
        }
        if (result is DataResult.Failure) {
            val local = withContext(Dispatchers.IO) { localStorage?.audios().orEmpty().filter {
                "${it.title} ${it.artistNames.joinToString(" ")}".contains(query.trim(), ignoreCase = true)
            } }
            if (local.isNotEmpty()) return DataResult.Success(local)
        }
        return result
    }

    /** Обновляет только сетку; кеш составов и «Моих треков» сохраняется, сетевые ошибки не очищают экран. */
    suspend fun refreshPlaylists(): DataResult<Unit> = operation("refreshPlaylists") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            val owner = currentUserId(active)
            mutablePlaylists.value = (active.getPlaylists(owner) + mutableSavedPlaylists.value.filter { it.id >= 0 }).distinctBy { it.fullId }
            playlistsKnown = true
            playlistsFresh = true
            persistLibrary(owner)
            logger.info(TAG, "[refreshPlaylists] Загружено плейлистов VK: ${mutablePlaylists.value.size}")
        }
    }

    /** Привязывает прежний token-only payload к проверенному аккаунту и восстанавливает его кеш. */
    private suspend fun currentUserId(active: VkApiClient): Long {
        mutableUserId.value?.let { return it }
        val owner = active.getCurrentUserId()
        mutableUserId.value = owner
        restoreLibrary(owner)
        if (cachedLibrary == null) myTrackAliases.value = localStorage?.myTrackAliases(owner).orEmpty()
        val payload = store.read()?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
        if (payload != null && !payload.startsWith("{")) saveSession(payload, owner)
        return owner
    }

    /** Получает обложки персональных подборок, не запрашивая треки или разделы каталога. */
    internal suspend fun recommendationCards(): DataResult<List<VkRecommendationCard>> = operation("recommendationCards") {
        val revision = sessionRevision.value
        VkRecommendationCatalog(client ?: throw VkApiException(5)).cards().also {
            check(revision == sessionRevision.value) { "Сессия VK изменилась при загрузке каталога" }
        }
    }

    /** Читает состав с кешем; виртуальные рекомендации загружает независимо от личной коллекции. */
    suspend fun getPlaylist(fullId: String, preferLocal: Boolean = false,
        allowSavedFallback: Boolean = true): DataResult<VkPlaylistContent> {
        if (fullId.startsWith(VK_RECOMMENDATION_PREFIX)) return operation("getRecommendationPlaylist") {
            val revision = sessionRevision.value
            val content = VkRecommendationCatalog(client ?: throw VkApiException(5)).content(fullId)
            check(revision == sessionRevision.value) { "Сессия VK изменилась при загрузке рекомендаций" }
            registerTracks(content.tracks)
            logger.info(TAG, "[getRecommendationPlaylist] Подборка ${content.playlist.title}: треков=${content.tracks.size}")
            content
        }
        if (preferLocal) cachedPlaylist(fullId)?.let { return DataResult.Success(it) }
        val saved = if (allowSavedFallback) withContext(Dispatchers.IO) {
            localStorage?.playlists()?.firstOrNull { it.playlist.fullId == fullId }
        } else null
        if (fullId == VK_MY_TRACKS || fullId == VK_ALL_TRACKS) {
            val result = getCollection(fullId)
            return if (result is DataResult.Failure && saved != null)
                DataResult.Success(VkPlaylistContent(saved.playlist, saved.tracks)) else result
        }
        val result = operation("getPlaylist") {
            mutex.withLock {
                val active = client ?: throw VkApiException(5)
                val owner = currentUserId(active)
                if (!playlistsFresh) {
                    // Постоянный снимок не содержит access_key; обновление получает его из сетки заново.
                    mutablePlaylists.value = (active.getPlaylists(owner) + mutableSavedPlaylists.value.filter { it.id >= 0 }).distinctBy { it.fullId }
                    playlistsKnown = true
                    playlistsFresh = true
                }
                // После восстановления маршрута сначала получаем ключи доступа из личной сетки.
                val known = mutablePlaylists.value.firstOrNull { it.fullId == fullId }
                    ?: active.getPlaylists(owner).also {
                        mutablePlaylists.value = it
                        playlistsKnown = true
                        playlistsFresh = true
                    }
                        .firstOrNull { it.fullId == fullId }
                    ?: throw IllegalArgumentException("Плейлист недоступен")
                val playlist = active.getPlaylistById(known.ownerId, known.id, known.accessKey).let {
                    it.copy(accessKey = it.accessKey ?: known.accessKey,
                        original = it.original ?: known.original, permissions = it.permissions ?: known.permissions,
                        photo = it.photo ?: known.photo, thumbs = it.thumbs.ifEmpty { known.thumbs })
                }
                val tracks = active.getPlaylistTracks(playlist)
                registerTracks(tracks)
                mutablePlaylists.value = mutablePlaylists.value.map { if (it.fullId == fullId) playlist else it }
                rememberPlaylistTracks(fullId, tracks)
                persistLibrary(owner)
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
            rememberPlaylistTracks(playlist.fullId, emptyList())
            persistLibrary(requireNotNull(mutableUserId.value))
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
            invalidatePlaylist(playlist.fullId)
            persistLibrary(requireNotNull(mutableUserId.value))
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[deletePlaylist] Плейлист VK удалён")
        }
    }

    /** Получает выбранное аудио для отдельного VK-сценария добавления в плейлист. */
    suspend fun getAudio(requestId: String): DataResult<VkAudio> = operation("getAudio") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            (active.getById(listOf(requestId)).firstOrNull()
                ?: throw IllegalArgumentException("Трек недоступен")).also(::rememberDownloadAudio)
        }
    }

    /** Добавляет трек только в свой плейлист, не заменяя source-id на Яндекс-id. */
    suspend fun addToPlaylist(playlist: VkPlaylist, audio: VkAudio): DataResult<Unit> = operation("addToPlaylist") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            require(playlist.canEdit(currentUserId(active)))
            registerTracks(listOf(audio))
            active.addToPlaylist(playlist, audio)
            invalidatePlaylist(playlist.fullId)
            persistLibrary(requireNotNull(mutableUserId.value))
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
            invalidatePlaylist(playlist.fullId)
            persistLibrary(requireNotNull(mutableUserId.value))
            mutableMembershipRevision.value += 1
            logger.info(TAG, "[removeFromPlaylist] Трек удалён из плейлиста VK")
        }
    }

    /** Дополняет кэш состава для меток плеера; успешно загруженный плейлист повторно не сканируется на каждом треке. */
    suspend fun refreshPlaylistMemberships(): DataResult<Unit> = operation("refreshPlaylistMemberships") {
        mutex.withLock {
            val active = client ?: throw VkApiException(5)
            if (!playlistsFresh) {
                mutablePlaylists.value = active.getPlaylists(currentUserId(active))
                playlistsKnown = true
                playlistsFresh = true
            }
            for (playlist in mutablePlaylists.value) {
                if (playlist.fullId in refreshedMemberships) continue
                try {
                    val tracks = active.getPlaylistTracks(playlist)
                    registerTracks(tracks)
                    rememberPlaylistTracks(playlist.fullId, tracks)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: VkApiException) {
                    if (error.code == 5) throw error
                    logger.warning(TAG, "[refreshPlaylistMemberships] Состав плейлиста VK недоступен: код ${error.code}")
                } catch (error: Exception) {
                    logger.warning(TAG, "[refreshPlaylistMemberships] Не удалось прочитать состав VK: ${error.javaClass.simpleName}")
                }
            }
            persistLibrary(requireNotNull(mutableUserId.value))
        }
    }

    /** Собирает готовую очередь без сетевого mutex фонотеки; URI разрешается отдельно плеером. */
    suspend fun playPlaylist(
        playlist: VkPlaylist, tracks: List<VkAudio>, startIndex: Int, player: PlayerRepository,
    ): DataResult<Unit> = operation("playPlaylist") {
        queueMutex.withLock {
            val session = sessionRevision.value
            require(tracks.isNotEmpty() && startIndex in tracks.indices)
            registerTracks(tracks)
            val queue = songs.songsForVkTracks(tracks)
            check(session == sessionRevision.value) { "Сессия VK изменилась при подготовке очереди" }
            require(queue.size == tracks.size) { "Не удалось собрать полный состав VK" }
            player.playQueue(queue, startIndex, VkPlaylistTracklist(playlist))
            logger.info(TAG, "[playPlaylist] Общая очередь VK передана плееру: треков=${queue.size}, позиция=$startIndex")
        }
    }
    /** Получает и проверяет свежую ссылку конкретного составного source-id. */
    private suspend fun freshAudio(active: VkApiClient, audio: VkAudio): VkAudio {
        val fresh = active.getById(listOf(audio.requestId)).firstOrNull { it.fullId == audio.fullId }
            ?: throw IllegalStateException("VK не вернул выбранный трек")
        require(fresh.url.isNotBlank() && java.net.URI(fresh.url).scheme == "https") { "VK не предоставил HTTPS-аудио" }
        return fresh
    }

    /** Запускает выбранный трек без ожидания сетевого обновления фонотеки; индекс имеет свой mutex. */
    suspend fun play(audio: VkAudio, player: PlayerRepository): DataResult<Unit> = operation("play") {
        queueMutex.withLock {
            val session = sessionRevision.value
            registerTracks(listOf(audio))
            val queue = songs.songsForVkTracks(listOf(audio))
            check(session == sessionRevision.value) { "Сессия VK изменилась при подготовке трека" }
            require(queue.size == 1) { "Не удалось собрать трек VK" }
            player.playQueue(queue, 0, VkSearchTracklist())
        }
    }
    /** Преобразует ошибки VK в общий контракт, не логируя token, callback или signed URL. */
    private suspend fun <T> operation(name: String, block: suspend () -> T): DataResult<T> =
        withContext(Dispatchers.IO) {
            try {
                if (authorizationOnly && name !in setOf("authorize", "authorizeWebSession", "logout")) {
                    return@withContext DataResult.Failure(DataError.InvalidData("VK отключён для проверки авторизации"))
                }
                DataResult.Success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: VkApiException) {
                logger.warning(TAG, "[$name] VK API вернул код ${error.code}")
                if (error.code == 5 && name !in setOf("authorize", "authorizeWebSession")) mutableAuthorized.value = false
                DataResult.Failure(if (error.code == 5) DataError.Unauthorized
                    else DataError.Remote(200, error.code.toString(), "VK API: ${error.code}"))
            } catch (error: Exception) {
                logger.warning(TAG, "[$name] Ошибка VK: ${error.javaClass.simpleName}")
                DataResult.Failure(DataError.InvalidData("Не удалось выполнить запрос VK. Повторите попытку"))
            }
        }

    /** Закрывает relay и инвалидирует все временные адреса, не меняя метадату Room. */
    private fun resetPlayback() = synchronized(playbackLock) {
        relay?.close()
        relay = null
        playbackUris.clear()
    }

    /** Освобождает только активные чтения после смены источника; URI выбранной очереди сохраняются до следующего VK-запуска. */
    suspend fun releaseInactivePlayback(player: PlayerRepository) {
        mutex.withLock {
            if (player.currentPlaybackTrack.value?.source != MusicSource.VK) {
                cancelPendingPlaybackRequests()
            }
        }
    }

    /** Освобождает сессию и текущий relay вместе с графом приложения. */
    override fun close() { client?.close(); resetPlayback() }

    companion object {
        /** Временный эксперимент Маруси: только OAuth и хранение токена; браузерная сессия работает обычно. */
        const val AUTHORIZATION_ONLY = true
        private const val TAG = "VkMusicRepository"
    }
}

/** Состав VK-плейлиста для source-интерфейса; его треки также индексируются общими Song. */
data class VkPlaylistContent(val playlist: VkPlaylist, val tracks: List<VkAudio>)

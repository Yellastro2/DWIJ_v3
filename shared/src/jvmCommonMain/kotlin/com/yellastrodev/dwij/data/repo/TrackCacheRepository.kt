package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Текущий фактический прогресс сохранения одного ЯМ-трека. */
data class LocalTrackDownloadProgress(
    val trackId: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { total -> total > 0L }
            ?.let { total ->
                (downloadedBytes.toDouble() / total.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }
}

/**
 * Управляет двумя уровнями ЯМ-аудио: ограничиваемым LRU-кэшем и постоянными
 * app-private файлами. Постоянный файл всегда имеет приоритет при воспроизведении.
 */
class TrackCacheRepository(
    private val cacheDir: File,
    private val persistentDir: File,
    val trackRepo: TrackRepository,
    private val cacheManager: CacheManager,
    private val logger: YamLogger,
    private val onAuthorizationRequired: () -> Unit,
) {

    private val fileMutex = Mutex()

    private val mutableLocalDownloads =
        MutableStateFlow<Map<String, LocalTrackDownloadProgress>>(emptyMap())

    val localDownloads: StateFlow<Map<String, LocalTrackDownloadProgress>> =
        mutableLocalDownloads.asStateFlow()

    private val mutableLocalStorageRevision = MutableStateFlow(0L)
    val localStorageRevision: StateFlow<Long> =
        mutableLocalStorageRevision.asStateFlow()

    init {
        cacheDir.mkdirs()
        persistentDir.mkdirs()
        persistentDir
            .listFiles { file -> file.isFile && file.extension == PART_FILE_EXTENSION }
            ?.forEach(File::delete)
    }

    private fun cacheFile(trackId: String): File =
        File(cacheDir, "$trackId.mp3")

    /** Возвращает неочевидное пользователю имя постоянного файла по SHA-256 trackId. */
    fun persistentFile(trackId: String): File =
        File(persistentDir, "${trackId.sha256()}.mp3")

    /** Учитывает оба офлайн-уровня для недоступных в ЯМ треков. */
    fun isCached(trackId: String): Boolean =
        persistentFile(trackId).isFile || cacheFile(trackId).isFile

    /** Проверяет наличие постоянной копии, не учитывая обычный кэш. */
    fun isSavedLocally(trackId: String): Boolean =
        persistentFile(trackId).isFile

    /** Считает полный размер только постоянных ЯМ-файлов. */
    fun localStorageSizeBytes(): Long =
        persistentDir
            .walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)

    /**
     * Возвращает локальный URI: постоянный файл, затем кэш, затем атомарно
     * скачанный cache-файл.
     */
    suspend fun getOrDownload(trackId: String): String =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                persistentFile(trackId)
                    .takeIf(File::isFile)
                    ?.let { file ->
                        logger.debug(TAG, "[getOrDownload] Трек $trackId есть в локальном хранении")
                        return@withLock file.toURI().toString()
                    }

                val cached = cacheFile(trackId)
                if (cached.isFile) {
                    cached.setLastModified(System.currentTimeMillis())
                    logger.debug(TAG, "[getOrDownload] Трек $trackId есть в кэше")
                    return@withLock cached.toURI().toString()
                }

                cacheDir.mkdirs()
                val temporary = File.createTempFile("track-", ".part", cacheDir)
                try {
                    when (
                        val result = temporary.outputStream().buffered().use { output ->
                            trackRepo.downloadTrackTo(trackId, output)
                        }
                    ) {
                        is DataResult.Success -> {
                            require(result.value > 0L && temporary.length() > 0L) {
                                "ЯМ вернула пустой файл трека $trackId"
                            }
                            publishTemporary(temporary, cached)
                            cacheManager.ensureWithinLimit()
                            logger.debug(
                                TAG,
                                "[getOrDownload] Трек $trackId загружен: ${cached.length()} байт",
                            )
                            cached.toURI().toString()
                        }
                        is DataResult.Failure -> {
                            handleDownloadFailure(trackId, result.error)
                            throw IllegalStateException(result.error.toString())
                        }
                    }
                } finally {
                    temporary.delete()
                }
            }
        }

    /** Фоново прогревает обычный LRU-кэш, не создавая постоянную копию трека. */
    suspend fun prefetch(trackId: String) {
        getOrDownload(trackId)
    }

    /**
     * Повышает готовый cache-файл до постоянного либо потоково загружает трек
     * сразу в постоянный каталог. Публикация выполняется только после успеха.
     */
    suspend fun saveLocally(
        trackId: String,
        onProgress: (LocalTrackDownloadProgress) -> Unit = {},
    ): DataResult<File> =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val target = persistentFile(trackId)
                if (target.isFile) {
                    val progress = LocalTrackDownloadProgress(
                        trackId = trackId,
                        downloadedBytes = target.length(),
                        totalBytes = target.length(),
                    )
                    onProgress(progress)
                    return@withLock DataResult.Success(target)
                }

                persistentDir.mkdirs()
                val temporary = File.createTempFile("local-track-", ".part", persistentDir)
                publishProgress(trackId, 0L, null, onProgress)

                try {
                    val cached = cacheFile(trackId)
                    val result = if (cached.isFile && cached.length() > 0L) {
                        copyCachedTrack(
                            trackId = trackId,
                            source = cached,
                            target = temporary,
                            onProgress = onProgress,
                        )
                    } else {
                        temporary.outputStream().buffered().use { output ->
                            trackRepo.downloadTrackTo(
                                trackId = trackId,
                                output = output,
                                onProgress = { downloadedBytes, totalBytes ->
                                    publishProgress(
                                        trackId = trackId,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        listener = onProgress,
                                    )
                                },
                            )
                        }
                    }

                    when (result) {
                        is DataResult.Success -> {
                            if (result.value <= 0L || temporary.length() <= 0L) {
                                return@withLock DataResult.Failure(
                                    DataError.InvalidData(
                                        "ЯМ вернула пустой файл трека $trackId",
                                    ),
                                )
                            }

                            publishTemporary(temporary, target)
                            cached.takeIf(File::isFile)?.delete()
                            mutableLocalStorageRevision.update { revision -> revision + 1L }
                            publishProgress(
                                trackId = trackId,
                                downloadedBytes = target.length(),
                                totalBytes = target.length(),
                                listener = onProgress,
                            )
                            logger.debug(
                                TAG,
                                "[saveLocally] Трек $trackId сохранён локально: ${target.length()} байт",
                            )
                            DataResult.Success(target)
                        }
                        is DataResult.Failure -> {
                            handleDownloadFailure(trackId, result.error)
                            result
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[saveLocally] Не удалось сохранить трек $trackId",
                        error,
                    )
                    DataResult.Failure(DataError.Storage(error))
                } finally {
                    temporary.delete()
                    mutableLocalDownloads.update { downloads -> downloads - trackId }
                }
            }
        }

    /** Очищает только постоянные ЯМ-файлы, не затрагивая кэш и медиатеку. */
    suspend fun clearLocalStorage(): Boolean =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val files = persistentDir
                    .walkBottomUp()
                    .filter { file -> file != persistentDir }
                    .toList()
                val deleteResults = files.map { file ->
                    !file.exists() || file.delete()
                }
                persistentDir.mkdirs()
                mutableLocalStorageRevision.update { revision -> revision + 1L }
                deleteResults.all { deleted -> deleted } &&
                    localStorageSizeBytes() == 0L
            }
        }

    /** Удаляет только обычную cache-копию одного трека. */
    fun remove(trackId: String) {
        cacheFile(trackId).delete()
    }

    /** Очищает только LRU-кэш; постоянное локальное хранение остаётся. */
    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    private suspend fun copyCachedTrack(
        trackId: String,
        source: File,
        target: File,
        onProgress: (LocalTrackDownloadProgress) -> Unit,
    ): DataResult<Long> = try {
        val totalBytes = source.length()
        var copiedBytes = 0L
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read
                    publishProgress(trackId, copiedBytes, totalBytes, onProgress)
                }
            }
        }
        DataResult.Success(copiedBytes)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DataResult.Failure(DataError.Storage(error))
    }

    private fun publishProgress(
        trackId: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        listener: (LocalTrackDownloadProgress) -> Unit,
    ) {
        val progress = LocalTrackDownloadProgress(
            trackId = trackId,
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.coerceAtLeast(0L),
        )
        mutableLocalDownloads.update { downloads -> downloads + (trackId to progress) }
        listener(progress)
    }

    private fun publishTemporary(temporary: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun handleDownloadFailure(trackId: String, error: DataError) {
        if (error == DataError.Unauthorized) {
            onAuthorizationRequired()
        }
        logger.error(
            TAG,
            "[download] Ошибка при скачивании трека $trackId: $error",
        )
    }

    private fun String.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val TAG = "TrackCacheRepository"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PART_FILE_EXTENSION = "part"
    }
}

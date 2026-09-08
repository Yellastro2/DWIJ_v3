package com.yellastrodev.dwij.data.source

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.playback.stream.StreamingTrackCache
import com.yellastrodev.dwij.playback.stream.StreamingTrackSession
import java.io.IOException
import java.net.URI

/** Готовые ЯМ-файлы открываются локально, остальные читаются через общий потоковый кэш. */
class YaLazyDataSourceFactory(
    context: Context,
    private val trackCacheRepository: TrackCacheRepository,
    private val streamingCache: StreamingTrackCache,
) : DataSource.Factory {
    private val defaultFactory = DefaultDataSource.Factory(context.applicationContext)

    override fun createDataSource(): DataSource = object : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var actual: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            if (transferListener !in listeners) listeners += transferListener
            actual?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            try {
                val id = yandexStreamingTrackId(dataSpec.uri.toString())
                val readyFile = id?.let(trackCacheRepository::readyFile)
                val source = if (id != null && readyFile == null) {
                    YandexStreamDataSource(id, streamingCache)
                } else {
                    defaultFactory.createDataSource()
                }
                actual = source
                listeners.forEach(source::addTransferListener)
                return source.open(
                    if (readyFile != null) dataSpec.withUri(Uri.fromFile(readyFile)) else dataSpec,
                )
            } catch (error: Exception) {
                runCatching { close() }
                if (error is IOException) throw error
                throw IOException("Не удалось открыть источник аудио", error)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
            checkNotNull(actual).read(buffer, offset, readLength)

        override fun getUri(): Uri? = actual?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = actual?.responseHeaders.orEmpty()

        override fun close() {
            val previous = actual
            actual = null
            previous?.close()
        }
    }
}

/** Выделяет ID также для ya:123; неподходящий ID не используется как имя файла. */
internal fun yandexStreamingTrackId(uri: String?): String? {
    if (uri == null || !uri.startsWith("ya:", ignoreCase = true)) return null
    val parsed = URI(uri)
    if (!parsed.scheme.equals("ya", ignoreCase = true)) return null
    val id = parsed.authority ?: parsed.schemeSpecificPart.removePrefix("//")
        .substringBefore('/').substringBefore('?').substringBefore('#')
    require(id.matches(Regex("[A-Za-z0-9:_-]+"))) { "Некорректный ID трека ЯМ" }
    return id
}

/** Адаптер позиций Media3 к общей сессии. close освобождает читателя, а не текущий трек. */
private class YandexStreamDataSource(
    private val trackId: String,
    private val cache: StreamingTrackCache,
) : BaseDataSource(true) {
    private var session: StreamingTrackSession? = null
    private var opened = false
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        val current = cache.acquire(trackId)
        session = current
        val total = current.length(position)
        if (position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - position
            else minOf(dataSpec.length, total - position)
        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(session).read(
            position, buffer, offset, minOf(readLength.toLong(), remaining).toInt(),
        )
        if (count > 0) {
            position += count
            remaining -= count
            bytesTransferred(count)
        }
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        session?.let { cache.release(trackId, it) }
        session = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

package com.yellastrodev.dwij.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.playback.PlaybackUriResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Media3 DataSource, который перед открытием ресурса
 * преобразует внутренние URI вида ya://trackId
 * в реальные локальные или сетевые URI.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
class YaLazyDataSourceFactory(
    context: Context,
    trackCacheRepository: TrackCacheRepository,
) : DataSource.Factory {

    private val defaultFactory =
        DefaultDataSource.Factory(
            context.applicationContext,
        )

    private val uriResolver =
        PlaybackUriResolver(
            trackCacheRepository =
                trackCacheRepository,
        )

    override fun createDataSource(): DataSource {
        val upstream =
            defaultFactory.createDataSource()

        return object : DataSource {

            private var actual: DataSource? = null

            override fun addTransferListener(
                transferListener: TransferListener,
            ) {
                upstream.addTransferListener(
                    transferListener,
                )
            }

            override fun open(
                dataSpec: DataSpec,
            ): Long {
                return try {
                    val resolvedUri =
                        resolveUri(dataSpec.uri)

                    val resolvedDataSpec =
                        if (resolvedUri == dataSpec.uri) {
                            dataSpec
                        } else {
                            dataSpec.withUri(
                                resolvedUri,
                            )
                        }

                    actual = upstream

                    upstream.open(
                        resolvedDataSpec,
                    )
                } catch (
                    error: CancellationException,
                ) {
                    closeActual()
                    throw error
                } catch (
                    error: IOException,
                ) {
                    closeActual()
                    throw error
                } catch (
                    error: Exception,
                ) {
                    closeActual()

                    throw IOException(
                        "Не удалось разрешить URI: " +
                                dataSpec.uri,
                        error,
                    )
                }
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                readLength: Int,
            ): Int {
                val currentDataSource =
                    requireNotNull(actual) {
                        "DataSource.read() вызван до open()"
                    }

                return currentDataSource.read(
                    buffer,
                    offset,
                    readLength,
                )
            }

            override fun getUri(): Uri? {
                return actual?.uri
            }

            override fun close() {
                closeActual()
            }

            private fun resolveUri(
                originalUri: Uri,
            ): Uri {
                val resolvedUriString =
                    runBlocking {
                        uriResolver.resolve(
                            originalUri.toString(),
                        )
                    }

                return resolvedUriString.toUri()
            }

            private fun closeActual() {
                runCatching {
                    actual?.close()
                }

                actual = null
            }
        }
    }
}
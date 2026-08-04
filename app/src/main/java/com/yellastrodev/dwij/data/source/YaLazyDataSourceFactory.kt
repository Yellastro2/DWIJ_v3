package com.yellastrodev.dwij.data.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import androidx.core.net.toUri

@OptIn(UnstableApi::class)
class YaLazyDataSourceFactory(
    private val context: Context,
    private val trackCacheRepo: TrackCacheRepository
) : DataSource.Factory {

    private val defaultFactory = DefaultDataSource.Factory(context)


    override fun createDataSource(): DataSource {
        val upstream = defaultFactory.createDataSource()
        return object : DataSource {
            private var actual: DataSource? = null
            override fun addTransferListener(transferListener: TransferListener) {
                upstream.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                return try {
                    val uri = dataSpec.uri

                    if (uri.scheme == "ya") {
                        val trackId = uri.authority
                            ?: throw IOException("Track ID отсутствует в URI: $uri")

                        val realUriString = runBlocking {
                            trackCacheRepo.getOrDownload(trackId)
                        }

                        val realUri = realUriString.toUri()
                        val newSpec = dataSpec.withUri(realUri)

                        actual = upstream
                        upstream.open(newSpec)
                    } else {
                        actual = upstream
                        upstream.open(dataSpec)
                    }
                } catch (e: IOException) {
                    actual?.close()
                    actual = null
                    throw e
                }
            }

            override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
                return actual!!.read(buffer, offset, readLength)
            }

            override fun getUri(): Uri? = actual?.uri

            override fun close() {
                actual?.close()
            }
        }
    }
}

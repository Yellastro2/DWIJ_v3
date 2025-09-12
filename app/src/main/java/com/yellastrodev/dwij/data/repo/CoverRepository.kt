package com.yellastrodev.dwij.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class CoverRepository(
    private val fClient: YamApiClient,
    private val cacheDir: File
) {

    // in-memory cache
    private val memoryCache = mutableMapOf<String, Bitmap>()

    private suspend fun downloadCover(url: String, size: CoverSize): Bitmap{
        val stream = fClient.getCover(url,200)
        return stream.use {
            BitmapFactory.decodeStream(it)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun getCover(track: YaTrack, size: CoverSize = CoverSize.`200x200`): Bitmap {

        val key = "track_"+ track.id

        return getCover(key, track.ogImageUri!!, size)

    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun getCover(playlist: YaPlaylist, size: CoverSize = CoverSize.`200x200`): Bitmap {

        val key = "playlist_" + playlist.playlistUuid

        return getCover(key, playlist.ogImageUri!!, size)


    }

    suspend fun getCover(key: String, url: String, size: CoverSize = CoverSize.`200x200`): Bitmap {
        // 1. Сначала память
        memoryCache[key]?.let { return it }

        // 2. Потом диск
        val file = File(cacheDir, "$key.JPEG")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                memoryCache[key] = it
                return it
            }
        }

        // 3. Если нет, загружаем с сети
        val bitmap = downloadCover(url, size)

        // сохраняем в память
        memoryCache[key] = bitmap

        // сохраняем на диск асинхронно
        GlobalScope.launch(Dispatchers.IO) {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        }
        return bitmap
    }
}
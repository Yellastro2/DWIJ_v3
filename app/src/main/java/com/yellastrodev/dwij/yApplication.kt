package com.yellastrodev.dwij

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import android.util.LruCache
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.repo.AlbumCoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.NetResult
import com.yellastrodev.yandexmusiclib.yAccount
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class yApplication: Application() {

    val yamClient: YamApiClient by lazy {
        runBlocking(Dispatchers.IO) {
            val result = initYaM(applicationContext)
            (result as ClientResult.Success).client
        }
    }
    val trackRepository: TrackRepository by lazy {
        TrackRepository(TrackRemoteSource(yamClient))
    }

    @OptIn(DelicateCoroutinesApi::class)
    val playlistRepository: PlaylistRepository by lazy {
        val lruCache = object : LruCache<Int, YaPlaylist>(50) {
            override fun sizeOf(key: Int, value: YaPlaylist) = 1
        }
        PlaylistRepository(
            cache = PlaylistCacheSource(lruCache),
            remote = PlaylistRemoteSource(yamClient),
            scope = GlobalScope,
            trackRepo = trackRepository
        )
    }

    val playerRepo: PlayerRepository by lazy {
        PlayerRepository(applicationContext)
    }

    val albumCoverRepository: AlbumCoverRepository by lazy {
        val dir = File(applicationContext.cacheDir, "album_covers")
        if (!dir.exists()) {
            dir.mkdirs() // создаёт папку, если её нет
        }
        AlbumCoverRepository(
            yamClient,
            dir)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        playerRepo.bind()


    }

    sealed class ClientResult {
        data class Success(val client: YamApiClient) : ClientResult()
        data class Error(val reason: Reason) : ClientResult()

        enum class Reason {
            NO_TOKEN,
            NETWORK_ERROR,
            UNKNOWN
        }
    }

    suspend fun initYaM(context: Context): ClientResult {

        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)

        sharedPref.getString(YA_TOKEN, null)?.let { token ->
            var userId = sharedPref.getString(YA_ID, null)
            if (userId == null) {

                var netResult = yAccount.showInformAccount(token)
                Log.i("DWIJ_TAG", netResult.toString())
                if (netResult is NetResult.Success){
                    var f_res = netResult.json
                        .getJSONObject("result")
                        .getJSONObject("account")
                        .getString("uid")
                    with (sharedPref.edit()) {
                        putString(YA_ID, f_res )
                        apply()
                    }
                    userId = f_res
                }else{
                    Log.e("DWIJ_TAG", "Ошибка авторизации: ${netResult.toString()}")
                    return ClientResult.Error(ClientResult.Reason.UNKNOWN)
                }

            }
            return ClientResult.Success(YamApiClient(token, userId))
        }?: run {
            withContext(Dispatchers.IO) {
                MainActivity.LOG.info("no YandexMusic login")
            }
            return ClientResult.Error(ClientResult.Reason.NO_TOKEN)
        }

    }
}
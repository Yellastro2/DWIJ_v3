package com.yellastrodev.dwij.data.source

import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import org.json.JSONObject

class PlaylistRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(kind: String): YaPlaylist = client.getPlaylistObj(kind)
    suspend fun fetchAll(): List<YaPlaylist> = client.getUserListPllists()
}
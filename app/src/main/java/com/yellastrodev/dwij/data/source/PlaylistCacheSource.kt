package com.yellastrodev.dwij.data.source

import android.util.LruCache
import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlin.uuid.Uuid

class PlaylistCacheSource(private val cache: LruCache<Int, dYaPlaylist>) {
    fun get(id: Int): dYaPlaylist? = cache[id]
    fun put(dPlaylist: dYaPlaylist) { cache.put(dPlaylist.playlistUuid.hashCode(), dPlaylist) }
    fun getAll(): List<dYaPlaylist> = cache.snapshot().values.toList()
    fun remove(uuid: String): dYaPlaylist = cache.remove(uuid.hashCode())
    fun putAll(playlists: List<dYaPlaylist>) {
        playlists.forEach { put(it) }
    }
}
package com.yellastrodev.dwij.data.source

import android.util.LruCache
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist

class PlaylistCacheSource(private val cache: LruCache<Int, YaPlaylist>) {
    fun get(id: Int): YaPlaylist? = cache[id]
    fun put(dPlaylist: YaPlaylist) { cache.put(dPlaylist.playlistUuid.hashCode(), dPlaylist) }
    fun getAll(): List<YaPlaylist> = cache.snapshot().values.toList()
}
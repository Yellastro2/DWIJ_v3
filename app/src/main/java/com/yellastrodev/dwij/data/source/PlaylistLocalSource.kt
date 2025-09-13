package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist

class PlaylistLocalSource(private val dao: dPlaylistDao) {
    suspend fun get(id: String): dYaPlaylist? = dao.getdPlaylistById(id)
    suspend fun getAll(): List<dYaPlaylist> = dao.getAlldPlaylists()
    suspend fun save(dPlaylist: dYaPlaylist) = dao.insert(dPlaylist)
    suspend fun saveAll(dPlaylists: List<dYaPlaylist>) = dao.insertAll(dPlaylists)
    suspend fun remove(string: String) {
        dao.delete(string)
    }
}
package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.dwij.entities.dYaTrack

class TrackLocalSource(private val dao: dTrackDao) {
    suspend fun get(id: String): dYaTrack? = dao.getTrack(id)
    suspend fun getAll(): List<dYaTrack> = dao.getAllTracks()
    suspend fun save(dPlaylist: dYaTrack) = dao.insert(dPlaylist)
    suspend fun saveAll(dPlaylists: List<dYaTrack>) = dao.insertAll(dPlaylists)
    suspend fun remove(string: String) {
        dao.delete(string)
    }
}
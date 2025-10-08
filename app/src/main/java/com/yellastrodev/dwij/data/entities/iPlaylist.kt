package com.yellastrodev.dwij.data.entities

interface iPlaylist {
    val title: String
    val tracks: List<dPlaylistTrack>
//    val kind: String
//    val id: String
    val revision: Int
    val trackCount: Int
    val durationMs: Int?

    fun getdId(): String
}
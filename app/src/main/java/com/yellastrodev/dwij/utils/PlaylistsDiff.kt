package com.yellastrodev.dwij.utils

import com.yellastrodev.yandexmusiclib.entities.YaPlaylist

class PlaylistsDiff {
    companion object {

        data class PlaylistDiffResult(
            val added: List<String>,
            val removed: List<String>,
            val changed: List<String>
        ) {
            fun isNotEmpty(): Boolean = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()
            suspend fun forEachNew(function: suspend (String) -> Unit) {
                added.forEach { function(it) }
                changed.forEach { function(it) }
            }
        }

        fun diffPlaylists(
            oldMap: Map<String, YaPlaylist>,
            newList: List<YaPlaylist>
        ): PlaylistDiffResult {
            val newMap = newList.associateBy { it.playlistUuid }

            val added = mutableListOf<String>()
            val removed = mutableListOf<String>()
            val changed = mutableListOf<String>()

            val allUuids = oldMap.keys union newMap.keys
            for (uuid in allUuids) {
                val oldPl = oldMap[uuid]
                val newPl = newMap[uuid]

                when {
                    oldPl == null && newPl != null -> added.add(uuid)
                    oldPl != null && newPl == null -> removed.add(uuid)
                    oldPl != null && newPl != null && oldPl.revision != newPl.revision -> changed.add(uuid)
                }
            }

            return PlaylistDiffResult(added, removed, changed)
        }
    }
}
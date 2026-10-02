package com.yellastrodev.dwij.data.repo

import com.yellastrodev.vkmusicsdk.VkAudio

/** Объединяет списки за линейное время: source-id, серверный release-id и подтверждённые audio.add связи. */
internal fun distinctVkLibraryTracks(tracks: List<VkAudio>, myTracks: List<VkAudio>,
    aliases: Map<String, String>): List<VkAudio> {
    val ownIds = buildMap {
        myTracks.forEach { audio ->
            put(audio.fullId, audio.fullId)
            audio.releaseAudioId?.takeIf(String::isNotBlank)?.let { put(it, audio.fullId) }
        }
    }
    val releaseIds = buildMap {
        tracks.forEach { audio ->
            audio.releaseAudioId?.takeIf(String::isNotBlank)?.let { release ->
                put(audio.fullId, release)
                put(release, release)
            }
        }
    }
    return tracks.distinctBy { audio ->
        ownIds[aliases[audio.fullId] ?: audio.fullId]
            ?: audio.releaseAudioId?.let { ownIds[it] }
            ?: releaseIds[audio.fullId]
            ?: aliases[audio.fullId]
            ?: audio.fullId
    }
}

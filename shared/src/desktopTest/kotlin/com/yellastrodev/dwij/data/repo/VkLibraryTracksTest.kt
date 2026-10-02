package com.yellastrodev.dwij.data.repo

import com.yellastrodev.vkmusicsdk.VkAudio
import kotlin.test.*

/** Проверяет объединение по серверной идентичности, не по похожим названиям. */
class VkLibraryTracksTest {
    /** Личные треки стоят первыми; повторы из нескольких плейлистов и audio.add копии схлопываются. */
    @Test fun unionKeepsOrderAndRemovesKnownCopies() {
        val own = VkAudio(100, 42)
        val original = VkAudio(9, -7)
        val another = VkAudio(10, -7)
        assertEquals(listOf(own, another), distinctVkLibraryTracks(
            listOf(own, original, another, another), listOf(own), mapOf(original.fullId to own.fullId)))
    }

    /** release_audio_id связывает копии после рестарта, одинаковые названия разных записей не объединяются. */
    @Test fun releaseIdentityDoesNotMergeByTitle() {
        val original = VkAudio(9, -7, title = "Одинаковое название")
        val own = VkAudio(100, 42, releaseAudioId = original.fullId, title = original.title)
        val other = VkAudio(11, -7, title = original.title)
        assertEquals(listOf(own, other), distinctVkLibraryTracks(
            listOf(own, original, other), listOf(own), emptyMap()))
        assertEquals(listOf(original, other), distinctVkLibraryTracks(listOf(original, other), emptyList(), emptyMap()))
    }
}

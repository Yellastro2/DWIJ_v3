package com.yellastrodev.dwij

import androidx.compose.runtime.Immutable

/**
 * Независимые от источника данные одной строки трека.
 *
 * [isYandexUnavailable], [hasMultipleSources] и [hasUnresolvedMatchCandidate] включают
 * правые индикаторы, а [isPlaybackBlocked] приглушает недоступные метаданные.
 */
@Immutable
data class TrackListItemUiModel(
    val key: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val shouldLoadCover: Boolean = true,
    val isYandexUnavailable: Boolean = false,
    val isPlaybackBlocked: Boolean = false,
    val hasMultipleSources: Boolean = false,
    val hasUnresolvedMatchCandidate: Boolean = false,
)
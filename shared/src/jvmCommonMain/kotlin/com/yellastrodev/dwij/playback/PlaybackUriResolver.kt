package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import java.net.URI

/**
 * Преобразует внутренний URI воспроизведения в реальный URI источника.
 *
 * Обычные file/content/http URI возвращаются без изменений.
 * URI вида ya://trackId скачивается или берётся из кэша.
 */
class PlaybackUriResolver(
    private val trackCacheRepository: TrackCacheRepository,
) {

    suspend fun resolve(
        uri: String,
    ): String {
        if (!uri.startsWith("$YANDEX_SCHEME:", ignoreCase = true)) {
            return uri
        }

        val trackId = extractYandexTrackId(uri)
            ?: throw IllegalArgumentException(
                "Track ID отсутствует в URI: $uri",
            )

        return trackCacheRepository.getOrDownload(
            trackId,
        )
    }

    private fun extractYandexTrackId(
        uri: String,
    ): String? {
        val parsed = runCatching {
            URI(uri)
        }.getOrNull() ?: return null

        if (!parsed.scheme.equals(YANDEX_SCHEME, ignoreCase = true)) {
            return null
        }

        /*
         * Основной формат:
         *
         * ya://123456
         *
         * authority == "123456"
         */
        parsed.authority
            ?.takeIf(String::isNotBlank)
            ?.let { authority ->
                return authority
            }

        /*
         * Дополнительно поддерживаем:
         *
         * ya:123456
         */
        return parsed.schemeSpecificPart
            ?.removePrefix("//")
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf(String::isNotBlank)
    }

    private companion object {
        const val YANDEX_SCHEME = "ya"
    }
}
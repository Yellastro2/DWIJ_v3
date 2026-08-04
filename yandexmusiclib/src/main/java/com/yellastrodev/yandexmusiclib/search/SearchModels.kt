package com.yellastrodev.yandexmusiclib.search

import com.yellastrodev.yandexmusiclib.entities.YaAlbum
import com.yellastrodev.yandexmusiclib.entities.YaArtist
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.yUtils.IntOrStringAsStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Тип сущностей, среди которых API Яндекс Музыки выполняет поиск. */
@Serializable
enum class SearchType(val apiValue: String) {
    @SerialName("all")
    ALL("all"),

    @SerialName("artist")
    ARTIST("artist"),

    @SerialName("user")
    USER("user"),

    @SerialName("album")
    ALBUM("album"),

    @SerialName("playlist")
    PLAYLIST("playlist"),

    @SerialName("track")
    TRACK("track"),

    @SerialName("video")
    VIDEO("video"),

    @SerialName("podcast")
    PODCAST("podcast"),

    @SerialName("podcast_episode")
    PODCAST_EPISODE("podcast_episode"),
}

/** Страница результатов одной категории поиска. */
@Serializable
data class SearchSection<T>(
    val total: Int,
    @SerialName("perPage")
    val perPage: Int,
    val order: Int,
    val results: List<T>,
)

/** Компактное представление результата категории, для которой в SDK нет собственной модели. */
@Serializable
data class SearchItem(
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val id: String? = null,
    @Serializable(with = IntOrStringAsStringSerializer::class)
    val uid: String? = null,
    val title: String? = null,
    val name: String? = null,
)

/** Лучшее совпадение, которое API возвращает вместе с поисковой выдачей. */
@Serializable
data class SearchBest(
    val type: SearchType? = null,
    val result: SearchItem? = null,
    val text: String? = null,
)

/** Полный ответ API Яндекс Музыки на запрос `/search`. */
@Serializable
data class SearchResponse(
    @SerialName("searchRequestId")
    val searchRequestId: String,
    val text: String,
    val best: SearchBest? = null,
    val albums: SearchSection<YaAlbum>? = null,
    val artists: SearchSection<YaArtist>? = null,
    val playlists: SearchSection<YaPlaylist>? = null,
    val tracks: SearchSection<YaTrack>? = null,
    val videos: SearchSection<SearchItem>? = null,
    val users: SearchSection<SearchItem>? = null,
    val podcasts: SearchSection<SearchItem>? = null,
    @SerialName("podcastEpisodes")
    val podcastEpisodes: SearchSection<SearchItem>? = null,
    val type: SearchType? = null,
    val page: Int? = null,
    @SerialName("perPage")
    val perPage: Int? = null,
    @SerialName("misspellResult")
    val misspellResult: String? = null,
    @SerialName("misspellOriginal")
    val misspellOriginal: String? = null,
    @SerialName("misspellCorrected")
    val misspellCorrected: Boolean? = null,
    val nocorrect: Boolean? = null,
)

/** Подсказки API Яндекс Музыки для введённой части поискового запроса. */
@Serializable
data class SearchSuggestions(
    val best: SearchBest? = null,
    val suggestions: List<String> = emptyList(),
)

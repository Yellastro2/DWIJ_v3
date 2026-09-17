# Лендинг Яндекс Музыки

Проверено по исходникам MarshalX/yandex-music-api 2026-09-17. Живой запрос с аккаунтом
в этой задаче не выполнялся; фикстуры тестов синтетические.

## Запрос и формат

`GET https://api.music.yandex.net/landing3?blocks=personalplaylists`
с `Authorization: OAuth <token>`. Несколько блоков передаются через запятую.

Известные блоки: `personalplaylists`, `promotions`, `new-releases`, `new-playlists`,
`mixes`, `chart`, `artists`, `albums`, `playlists`, `play_contexts`; OpenAPI-схема
также перечисляет `podcasts`. Доступность и наполнение зависят от сервера/аккаунта.

Карточки лежат в `result.blocks[].entities[]`. Типы карточек отличаются от типов
блоков: `personal-playlist`, `promotion`, `album`, `playlist`, `chart-item`,
`play-context`, `mix-link`. Поле `data` зависит от типа карточки.

Для `personal-playlist` поле `data` — обёртка с `type`, `ready`, `notify` и ещё одним
`data` (кратким плейлистом, может отсутствовать). Тип плейлиста дня —
`playlistOfTheDay`: проверяется `data.data.generatedPlaylistType`, а при его
отсутствии — `data.type`. Прочие известные типы обёртки: `origin`, `recentTracks`,
`neverHeard`, `podcasts`, `missedLikes`.

Готовый плейлист раскрывается через `/users/{uid}/playlists/{kind}` с идентификаторами
из карточки, не с UID текущего клиента. Нельзя считать краткую карточку полной моделью
YaPlaylist: обязательные для detail-ответа поля могут отсутствовать.

## SDK

`YamApiClient.landing(blocks)` сохраняет произвольное содержимое карточек как JsonElement.
`personalPlaylists()` возвращает типизированные GeneratedPlaylist, включая неготовые.
`playlistOfTheDay()` возвращает PlaylistDetails или Success(null), если готовой подборки
нет. Сетевые ошибки и ошибки формата остаются Failure. Автоматический fallback на `/feed`
не реализован.

## Ограничения проверки

Внешняя Python-библиотека дополнительно передаёт константный `eitherUserId` с TODO о его
назначении. SDK его не копирует: необходимость параметра для нашего аккаунта не проверена.
Если сервер не выдаёт персональный блок, сначала проверить живой ответ и этот параметр.

## Источники

- [Запросы landing и feed](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/_client/landing.py)
- [Типы карточек](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/landing/block_entity.py)
- [Обёртка персонального плейлиста](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/feed/generated_playlist.py)
- [Пример поиска плейлиста дня](https://ym.marshal.dev/examples.daily_playlist_updater/)
- [OpenAPI-схема](https://github.com/acherkashin/yandex-music-open-api/blob/main/src/yandex-music.yaml)

# VK Music API: источники и наблюдения

Исходники предоставлены пользователем в `D:\ExternalRepos`:

- [ilyhalight/vk-audio](https://github.com/ilyhalight/vk-audio): основа транспорта,
  моделей audio, поиска и catalog.getAudio/getSection; url в Audio — m3u8.
- [issamansur/vkpymusic](https://github.com/issamansur/vkpymusic): дополнительные
  audio.getById/getPlaylists/get/getRecommendations и HLS AES/IV/BYTERANGE/MAP.
- [LavaSrc, VK Music](https://github.com/topi314/LavaSrc#vk-music): только рецепт
  OAuth клиента Маруси 6463690, redirect https://oauth.vk.com/blank.html,
  response_type=token, scope=1073737727. Клиентский secret для implicit flow не нужен.

Проверено чтением локальных исходников 2026-10-02; настоящие запросы с аккаунтом
VK не выполнялись. README vk-audio и LavaSrc также просмотрены на GitHub.

Особенности, влияющие на реализацию:

- Идентичность аудио — owner_id + id; owner_id может быть отрицательным.
- access_key нужно передавать при повторном запросе закрытого аудио/плейлиста.
- Подписанный media URL запрашивается заново перед запуском, не сохраняется в Room.
- vkpymusic явно обрабатывает AES-128 CBC, sequence IV, explicit IV, смену ключей,
  init map и byte range; это не гарантирует поддерживаемый кодек в JavaFX.
- Рецепт LavaSrc сообщает о работающем музыкальном токене Маруси, но применимость
  к конкретному аккаунту/региону требует реального входа и запроса audio.search.
- VK error_code не равен HTTP-коду: API может вернуть ошибку в HTTP 200.
  Код 5 означает отклонённую авторизацию; captcha/лимиты не обходятся автоматически.
- Пример callback в README LavaSrc не содержит state. Обязательная проверка его наличия
  отклоняет такой вручную вставленный URL до сетевого запроса. В нашем ручном сценарии
  отсутствующий state принимается с диагностикой, присутствующий должен совпасть.
  Это исключение не предназначено для автоматического callback/deep link.

## Диагностика первого запуска (2026-10-02)

Пользователь подтвердил запуск desktop-интерфейса логом и скриншотами, сообщил
о неконтрастных кнопках/label и отказе при вставке ссылки. Позже предоставленный callback
подтвердил домен oauth.vk.ru: исходный обработчик принимал только oauth.vk.com.
Содержимое callback и учётные данные не сохраняются. В исходном обработчике также были
безусловное требование state и общий catch без логирования. Исправлена ручная
совместимость, добавлены типизированные безопасные причины и читаемый текст UI.
После исправления пользователь подтвердил работающий VK-сценарий.

### Android: промежуточный VK ID callback (2026-10-02)

Пользователь предоставил callback `oauth.vk.ru/blank.html#payload=...&state=...`.
В payload `type=silent_token`, `auth=1`, `ttl=600`; access_token отсутствует.
Персональный профиль, token, hash и исходный URL не сохраняются в документации.
Это подтверждает иной формат ответа на данном Android-входе, но не доказывает,
что любой мобильный браузер всегда возвращает такой формат.

Рецепт [LavaSrc](https://github.com/topi314/LavaSrc#vk-music) рассчитан на callback
с access_token. В [исходниках vksdk](https://github.com/SevereCloud/vksdk/blob/master/api/auth.go)
обмен silent-токена описан отдельным методом `auth.exchangeSilentAuthToken`,
возвращающим access_token. Совместимость такого обмена с OAuth Маруси не проверена;
в Движе он не реализован, payload.token не подменяет музыкальный токен.

Гипотеза для ручной проверки: повторный вход через desktop-версию страницы в браузере
может вернуть обычный implicit callback. OAuth уже передаёт display=page, поэтому
добавление этого же параметра не исправляет наблюдаемый ответ само по себе.

Android-проверка WebView выявила попытку перехода вне HTTPS; исходный лог не содержит
схему, поэтому конкретный протокол ещё не установлен. Обработка `intent://` и VK-схем
добавлена с безопасной диагностикой. [Chrome Intent URI](https://developer.chrome.com/docs/android/intents)
может содержать `browser_fallback_url` для случая отсутствия внешнего приложения.
Сам запуск внешнего приложения не задаёт контракт возврата в исходный WebView;
способ продолжения именно этого VK-входа требует проверки на устройстве.

## Фонотека и операции плейлистов

В [объяснении команды VK Музыки от 2024-10-21](https://vc.ru/design/1600718-sdelat-prilozhenie-takim-zhe-emocionalnym-kak-sama-muzyka)
«Мои треки» названы основной сущностью личной коллекции. Добавление в «Мою музыку»
и лайк объединены в одну кнопку, которая также влияет на рекомендации.
Название поля `like` в audio само по себе не доказывает наличие отдельного списка любимых.

Параметры операций сверены с [описанием VK Audio Token](https://vodka2.github.io/vk-audio-token/)
и исходниками vk-audio/vkpymusic; это не официальный контракт VK и не проверка на живом аккаунте:

- `users.get` без user_ids определяет пользователя токена.
- `audio.getPlaylists`: owner_id, offset/count; `audio.getPlaylistById`: owner_id, playlist_id, access_key.
- `audio.createPlaylist`: owner_id, title; `audio.deletePlaylist`: owner_id, playlist_id.
- `audio.addToPlaylist` и `audio.removeFromPlaylist`: owner_id, playlist_id, **audio_ids**.
  В таблице справочника написано audios, но рабочие примеры используют audio_ids;
  SDK использует параметр из примеров. Идентификаторы имеют формат owner_id_audio_id,
  для добавления закрытого трека передаётся также access_key.
- `audio.get` читает состав по **album_id**, хотя сущность называется playlist.
- `permissions.edit/delete` — права из модели vk-audio; `original` указывает на исходную
  чужую подборку. Добавление чужого плейлиста и копирование всех треков — разные операции.
- Удаление из плейлиста вызывает `audio.removeFromPlaylist`, а не `audio.delete`.
- Новые методы плейлистов в текущем токене Маруси требуют ручной проверки пользователем.

## Личная коллекция: сверка локальных исходников 2026-10-02

- `vkpymusic/vk_api/vk_api_request_builder.py:build_req_get`: `audio.get` без
  `album_id` читает аудио пользователя, с `album_id` — состав конкретного плейлиста.
- `vk-audio/src/types/api/audio/add.ts` и `src/index.ts:add`: новый ответ `audio.add`
  содержит `items[].new_audio_id/new_owner_id`, а не просто числовой ID.
  `src/types/api/audio/delete.ts`: удаление подтверждается `audio_ids: string[]`.
- `vk-audio/src/types/api/audio/index.ts:Audio` содержит необязательный `release_audio_id`;
  приложение использует предоставленные сервером связи ID, не сопоставляет по названиям.
- Пользователь проверил в VK: добавление в плейлист не добавляет трек в «Мои треки».
  Членство в личной коллекции и в плейлистах должно храниться раздельно.

## Гипотезы

Версия API 5.282 и VKAndroidApp user-agent из vk-audio совместимы с токеном Маруси.
Это нужно подтвердить первым тестом. SDK оставляет API version настраиваемой.

Полностью расшифрованные media-сегменты VK воспроизводятся через native HLS
Android/JavaFX без перекодирования. Это зависит от реального контейнера/кодека
и пока не подтверждено запуском приложения.

# yandexmusiclib

`yandexmusiclib` — самостоятельная Kotlin-JRE-библиотека для работы с API
Яндекс Музыки, созданная на основе знаний и контрактов, собранных в
[MarshalX/yandex-music-api](https://github.com/MarshalX/yandex-music-api).

Модуль предоставляет необходимую приложению `Движ` работу с API Яндекс Музыки:
авторизацию, аккаунт, треки, альбомы, плейлисты, «Мою волну», получение ссылок
для воспроизведения и отправку feedback-событий.

Это неофициальная библиотека. API Яндекс Музыки не является публичным
стабильным контрактом, поэтому поведение методов и модели ответов следует
сверять с оригинальной Python SDK и фактическими ответами сервиса.

## Концепция модуля

`yandexmusiclib` не является переводом Python-библиотеки на Kotlin и не
стремится построчно или структурно повторять её код.

Оригинальная SDK используется как проверенная основа знаний об API: источник
endpoint-адресов, параметров запросов, моделей ответов, особенностей поведения
и результатов reverse engineering. На этой основе модуль проектируется
самостоятельно:

- публичный API следует Kotlin-конвенциям, а не структуре Python-пакета;
- асинхронные операции строятся вокруг Kotlin Coroutines и `suspend`;
- модели учитывают Kotlin null-safety и типизированные результаты;
- авторизация, сетевое взаимодействие и жизненный цикл ресурсов адаптируются
  для использования внутри Android-приложений;
- классы, методы и обёртки Python SDK не обязаны иметь соответствие один к
  одному с Kotlin API;
- приоритетом являются корректное поведение API, удобство Android-разработки
  и потребности приложения `Движ`.

Если контракт Python SDK и идиоматичный Kotlin-дизайн расходятся, необходимо
сохранить сетевую семантику и ожидаемый результат, но выбрать решение,
естественное для Kotlin и Android.

## Локальный клон оригинальной SDK

Для анализа поведения API рядом с проектом используется локальный клон
оригинального репозитория. Путь к нему задаётся в файле
`yandexmusiclib/local.properties`:

```properties
LOCAL_CLONE=C\:\\path\\to\\yandex-music-api
```

У каждого разработчика путь может быть своим. `LOCAL_CLONE`
служит ориентиром для локальной разработки и сравнения реализаций; клон Python
SDK не является зависимостью Android-сборки.

Если клона ещё нет, его можно подготовить отдельно:

```powershell
git clone https://github.com/MarshalX/yandex-music-api.git C:\path\to\yandex-music-api
```

## Как использовать оригинал

При реализации или проверке метода:

1. Найти соответствующий публичный метод и модель в локальном клоне.
2. Проверить endpoint, HTTP-метод, параметры, заголовки и формат тела запроса.
3. Сопоставить nullable-поля, значения по умолчанию и вложенные модели ответа.
4. Спроектировать идиоматичный Kotlin-контракт с учётом Coroutines и Android.
5. Не воспроизводить Python-структуру и обёртки, если они не нужны контракту
   модуля.
6. Не логировать токены, cookies и полные заголовки авторизации.

Основные точки входа Kotlin-реализации:

- `YamApiClient.kt` — запросы к API и высокоуровневые операции;
- `network/` — типизированные результаты и новый сетевой transport;
- `auth/YandexDeviceAuth.kt` — OAuth Device Flow;
- `account/` — типизированные модели и операции аккаунта;
- `likes/`, `playlists/`, `tracks/`, `rotor/` — типизированные API-срезы;
- `download/` и `covers/` — ссылки воспроизведения и бинарный контент;
- `entities/` — модели данных;

## Авторизация

Device Flow повторяет сетевую семантику локальной Python SDK, но предоставляет
Kotlin API с `suspend` и типизированным результатом:

```kotlin
val result = YandexDeviceAuth().authorize { code ->
    println("Откройте ${code.verificationUrl} и введите ${code.userCode}")
}

when (result) {
    is DeviceAuthResult.Success -> {
        val token = result.value
        // Сохранить token.accessToken и при необходимости token.refreshToken.
    }
    is DeviceAuthResult.Failure -> {
        // Обработать типизированную result.error.
    }
}
```

Корутину с `authorize` можно отменить штатным способом. Низкоуровневые методы
`requestDeviceCode()` и `pollDeviceToken()` доступны отдельно; pending-ответ
при опросе представлен как `Success(null)`.

## Типизированные запросы API

Новые операции API возвращают единый `YamResult<T>`. Например:

```kotlin
when (val result = client.accountStatus()) {
    is YamResult.Success -> {
        val account = result.value.account
        println(account?.login)
    }
    is YamResult.Failure -> {
        // result.error: Unauthorized, Timeout, NoInternet, Http и т. д.
    }
}
```

Операции лайков также типизированы:

```kotlin
val update = client.setTrackLiked(trackId = trackId, liked = true)
val likedTracks = client.likedTracks()
```

Остальные основные срезы используют тот же контракт:

```kotlin
val playlists = client.playlists()
val playlist = client.playlist(kind = 1000)
val tracks = client.tracks(listOf("123", "456"))

val wave = client.startWave("user:onyourwave")
val directUrl = client.trackDownloadUrl("123")
val cover = client.coverBytes(coverUri, CoverSize.`400x400`)
```

Универсальная телеметрия прослушивания отправляется через `/play-audio`:

```kotlin
val started = client.playAudio(
    PlayAudioRequest(
        trackId = "123",
        albumId = "456",
        source = "dwij-android",
        playId = playId,
        endPositionSeconds = durationSeconds
    )
)
```

При завершении или прерывании воспроизведения отправляется второй запрос с тем
же `playId`, фактическими `totalPlayedSeconds` и `endPositionSeconds`.
`playlistId` указывается только при воспроизведении из плейлиста. Для радио
`play-audio` дополняет, но не заменяет специализированные rotor feedback.

`JSONObject`, `JSONArray` и транспортные ответы не появляются в публичном API.
Сетевые операции возвращают `YamResult<T>`, а транспорт отдельно обрабатывает
JSON API и бинарный контент.

## Лицензия и атрибуция

Оригинальный проект `MarshalX/yandex-music-api` распространяется по лицензии
LGPL-3.0. При прямом использовании или адаптации его кода необходимо учитывать
условия этой лицензии и сохранять ссылку на источник.

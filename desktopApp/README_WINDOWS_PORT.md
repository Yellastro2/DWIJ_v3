# DWIJ Windows prototype

Первый desktop-модуль поверх существующего `shared`.

## Что уже заведено

- тот же shared `DwijComponent`;
- тот же shared Compose `DwijApp`;
- Room database в `%APPDATA%\DWIJ\dwij.db`;
- shared key-value settings в `%APPDATA%\DWIJ\settings.properties`;
- кэши в `%LOCALAPPDATA%\DWIJ\cache`;
- `DesktopPlayerEngine` на JavaFX Media;
- Swing `Dispatchers.Main` для shared ViewModel/Lifecycle;
- Yandex `ya://trackId` проходит через существующий `PlaybackUriResolver`,
  поэтому скачивание/кэш остаются shared;
- базовое чтение локальной музыки из `%USERPROFILE%\Music`;
- экспорт DWIJ M3U8;
- clipboard/browser для Settings;
- возврат фокуса после OAuth browser как аналог Android `ON_RESUME`;
- Windows `exe`/`msi` target в Compose Desktop.

## Что надо сделать в корне проекта

Нужны три небольшие правки (готовые `.patch` лежат рядом с модулем в архиве):

1. В `gradle/libs.versions.toml` добавить plugin alias:

```toml
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

2. В root `build.gradle.kts` добавить:

```kotlin
alias(libs.plugins.kotlin.jvm) apply false
```

3. В `settings.gradle.kts` добавить:

```kotlin
include(":desktopApp")
```

Такой отдельный JVM-модуль соответствует текущей рекомендуемой структуре Compose Desktop.

## Запуск

Из PowerShell в корне репозитория:

```powershell
.\gradlew.bat :desktopApp:run
```

Сборка Windows distribution:

```powershell
.\gradlew.bat :desktopApp:packageMsi
```

или:

```powershell
.\gradlew.bat :desktopApp:packageExe
```

## Yandex OAuth

При обычном запуске из корня проекта модуль читает те же ключи из
`local.properties`, что и Android:

```properties
YANDEX_OAUTH_CLIENT_ID=...
YANDEX_OAUTH_CLIENT_SECRET=...
```

Также поддерживаются environment variables:

```text
DWIJ_YANDEX_OAUTH_CLIENT_ID
DWIJ_YANDEX_OAUTH_CLIENT_SECRET
```

Для packaged EXE/MSI environment variables надёжнее, потому что
`local.properties` рядом с установленным приложением обычно нет.

## Музыкальные каталоги

По умолчанию сканируется:

```text
%USERPROFILE%\Music
```

Можно задать свои каталоги через `DWIJ_MUSIC_DIRS`.
На Windows несколько путей разделяются `;`, например:

```text
D:\Music;E:\Audio
```

## Ограничения первого прохода

1. JavaFX backend пока рассчитан на MP3/AAC/M4A/WAV/AIFF.
   FLAC/OGG пока не индексируются.
2. Локальные ID3/Vorbis metadata пока не читаются:
   title/artist грубо определяются по `Artist - Title.ext`,
   album — по имени папки, duration в БД сначала `0`.
3. Локальная обложка пока только sidecar `cover.jpg/png`,
   `folder.jpg/png`, `front.jpg/png`.
4. Импорт чужих M3U пока не реализован. Экспорт DWIJ M3U уже есть.
5. Нет Windows SMTC/media keys/виджета системного плеера.
6. Нет FileSystem watcher — синхронизация выполняется на старте и
   по существующим кнопкам refresh/sync.
7. Desktop playback пока не отправляет Android Media3-style
   playback feedback events в `PlaybackFeedbackTracker`.
8. Shuffle в прототипе выбирает случайный следующий индекс без истории.

Именно эти места стоит проверять после первого запуска. Если базовое окно,
Room, Яндекс, очередь и звук стартуют нормально — дальше можно улучшать
платформенную часть без переделки shared UI/репозиториев.

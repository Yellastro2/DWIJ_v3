# Движ для Windows

Windows/JVM-модуль поверх общего `:shared`. Первоначальная настройка репозитория
и требования описаны в [корневом README](../README.md).

## Что уже заведено

- тот же shared `DwijComponent`;
- тот же shared Compose `DwijApp`;
- Room database в `%APPDATA%\DWIJ\dwij.db`;
- shared key-value settings в `%APPDATA%\DWIJ\settings.properties`;
- зашифрованная через Windows DPAPI сессия Яндекс Музыки в
  `%APPDATA%\DWIJ\yandex-session.bin`;
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

## Ограничения текущей версии

1. JavaFX backend пока рассчитан на MP3/AAC/M4A/WAV/AIFF.
   FLAC/OGG пока не индексируются.
2. Локальные ID3/Vorbis metadata пока не читаются:
   title/artist грубо определяются по `Artist - Title.ext`,
   album — по имени папки, duration в БД сначала `0`.
3. Локальная обложка пока только sidecar `cover.jpg/png`,
   `folder.jpg/png`, `front.jpg/png`.
4. Импорт чужих M3U пока не реализован. Экспорт DWIJ M3U уже есть.
5. Нет FileSystem watcher — синхронизация выполняется на старте и
   по существующим кнопкам refresh/sync.
6. Shuffle в прототипе выбирает случайный следующий индекс без истории.

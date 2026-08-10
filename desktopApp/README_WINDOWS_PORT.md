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
- ID3/MP4/WAV/AIFF metadata, duration и embedded artwork через
  `jaudiotagger`, с fallback на имя файла, каталог и sidecar-картинки;
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

Автоматического поиска музыки по устройству пока нет. По умолчанию desktop-клиент
рекурсивно сканирует только один каталог:

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
   У raw AAC без контейнера теги и duration могут остаться недоступны;
   для него сохраняется fallback по имени файла и каталогу.
2. Импорт чужих M3U пока не реализован. Экспорт DWIJ M3U уже есть.
3. Нет FileSystem watcher — синхронизация выполняется на старте и
   по существующим кнопкам refresh/sync.
4. Shuffle в прототипе выбирает случайный следующий индекс без истории.

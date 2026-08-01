# Растеризация тяжёлых SVG

Тяжёлые декоративные изображения хранятся как редактируемые SVG в
`app/src/main/vector-png`. Во время Android-сборки задача
`RasterizeSvgToPngTask` создаёт PNG для `mdpi`, `hdpi`, `xhdpi`, `xxhdpi` и
`xxxhdpi` и подключает их к variant resources.

## Как запускается

Для каждого Android-варианта регистрируется собственная задача, например:

- `rasterizeDebugSvgToPng`;
- `rasterizeReleaseSvgToPng`.

Обычные `assembleDebug`, запуск приложения из Android Studio и resource merge
автоматически вызывают нужную задачу. Результат находится в
`app/build/generated/res/vectorPng/<variant>/drawable-<density>` и не должен
добавляться в Git.

Задача объявляет SVG-каталог, список ресурсов и density-конфигурацию входами,
а generated-res каталог — выходом. Поэтому без изменений она получает статус
`UP-TO-DATE`; при доступном Gradle build cache результат также может быть
восстановлен со статусом `FROM-CACHE`. `clean` удаляет generated-res каталог,
после чего PNG создаются заново или восстанавливаются из build cache.

Если изменился хотя бы один SVG, текущая пакетная задача заново генерирует все
настроенные PNG. Для тринадцати ресурсов это намеренно проще отдельного
incremental-учёта каждого файла.

## Где настраивается

Имена и размеры ресурсов задаются в `rasterizedSvgAssets`, а коэффициенты
плотности — в `rasterizedSvgDensities` внутри `app/build.gradle.kts`. При
добавлении SVG нужно одновременно добавить его имя и исходный размер в dp.

Рендер выполняет Apache Batik `1.19`, подключённый только к build logic через
`buildSrc`; библиотека не попадает в APK. При первой сборке Gradle скачивает её
из Maven Central, если зависимости ещё нет в локальном кэше.

Исходные VectorDrawable XML сохранены в `res/drawable` с префиксом `_` как
отдельные reference-ресурсы для визуального сравнения. Они больше не определяют
исходные имена `R.drawable`: эти имена создаются только PNG из density-каталогов.

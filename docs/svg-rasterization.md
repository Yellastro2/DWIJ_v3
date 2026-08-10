# Растеризация тяжёлых SVG

Тяжёлые общие декоративные изображения хранятся как редактируемые SVG в
`shared/src/commonMain/vector-png`. Они принадлежат модулю `:shared`, поэтому
один набор ресурсов используется Android- и Windows-интерфейсом.

Задача `rasterizeSharedSvgToPng` создаёт PNG для `mdpi`, `hdpi`, `xhdpi`,
`xxhdpi` и `xxxhdpi`. Затем `mergeSharedComposeResources` объединяет результат
с обычными ресурсами из `shared/src/commonMain/composeResources`, а Compose
Multiplatform генерирует общий `Res`-класс.

## Как запускается

Генерация Compose resources автоматически зависит от задач объединения и
растеризации, поэтому обычная сборка Android или desktop сама создаёт нужные
PNG. Для отдельной ручной проверки из корня можно выполнить:

```powershell
.\gradlew.bat :shared:rasterizeSharedSvgToPng
```

Сгенерированные файлы находятся в:

```text
shared/build/generated/composeResources/rasterizedCommonMain/drawable-<density>
```

Объединённый каталог находится в:

```text
shared/build/generated/composeResources/mergedCommonMain
```

Оба каталога являются результатом сборки и не должны добавляться в Git или
редактироваться вручную. `clean` удаляет их; следующая сборка создаёт файлы
заново либо восстановит их из Gradle build cache.

Задача объявляет каталог SVG, карту ресурсов и коэффициенты плотности входами,
а generated-каталог — выходом. Без изменений она получает `UP-TO-DATE`. При
изменении одного SVG текущая пакетная задача заново создаёт весь небольшой
набор PNG.

## Где настраивается

Имена и исходные размеры ресурсов задаются в `rasterizedSvgAssets`, а
коэффициенты плотности — в `rasterizedSvgDensities` внутри
`shared/build.gradle.kts`. Чтобы добавить ресурс:

1. положите `<имя>.svg` в `shared/src/commonMain/vector-png`;
2. добавьте то же имя и исходный размер в `rasterizedSvgAssets`;
3. обращайтесь к сгенерированному ресурсу через общий Compose `Res.drawable`.

Имя нового SVG не должно конфликтовать с файлом в
`shared/src/commonMain/composeResources/drawable`.

Рендер выполняет Apache Batik `1.19`, подключённый только к build logic через
`buildSrc`; библиотека не попадает в Android-приложение или desktop-дистрибутив.

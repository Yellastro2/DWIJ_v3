import com.yellastrodev.build.RasterizeSvgToPngTask
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)

    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.ksp)
}

/**
 * Размеры ресурсов в условных dp.
 *
 * Gradle-задача умножит их на коэффициент density
 * и создаст отдельный PNG для каждой плотности.
 */
val rasterizedSvgAssets = mapOf(
    "ic_player_progress_ring_base" to "355x237",
    "ic_player_accent_v2" to "355x237",
    "bg_player_glitch_v2" to "355x355",
    "ic_player_play_v2" to "355x237",
    "ic_player_waveform" to "360x32",
    "ic_player_waveform_head" to "12x32",
    "ic_player_progress_head" to "24x24",

    "bg_drive_texture" to "160x92",
    "bg_focus_texture" to "160x92",
    "bg_calm_texture" to "160x92",
    "bg_party_texture" to "160x92",

    "dvizh_drive_glitch_frame_contour" to "160x92",
    "dvizh_focus_glitch_frame_contour" to "160x92",
    "dvizh_orange_glitch_frame_contour" to "160x92",
    "dvizh_calm_glitch_frame_contour" to "160x92",

    "dvizh_album_thumb_glitch_frame_contour" to "74x74",

    "bg_home_source_chip" to "152x56",
    "bg_home_source_chip_selected" to "152x56",

    "ic_playlist_create" to "64x64",
    "ic_playlist_liked" to "64x64",

    "ic_source_yandex" to "24x24",
    "ic_source_local_storage" to "24x24",

    "bg_playlist_tile_overlay" to "112x112",
    "bg_playlist_tile_overlay_highlighted" to "112x112",

    "bg_playlist_title_plate" to "104x48",
    "bg_playlist_title_plate_highlighted" to "104x48",
    "bg_playlist_details_plate" to "104x38",
)

val rasterizedSvgDensities = mapOf(
    "mdpi" to 1.0,
    "hdpi" to 1.5,
    "xhdpi" to 2.0,
    "xxhdpi" to 3.0,
    "xxxhdpi" to 4.0,
)

/**
 * Генерирует Compose Multiplatform resources:
 *
 * build/generated/composeResources/rasterizedCommonMain/
 * ├── drawable-mdpi/
 * ├── drawable-hdpi/
 * ├── drawable-xhdpi/
 * ├── drawable-xxhdpi/
 * └── drawable-xxxhdpi/
 */
val rasterizeSharedSvgToPng =
    tasks.register<RasterizeSvgToPngTask>(
        "rasterizeSharedSvgToPng",
    ) {
        sourceDirectory.set(
            layout.projectDirectory.dir(
                "src/commonMain/vector-png",
            ),
        )

        assets.set(
            rasterizedSvgAssets,
        )

        densityScales.set(
            rasterizedSvgDensities,
        )

        outputDirectory.set(
            layout.buildDirectory.dir(
                "generated/composeResources/rasterizedCommonMain",
            ),
        )
    }

/**
 * Объединяет обычные Compose Multiplatform resources
 * со сгенерированными density-PNG в едином каталоге.
 *
 * customDirectory заменяет стандартный каталог source set целиком,
 * поэтому напрямую подключать только результат растеризации нельзя.
 */
val mergeSharedComposeResources =
    tasks.register<Sync>(
        "mergeSharedComposeResources",
    ) {
        from(
            layout.projectDirectory.dir(
                "src/commonMain/composeResources",
            ),
        )

        from(
            rasterizeSharedSvgToPng,
        )

        into(
            layout.buildDirectory.dir(
                "generated/composeResources/mergedCommonMain",
            ),
        )
    }

val mergedSharedComposeResources =
    layout.dir(
        mergeSharedComposeResources.map { task ->
            task.destinationDir
        },
    )

kotlin {
    android {
        namespace = "com.yellastrodev.dwij.shared"
        compileSdk = 36
        minSdk = 26

        /*
         * Для com.android.kotlin.multiplatform.library
         * обработка Android resources по умолчанию выключена.
         */
        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation(compose.components.uiToolingPreview)
                /*
                 * Генерация Res.drawable,
                 * Res.string, Res.font и остальных
                 * multiplatform accessors.
                 */
                api(compose.components.resources)
            }
        }

        val jvmCommonMain by creating {
            dependsOn(
                commonMain,
            )

            dependencies {
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.navigation.compose)
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
                api(project(":yandexmusiclib"))
            }
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
        }
    }
}

/**
 * Настройка генерируемого Res-класса.
 */
compose.resources {
    /*
     * Пока часть Android app-кода ещё обращается
     * к shared-ресурсам напрямую, Res должен быть public.
     */
    publicResClass = true

    packageOfResClass =
        "com.yellastrodev.dwij.resources"

    /*
     * Подключаем объединённый каталог обычных ресурсов
     * и результата SVG → PNG задачи.
     *
     * Gradle сам построит зависимость:
     *
     * generate Res
     *     ↓
     * mergeSharedComposeResources
     *     ↓
     * rasterizeSharedSvgToPng
     */
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = mergedSharedComposeResources,
    )
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

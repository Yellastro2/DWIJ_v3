import com.yellastrodev.build.RasterizeSvgToPngTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

val rasterizedSvgAssets = mapOf(
    "ic_player_progress_ring_base" to "355x237",
    "ic_player_accent_v2" to "355x237",
    "bg_player_glitch_v2" to "355x355",
    "ic_player_play_v2" to "355x237",
    "ic_player_waveform" to "360x32",
    "ic_player_waveform_head" to "12x32",
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

android {
    namespace = "com.yellastrodev.dwij"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yellastrodev.dwij"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalizedVariantName = variant.name.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
        val rasterizeTask = tasks.register<RasterizeSvgToPngTask>(
            "rasterize${capitalizedVariantName}SvgToPng",
        ) {
            sourceDirectory.set(layout.projectDirectory.dir("src/main/vector-png"))
            assets.set(rasterizedSvgAssets)
            densityScales.set(rasterizedSvgDensities)
            outputDirectory.set(
                layout.buildDirectory.dir("generated/res/vectorPng/${variant.name}"),
            )
        }

        variant.sources.res?.addGeneratedSourceDirectory(
            rasterizeTask,
            RasterizeSvgToPngTask::getOutputDirectory,
        )
    }
}

dependencies {
    implementation(project(mapOf("path" to ":yandexmusiclib")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation(libs.material)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    val room_version = "2.8.0"

    implementation("androidx.room:room-runtime:$room_version")

    // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
    // See Add the KSP plugin to your project
    ksp("androidx.room:room-compiler:$room_version")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

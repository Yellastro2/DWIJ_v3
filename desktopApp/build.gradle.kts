import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")

    if (file.isFile) {
        file.inputStream().use {
            load(it)
        }
    }
}

val desktopJavaHome =
    localProperties
        .getProperty(
            "DESKTOP_JAVA_HOME",
        )
        ?.trim()
        ?.takeIf(String::isNotEmpty)

val yandexOAuthClientId =
    localProperties.getProperty(
        "YANDEX_OAUTH_CLIENT_ID",
        "",
    )

val yandexOAuthClientSecret =
    localProperties.getProperty(
        "YANDEX_OAUTH_CLIENT_SECRET",
        "",
    )

val desktopAppVersion =
    "0.1.4"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(
            JvmTarget.JVM_21,
        )
    }
}

dependencies {
    implementation(
        project(":shared"),
    )

    implementation(
        compose.desktop.currentOs,
    )
    implementation(
        compose.runtime,
    )
    implementation(
        compose.foundation,
    )
    implementation(
        compose.material3,
    )
    implementation(
        compose.components.resources,
    )
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)

    /*
     * Нужен desktop Main dispatcher для Lifecycle/ViewModel и Compose.
     * Версия совпадает с coroutines-core в yaMusicSdk.
     */
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1",
    )

    /*
     * Windows-specific JavaFX artifacts.
     *
     * JavaFX используется только как первый desktop audio backend.
     * После стабилизации порта его можно заменить на VLC/libVLC,
     * FFmpeg/native Windows backend, не меняя shared PlayerEngine.
     */
    implementation(
        "org.openjfx:javafx-base:17.0.19:win",
    )
    implementation(
        "org.openjfx:javafx-graphics:17.0.19:win",
    )
    implementation(
        "org.openjfx:javafx-media:17.0.19:win",
    )
    implementation(
        "io.github.selemba1000:JavaMediaTransportControls:0.0.3",
    )

    implementation(
        "net.jthink:jaudiotagger:3.0.1",
    )

    implementation(
        "net.java.dev.jna:jna:5.14.0",
    )
    implementation(
        "net.java.dev.jna:jna-platform:5.14.0",
    )

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        desktopJavaHome?.let { configuredJavaHome ->
            javaHome = configuredJavaHome
        }

        mainClass =
            "com.yellastrodev.dwij.desktop.MainKt"

        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",

            "-Ddwij.app.version=$desktopAppVersion",

            "-Ddwij.yandex.clientId=$yandexOAuthClientId",
            "-Ddwij.yandex.clientSecret=$yandexOAuthClientSecret",
        )

        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Msi,
            )

            packageName =
                "DWIJ"

            packageVersion =
                desktopAppVersion

            description =
                "DWIJ desktop prototype"

            vendor =
                "Yellastro"

            windows {
                iconFile.set(
                    project.file(
                        "src/main/resources/dwij.ico",
                    ),
                )

                shortcut = true

                menu = true

                menuGroup = "DWIJ"
            }
        }
    }
}

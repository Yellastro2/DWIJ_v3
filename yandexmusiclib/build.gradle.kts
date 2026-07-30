import java.util.Properties

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization") version "2.0.21"
}

val moduleLocalProperties = Properties().apply {
    val localPropertiesFile = project.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

android {
    namespace = "com.yellastrodev.yandexmusiclib"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        buildConfigField(
            "String",
            "YANDEX_OAUTH_CLIENT_ID",
            buildConfigString(moduleLocalProperties.getProperty("YANDEX_OAUTH_CLIENT_ID", ""))
        )
        buildConfigField(
            "String",
            "YANDEX_OAUTH_CLIENT_SECRET",
            buildConfigString(moduleLocalProperties.getProperty("YANDEX_OAUTH_CLIENT_SECRET", ""))
        )
    }

    buildFeatures {
        buildConfig = true
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/*"
        }
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}



dependencies {
    implementation ("org.json:json:20230227")
    implementation ("commons-codec:commons-codec:1.15")
	implementation("androidx.annotation:annotation-jvm:1.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}

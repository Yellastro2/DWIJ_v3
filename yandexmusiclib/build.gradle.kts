plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.10"
}

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
    implementation ("com.yandex.android:authsdk:2.5.1")
	implementation("androidx.annotation:annotation-jvm:1.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")


}
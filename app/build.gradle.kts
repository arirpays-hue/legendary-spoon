plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 10 + runNumber
        versionName = "1.2.$runNumber"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("persistent") {
            val ksFile = file("app-signing.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "translator123"
                keyAlias = "translator_key"
                keyPassword = "translator123"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            if (file("app-signing.jks").exists()) {
                signingConfig = signingConfigs.getByName("persistent")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            if (file("app-signing.jks").exists()) {
                signingConfig = signingConfigs.getByName("persistent")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Offline OCR (Latin)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Offline Translation (English -> Hebrew)
    implementation("com.google.mlkit:translate:17.0.3")
}

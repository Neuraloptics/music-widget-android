import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.musicwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musicwidget"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 31
        versionName   = "1.30"
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("SIGNING_KEY_PATH")
            if (path != null) {
                storeFile = File(path)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (System.getenv("SIGNING_KEY_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}

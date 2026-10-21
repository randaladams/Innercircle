plugins {
    id("com.android.application")
}

android {
    namespace = "com.adamselite.innercircle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adamselite.innercircle"
        minSdk = 24
        targetSdk = 36           // Google Play requires API 36 for new apps from Aug 31, 2026
        versionCode = 1          // must go up by at least 1 for every Google Play upload
        versionName = "1.0.0"
    }

    // One fixed debug key, checked into the repo, so every GitHub build is signed the same way.
    // That lets "adb install -r" update the app in place and keep your saved scores.
    // (Debug only. A Google Play release will need its own private key, kept out of the repo.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

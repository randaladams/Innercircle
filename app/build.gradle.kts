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
        // Google Play upload key. It is NOT in the repo: GitHub Actions decodes it from the
        // UPLOAD_KEYSTORE_* secrets and passes its location and passwords in as environment variables.
        create("release") {
            val path = System.getenv("UPLOAD_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Shrink and optimize the Play build (R8)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (!System.getenv("UPLOAD_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")        // AdMob
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")   // ad consent (EU/UK)
    implementation("com.android.billingclient:billing:9.1.0")                // Google Play Billing
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.adamselite.innercircle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adamselite.innercircle"
        minSdk = 24
        targetSdk = 36           // Google Play requires API 36 (Android 16) for new apps and updates
        versionCode = 1          // must go up by at least 1 for every Google Play upload
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true       // BuildConfig.DEBUG keeps test-only features out of release builds
    }

    signingConfigs {
        // One fixed debug key, checked into the repo, so every GitHub build is signed the same way.
        // That lets "adb install -r" update the app in place and keep your saved scores.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key. Never stored in the repo: the GitHub build decodes it from a
        // secret and passes its path as -PuploadStoreFile; passwords come from secrets too.
        create("release") {
            val uploadStore = providers.gradleProperty("uploadStoreFile").orNull
            if (uploadStore != null) {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
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
            // R8 shrinks and optimizes the release build (smaller download, faster start).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("uploadStoreFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17   // Kotlin's jvmTarget follows this automatically
    }
}

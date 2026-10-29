plugins {
    id("com.android.application")
}

android {
    namespace = "com.amusic.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.amusic.companion"
        minSdk = 21          // Android TV 5.0+
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No dependencies: framework APIs only, to keep the app as small and light as possible.

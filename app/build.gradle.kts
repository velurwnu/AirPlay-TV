plugins {
    id("com.android.application")
}

android {
    namespace = "com.amusic.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.amusic.tv"
        minSdk = 24          // Android TV 7.0+
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Only arm64-v8a. The app has no native code of its own, so this
        // just keeps the APK from carrying other ABIs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // For now release is signed with the debug key so it installs on the TV.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
}

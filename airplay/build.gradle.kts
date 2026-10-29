plugins {
    id("com.android.application")
}

android {
    namespace = "com.amusic.airplay"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.amusic.airplay"
        minSdk = 26          // Android TV 8.0+ (built for Android 9)
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }   // the OpenSSL prefab needs the shared STL
        }
    }

    buildFeatures { prefab = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so it installs on the TV directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libcrypto for the AirPlay core (pairing, FairPlay, AES).
    implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1")
    // Player for "AirPlay video" (HLS from YouTube and other apps).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.bio.bioplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bio.bioplayer"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")

    // ExoPlayer (Media3) - HLS + MP4 playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Networking for fetching remote m3u/json playlists
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for background parsing/loading
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading for channel/group logos
    implementation("io.coil-kt:coil:2.6.0")
}

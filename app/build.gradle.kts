plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keno.billiards"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keno.billiards"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
    }

    // Java and Kotlin bytecode targets are both 17 (AGP 8.7 needs JDK 17).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
    }
}
// No third-party dependencies on purpose: the app only needs the Android framework and the Kotlin stdlib.

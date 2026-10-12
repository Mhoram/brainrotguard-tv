plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mhoram.brainrotguardtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mhoram.brainrotguardtv"
        minSdk = 21          // covers old Android TV boxes / most Google TVs
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

// Intentionally zero runtime dependencies — the whole app is a WebView shell.
// (JUnit is test-only, so the APK stays dependency-free.)
dependencies {
    testImplementation("junit:junit:4.13.2")
}

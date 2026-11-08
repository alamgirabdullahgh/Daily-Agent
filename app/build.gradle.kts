plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rahcare.dailyagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rahcare.dailyagent"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KS_PASSWORD")
            keyAlias = "dailyagent"
            keyPassword = System.getenv("KS_PASSWORD")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    // অফলাইন "My Agent" ডাক শোনার ইঞ্জিন (ফোনের ভেতরে চলে, ইন্টারনেট লাগে না)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}

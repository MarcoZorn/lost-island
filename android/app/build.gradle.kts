plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.zorn.lostisland"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.zorn.lostisland"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.6.0"
    }

    signingConfigs {
        create("release") {
            // public sideload key, deliberately checked in — not a secret
            storeFile = rootProject.file("sideload.keystore")
            storePassword = "lostisland"
            keyAlias = "lostisland"
            keyPassword = "lostisland"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

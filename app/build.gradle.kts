plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sariel.deskpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sariel.deskpet"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
    versionName = "2.2"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("../keystore/deskpet.keystore")
            storePassword = "deskpet123"
            keyAlias = "deskpet"
            keyPassword = "deskpet123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
}

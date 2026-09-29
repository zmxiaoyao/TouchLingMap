plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.touchling.mapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.touchling.mapper"
        minSdk = 31
        targetSdk = 34
        versionCode = 8
        versionName = "0.4.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/touchling.keystore")
            storePassword = "touchling123"
            keyAlias = "touchling"
            keyPassword = "touchling123"
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

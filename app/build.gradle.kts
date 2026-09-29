plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.touchling.mapper"
    compileSdk = 35

    // v2.4.1：强制解压原生库到 nativeLibraryDir（libgrab.so 需要被 shell 执行）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "com.touchling.mapper"
        minSdk = 31
        targetSdk = 34
        versionCode = 30
        versionName = "2.4.5"
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

plugins {
    id("com.android.application")
}

android {
    namespace = "com.kiosk.app"
    compileSdk = 33

    signingConfigs {
        create("kiosk") {
            storeFile = file("../kiosk.keystore")
            storePassword = "kiosk123"
            keyAlias = "kiosk"
            keyPassword = "kiosk123"
        }
    }

    defaultConfig {
        applicationId = "com.kiosk.app"
        minSdk = 30
        targetSdk = 33
        versionCode = 25
        versionName = "1.0.25"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("kiosk")
        }
        release {
            signingConfig = signingConfigs.getByName("kiosk")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

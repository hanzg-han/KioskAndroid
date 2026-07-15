plugins {
    id("com.android.application")
}

android {
    namespace = "com.kiosk.app"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.kiosk.app"
        minSdk = 30
        targetSdk = 33
        versionCode = 17
        versionName = "1.0.17"
    }

    buildTypes {
        release {
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
}

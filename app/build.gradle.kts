plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rejwane.tiktokgrab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rejwane.tiktokgrab"
        minSdk = 24
        targetSdk = 34
        // Every CI build is numbered higher than the last, so an update is never "older".
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 1
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1}"
    }

    /**
     * Every CI run used to make its own debug key, so Android refused to update the installed app
     * without uninstalling it first. This one key in the repo keeps every build upgrade-compatible.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/tiktokgrab.p12")
            storePassword = "android"
            keyAlias = "tiktokgrab"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linglu.usbcam"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.linglu.usbcam"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 只打包 arm64-v8a：目标设备均为 arm64，且该 ABI 的 .so 已 16KB 对齐
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 主选：AUSBC 的活跃 fork，arm64/x86_64 的 .so 已 16KB 对齐，依赖树干净
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

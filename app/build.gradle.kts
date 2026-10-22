import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.sevenxu.eq"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sevenxu.eq"
        minSdk = 28
        targetSdk = 28
        versionCode = 182
        versionName = "3.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        resourceConfigurations += listOf("en", "zh", "zh-rHK", "zh-rTW")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // PC build 跑 assembleDebug → 壓縮要寫喺 debug 入面
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

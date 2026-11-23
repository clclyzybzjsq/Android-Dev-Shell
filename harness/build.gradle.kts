import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ads.shell"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.ads.harness"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // 与 ADS 共享同一份源码/资源/原生库，仅 manifest 与入口不同：
    // 打开即 DeepSeek Harness（官方 WebUI），ADS shell 作为内置工具
    sourceSets {
        getByName("main") {
            java.srcDirs("../app/src/main/java")
            aidl.srcDirs("../app/src/main/aidl")
            res.srcDirs("../app/src/main/res")
            assets.srcDirs("../app/src/main/assets")
            jniLibs.srcDirs("../app/src/main/jniLibs")
        }
    }

    buildFeatures { aidl = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}

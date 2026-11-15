plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.vpn8"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vpn8"
        minSdk = 26
        targetSdk = 35
        versionCode = 79
        versionName = "7.8"
    }

    val ksPath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                // v1 + v2 + v3: widest compatibility across OEM installers.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never fall back to the debug key: a debug-signed APK is flagged by Play Protect
            // and cannot update an installed build ("App not installed").
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // Three outputs: armeabi-v7a, arm64-v8a and a universal APK (v7a + v8a + x86_64).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    // The Aether / Psiphon / Lyrebird executables ship as lib*.so and are run from
    // nativeLibraryDir, so they must be extracted to disk.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Refuse to produce a release APK without the real keystore.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) && it.name.startsWith("assemble") }
    if (wantsRelease && System.getenv("KEYSTORE_FILE") == null) {
        throw GradleException("KEYSTORE_FILE is not set: release APKs must be signed with the permanent release key.")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // sing-box (libbox), built by the "libbox" job of the GitHub workflow into app/libs/libbox.aar.
    implementation(files("libs/libbox.aar"))
}

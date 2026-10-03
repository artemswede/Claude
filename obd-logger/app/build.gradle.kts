import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
/** «20261003_2247» — build time (Moscow) set by CI; shown in Settings and in the APK file name. */
val buildVersion = System.getenv("BORTACH_VERSION") ?: "dev"

android {
    namespace = "com.obdlogger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obdlogger.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = buildVersion
    }

    // Fixed debug key so CI builds install over each other without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // java.time (trip analysis, CSV timestamps) on Android 7.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":obd-core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

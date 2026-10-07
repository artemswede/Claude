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
        // Android 5.0+: old car head units often run 5–6 (or a vendor 7 that installs only with v1 signing).
        minSdk = 21
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
            // v1 (JAR) signature too: old and vendor firmwares reject APKs signed with v2 only
            // («ошибка синтаксического анализа пакета»).
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    // Fail the build if code calls an API newer than minSdk without a version check.
    lint {
        checkOnly += "NewApi"
        abortOnError = true
    }

    buildFeatures {
        buildConfig = true
    }

    // Screenshot tests (Robolectric, native graphics) need the real resources and assets.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("screens.dir", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
                it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

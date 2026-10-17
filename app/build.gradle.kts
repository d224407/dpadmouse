import java.util.Properties

plugins {
    alias(libs.plugins.android.application) // AGP 9: Kotlin is built in, no kotlin-android plugin needed
}

// --- Release signing: read from keystore.properties if present -------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "app.dpadmouse"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dpadmouse"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // BuildConfig.DEBUG ...
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // ------------------------------------------------------------------ DEBUG
        // Goal: easiest debugging. No minify, keep class/line names, verbose logging,
        // installs side by side with the release build (.debug suffix).
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "DpadMouse (debug)"
            signingConfig = signingConfigs.getByName("debug")
        }

        // ------------------------------------------------------------------ RELEASE
        release {
            isDebuggable = false
            isMinifyEnabled = true      // R8
            isShrinkResources = true
            manifestPlaceholders["appLabel"] = "DpadMouse"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the real key if keystore.properties exists; otherwise sign with the debug key
            // so it can still be installed for testing (NOT for distribution).
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Debug only: automatic leak detection (self-installing, no code needed)
    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
}

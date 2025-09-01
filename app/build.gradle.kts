import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val keystoreProperties = Properties().apply {
    load(FileInputStream(rootProject.file("keystore.properties")))
}

android {
    namespace = "com.milen.grounpringtonesetter"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.milen.grounpringtonesetter"
        minSdk = 27
        targetSdk = 35
        versionCode = 704
        versionName = "7.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // JDK 17 toolchain
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    // ViewBinding (Kotlin DSL)
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core & UI
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxConstraintlayout)
    implementation(libs.androidxAppcompat)
    implementation(libs.material)

    // Coroutines & Lifecycle
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bundles.lifecycle)

    // Splash & Accompanist
    implementation(libs.androidxCoreSplashscreen)
    implementation(libs.bundles.accompanist)

    // Security (Encrypted SharedPreferences remove in favor of DataStore)
    implementation(libs.androidxSecurityCrypto)
    // use only this prefs for crypro prefs
    implementation(libs.androidx.datastore.preferences)

    // Navigation
    implementation(libs.androidxNavigationFragmentKtx)
    implementation(libs.androidxNavigationUiKtx)

    // Google Play Services (Ads)
    implementation(libs.playServicesAds)

    // Firebase (BoM + libs)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // Play Billing
    implementation(libs.billingKtx)

    // Tests
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
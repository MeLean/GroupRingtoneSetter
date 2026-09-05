import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    jacoco
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.milen.grounpringtonesetter"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.milen.grounpringtonesetter"
        minSdk = 28
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 869
        versionName = "8.6.9"

        testInstrumentationRunner = "com.milen.grounpringtonesetter.testing.RegressionTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        }
        getByName("release") {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-6177746105485183~9226068349"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // Keep Java and Kotlin bytecode aligned on the supported Android target.
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

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails release builds when the private signing configuration is incomplete."
    doLast {
        check(keystorePropertiesFile.isFile) {
            "Release signing requires keystore.properties. Debug and test builds do not."
        }
        listOf("keyAlias", "keyPassword", "storeFile", "storePassword").forEach { key ->
            check(!keystoreProperties.getProperty(key).isNullOrBlank()) {
                "Release signing property '$key' is missing."
            }
        }
        val releaseStoreFile = project.file(keystoreProperties.getProperty("storeFile"))
        check(releaseStoreFile.isFile) {
            "Release signing store does not exist: ${releaseStoreFile.path}"
        }
    }
}

tasks.matching { task -> task.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

jacoco {
    toolVersion = "0.8.15"
}

dependencies {
    // Core & UI
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxActivityKtx)
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
    implementation(libs.playFeatureDelivery)
    implementation(libs.userMessagingPlatform)

    // Ads transitively brings WorkManager 2.7.0. Pin a current version to avoid its
    // startup database initialization crash on modern Android versions.
    implementation(libs.androidxWorkRuntime)

    // Firebase (BoM + libs)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // Play Billing
    implementation(libs.billingKtx)

    // Tests
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.espresso.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidxNavigationTesting)
    androidTestUtil(libs.androidx.test.orchestrator)
}

val coverageExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/*Binding.*",
    "**/*Fragment.*",
    "**/*Activity.*",
    "**/*Adapter.*",
    "**/customviews/**",
)

val coreCoverageIncludes = listOf(
    "**/MainInfoDialogSpecKt.class",
    "**/backup/GrsManifestCodec.class",
    "**/backup/RestoreDefaultTonePermissionPolicy.class",
    "**/backup/RestorePlanner.class",
    "**/billing/BillingDiagnosticsPolicy.class",
    "**/billing/BillingResultMessageResolver.class",
    "**/ui/defaulttones/NotificationToneDurationPolicy.class",
    "**/ui/home/HomeEmptyStateMessageKt.class",
    "**/ui/home/HomeLabelItemsPresentationKt.class",
    "**/ui/picker/PickerContactSortingKt.class",
    "**/ui/picker/PickerManageContactsSelectionKt.class",
    "**/utils/ContentProviderBatchUtilsKt.class",
    "**/utils/GroupDeletionCapabilityKt.class",
    "**/utils/MediaStoreToneImporterKt.class",
)

val debugClassDirectories = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include(coreCoverageIncludes)
        exclude(coverageExcludes)
    },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        include(coreCoverageIncludes)
        exclude(coverageExcludes)
    },
)

val debugSourceDirectories = files("src/main/java", "src/main/kotlin")
val debugExecutionData = fileTree(layout.buildDirectory) {
    include("jacoco/testDebugUnitTest.exec")
}

tasks.register<JacocoReport>("jacocoTestReportDebug") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(debugClassDirectories)
    sourceDirectories.setFrom(debugSourceDirectories)
    executionData.setFrom(debugExecutionData)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerificationDebug") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(debugClassDirectories)
    sourceDirectories.setFrom(debugSourceDirectories)
    executionData.setFrom(debugExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.register("localRegressionGate") {
    group = "verification"
    description = "Runs unit tests, lint, localization checks, and core coverage verification."
    dependsOn(
        "testDebugUnitTest",
        "lintDebug",
        "jacocoTestReportDebug",
        "jacocoTestCoverageVerificationDebug",
    )
}

tasks.register("uiTestSuite") {
    group = "verification"
    description = "Runs the complete debug UI and Android contract test suite on connected devices."
    dependsOn("connectedDebugAndroidTest")
}

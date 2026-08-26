plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.detekt")
}

android {
    namespace = "io.github.christianherget.trackglance.bridge"
    compileSdk = 37

    val releaseKeystorePath = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull
    val releaseKeystorePassword =
        providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PASSWORD").orNull
    if (releaseKeystorePath != null || releaseKeystorePassword != null) {
        require(!releaseKeystorePath.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank()) {
            "Both Android release signing environment variables must be set"
        }
        signingConfigs.create("releaseEnvironment") {
            storeFile = file(releaseKeystorePath)
            storePassword = releaseKeystorePassword
            keyAlias = "trackglance-release"
            keyPassword = releaseKeystorePassword
            storeType = "PKCS12"
        }
    }

    defaultConfig {
        // Keep the identity used by every distributable build so upgrades replace the existing app.
        applicationId = "app.trackglance.bridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("releaseEnvironment")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable +=
            setOf(
                "AndroidGradlePluginVersion",
                "GradleDependency",
                "NewerVersionAvailable",
                "OldTargetApi",
            )
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.rebble.pebblekit2:client:1.3.0")
    implementation("com.github.asamm.locus-api:locus-api-android:0.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

base { archivesName.set("trackglance-bridge") }

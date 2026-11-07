import org.cyclonedx.model.Component

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.detekt")
    id("org.cyclonedx.bom")
    id("org.spdx.sbom")
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
        versionCode = 20
        versionName = "0.2.8"
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.rebble.pebblekit2:client:1.3.2")
    implementation("com.github.asamm.locus-api:locus-api-android:0.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

base { archivesName.set("trackglance-bridge") }

tasks.cyclonedxDirectBom {
    projectType = Component.Type.APPLICATION
    componentName = "trackglance-bridge"
    componentVersion = android.defaultConfig.versionName ?: "unspecified"
    includeConfigs = listOf("releaseRuntimeClasspath")
    jsonOutput = layout.buildDirectory.file("reports/cyclonedx-direct/release.json")
    xmlOutput.unsetConvention()
}

spdxSbom {
    onlyUseLocalLicenses.set(true)
    targets {
        create("release") {
            configurations.set(listOf("releaseRuntimeClasspath"))
            outputFile.set(layout.buildDirectory.file("reports/spdx/release.spdx.json"))
            document {
                name.set("trackglance-bridge")
                namespace.set("https://github.com/ChristianHerget/trackglance/sbom/android")
                creator.set("Organization: TrackGlance")
            }
        }
    }
}

// AGP resolves lint and device test tooling outside the buildscript classpath.
configurations.configureEach {
    if (
        name == "androidLintTool" ||
            name.startsWith("lint") ||
            name.startsWith("unified-test-platform")
    ) {
        dependencies.add(project.dependencies.platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        dependencyConstraints.add(
            project.dependencies.constraints.create("com.google.guava:guava:33.7.2-jre")
        )
        dependencyConstraints.add(project.dependencies.constraints.create("org.jsoup:jsoup:1.23.2"))
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.bouncycastle:bcprov-jdk18on:1.85")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.bouncycastle:bcpkix-jdk18on:1.85")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.bouncycastle:bcutil-jdk18on:1.85")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.apache.commons:commons-lang3:3.20.0")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.apache.httpcomponents:httpclient:4.5.14")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.apache.httpcomponents:httpmime:4.5.14")
        )
        dependencyConstraints.add(
            project.dependencies.constraints.create("org.bitbucket.b_c:jose4j:0.9.6")
        )
        dependencyConstraints.add(project.dependencies.constraints.create("org.jdom:jdom2:2.0.6.1"))
    }
}

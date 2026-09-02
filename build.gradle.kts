plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.1"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.cyclonedx.bom") version "3.4.1" apply false
    id("org.spdx.sbom") version "0.12.0" apply false
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt("0.64").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt("0.64").kotlinlangStyle()
    }
    java {
        target("**/*.java")
        targetExclude("**/build/**")
        googleJavaFormat("1.36.1")
    }
}

val verifyWatchStack =
    tasks.register<Exec>("verifyWatchStack") {
        group = "verification"
        description = "Checks Pebble stack storage and the serialized watch outbox."
        workingDir(layout.projectDirectory.dir("watchapp"))
        commandLine("node", "test/watch_stack.test.js")
        inputs.files(
            fileTree(layout.projectDirectory.dir("watchapp/src/c")) {
                include("**/*.c")
                include("**/*.h")
            },
            layout.projectDirectory.file("watchapp/test/watch_stack.test.js"),
        )
    }

val verifyProtocolParity =
    tasks.register<Exec>("verifyProtocolParity") {
        group = "verification"
        description = "Checks protocol keys, versions, limits, targets, and companion metadata."
        workingDir(layout.projectDirectory.dir("watchapp"))
        commandLine("node", "test/protocol_parity.test.js")
        inputs.files(
            layout.projectDirectory.file("android/app/build.gradle.kts"),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/protocol/BridgeProtocol.kt"
            ),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/core/BridgeOperationCoordinator.kt"
            ),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/core/BridgeRuntime.kt"
            ),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/pebble/AuthenticatedIngress.kt"
            ),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/pebble/PebbleMessages.kt"
            ),
            layout.projectDirectory.file(
                "android/app/src/main/java/io/github/christianherget/trackglance/bridge/pebble/PebbleTransport.kt"
            ),
            layout.projectDirectory.file("docs/development.md"),
            layout.projectDirectory.file("docs/end-to-end-testing.md"),
            layout.projectDirectory.file("docs/podman-testing.md"),
            layout.projectDirectory.file("docs/sphinx/conf.py"),
            layout.projectDirectory.file("docs/sphinx/getting-started.rst"),
            layout.projectDirectory.file("gradle/verification-metadata.xml"),
            layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar"),
            layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"),
            layout.projectDirectory.file("protocol/README.md"),
            layout.projectDirectory.file("settings.gradle.kts"),
            layout.projectDirectory.file("watchapp/package-lock.json"),
            layout.projectDirectory.file("watchapp/package.json"),
            layout.projectDirectory.file("watchapp/src/c/main.c"),
            layout.projectDirectory.file("watchapp/src/c/watch_config.c"),
            layout.projectDirectory.file("watchapp/src/c/watch_config.h"),
            layout.projectDirectory.file("watchapp/src/c/watch_state.h"),
            layout.projectDirectory.file("watchapp/src/c/ui_metrics.h"),
            layout.projectDirectory.file("watchapp/src/pkjs/index.js"),
            layout.projectDirectory.file("watchapp/test/protocol_parity.test.js"),
            layout.projectDirectory.file("tools/podman/versions.env"),
            layout.projectDirectory.file("tools/podman/Containerfile.build"),
            layout.projectDirectory.file("tools/podman/Containerfile.web"),
            layout.projectDirectory.file("tools/podman/build-coreapp.sh"),
            layout.projectDirectory.file("android/app/src/debug/AndroidManifest.xml"),
        )
    }

val regenerateDocumentationScreenshots =
    tasks.register<Exec>("regenerateDocumentationScreenshots") {
        group = "documentation"
        description =
            "Regenerates committed documentation screenshots using Pebble QEMU and browser tooling."
        workingDir(layout.projectDirectory)
        commandLine("bash", "docs/generate_screenshots.sh")
        inputs.file(layout.projectDirectory.file("docs/generate_screenshots.sh"))
        inputs.file(layout.projectDirectory.file("docs/package-lock.json"))
        inputs.file(layout.projectDirectory.file("docs/package.json"))
        inputs.file(layout.projectDirectory.file("docs/render_watch_settings_screenshots.js"))
        inputs.dir(layout.projectDirectory.dir("watchapp/src"))
        inputs.file(layout.projectDirectory.file("watchapp/package.json"))
        inputs.file(layout.projectDirectory.file("watchapp/wscript"))
        outputs.files(
            listOf(
                    "screenshot_emery_dashboard.png",
                    "screenshot_emery_no_bridge.png",
                    "screenshot_emery_stopped.png",
                    "screenshot_emery_units_imperial.png",
                    "screenshot_emery_units_nautical.png",
                    "screenshot_emery_menu.png",
                    "screenshot_emery_profiles.png",
                    "screenshot_emery_waypoints.png",
                    "screenshot_emery_layout_1.png",
                    "screenshot_emery_layout_2.png",
                    "screenshot_emery_layout_3.png",
                    "screenshot_emery_layout_4.png",
                    "screenshot_emery_layout_5.png",
                    "screenshot_emery_layout_6.png",
                    "screenshot_gabbro_dashboard.png",
                    "screenshot_gabbro_no_bridge.png",
                    "screenshot_gabbro_stopped.png",
                    "screenshot_gabbro_menu.png",
                    "watch_settings_overview.png",
                    "watch_settings_general.png",
                    "watch_settings_profile.png",
                )
                .map { screenshot ->
                    layout.projectDirectory.file("docs/sphinx/_static/$screenshot")
                }
        )
    }

tasks.register<Exec>("regenerateAndroidBridgeScreenshots") {
    group = "documentation"
    description =
        "Captures light and dark Android Bridge documentation screenshots from a connected emulator."
    dependsOn(":android:app:assembleDebug")
    workingDir(layout.projectDirectory)
    commandLine("bash", "docs/capture_android_bridge_screenshots.sh")
    inputs.file(layout.projectDirectory.file("docs/capture_android_bridge_screenshots.sh"))
    inputs.file(layout.projectDirectory.file("docs/validate_bridge_screenshots.py"))
    inputs.dir(layout.projectDirectory.dir("android/app/src/main"))
    inputs.dir(layout.projectDirectory.dir("android/app/src/debug"))
    outputs.files(
        layout.projectDirectory.file("docs/sphinx/_static/bridge_app_light.png"),
        layout.projectDirectory.file("docs/sphinx/_static/bridge_app_dark.png"),
    )
}

val verifyDocumentation =
    tasks.register<Exec>("verifyDocumentation") {
        group = "verification"
        description = "Builds the user documentation and treats Sphinx warnings as errors."
        workingDir(layout.projectDirectory)
        commandLine("bash", "docs/build_html.sh")
        inputs.file(layout.projectDirectory.file("docs/build_html.sh"))
        inputs.file(layout.projectDirectory.file("docs/requirements.txt"))
        inputs.file(layout.projectDirectory.file("docs/validate_bridge_screenshots.py"))
        inputs.file(layout.projectDirectory.file("docs/validate_locus_screenshots.py"))
        inputs.files(
            fileTree(layout.projectDirectory.dir("docs/sphinx")) {
                include("**/*.rst")
                include("conf.py")
                include("_static/**")
                exclude("_build/**")
            }
        )
        outputs.dir(layout.projectDirectory.dir("docs/sphinx/_build/html"))
    }

val verifyPebbleTargets =
    tasks.register("verifyPebbleTargets") {
        group = "verification"
        description = "Runs fast Pebble stack and cross-language protocol checks."
        dependsOn(verifyProtocolParity, verifyWatchStack)
    }

tasks.register<Exec>("fullAcceptance") {
    group = "verification"
    description = "Runs the explicit Podman Android/CoreApp/Pebble acceptance environment."
    workingDir(layout.projectDirectory)
    commandLine("bash", "tools/podman-test", "acceptance")
}

tasks.register<Exec>("verifyPebbleBundle") {
    group = "verification"
    description = "Checks the built PBW platforms, metadata, resources, and embedded PKJS."
    workingDir(layout.projectDirectory.dir("watchapp"))
    commandLine("node", "test/pbw.test.js")
    inputs.files(
        layout.projectDirectory.file("watchapp/build/watchapp.pbw"),
        layout.projectDirectory.file("watchapp/package.json"),
        layout.projectDirectory.file("watchapp/test/pbw.test.js"),
        layout.projectDirectory.file("watchapp/wscript"),
    )
    inputs.dir(layout.projectDirectory.dir("watchapp/src"))
}

project(":android:app") {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks
        .matching { it.name == "check" }
        .configureEach {
            dependsOn(rootProject.tasks.named("verifyPebbleTargets"))
        }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

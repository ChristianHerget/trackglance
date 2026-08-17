plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

val verifyWatchStack = tasks.register<Exec>("verifyWatchStack") {
    group = "verification"
    description = "Checks Pebble stack storage and the serialized watch outbox."
    workingDir(layout.projectDirectory.dir("watchapp"))
    commandLine("node", "test/watch_stack.test.js")
    inputs.files(
        layout.projectDirectory.file("watchapp/src/c/main.c"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.c"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.h"),
        layout.projectDirectory.file("watchapp/test/watch_stack.test.js"),
    )
}

val verifyProtocolParity = tasks.register<Exec>("verifyProtocolParity") {
    group = "verification"
    description = "Checks protocol keys, versions, limits, targets, and companion metadata."
    workingDir(layout.projectDirectory.dir("watchapp"))
    commandLine("node", "test/protocol_parity.test.js")
    inputs.files(
        layout.projectDirectory.file("android/app/build.gradle.kts"),
        layout.projectDirectory.file(
            "android/app/src/main/java/app/locuspebble/bridge/protocol/BridgeProtocol.kt",
        ),
        layout.projectDirectory.file("protocol/README.md"),
        layout.projectDirectory.file("watchapp/package-lock.json"),
        layout.projectDirectory.file("watchapp/package.json"),
        layout.projectDirectory.file("watchapp/src/c/main.c"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.c"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.h"),
        layout.projectDirectory.file("watchapp/src/pkjs/index.js"),
        layout.projectDirectory.file("watchapp/test/protocol_parity.test.js"),
    )
}

val verifyPebbleTargets = tasks.register("verifyPebbleTargets") {
    group = "verification"
    description = "Checks supported Pebble targets and cross-language protocol parity."
    dependsOn(verifyProtocolParity, verifyWatchStack)
}

tasks.register<Exec>("verifyPebbleBundle") {
    group = "verification"
    description = "Checks the built PBW platforms, metadata, resources, and embedded PKJS."
    workingDir(layout.projectDirectory.dir("watchapp"))
    commandLine("node", "test/pbw.test.js")
    inputs.files(
        layout.projectDirectory.file("watchapp/build/watchapp.pbw"),
        layout.projectDirectory.file("watchapp/package.json"),
        layout.projectDirectory.file("watchapp/src/c/main.c"),
        layout.projectDirectory.file("watchapp/src/c/persistent_blob.c"),
        layout.projectDirectory.file("watchapp/src/c/persistent_blob.h"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.c"),
        layout.projectDirectory.file("watchapp/src/c/watch_config.h"),
        layout.projectDirectory.file("watchapp/src/pkjs/index.js"),
        layout.projectDirectory.file("watchapp/test/pbw.test.js"),
    )
}

project(":android:app") {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyPebbleTargets"))
    }
}

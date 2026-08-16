plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

val verifyWatchStack by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects local C buffers that exceed the Pebble stack budget."
    workingDir(layout.projectDirectory.dir("watchapp"))
    commandLine("node", "test/watch_stack.test.js")
    inputs.files(
        layout.projectDirectory.file("watchapp/src/c/main.c"),
        layout.projectDirectory.file("watchapp/test/watch_stack.test.js"),
    )
}

val verifyPebbleTargets by tasks.registering {
    group = "verification"
    description = "Ensures the watchapp is built only for Time 2 and Round 2."
    val packageFile = layout.projectDirectory.file("watchapp/package.json")
    val pkjsFile = layout.projectDirectory.file("watchapp/src/pkjs/index.js")
    val watchSourceFile = layout.projectDirectory.file("watchapp/src/c/main.c")
    val androidBuildFile = layout.projectDirectory.file("android/app/build.gradle.kts")
    inputs.files(packageFile, pkjsFile, watchSourceFile, androidBuildFile)
    dependsOn(verifyWatchStack)
    doLast {
        val packageText = packageFile.asFile.readText()
        val targetBlock = Regex(""""targetPlatforms"\s*:\s*\[([^]]*)]""")
            .find(packageText)?.groupValues?.get(1)
            ?: error("watchapp/package.json has no targetPlatforms array")
        val targets = Regex(""""([^\"]+)"""").findAll(targetBlock)
            .map { it.groupValues[1] }.toSet()
        check(targets == setOf("emery", "gabbro")) {
            "Expected only emery and gabbro, found $targets"
        }
        check(Regex("\"version\"\\s*:\\s*\"0\\.1\\.5\"").containsMatchIn(packageText))
        val watchVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .find(packageText)?.groupValues?.get(1) ?: error("Missing watch version")
        val androidVersion = Regex("versionName\\s*=\\s*\"([^\"]+)\"")
            .find(androidBuildFile.asFile.readText())?.groupValues?.get(1)
            ?: error("Missing Android versionName")
        check(watchVersion == androidVersion) {
            "APK version $androidVersion and PBW version $watchVersion must match"
        }
        check(watchSourceFile.asFile.readText().contains("#define RELEASE_VERSION \"$watchVersion\""))
        check(pkjsFile.asFile.readText().contains("RELEASE='$watchVersion'"))
        check(Regex("\"capabilities\"\\s*:\\s*\\[[^]]*\"configurable\"").containsMatchIn(packageText))
        check(Regex("\"enableMultiJS\"\\s*:\\s*true").containsMatchIn(packageText))
        check(pkjsFile.asFile.isFile) { "Embedded PKJS is missing" }
        check(watchSourceFile.asFile.readText().contains("#define PROTOCOL_VERSION 3"))
        check(Regex("\"WAYPOINT_NAME\"\\s*:\\s*36").containsMatchIn(packageText))
    }
}

project(":android:app") {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyPebbleTargets"))
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

val verifyPebbleTargets by tasks.registering {
    group = "verification"
    description = "Ensures the watchapp is built only for Time 2 and Round 2."
    val packageFile = layout.projectDirectory.file("watchapp/package.json")
    val pkjsFile = layout.projectDirectory.file("watchapp/src/pkjs/index.js")
    val watchSourceFile = layout.projectDirectory.file("watchapp/src/c/main.c")
    inputs.files(packageFile, pkjsFile, watchSourceFile)
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
        check(Regex("\"version\"\\s*:\\s*\"0\\.1\\.1\"").containsMatchIn(packageText))
        check(Regex("\"capabilities\"\\s*:\\s*\\[[^]]*\"configurable\"").containsMatchIn(packageText))
        check(Regex("\"enableMultiJS\"\\s*:\\s*true").containsMatchIn(packageText))
        check(pkjsFile.asFile.isFile) { "Embedded PKJS is missing" }
        check(watchSourceFile.asFile.readText().contains("#define PROTOCOL_VERSION 3"))
    }
}

project(":android:app") {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyPebbleTargets"))
    }
}

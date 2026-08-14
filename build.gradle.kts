plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

val verifyPebbleTargets by tasks.registering {
    group = "verification"
    description = "Ensures the watchapp is built only for Time 2 and Round 2."
    val packageFile = layout.projectDirectory.file("watchapp/package.json")
    inputs.file(packageFile)
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
    }
}

project(":android:app") {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyPebbleTargets"))
    }
}

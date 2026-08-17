pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            name = "JitPack"
            content {
                includeGroup("com.github.asamm")
                includeGroup("com.github.asamm.locus-api")
            }
        }
    }
}

rootProject.name = "locus-pebble-bridge"
include(":android:app")

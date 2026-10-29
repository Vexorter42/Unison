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
        // NewPipe Extractor (YouTube) публикуется через JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "MusicHub"
include(":app")
include(":desktop")

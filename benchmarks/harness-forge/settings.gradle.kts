// pfbench for Forge 1.20.1: the benchmark-only harness mod (benchmarks/HARNESS.md), a separate Gradle build like
// ../harness (NeoForge 1.21.1). The release build (../../settings.gradle.kts), CI and CurseForge never see it.
// Build or run it with the repository's wrapper: ./gradlew -p benchmarks/harness-forge <task> (from the repo root).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pfbench-forge"

// pfbench: the benchmark-only harness mod (benchmarks/HARNESS.md). A separate Gradle build on purpose: the
// release build (../../settings.gradle.kts), CI and the CurseForge upload never see it.
// Build or run it with the repository's wrapper: ../../gradlew -p benchmarks/harness <task> (from the repo root).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pfbench"

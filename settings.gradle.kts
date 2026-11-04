pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One build per Minecraft version and loader, as versions/<mc>-<loader>, each using build.<loader>.gradle.kts.
// The source in src/ is shared; `//? if ...` comments switch the parts that differ (see stonecutter.gradle.kts).
stonecutter {
    create(rootProject) {
        fun target(minecraft: String, loader: String) =
            version("$minecraft-$loader", minecraft).buildscript("build.$loader.gradle.kts")

        target("1.21.1", "neoforge")
        target("1.20.1", "forge")
        vcsVersion = "1.21.1-neoforge"
    }
}

rootProject.name = "particlefilter"

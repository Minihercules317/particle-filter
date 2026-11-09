// pfbench harness for Forge 1.20.1. Mirrors ../../build.forge.gradle.kts (ModDevGradle legacyforge, Java 17, mixin
// refmap) and ../harness/build.gradle.kts (launch settings from Gradle properties; see there).
//
// The harness code is shared with the NeoForge 1.21.1 build: this build compiles ../harness/src/main (Runner,
// counters, logs, particles, mixins, particle definitions) plus its own src/main (Forge entry point, Platform,
// mods.toml, mixin config). The harness version comes from ../harness/gradle.properties, so both builds carry
// the same version.
//
// Particle Filter is not a build dependency: pfbench.py copies its jar into the run's mods/ folder. This dev runtime
// uses Mojang's names, so it loads Particle Filter's named-mappings jar from the same build as the release jar
// (versions/1.20.1-forge/build/devlibs/); the reobfuscated release jar (SRG names) only runs in a production Forge.
import java.util.Properties

plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
}

val shared = file("../harness")
val sharedProps = Properties().apply { shared.resolve("gradle.properties").reader().use { load(it) } }
val harnessVersion = sharedProps.getProperty("harness_version")
version = "$harnessVersion+${property("minecraft_version")}"
group = "pfbench"
base.archivesName = "pfbench-forge"

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

sourceSets.main {
    java.srcDir(shared.resolve("src/main/java"))
    resources.srcDir(shared.resolve("src/main/resources"))
}

fun prop(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
fun words(value: String?): List<String> = value?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

legacyForge {
    version = property("forge_version") as String

    runs {
        register("client") {
            client()
            gameDirectory = file(prop("pfbench.gameDir") ?: "run/dev")
            prop("pfbench.plan")?.let { systemProperty("pfbench.plan", it) }
            prop("pfbench.world")?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            programArguments.addAll(words(prop("pfbench.gameArgs")))
            jvmArguments.addAll(words(prop("pfbench.jvmArgs")))
        }
    }

    mods {
        register("pfbench") {
            sourceSet(sourceSets.main.get())
        }
    }
}

// Forge runs on SRG names in production, so mixins need a refmap (in the dev runtime it is remapped back).
mixin {
    add(sourceSets.main.get(), "pfbench.refmap.json")
    config("pfbench.mixins.json")
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to harnessVersion)
        inputs.properties(props)
        filesMatching("META-INF/mods.toml") { expand(props) }
    }

    jar {
        manifest.attributes("MixinConfigs" to "pfbench.mixins.json")
    }
}

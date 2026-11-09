// pfbench harness for NeoForge 1.21.1. Mirrors ../../build.neoforge.gradle.kts (ModDevGradle, Java 21).
//
// Particle Filter is NOT a build dependency: the launcher (pfbench.py) copies its release jar into the run
// directory's mods/ folder for configurations B/C/D and leaves it out for A, and the harness reaches it only
// by reflection. That way one harness build serves every configuration and the jar under test is the one
// players run.
//
// Launch settings come from Gradle properties so pfbench.py can point each launch at its own run directory:
//   -Ppfbench.gameDir=<abs dir>   game directory (default: run/dev)
//   -Ppfbench.plan=<abs file>     plan.json the harness executes (none: the harness stays idle)
//   -Ppfbench.world=<name>        world to open with --quickPlaySingleplayer (none: title screen)
//   -Ppfbench.jvmArgs="..."       extra JVM arguments, split on spaces
//   -Ppfbench.gameArgs="..."      extra game arguments, split on spaces (e.g. --width 1280 --height 720)
plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val harnessVersion = property("harness_version") as String
version = "$harnessVersion+${property("minecraft_version")}"
group = "pfbench"
base.archivesName = "pfbench-neoforge"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

fun prop(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
fun words(value: String?): List<String> = value?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

neoForge {
    version = property("neoforge_version") as String

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

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to harnessVersion)
        inputs.properties(props)
        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
    }
}

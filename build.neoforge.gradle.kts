// Build for one NeoForge version; Stonecutter runs it once per versions/<mc>-neoforge.
plugins {
    id("net.neoforged.moddev") version "2.0.148"
    id("neoforge-mutex")
}

val modId = property("mod.id") as String
val modVersion = property("mod.version") as String
val javaVersion = (property("java") as String).toInt()
val runDir = rootProject.file("run/${project.name}")
val loaderRange = property("mod.loader_range") as String
val mcRange = property("mod.mc_range") as String
val packFormat = property("mod.pack_format").toString()

version = "$modVersion+${sc.current.version}"
group = modId
base.archivesName = "$modId-neoforge"

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

neoForge {
    version = property("deps.neoforge") as String

    runs {
        register("client") {
            gameDirectory = runDir
            client()
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf(
            "version" to modVersion,
            "loader" to "neoforge",
            "loader_range" to loaderRange,
            "mc_range" to mcRange,
            "java" to "JAVA_$javaVersion",
            "pack_format" to packFormat,
            "refmap" to "",
            "required" to "type = \"required\"",
        )
        inputs.properties(props)
        filesMatching(listOf("META-INF/mods.toml", "*.mixins.json", "pack.mcmeta")) { expand(props) }
        rename("mods.toml", "neoforge.mods.toml")
    }

    // Copies the jar to build/libs/<version>/ at the root, next to the other versions' jars.
    register<Copy>("buildAndCollect") {
        group = "build"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }
}

// Build for one Forge version (1.20.1 and older); Stonecutter runs it once per versions/<mc>-forge.
plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
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
base.archivesName = "$modId-forge"

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

legacyForge {
    version = property("deps.forge") as String

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

// Forge runs on SRG names, so mixins need a refmap and the jar is reobfuscated (the dev jar goes to build/devlibs).
mixin {
    add(sourceSets.main.get(), "$modId.refmap.json")
    config("$modId.mixins.json")
}

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf(
            "version" to modVersion,
            "loader" to "forge",
            "required" to "mandatory = true",
            "loader_range" to loaderRange,
            "mc_range" to mcRange,
            "java" to "JAVA_$javaVersion",
            "pack_format" to packFormat,
            "refmap" to "\n  \"refmap\": \"$modId.refmap.json\",",
        )
        inputs.properties(props)
        filesMatching(listOf("META-INF/mods.toml", "*.mixins.json", "pack.mcmeta")) { expand(props) }
    }

    jar {
        manifest.attributes("MixinConfigs" to "$modId.mixins.json")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(named<Jar>("reobfJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }
}

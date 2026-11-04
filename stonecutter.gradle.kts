plugins {
    id("dev.kikugie.stonecutter")
}

// The version src/ is currently written for. Commit with the vcsVersion from settings.gradle.kts active.
stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    val loader = current.project.substringAfter('-')

    // Makes [<loader>."<mc>"] sections of stonecutter.properties.toml apply to that build.
    properties {
        tags(current.version, loader)
    }

    // `//? if forge {` or `//? if neoforge {` in the source.
    constants {
        match(loader, "forge", "neoforge")
    }
}

plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

group = "fr.ekaii.litematica"
version = "0.8.1+26.3-pre2"
description = "Server-side Litematica for Paper/Folia: parses .litematic and pastes via RegionScheduler"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    // Paper 26.3-pre-2 dev bundle + API are published to mavenLocal only
    // (no upstream 26.3 artifact yet); scope mavenLocal to that group so
    // nothing else resolves from a stale local repository.
    mavenLocal {
        content { includeGroup("io.papermc.paper") }
    }
    maven("https://maven.playpro.com") {
        content { includeGroup("net.coreprotect") }
    }
    maven("https://maven.enginehub.org/repo/") {
        content {
            includeGroup("com.sk89q.worldedit")
            includeGroup("com.sk89q.worldedit.worldedit-libs")
        }
    }
    // FAWE maven repo (mvn.intellectualsites.com) currently NXDOMAIN — Phase 3
    // FAWE adapter is on hold; re-enable when the upstream repo is reachable.
    // maven("https://mvn.intellectualsites.com/content/repositories/releases/") {
    //     content { includeGroup("com.fastasyncworldedit") }
    // }
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.3-pre-2.local-SNAPSHOT")
    compileOnly(libs.luckperms)
    // Optional runtime hook. CoreProtect is supplied by the server and is
    // never bundled into the plugin jar.
    compileOnly("net.coreprotect:coreprotect:24.0")
    // Easy Place V3 is a direct Netty handler on the server's own packet
    // classes (dev bundle): no PacketEvents, nothing shaded (issue #5).
    // FAWE compileOnly deps disabled — see repository block above.
    // compileOnly(libs.fawe.bukkit)
    // compileOnly(libs.fawe.core)
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
    shadowJar {
        // No runtime dependencies are bundled any more; the -all jar is kept
        // as the release artifact name the CI, docs and servers expect.
    }
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(25)
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf(
            "name" to project.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "26.2"
        )
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
        // Forward selected -D properties to the test JVM so EnabledIfSystemProperty
        // gates (e.g. litematica.stress) can be flipped from the gradle command line.
        listOf("litematica.stress").forEach { key ->
            val v = providers.systemProperty(key).orNull
            if (v != null) systemProperty(key, v)
        }
        // Stress test materialises a ~4 M-int block grid plus full TE/Entity NBT
        // before round-tripping through gzip. 2 GiB is comfortable for both
        // routine + stress runs.
        maxHeapSize = "2g"
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("net.coreprotect:coreprotect:24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

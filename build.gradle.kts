plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

group = "fr.ekaii.litematica"
version = "0.10.0+1.21.11-26.2"
description = "Server-side Litematica for Paper/Folia: parses .litematic and pastes via RegionScheduler"

java {
    // Compiled with JDK 25 against the 26.x dev bundle, but emitted as Java 21
    // bytecode so the same jar loads on 1.21.11 servers (Java 21). The 26.x
    // dev bundle declares JVM 25, so Gradle's target-JVM check is disabled.
    disableAutoTargetJvm()
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
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
    // One jar for 1.21.11, 26.1.x and 26.2: the NMS calls compile to the
    // same bytecode against all three dev bundles. Check another version with
    // ./gradlew -PdevBundle=1.21.11-R0.1-SNAPSHOT (or 26.1.2.build.53-stable) test
    paperweight.paperDevBundle(providers.gradleProperty("devBundle").getOrElse("26.2.build.111-stable"))
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
        options.release.set(21)
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
            "apiVersion" to "1.21"
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

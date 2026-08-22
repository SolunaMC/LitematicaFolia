plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

group = "fr.ekaii.litematica"
version = "0.6.1+26.2"
description = "Server-side Litematica for Paper/Folia: parses .litematic and pastes via RegionScheduler"

java {
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
    maven("https://repo.codemc.io/repository/maven-releases/") {
        content { includeGroup("com.github.retrooper") }
    }
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.2.build.111-stable")
    compileOnly(libs.luckperms)
    // Optional runtime hook. CoreProtect is supplied by the server and is
    // never bundled into the plugin jar.
    compileOnly("net.coreprotect:coreprotect:24.0")
    // PacketEvents — required by EasyPlaceListener (P13). Shaded into the
    // final fat jar so end users don't need to install PacketEvents as a
    // separate plugin. PacketEvents 2.6+ supports Folia per upstream
    // changelog.
    implementation(libs.packetevents.spigot)
    // FAWE compileOnly deps disabled — see repository block above.
    // compileOnly(libs.fawe.bukkit)
    // compileOnly(libs.fawe.core)
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
    shadowJar {
        // Relocate PacketEvents to avoid classpath clashes if another
        // plugin on the server also bundles a different PacketEvents
        // version. Keep adventure / kyori untouched — they're already
        // provided by Paper and PacketEvents transitively pulls them as
        // `compile` scope.
        relocate("com.github.retrooper.packetevents", "fr.ekaii.litematica.shaded.packetevents")
        relocate("io.github.retrooper.packetevents", "fr.ekaii.litematica.shaded.packetevents.spigot")
        // Strip transitive adventure / kyori from the shaded jar — Paper
        // already provides them. EXCEPT adventure-nbt (+ the examination-*
        // artifacts its types implement): PacketEvents 2.13 needs them and
        // Paper does not expose them to plugins (NoClassDefFoundError:
        // net/kyori/adventure/nbt/BinaryTag, net/kyori/examination/Examinable).
        val shadedKyori = setOf("adventure-nbt", "examination-api", "examination-string")
        dependencies {
            exclude { dep ->
                dep.moduleGroup == "net.kyori" && dep.moduleName !in shadedKyori
            }
        }
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

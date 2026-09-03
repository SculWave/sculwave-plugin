plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
    kotlin("plugin.serialization") version "1.5.31"
}

repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // PacketEvents - supports 26.2 / Paper / Spigot / Folia
    implementation("com.github.retrooper:packetevents-spigot:2.13.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.socket:socket.io-client:2.1.2")
    implementation("org.json:json:20250517")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        relocate(
            "com.github.retrooper",
            "org.nxblack.echoWave.libs.packetevents"
        )

        relocate(
            "io.github.retrooper",
            "org.nxblack.echoWave.libs.packetevents"
        )
    }

    processResources {
        val props = mapOf("version" to version)

        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

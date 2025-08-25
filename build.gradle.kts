buildscript {
    repositories { mavenCentral() }

    extra.apply {
        set("mindustryVersion", "v151")
        set("kotlinVersion", "2.2.0")
        set("sqliteJdbcVersion", "3.50.3.0")
        set("argon2Version", "2.12")
        set("kotlinxSerializationVersion", "1.9.0")
        set("jdaVersion", "6.0.0-rc.2")
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${project.extra["kotlinVersion"]}")
    }
}

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    idea
}

kotlin { jvmToolchain(22) }

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    maven { url = uri("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository") }
    maven { url = uri("https://www.jitpack.io") }
}

dependencies {
    compileOnly("com.github.Anuken.Arc:arc-core:${project.extra["mindustryVersion"]}")
    compileOnly("com.github.Anuken.Mindustry:core:${project.extra["mindustryVersion"]}")
    compileOnly("com.github.anuken.mindustry:server:${project.extra["mindustryVersion"]}")

    implementation("org.xerial:sqlite-jdbc:${project.extra["sqliteJdbcVersion"]}")
    implementation("de.mkammerer:argon2-jvm:${project.extra["argon2Version"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.extra["kotlinxSerializationVersion"]}")
    implementation("net.dv8tion:JDA:${project.extra["jdaVersion"]}")
}

tasks.shadowJar {
    configurations = listOf(project.configurations.runtimeClasspath.get())
    mergeServiceFiles()
}

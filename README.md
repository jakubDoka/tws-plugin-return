# Better kotlin mindustry plugin template

Features:
- ShadowJar plugin
- Commands Structure
- Kts gradle build script
- Start script
# How to use

1. Create repository using this template
2. Rename `rename.me` to you package name
3. Edit `src/main/resources/plugin.json`
4. Run `./gradlew shadowJar` (linux/mac) or `gradlew shadowJar` (windows)

Linux/Mac users can use `./start.sh`, this script do all annoying things for you, such as downloading server JAR, build project, copy mods and run everything.
# Requirements

- [Kotlin](https://kotlinlang.org/)
- [Gradle](https://gradle.org/)
- [Mindustry](https://github.com/Mindustry/Mindustry/)
- JDK >= 17 ([OpenJDK](https://openjdk.java.net/) or [Adoptium](https://adoptium.net/))

# Adding Dependencies

Please note that all dependencies on Mindustry, Arc or its submodules must be declared as compileOnly in Gradle. Never use implementation for core Mindustry or Arc dependencies.

- `implementation` places the entire dependency in the jar, which is, in most mod dependencies, very undesirable. You do not want the entirety of the Mindustry API included with your mod.
- `compileOnly` means that the dependency is only around at compile time, and not included in the jar.

Only use implementation if you want to package another Java library with your mod, and that library is not present in Mindustry already.
# Note

Unlike the mods, you don't need to have AndroidSDK for building plugins, because they run on the standard JVM.
This template doesn't include GitHub Actions, because I think you never use it for plugins.

Author: [MichaAI](https://github.com/MichaAI)
# tws-plugin-return

plugins {
    java
}

group = "fi.alavesa"
version = "1.0.0"

repositories {
    mavenCentral()
    // Paper's 26.x API is published here as <mc>.build.<n>-<channel>; there is no -R0.1-SNAPSHOT any more.
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pinned to an exact stable build for reproducible builds. Bump the build number to move forward.
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
}

tasks.withType<JavaCompile>().configureEach {
    // Paper 26.3 requires Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download needed).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

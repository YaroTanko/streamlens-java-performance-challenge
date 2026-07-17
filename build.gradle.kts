import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    java
    application
}

group = "com.streamlens"
version = "2.0.0-rc"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.streamlens.StreamLens"
}

val jmh = sourceSets.create("jmh")
jmh.compileClasspath += sourceSets.main.get().output
jmh.runtimeClasspath += sourceSets.main.get().output

configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(jmh.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmh.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    jvmArgs(
        "-Xms1g",
        "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:ActiveProcessorCount=1",
    )
}

val benchmarkJvmArgs = listOf(
    "-Xms1g",
    "-Xmx1g",
    "-XX:+UseG1GC",
    "-XX:ActiveProcessorCount=1",
)

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the JMH assessment benchmarks in a fixed single-CPU fork."
    dependsOn(jmh.classesTaskName)
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"

    args(
        "-f", "1",
        "-wi", "3",
        "-i", "5",
        "-tu", "ns",
        "-prof", "gc",
        "-jvmArgsAppend", benchmarkJvmArgs.joinToString(" "),
    )

    providers.gradleProperty("jmhArgs").orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.split(Regex("\\s+"))
        ?.let(::args)
}

tasks.register<Jar>("jmhJar") {
    group = "build"
    description = "Builds a runnable, self-contained JMH benchmark jar."
    dependsOn(jmh.classesTaskName)
    archiveClassifier = "jmh"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    manifest {
        attributes["Main-Class"] = "org.openjdk.jmh.Main"
    }
    from(jmh.output)
    from({
        jmh.runtimeClasspath
            .filter { dependency -> dependency.exists() }
            .map { dependency ->
                if (dependency.isDirectory) dependency else zipTree(dependency)
            }
    })
}

tasks.named("assemble") {
    dependsOn("jmhJar")
}

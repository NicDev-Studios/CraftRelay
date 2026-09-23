import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import tv.nicdev.craftrelay.build.DockerSmokeTask
import tv.nicdev.craftrelay.build.EmbeddedSmokeTask
import tv.nicdev.craftrelay.build.GenerateDockerTopologyTask
import tv.nicdev.craftrelay.build.GenerateEmbeddedSmokeTopologyTask

plugins {
    base
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.license.report)
    alias(libs.plugins.maven.publish) apply false
}

fun escapeBuildMetadata(value: String): String =
    buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    require(!character.isISOControl()) {
                        "craftrelayAuthors must not contain control characters"
                    }
                    append(character)
                }
            }
        }
    }

val craftrelayAuthors =
    providers.gradleProperty("craftrelayAuthors").map { configuredAuthors ->
        val entries = configuredAuthors.split(',')
        require(entries.none { it.isBlank() }) {
            "craftrelayAuthors must be a comma-separated list without empty entries"
        }
        entries.map(String::trim).distinct().take(10).also {
            require(it.isNotEmpty()) { "craftrelayAuthors must contain at least one author" }
        }
    }.get()
val escapedAuthors =
    craftrelayAuthors.map(::escapeBuildMetadata)
val authorsListLiteral =
    escapedAuthors.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
val authorsJavaLiteral =
    escapedAuthors.joinToString(separator = ", ") { "\"$it\"" }
val craftrelayVersion = providers.gradleProperty("craftrelayVersion").get()

allprojects {
    group = "tv.nicdev"
    version = craftrelayVersion

    extensions.extraProperties["craftrelayAuthors"] = craftrelayAuthors
    extensions.extraProperties["craftrelayAuthorsListLiteral"] = authorsListLiteral
    extensions.extraProperties["craftrelayAuthorsJavaLiteral"] = authorsJavaLiteral
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }

        withSourcesJar()
        // The Maven-publish convention creates its own plainJavadocJar. Applying Gradle's
        // withJavadocJar in addition would create two tasks writing the same classifier.
        if (project.path != ":craftrelay-api" && project.path != ":craftrelay-embedded") {
            withJavadocJar()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Jar>().configureEach {
        exclude(".gitkeep", ".gitkeep-*")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("craftrelayAuthors", craftrelayAuthors.joinToString(","))
        systemProperty("craftrelayVersion", project.version.toString())
    }
}

val installablePluginTasks = listOf(
    ":craftrelay-platform-paper:shadowJar",
    ":craftrelay-platform-velocity:shadowJar",
    ":craftrelay-example-plugin:paper:shadowJar",
    ":craftrelay-example-plugin:velocity:shadowJar",
)
val dockerEnvironmentFile = layout.projectDirectory.file("docker/.env")
val dockerDefaultEnvironmentFile = layout.projectDirectory.file("docker/.env.example")
val activeDockerEnvironmentFile =
    if (dockerEnvironmentFile.asFile.isFile) {
        dockerEnvironmentFile
    } else {
        dockerDefaultEnvironmentFile
    }
val dockerGeneratedDirectory = layout.projectDirectory.dir("docker/.generated")
val dockerComposeFile = dockerGeneratedDirectory.file("compose.yml")
val embeddedSmokeGeneratedDirectory = layout.projectDirectory.dir("docker/.generated-embedded")
val embeddedSmokeComposeFile = embeddedSmokeGeneratedDirectory.file("compose.yml")
val embeddedSmokePresenceKeyPrefix = "craftrelay-embedded-smoke"
val dockerPresenceKeyPrefix =
    providers.fileContents(
        layout.projectDirectory.file(
            "docker/templates/paper/server-config/plugins/CraftRelay/config.yml",
        ),
    ).asText.map { config ->
        Regex("""(?m)^\s*prefix:\s*"([^"]+)"\s*$""")
            .find(config)
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Docker CraftRelay config has no messaging prefix.")
    }
val dockerEnvironment = providers.fileContents(activeDockerEnvironmentFile).asText.map { contents ->
    contents.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }
}
fun configuredDockerCount(name: String): Provider<Int> = dockerEnvironment.map { environment ->
    val raw = environment[name] ?: return@map 2
    val value = raw.toIntOrNull()
        ?: throw GradleException("$name must be an integer, but was '$raw'.")
    if (value !in 1..10) {
        throw GradleException("$name must be between 1 and 10.")
    }
    value
}
val dockerPaperCount = configuredDockerCount("PAPER_COUNT")
val dockerVelocityCount = configuredDockerCount("VELOCITY_COUNT")
val generateDockerTopology = tasks.register<GenerateDockerTopologyTask>("generateDockerTopology") {
    group = "development"
    description = "Generates the Docker topology configured by docker/.env."
    templateDirectory.set(layout.projectDirectory.dir("docker/templates"))
    defaultEnvironmentFile.set(dockerDefaultEnvironmentFile)
    environmentFile.set(dockerEnvironmentFile)
    outputDirectory.set(dockerGeneratedDirectory)
    pluginVersion.set(project.version.toString())
}
val generateEmbeddedSmokeTopology =
    tasks.register<GenerateEmbeddedSmokeTopologyTask>("generateEmbeddedSmokeTopology") {
        group = "development"
        description = "Generates the minimal Paper and Velocity Embedded SDK smoke topology."
        templateDirectory.set(layout.projectDirectory.dir("docker/templates"))
        environmentFile.set(activeDockerEnvironmentFile)
        outputDirectory.set(embeddedSmokeGeneratedDirectory)
        pluginVersion.set(project.version.toString())
    }

tasks.register<Exec>("devUp") {
    group = "development"
    description = "Builds all plugins and starts the local CraftRelay network."
    dependsOn(installablePluginTasks, generateDockerTopology)
    workingDir(layout.projectDirectory)
    commandLine(
        "docker",
        "compose",
        "--env-file",
        dockerGeneratedDirectory.file(".env").asFile.absolutePath,
        "--file",
        dockerComposeFile.asFile.absolutePath,
        "up",
        "--detach",
        "--wait",
        "--remove-orphans",
    )
}

tasks.register<Exec>("devDown") {
    group = "development"
    description = "Stops the local CraftRelay network without deleting its volumes."
    dependsOn(generateDockerTopology)
    workingDir(layout.projectDirectory)
    commandLine(
        "docker",
        "compose",
        "--env-file",
        dockerGeneratedDirectory.file(".env").asFile.absolutePath,
        "--file",
        dockerComposeFile.asFile.absolutePath,
        "down",
        "--remove-orphans",
    )
}

tasks.register<Exec>("devLogs") {
    group = "development"
    description = "Follows logs from the local CraftRelay network."
    dependsOn(generateDockerTopology)
    workingDir(layout.projectDirectory)
    commandLine(
        "docker",
        "compose",
        "--env-file",
        dockerGeneratedDirectory.file(".env").asFile.absolutePath,
        "--file",
        dockerComposeFile.asFile.absolutePath,
        "logs",
        "--follow",
    )
}

tasks.register<DockerSmokeTask>("devSmoke") {
    group = "verification"
    description = "Runs the cross-platform developer smoke test."
    dependsOn(installablePluginTasks, generateDockerTopology)
    composeFile.set(dockerComposeFile)
    environmentFile.set(dockerGeneratedDirectory.file(".env"))
    repositoryDirectory.set(layout.projectDirectory)
    composeProjectName.set("craftrelay-smoke")
    paperCount.set(dockerPaperCount)
    velocityCount.set(dockerVelocityCount)
    presenceKeyPrefix.set(dockerPresenceKeyPrefix)
}

tasks.register<EmbeddedSmokeTask>("embeddedSmoke") {
    group = "verification"
    description = "Builds and runs the minimal Paper and Velocity Embedded SDK smoke test."
    dependsOn(
        ":craftrelay-embedded:paper-smoke:shadowJar",
        ":craftrelay-embedded:velocity-smoke:shadowJar",
        generateEmbeddedSmokeTopology,
    )
    composeFile.set(embeddedSmokeComposeFile)
    environmentFile.set(embeddedSmokeGeneratedDirectory.file(".env"))
    repositoryDirectory.set(layout.projectDirectory)
    composeProjectName.set("craftrelay-embedded-smoke")
    presenceKeyPrefix.set(embeddedSmokePresenceKeyPrefix)
}

apply(from = "gradle/release.gradle.kts")

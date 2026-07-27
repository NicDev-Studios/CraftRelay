import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.javadoc.Javadoc

plugins {
    base
}

abstract class GenerateDockerTopologyTask : DefaultTask() {

    @get:InputDirectory
    abstract val templateDirectory: DirectoryProperty

    @get:InputFile
    abstract val defaultEnvironmentFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val environmentFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val templates = templateDirectory.get().asFile.toPath()
        val output = outputDirectory.get().asFile.toPath()
        val configuredEnvironment = environmentFile.orNull?.asFile?.takeIf(File::isFile)
            ?: defaultEnvironmentFile.get().asFile
        val environment = readEnvironment(configuredEnvironment.toPath())
        val paperCount = environment.readInt("PAPER_COUNT", 2, 1..10)
        val velocityCount = environment.readInt("VELOCITY_COUNT", 2, 1..10)
        val firstVelocityPort = environment.readInt("VELOCITY_PORT", 25_565, 1..65_535)
        if (firstVelocityPort + velocityCount - 1 > 65_535) {
            throw GradleException("VELOCITY_PORT leaves too few ports for VELOCITY_COUNT.")
        }

        if (Files.exists(output)) {
            Files.walk(output).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        repeat(paperCount) { index ->
            val number = index + 1
            val target = output.resolve("paper-$number/server-config")
            copyDirectory(templates.resolve("paper/server-config"), target)
            replace(target.resolve("plugins/CraftRelay/config.yml"), "paper-1", "paper-$number")
        }
        repeat(velocityCount) { index ->
            val number = index + 1
            val target = output.resolve("velocity-$number/server-config")
            copyDirectory(templates.resolve("velocity/server-config"), target)
            replace(
                target.resolve("plugins/craftrelay/config.yml"),
                "velocity-1",
                "velocity-$number",
            )
            val velocityConfig = target.resolve("velocity.toml")
            val backendEntries = (1..paperCount).joinToString("\n") {
                "paper-$it = \"paper-$it:25565\""
            }
            val attempts = (1..paperCount).joinToString(", ") { "\"paper-$it\"" }
            Files.writeString(
                velocityConfig,
                Files.readString(velocityConfig).replace(
                    Regex("""(?s)\[servers]\R.*?\R\R\[forced-hosts]"""),
                    "[servers]\n$backendEntries\ntry = [$attempts]\n\n[forced-hosts]",
                ),
            )
        }

        Files.createDirectories(output)
        Files.writeString(
            output.resolve("compose.yml"),
            composeFile(paperCount, velocityCount, firstVelocityPort),
        )
        Files.copy(
            configuredEnvironment.toPath(),
            output.resolve(".env"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun Map<String, String>.readInt(
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val raw = get(name) ?: return default
        val value = raw.toIntOrNull()
            ?: throw GradleException("$name must be an integer, but was '$raw'.")
        if (value !in range) {
            throw GradleException("$name must be between ${range.first} and ${range.last}.")
        }
        return value
    }

    private fun readEnvironment(path: java.nio.file.Path): Map<String, String> =
        Files.readAllLines(path).mapNotNull { line ->
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#")) {
                null
            } else {
                val separator = value.indexOf('=')
                if (separator <= 0) {
                    throw GradleException("Invalid environment entry in $path: '$line'.")
                }
                value.substring(0, separator).trim() to
                    value.substring(separator + 1).trim()
            }
        }.toMap()

    private fun copyDirectory(source: java.nio.file.Path, target: java.nio.file.Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun replace(path: java.nio.file.Path, old: String, new: String) {
        Files.writeString(path, Files.readString(path).replace(old, new))
    }

    private fun composeFile(
        paperCount: Int,
        velocityCount: Int,
        firstVelocityPort: Int,
    ): String = buildString {
        appendLine("name: craftrelay-dev")
        appendLine()
        appendLine("services:")
        appendLine("  redis:")
        appendLine("    image: redis:7.4.2-alpine")
        appendLine("""    command: ["redis-server", "--save", "", "--appendonly", "no"]""")
        appendLine("    healthcheck:")
        appendLine("""      test: ["CMD", "redis-cli", "ping"]""")
        appendLine("      interval: 2s")
        appendLine("      timeout: 2s")
        appendLine("      retries: 15")
        appendLine("    networks: [craftrelay]")
        appendLine()
        repeat(paperCount) { index ->
            appendPaper(index + 1)
        }
        repeat(velocityCount) { index ->
            appendVelocity(index + 1, firstVelocityPort + index, paperCount)
        }
        appendLine("networks:")
        appendLine("  craftrelay:")
        appendLine("    driver: bridge")
        appendLine()
        appendLine("volumes:")
        (1..paperCount).forEach { appendLine("  paper-$it-data:") }
        (1..velocityCount).forEach { appendLine("  velocity-$it-data:") }
    }

    private fun StringBuilder.appendPaper(number: Int) {
        appendLine("  paper-$number:")
        appendLine("    image: itzg/minecraft-server:java21")
        appendLine("    environment:")
        appendLine("""      EULA: "TRUE"""")
        appendLine("""      TYPE: "PAPER"""")
        appendLine("""      VERSION: "${'$'}{MINECRAFT_VERSION:-1.20.6}"""")
        appendLine("""      ONLINE_MODE: "FALSE"""")
        appendLine("""      COPY_CONFIG_DEST: "/data"""")
        appendLine("""      MEMORY: "${'$'}{PAPER_MEMORY:-1G}"""")
        appendLine("""      OPS: "${'$'}{PAPER_OPS:-}"""")
        appendLine("""      EXISTING_OPS_FILE: "SYNCHRONIZE"""")
        appendLine("""      MOTD: "CraftRelay Paper $number"""")
        appendLine("""      SPAWN_PROTECTION: "0"""")
        appendLine("""      ENABLE_RCON: "TRUE"""")
        appendLine("""      RCON_PASSWORD: "craftrelay-smoke"""")
        appendLine("""      BROADCAST_RCON_TO_OPS: "FALSE"""")
        appendLine("    volumes:")
        appendLine("      - paper-$number-data:/data")
        appendLine("      - ./paper-$number/server-config:/config:ro")
        appendLine("      - ../../craftrelay-platform-paper/build/libs/craftrelay-platform-paper-0.1.0-SNAPSHOT.jar:/plugins/CraftRelay.jar:ro")
        appendLine("      - ../../craftrelay-example-plugin/paper/build/libs/craftrelay-example-paper-0.1.0-SNAPSHOT.jar:/plugins/CraftRelayExample.jar:ro")
        appendLine("    depends_on:")
        appendLine("      redis:")
        appendLine("        condition: service_healthy")
        appendLine("    healthcheck:")
        appendLine("""      test: ["CMD", "mc-health"]""")
        appendLine("      start_period: 90s")
        appendLine("      interval: 10s")
        appendLine("      timeout: 5s")
        appendLine("      retries: 12")
        appendLine("    networks: [craftrelay]")
        appendLine()
    }

    private fun StringBuilder.appendVelocity(number: Int, port: Int, paperCount: Int) {
        appendLine("  velocity-$number:")
        appendLine("    image: itzg/mc-proxy:java21")
        appendLine("    environment:")
        appendLine("""      TYPE: "VELOCITY"""")
        appendLine("""      VELOCITY_VERSION: "${'$'}{VELOCITY_VERSION:-3.4.0-SNAPSHOT}"""")
        appendLine("""      MEMORY: "${'$'}{VELOCITY_MEMORY:-512M}"""")
        appendLine("""      CRAFTRELAY_DEV_ADMINS: "${'$'}{PAPER_OPS:-}"""")
        appendLine("    ports:")
        appendLine("""      - "$port:25565"""")
        appendLine("    volumes:")
        appendLine("      - velocity-$number-data:/server")
        appendLine("      - ./velocity-$number/server-config:/config:ro")
        appendLine("      - ../../craftrelay-platform-velocity/build/libs/craftrelay-platform-velocity-0.1.0-SNAPSHOT.jar:/plugins/CraftRelay.jar:ro")
        appendLine("      - ../../craftrelay-example-plugin/velocity/build/libs/craftrelay-example-velocity-0.1.0-SNAPSHOT.jar:/plugins/CraftRelayExample.jar:ro")
        appendLine("    depends_on:")
        appendLine("      redis:")
        appendLine("        condition: service_healthy")
        (1..paperCount).forEach { paper ->
            appendLine("      paper-$paper:")
            appendLine("        condition: service_healthy")
        }
        appendLine("    networks: [craftrelay]")
        appendLine()
    }
}

abstract class DockerSmokeTask : DefaultTask() {

    @get:InputFile
    abstract val composeFile: RegularFileProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val composeProjectName: Property<String>

    @get:InputFile
    abstract val environmentFile: RegularFileProperty

    @get:Input
    abstract val paperCount: Property<Int>

    @get:Input
    abstract val velocityCount: Property<Int>

    @TaskAction
    fun runSmokeTest() {
        try {
            compose("up", "--detach", "--wait")
            awaitInstanceLeases()
            compose("exec", "-T", "paper-1", "rcon-cli", "crelay instances")
            awaitInstanceOutput()
            compose(
                "exec",
                "-T",
                "paper-1",
                "rcon-cli",
                "crelay broadcast CraftRelay smoke test",
            )
            logger.lifecycle("CraftRelay developer smoke test passed.")
        } catch (failure: RuntimeException) {
            logger.error("CraftRelay developer smoke test failed.", failure)
            composeIgnoringFailure("ps")
            composeIgnoringFailure("logs", "--no-color", "--tail", "200")
            throw failure
        } finally {
            composeIgnoringFailure("down")
        }
    }

    private fun awaitInstanceLeases() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        do {
            val count = composeCaptured(
                "exec",
                "-T",
                "redis",
                "redis-cli",
                "ZCARD",
                "craftrelay:presence:instances",
            ).lineSequence().lastOrNull()?.trim()
            if (count == (paperCount.get() + velocityCount.get()).toString()) {
                return
            }
            Thread.sleep(2_000)
        } while (System.nanoTime() < deadline)
        throw GradleException("Expected all configured CraftRelay instance leases.")
    }

    private fun awaitInstanceOutput() {
        val expectedInstances =
            (1..paperCount.get()).map { "paper-$it" } +
                (1..velocityCount.get()).map { "velocity-$it" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        do {
            val output = composeCaptured("logs", "--no-color", "paper-1")
            if (expectedInstances.all(output::contains)) {
                return
            }
            Thread.sleep(2_000)
        } while (System.nanoTime() < deadline)
        throw GradleException("RCON instance output was incomplete.")
    }

    private fun compose(vararg arguments: String) {
        execute(arguments.toList(), captureOnly = false, ignoreFailure = false)
    }

    private fun composeCaptured(vararg arguments: String): String =
        execute(arguments.toList(), captureOnly = true, ignoreFailure = false)

    private fun composeIgnoringFailure(vararg arguments: String) {
        execute(arguments.toList(), captureOnly = false, ignoreFailure = true)
    }

    private fun execute(
        arguments: List<String>,
        captureOnly: Boolean,
        ignoreFailure: Boolean,
    ): String {
        val command = listOf(
            "docker",
            "compose",
            "--project-name",
            composeProjectName.get(),
            "--env-file",
            environmentFile.get().asFile.absolutePath,
            "--file",
            composeFile.get().asFile.absolutePath,
        ) + arguments
        val process = ProcessBuilder(command)
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
            it.readText()
        }
        val exitCode = process.waitFor()
        if (!captureOnly && output.isNotBlank()) {
            logger.lifecycle(output.trimEnd())
        }
        if (exitCode != 0 && !ignoreFailure) {
            throw GradleException(
                "Command '${command.joinToString(" ")}' failed with exit code $exitCode.",
            )
        }
        return output
    }
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
    group = "de.nicdevtv"
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
        withJavadocJar()
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
val paperCount = configuredDockerCount("PAPER_COUNT")
val velocityCount = configuredDockerCount("VELOCITY_COUNT")
val generateDockerTopology = tasks.register<GenerateDockerTopologyTask>("generateDockerTopology") {
    group = "development"
    description = "Generates the Docker topology configured by docker/.env."
    templateDirectory.set(layout.projectDirectory.dir("docker/templates"))
    defaultEnvironmentFile.set(dockerDefaultEnvironmentFile)
    environmentFile.set(dockerEnvironmentFile)
    outputDirectory.set(dockerGeneratedDirectory)
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
    paperCount.set(paperCount)
    velocityCount.set(velocityCount)
}

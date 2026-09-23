package tv.nicdev.craftrelay.build

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

private fun readEnvironment(path: Path): Map<String, String> =
    Files.readAllLines(path).mapNotNull { line ->
        val value = line.trim()
        if (value.isEmpty() || value.startsWith("#")) {
            null
        } else {
            val separator = value.indexOf('=')
            if (separator <= 0) {
                throw GradleException("Invalid environment entry in $path: '$line'.")
            }
            value.substring(0, separator).trim() to value.substring(separator + 1).trim()
        }
    }.toMap()

private fun copyDirectory(source: Path, target: Path) {
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

private fun deleteDirectory(directory: Path) {
    if (Files.exists(directory)) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
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

    @get:Input
    abstract val pluginVersion: Property<String>

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

        deleteDirectory(output)
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
        Files.writeString(output.resolve("compose.yml"), composeFile(paperCount, velocityCount, firstVelocityPort))
        Files.copy(configuredEnvironment.toPath(), output.resolve(".env"), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun Map<String, String>.readInt(name: String, default: Int, range: IntRange): Int {
        val raw = get(name) ?: return default
        val value = raw.toIntOrNull()
            ?: throw GradleException("$name must be an integer, but was '$raw'.")
        if (value !in range) {
            throw GradleException("$name must be between ${range.first} and ${range.last}.")
        }
        return value
    }

    private fun replace(path: Path, old: String, new: String) {
        Files.writeString(path, Files.readString(path).replace(old, new))
    }

    private fun composeFile(paperCount: Int, velocityCount: Int, firstVelocityPort: Int): String =
        buildString {
            appendLine("name: craftrelay-dev")
            appendLine()
            appendLine("services:")
            appendLine("  redis:")
            appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay}-redis")
            appendLine("    image: redis:7.4.2-alpine")
            appendLine("    command: [\"redis-server\", \"--save\", \"\", \"--appendonly\", \"no\"]")
            appendLine("    healthcheck:")
            appendLine("      test: [\"CMD\", \"redis-cli\", \"ping\"]")
            appendLine("      interval: 2s")
            appendLine("      timeout: 2s")
            appendLine("      retries: 15")
            appendLine("    networks: [craftrelay]")
            appendLine()
            repeat(paperCount) { appendPaper(it + 1) }
            repeat(velocityCount) { appendVelocity(it + 1, firstVelocityPort + it, paperCount) }
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
        appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay}-paper-$number")
        appendLine("    image: itzg/minecraft-server:java21")
        appendLine("    environment:")
        appendLine("      EULA: \"TRUE\"")
        appendLine("      TYPE: \"PAPER\"")
        appendLine("      VERSION: \"${'$'}{MINECRAFT_VERSION:-1.20.6}\"")
        appendLine("      ONLINE_MODE: \"FALSE\"")
        appendLine("      COPY_CONFIG_DEST: \"/data\"")
        appendLine("      MEMORY: \"${'$'}{PAPER_MEMORY:-1G}\"")
        appendLine("      OPS: \"${'$'}{PAPER_OPS:-}\"")
        appendLine("      EXISTING_OPS_FILE: \"SYNCHRONIZE\"")
        appendLine("      MOTD: \"CraftRelay Paper $number\"")
        appendLine("      SPAWN_PROTECTION: \"0\"")
        appendLine("      ENABLE_RCON: \"TRUE\"")
        appendLine("      RCON_PASSWORD: \"craftrelay-smoke\"")
        appendLine("      BROADCAST_RCON_TO_OPS: \"FALSE\"")
        appendLine("    volumes:")
        appendLine("      - paper-$number-data:/data")
        appendLine("      - ./paper-$number/server-config:/config:ro")
        appendLine("      - ../../craftrelay-platform-paper/build/libs/craftrelay-platform-paper-${pluginVersion.get()}.jar:/plugins/CraftRelay.jar:ro")
        appendLine("      - ../../craftrelay-example-plugin/paper/build/libs/craftrelay-example-paper-${pluginVersion.get()}.jar:/plugins/CraftRelayExample.jar:ro")
        appendLine("    depends_on:")
        appendLine("      redis:")
        appendLine("        condition: service_healthy")
        appendLine("    healthcheck:")
        appendLine("      test: [\"CMD\", \"mc-health\"]")
        appendLine("      start_period: 90s")
        appendLine("      interval: 10s")
        appendLine("      timeout: 5s")
        appendLine("      retries: 12")
        appendLine("    networks: [craftrelay]")
        appendLine()
    }

    private fun StringBuilder.appendVelocity(number: Int, port: Int, paperCount: Int) {
        appendLine("  velocity-$number:")
        appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay}-velocity-$number")
        appendLine("    image: itzg/mc-proxy:java21")
        appendLine("    environment:")
        appendLine("      TYPE: \"VELOCITY\"")
        appendLine("      VELOCITY_VERSION: \"${'$'}{VELOCITY_VERSION:-3.4.0-SNAPSHOT}\"")
        appendLine("      MEMORY: \"${'$'}{VELOCITY_MEMORY:-512M}\"")
        appendLine("      CRAFTRELAY_DEV_ADMINS: \"${'$'}{PAPER_OPS:-}\"")
        appendLine("    ports:")
        appendLine("      - \"$port:25565\"")
        appendLine("    volumes:")
        appendLine("      - velocity-$number-data:/server")
        appendLine("      - ./velocity-$number/server-config:/config:ro")
        appendLine("      - ../../craftrelay-platform-velocity/build/libs/craftrelay-platform-velocity-${pluginVersion.get()}.jar:/plugins/CraftRelay.jar:ro")
        appendLine("      - ../../craftrelay-example-plugin/velocity/build/libs/craftrelay-example-velocity-${pluginVersion.get()}.jar:/plugins/CraftRelayExample.jar:ro")
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

abstract class GenerateEmbeddedSmokeTopologyTask : DefaultTask() {

    @get:InputDirectory
    abstract val templateDirectory: DirectoryProperty

    @get:InputFile
    abstract val environmentFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val pluginVersion: Property<String>

    @TaskAction
    fun generate() {
        val templates = templateDirectory.get().asFile.toPath()
        val output = outputDirectory.get().asFile.toPath()
        val environment = readEnvironment(environmentFile.get().asFile.toPath())
        val minecraftVersion = environment["MINECRAFT_VERSION"] ?: "1.20.6"
        val velocityVersion = environment["VELOCITY_VERSION"] ?: "3.4.0-SNAPSHOT"
        val paperMemory = environment["PAPER_MEMORY"] ?: "1G"
        val velocityMemory = environment["VELOCITY_MEMORY"] ?: "512M"

        deleteDirectory(output)
        copyDirectory(
            templates.resolve("embedded-paper/server-config"),
            output.resolve("paper/server-config"),
        )
        copyDirectory(
            templates.resolve("embedded-velocity/server-config"),
            output.resolve("velocity/server-config"),
        )
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("compose.yml"),
            composeFile(minecraftVersion, velocityVersion, paperMemory, velocityMemory),
        )
        Files.copy(
            environmentFile.get().asFile.toPath(),
            output.resolve(".env"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun composeFile(
        minecraftVersion: String,
        velocityVersion: String,
        paperMemory: String,
        velocityMemory: String,
    ): String = buildString {
        appendLine("name: craftrelay-embedded-smoke")
        appendLine()
        appendLine("services:")
        appendLine("  redis:")
        appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay-embedded-smoke}-redis")
        appendLine("    image: redis:7.4.2-alpine")
        appendLine("    command: [\"redis-server\", \"--save\", \"\", \"--appendonly\", \"no\"]")
        appendLine("    healthcheck:")
        appendLine("      test: [\"CMD\", \"redis-cli\", \"ping\"]")
        appendLine("      interval: 2s")
        appendLine("      timeout: 2s")
        appendLine("      retries: 15")
        appendLine("    networks: [craftrelay]")
        appendLine()
        appendLine("  paper:")
        appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay-embedded-smoke}-paper")
        appendLine("    image: itzg/minecraft-server:java21")
        appendLine("    environment:")
        appendLine("      EULA: \"TRUE\"")
        appendLine("      TYPE: \"PAPER\"")
        appendLine("      VERSION: \"$minecraftVersion\"")
        appendLine("      ONLINE_MODE: \"FALSE\"")
        appendLine("      COPY_CONFIG_DEST: \"/data\"")
        appendLine("      MEMORY: \"$paperMemory\"")
        appendLine("      ENABLE_RCON: \"FALSE\"")
        appendLine("      MOTD: \"CraftRelay Embedded Paper Smoke\"")
        appendLine("      SPAWN_PROTECTION: \"0\"")
        appendLine("    volumes:")
        appendLine("      - paper-data:/data")
        appendLine("      - ./paper/server-config:/config:ro")
        appendLine("      - ../../craftrelay-embedded/paper-smoke/build/libs/craftrelay-embedded-paper-smoke-${pluginVersion.get()}.jar:/plugins/CraftRelayEmbeddedPaperSmoke.jar:ro")
        appendLine("    depends_on:")
        appendLine("      redis:")
        appendLine("        condition: service_healthy")
        appendLine("    healthcheck:")
        appendLine("      test: [\"CMD\", \"mc-health\"]")
        appendLine("      start_period: 90s")
        appendLine("      interval: 10s")
        appendLine("      timeout: 5s")
        appendLine("      retries: 12")
        appendLine("    networks: [craftrelay]")
        appendLine()
        appendLine("  velocity:")
        appendLine("    container_name: ${'$'}{CRAFTRELAY_CONTAINER_PREFIX:-craftrelay-embedded-smoke}-velocity")
        appendLine("    image: itzg/mc-proxy:java21")
        appendLine("    environment:")
        appendLine("      TYPE: \"VELOCITY\"")
        appendLine("      VELOCITY_VERSION: \"$velocityVersion\"")
        appendLine("      MEMORY: \"$velocityMemory\"")
        appendLine("    volumes:")
        appendLine("      - velocity-data:/server")
        appendLine("      - ./velocity/server-config:/config:ro")
        appendLine("      - ../../craftrelay-embedded/velocity-smoke/build/libs/craftrelay-embedded-velocity-smoke-${pluginVersion.get()}.jar:/plugins/CraftRelayEmbeddedVelocitySmoke.jar:ro")
        appendLine("    depends_on:")
        appendLine("      redis:")
        appendLine("        condition: service_healthy")
        appendLine("      paper:")
        appendLine("        condition: service_healthy")
        appendLine("    healthcheck:")
        // Embedded smoke verifies SDK readiness and Redis leases itself.  The proxy image's
        // mc-health probe requires an externally reachable player session and is therefore
        // unsuitable for this isolated, no-player topology; PID 1 liveness is deterministic.
        appendLine("      test: [\"CMD-SHELL\", \"kill -0 1\"]")
        appendLine("      start_period: 45s")
        appendLine("      interval: 10s")
        appendLine("      timeout: 5s")
        appendLine("      retries: 12")
        appendLine("    networks: [craftrelay]")
        appendLine()
        appendLine("networks:")
        appendLine("  craftrelay:")
        appendLine("    driver: bridge")
        appendLine()
        appendLine("volumes:")
        appendLine("  paper-data:")
        appendLine("  velocity-data:")
    }
}

abstract class DockerComposeTask : DefaultTask() {

    @get:InputFile
    abstract val composeFile: RegularFileProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val composeProjectName: Property<String>

    @get:InputFile
    abstract val environmentFile: RegularFileProperty

    protected fun compose(vararg arguments: String) {
        execute(arguments.toList(), captureOnly = false, ignoreFailure = false)
    }

    protected fun composeCaptured(vararg arguments: String): String =
        execute(arguments.toList(), captureOnly = true, ignoreFailure = false)

    protected fun composeIgnoringFailure(vararg arguments: String) {
        execute(arguments.toList(), captureOnly = false, ignoreFailure = true)
    }

    /** Polls an external Compose condition without duplicating timeout/interrupt handling. */
    protected fun awaitCondition(
        timeoutSeconds: Long,
        intervalMillis: Long = 2_000L,
        condition: () -> Boolean,
        failureMessage: () -> String,
    ) {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            if (condition()) {
                return
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw GradleException(failureMessage())
            }
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(minOf(intervalMillis, remainingMillis)))
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw GradleException("Interrupted while waiting for Docker Compose.")
            }
        }
    }

    private fun execute(arguments: List<String>, captureOnly: Boolean, ignoreFailure: Boolean): String {
        val command = listOf(
            "docker", "compose", "--project-name", composeProjectName.get(),
            "--env-file", environmentFile.get().asFile.absolutePath,
            "--file", composeFile.get().asFile.absolutePath,
        ) + arguments
        val processBuilder = ProcessBuilder(command)
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
        // Compose project names do not affect explicit container_name values. Set the same
        // prefix used by the generated files so development, normal smoke, and embedded smoke
        // environments can run side by side without collisions.
        processBuilder.environment()["CRAFTRELAY_CONTAINER_PREFIX"] = composeProjectName.get()
        val process = try {
            processBuilder.start()
        } catch (failure: IOException) {
            if (ignoreFailure) {
                logger.warn("Could not start Docker Compose while cleaning up: ${failure.message}")
                return ""
            }
            throw GradleException("Could not start Docker Compose. Is Docker installed and running?", failure)
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = try {
            process.waitFor()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("Interrupted while waiting for Docker Compose.", interrupted)
        }
        if (!captureOnly && output.isNotBlank()) {
            logger.lifecycle(output.trimEnd())
        }
        if (exitCode != 0 && !ignoreFailure) {
            throw GradleException("Command '${command.joinToString(" ")}' failed with exit code $exitCode.")
        }
        return output
    }
}

abstract class DockerSmokeTask : DockerComposeTask() {

    @get:Input
    abstract val paperCount: Property<Int>

    @get:Input
    abstract val velocityCount: Property<Int>

    @get:Input
    abstract val presenceKeyPrefix: Property<String>

    @TaskAction
    fun runSmokeTest() {
        try {
            compose("up", "--detach", "--wait")
            awaitInstanceLeases()
            compose("exec", "-T", "paper-1", "rcon-cli", "crelay instances")
            awaitInstanceIndex()
            compose("exec", "-T", "paper-1", "rcon-cli", "crelay broadcast CraftRelay smoke test")
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
        val expected = paperCount.get() + velocityCount.get()
        var observed: Int? = null
        awaitCondition(
            timeoutSeconds = 60,
            condition = {
                observed = composeCaptured(
                    "exec", "-T", "redis", "redis-cli", "ZCARD",
                    "${presenceKeyPrefix.get()}:presence:instances",
                ).lineSequence().map(String::trim).mapNotNull(String::toIntOrNull).lastOrNull()
                observed == expected
            },
            failureMessage = {
                "Expected $expected CraftRelay instance leases, but observed ${observed ?: "none"}."
            },
        )
    }

    private fun awaitInstanceIndex() {
        val expectedInstances =
            (1..paperCount.get()).map { "paper-$it" } +
                (1..velocityCount.get()).map { "velocity-$it" }
        var observedInstances = emptySet<String>()
        awaitCondition(
            timeoutSeconds = 30,
            condition = {
                val output = composeCaptured(
                    "exec", "-T", "redis", "redis-cli", "ZRANGE",
                    "${presenceKeyPrefix.get()}:presence:instances", "0", "-1",
                )
                observedInstances = output.lineSequence().map(String::trim).mapNotNull { encoded ->
                    runCatching {
                        String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    }.getOrNull()
                }.toSet()
                observedInstances.containsAll(expectedInstances)
            },
            failureMessage = { "Redis instance index was incomplete: $observedInstances." },
        )
    }
}

abstract class EmbeddedSmokeTask : DockerComposeTask() {

    @get:Input
    abstract val presenceKeyPrefix: Property<String>

    @TaskAction
    fun runEmbeddedSmokeTest() {
        try {
            compose("up", "--detach", "--wait")
            awaitInstanceLeases()
            val readyLogs = composeCaptured("logs", "--no-color", "paper", "velocity")
            if (!readyLogs.contains("Embedded CraftRelay is ready")) {
                throw GradleException("Embedded smoke hosts did not report readiness.")
            }
            val bannerHosts = readyLogs.lineSequence().count { it.contains("____ ____") }
            if (bannerHosts < 2) {
                throw GradleException("Embedded smoke hosts did not report the CraftRelay banner.")
            }
            logger.lifecycle("CraftRelay embedded SDK smoke test passed.")
        } catch (failure: RuntimeException) {
            logger.error("CraftRelay embedded SDK smoke test failed.", failure)
            composeIgnoringFailure("ps")
            composeIgnoringFailure("logs", "--no-color", "--tail", "200")
            throw failure
        } finally {
            composeIgnoringFailure("down", "--volumes")
        }
    }

    private fun awaitInstanceLeases() {
        var observed: Int? = null
        awaitCondition(
            timeoutSeconds = 90,
            condition = {
                observed = composeCaptured(
                    "exec", "-T", "redis", "redis-cli", "ZCARD",
                    "${presenceKeyPrefix.get()}:presence:instances",
                ).lineSequence().map(String::trim).mapNotNull(String::toIntOrNull).lastOrNull()
                observed == 2
            },
            failureMessage = {
                "Expected two embedded SDK instance leases, but observed ${observed ?: "none"}."
            },
        )
    }
}

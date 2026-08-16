import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private val releaseVersionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun File.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().hex()
}

abstract class GenerateApiBaselineReportTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiJar: RegularFileProperty

    @get:Input
    abstract val apiVersion: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val archive = apiJar.get().asFile
        val publicPackageEntries = ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("tv/nicdev/craftrelay/api/") && it.endsWith(".class") }
                .filterNot { it.contains('$') }
                .sorted()
                .toList()
        }
        val report = buildString {
            appendLine("# CraftRelay API baseline ${apiVersion.get()}")
            appendLine()
            appendLine("This is the first release in its compatibility line; there is no prior artifact to compare.")
            appendLine()
            appendLine("Archive SHA-256: `${archive.digest("SHA-256")}`")
            appendLine()
            appendLine("## API package classes")
            appendLine()
            publicPackageEntries.forEach { appendLine("- `${it.removeSuffix(".class").replace('/', '.')}`") }
        }
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(report, StandardCharsets.UTF_8)
    }
}

abstract class VerifyRuntimeLicensesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policyFile: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val noticesFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val policy = Properties().apply {
            policyFile.get().asFile.inputStream().use(::load)
        }.entries.associate { it.key.toString() to it.value.toString() }
        val externalArtifacts = runtimeArtifacts.files
            .filter { it.extension.equals("jar", ignoreCase = true) }
            .filterNot { it.name.startsWith("craftrelay-") }
            .sortedBy { it.name }
        val resolved = externalArtifacts.map { artifact ->
            val matches = policy.keys.filter { coordinate ->
                artifact.name.startsWith("${coordinate.substringAfter(':')}-")
            }
            val longestModuleLength = matches.maxOfOrNull { it.substringAfter(':').length }
            val bestMatches = matches.filter { it.substringAfter(':').length == longestModuleLength }
            if (bestMatches.size != 1) null else bestMatches.single() to artifact
        }
        val missing = resolved.mapIndexedNotNull { index, match ->
            if (match == null) externalArtifacts[index].name else null
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Unreviewed runtime licenses: ${missing.joinToString()}. " +
                    "Review the artifacts and update gradle/runtime-licenses.properties.",
            )
        }
        val output = noticesFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("CraftRelay third-party notices")
                appendLine("===============================")
                appendLine()
                appendLine("The following components are embedded in the Paper and Velocity plugin artifacts:")
                appendLine()
                resolved.filterNotNull().forEach { (coordinate, artifact) ->
                    val module = coordinate.substringAfter(':')
                    val version = artifact.nameWithoutExtension.removePrefix("$module-")
                    appendLine("- $coordinate:$version — ${policy.getValue(coordinate)}")
                }
                appendLine()
                appendLine("Full license and notice texts are retained in the plugin JARs under")
                appendLine("META-INF/craftrelay/licenses/. CraftRelay itself is licensed under Apache-2.0.")
            },
            StandardCharsets.UTF_8,
        )
    }
}

abstract class GenerateLegalResourcesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticesFile: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val metadata = root.resolve("META-INF/craftrelay")
        metadata.mkdirs()
        projectLicense.get().asFile.copyTo(metadata.resolve("LICENSE.txt"), overwrite = true)
        noticesFile.get().asFile.copyTo(metadata.resolve("THIRD-PARTY-NOTICES.txt"), overwrite = true)

        runtimeArtifacts.files.filter { it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.name }
            .forEach { archive ->
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .filter { entry ->
                            val name = entry.name.uppercase()
                            name == "LICENSE" || name == "NOTICE" ||
                                name.startsWith("META-INF/LICENSE") ||
                                name.startsWith("META-INF/NOTICE")
                        }
                        .forEach { entry ->
                            val artifactDirectory = metadata.resolve("licenses/${archive.nameWithoutExtension}")
                            artifactDirectory.mkdirs()
                            val targetName = entry.name.replace('/', '_').replace('\\', '_')
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, artifactDirectory.resolve(targetName).toPath())
                            }
                        }
                }
            }
    }
}

abstract class VerifyReleaseArtifactsTask : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val pluginJars: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val forbiddenPrefixes = listOf(
            "org/bukkit/",
            "com/velocitypowered/api/",
            "io/lettuce/",
            "io/netty/",
            "tools/jackson/",
            "org/snakeyaml/engine/",
            "reactor/",
            "org/reactivestreams/",
        )
        pluginJars.files.sortedBy { it.name }.forEach { archive ->
            if (!archive.isFile) throw GradleException("Missing release artifact: $archive")
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                val forbidden = entries.firstOrNull { entry ->
                    entry.contains(".gitkeep") || entry.endsWith("Test.class") ||
                        forbiddenPrefixes.any(entry::startsWith)
                }
                if (forbidden != null) {
                    throw GradleException("${archive.name} contains forbidden entry '$forbidden'.")
                }
                if (entries.none { it == "META-INF/craftrelay/LICENSE.txt" }) {
                    throw GradleException("${archive.name} does not contain the CraftRelay license.")
                }
                if (archive.name.startsWith("craftrelay-platform-") &&
                    entries.none { it == "META-INF/craftrelay/THIRD-PARTY-NOTICES.txt" }
                ) {
                    throw GradleException("${archive.name} does not contain third-party notices.")
                }
                if (archive.name.contains("paper") && entries.none { it == "plugin.yml" }) {
                    throw GradleException("${archive.name} does not contain plugin.yml.")
                }
            }
        }
    }
}

abstract class VerifyApiPublicationTask : DefaultTask() {
    @get:InputFile
    abstract val pomFile: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val publicationJars: ConfigurableFileCollection

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val pom = pomFile.get().asFile.readText(StandardCharsets.UTF_8)
        val required = listOf(
            "<groupId>de.nicdevtv</groupId>",
            "<artifactId>craftrelay-api</artifactId>",
            "<version>${expectedVersion.get()}</version>",
            "<name>CraftRelay API</name>",
            "<license>",
            "<scm>",
        )
        required.forEach { value ->
            if (value !in pom) throw GradleException("Generated POM is missing '$value'.")
        }
        if ("<dependencies>" in pom) {
            throw GradleException("craftrelay-api must not publish runtime dependencies.")
        }
        val names = publicationJars.files.map { it.name }
        if (names.none { it.endsWith("-sources.jar") } || names.none { it.endsWith("-javadoc.jar") }) {
            throw GradleException("API publication is missing sources or JavaDoc JAR.")
        }
    }
}

abstract class ReleaseBundleTask : DefaultTask() {
    @get:Input
    abstract val releaseVersion: Property<String>

    @get:InputFiles
    @get:Classpath
    abstract val pluginJars: ConfigurableFileCollection

    @get:InputFile
    abstract val rawSbom: RegularFileProperty

    @get:InputFile
    abstract val noticesFile: RegularFileProperty

    @get:InputFile
    abstract val changelogFile: RegularFileProperty

    @get:OutputDirectory
    abstract val bundleDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val version = releaseVersion.get()
        if (!releaseVersionPattern.matches(version)) {
            throw GradleException("releaseBundle requires a numeric release version, not '$version'.")
        }
        val output = bundleDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        pluginJars.files.sortedBy { it.name }.forEach { it.copyTo(output.resolve(it.name), overwrite = true) }
        noticesFile.get().asFile.copyTo(output.resolve("THIRD-PARTY-NOTICES.txt"), overwrite = true)

        val changelog = changelogFile.get().asFile.readText(StandardCharsets.UTF_8)
        val escapedVersion = Regex.escape(version)
        val notes = Regex("(?ms)^## \\[$escapedVersion](?: - [^\\r\\n]+)?\\R(.*?)(?=^## |\\z)")
            .find(changelog)?.groupValues?.get(1)?.trim()
            ?: throw GradleException("CHANGELOG.md has no section for $version.")
        output.resolve("RELEASE_NOTES.md").writeText(notes + "\n", StandardCharsets.UTF_8)

        val normalizedSbom = rawSbom.get().asFile.readText(StandardCharsets.UTF_8)
            .replace(Regex("(?m)^\\s*\"timestamp\"\\s*:\\s*\"[^\"]+\",?\\R?"), "")
        output.resolve("craftrelay-$version.cdx.json")
            .writeText(normalizedSbom, StandardCharsets.UTF_8)

        val assets = output.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith("SHA") }
            .sortedBy { it.name }
        output.resolve("SHA256SUMS").writeText(
            assets.joinToString("\n", postfix = "\n") { "${it.digest("SHA-256")}  ${it.name}" },
            StandardCharsets.UTF_8,
        )
        output.resolve("SHA512SUMS").writeText(
            assets.joinToString("\n", postfix = "\n") { "${it.digest("SHA-512")}  ${it.name}" },
            StandardCharsets.UTF_8,
        )
    }
}

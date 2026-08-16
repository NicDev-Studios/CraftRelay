import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar

private fun Any.gradleGetter(name: String): Any =
    javaClass.getMethod("get${name.replaceFirstChar(Char::uppercase)}").invoke(this)

@Suppress("UNCHECKED_CAST")
private fun Any.gradleStringList(name: String): ListProperty<String> =
    gradleGetter(name) as ListProperty<String>

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Any.gradleProperty(name: String): Property<T> =
    gradleGetter(name) as Property<T>

private fun Any.gradleRegularFile(name: String): RegularFileProperty =
    gradleGetter(name) as RegularFileProperty

private fun Any.gradleFileCollection(name: String): ConfigurableFileCollection =
    gradleGetter(name) as ConfigurableFileCollection

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val installableProjectPaths = listOf(
    ":craftrelay-platform-paper",
    ":craftrelay-platform-velocity",
    ":craftrelay-example-plugin:paper",
    ":craftrelay-example-plugin:velocity",
)
installableProjectPaths.forEach(::evaluationDependsOn)
val installableProjects = installableProjectPaths.map(::project)
val platformProjects = installableProjects.take(2)
val pluginJarTasks = installableProjects.map { it.tasks.named("shadowJar", Jar::class.java) }
val pluginJarFiles = files(pluginJarTasks.map { it.flatMap(Jar::getArchiveFile) })
val releaseRuntimeClasspath = configurations.create("releaseRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}
dependencies.add(
    releaseRuntimeClasspath.name,
    dependencies.project(":craftrelay-platform-paper"),
)
dependencies.add(
    releaseRuntimeClasspath.name,
    dependencies.project(":craftrelay-platform-velocity"),
)
val releaseRuntimeArtifacts = releaseRuntimeClasspath.incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.files
subprojects {
    tasks.matching { it.name == "cyclonedxDirectBom" }.configureEach {
        enabled = project in installableProjects
        gradleStringList("includeConfigs").set(listOf("runtimeClasspath"))
        gradleStringList("skipConfigs").set(listOf(".*[Tt]est.*", ".*[Cc]ompileOnly.*"))
        gradleProperty<Boolean>("includeBomSerialNumber").set(false)
        gradleProperty<Boolean>("includeMetadataResolution").set(false)
        gradleProperty<Boolean>("includeBuildEnvironment").set(false)
        gradleProperty<Boolean>("includeBuildSystem").set(false)
        gradleProperty<String>("componentVersion").set(rootProject.version.toString())
        gradleRegularFile("xmlOutput").unsetConvention()
    }
}

val cyclonedxOutput = layout.buildDirectory.file("reports/cyclonedx/craftrelay.cdx.json")
val cyclonedxBom = tasks.named("cyclonedxBom") {
    gradleProperty<String>("componentGroup").set(project.group.toString())
    gradleProperty<String>("componentName").set("craftrelay")
    gradleProperty<String>("componentVersion").set(project.version.toString())
    gradleProperty<Boolean>("includeBomSerialNumber").set(false)
    gradleProperty<Boolean>("includeBuildSystem").set(false)
    gradleRegularFile("jsonOutput").set(cyclonedxOutput)
    gradleRegularFile("xmlOutput").unsetConvention()
}

val verifyRuntimeLicenses = tasks.register<VerifyRuntimeLicensesTask>("verifyRuntimeLicenses") {
    group = "verification"
    description = "Verifies the reviewed SPDX allowlist for embedded runtime dependencies."
    policyFile.set(layout.projectDirectory.file("gradle/runtime-licenses.properties"))
    runtimeArtifacts.from(releaseRuntimeArtifacts)
    noticesFile.set(layout.buildDirectory.file("reports/licenses/THIRD-PARTY-NOTICES.txt"))
}

val generateLegalResources = tasks.register<GenerateLegalResourcesTask>("generateLegalResources") {
    dependsOn(verifyRuntimeLicenses)
    projectLicense.set(layout.projectDirectory.file("LICENSE"))
    noticesFile.set(verifyRuntimeLicenses.flatMap(VerifyRuntimeLicensesTask::noticesFile))
    runtimeArtifacts.from(releaseRuntimeArtifacts)
    outputDirectory.set(layout.buildDirectory.dir("generated/release-legal"))
}

pluginJarTasks.forEachIndexed { index, jarTask ->
    jarTask.configure {
        if (index < platformProjects.size) {
            dependsOn(generateLegalResources)
            from(generateLegalResources.flatMap(GenerateLegalResourcesTask::outputDirectory))
        } else {
            from(layout.projectDirectory.file("LICENSE")) {
                into("META-INF/craftrelay")
                rename { "LICENSE.txt" }
            }
        }
    }
}

val verifyReleaseArtifacts = tasks.register<VerifyReleaseArtifactsTask>("verifyReleaseArtifacts") {
    group = "verification"
    description = "Inspects the four installable plugin JARs for release invariants."
    dependsOn(pluginJarTasks)
    pluginJars.from(pluginJarFiles)
}

val apiProject = project(":craftrelay-api")
val apiJarTask = apiProject.tasks.named("jar", Jar::class.java)
val cleanVersion = project.version.toString().removeSuffix("-SNAPSHOT")
val versionParts = cleanVersion.split('.').mapNotNull(String::toIntOrNull)
if (versionParts.size != 3) throw GradleException("CraftRelay version must use MAJOR.MINOR.PATCH.")
val compatibilityBaseline = if (versionParts[0] == 0) {
    "${versionParts[0]}.${versionParts[1]}.0"
} else {
    "${versionParts[0]}.0.0"
}

val apiCompatibilityDependency = if (cleanVersion == compatibilityBaseline) {
    apiProject.tasks.register<GenerateApiBaselineReportTask>("apiBaselineReport") {
        dependsOn(apiJarTask)
        apiJar.set(apiJarTask.flatMap { it.archiveFile })
        apiVersion.set(cleanVersion)
        reportFile.set(apiProject.layout.buildDirectory.file("reports/api/baseline-$cleanVersion.md"))
    }
} else {
    val baseline = apiProject.configurations.create("apiCompatibilityBaseline") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    apiProject.dependencies.add(baseline.name, "de.nicdevtv:craftrelay-api:$compatibilityBaseline")
    @Suppress("UNCHECKED_CAST")
    val taskType = Class.forName("me.champeau.gradle.japicmp.JapicmpTask")
        .asSubclass(Task::class.java) as Class<Task>
    apiProject.tasks.register("apiCompatibilityCheck", taskType) {
        dependsOn(apiJarTask)
        gradleFileCollection("oldArchives").from(baseline)
        gradleFileCollection("newArchives").from(apiJarTask.flatMap { it.archiveFile })
        gradleFileCollection("oldClasspath").from(baseline)
        gradleStringList("packageIncludes").set(listOf("tv.nicdev.craftrelay.api.**"))
        gradleProperty<String>("accessModifier").set("public")
        gradleProperty<Boolean>("onlyModified").set(true)
        gradleProperty<Boolean>("failOnModification").set(true)
        gradleProperty<Boolean>("failOnSourceIncompatibility").set(true)
        gradleProperty<Boolean>("includeSynthetic").set(false)
        gradleProperty<Boolean>("ignoreMissingClasses").set(false)
        gradleRegularFile("htmlOutputFile")
            .set(apiProject.layout.buildDirectory.file("reports/api/compatibility.html"))
        gradleRegularFile("mdOutputFile")
            .set(apiProject.layout.buildDirectory.file("reports/api/compatibility.md"))
    }
}

tasks.register("apiCompatibility") {
    group = "verification"
    description = "Checks public API compatibility or records the first baseline report."
    dependsOn(apiCompatibilityDependency)
}

val verifyApiPublication = tasks.register<VerifyApiPublicationTask>("verifyApiPublication") {
    group = "verification"
    description = "Validates the generated Maven publication for craftrelay-api."
    dependsOn(
        apiProject.tasks.named("generatePomFileForMavenPublication"),
        apiProject.tasks.named("jar"),
        apiProject.tasks.named("sourcesJar"),
        apiProject.tasks.named("javadocJar"),
    )
    pomFile.set(apiProject.layout.buildDirectory.file("publications/maven/pom-default.xml"))
    publicationJars.from(
        apiProject.tasks.named("jar", Jar::class.java).flatMap(Jar::getArchiveFile),
        apiProject.tasks.named("sourcesJar", Jar::class.java).flatMap(Jar::getArchiveFile),
        apiProject.tasks.named("javadocJar", Jar::class.java).flatMap(Jar::getArchiveFile),
    )
    expectedVersion.set(project.version.toString())
}

val releaseBundle = tasks.register<ReleaseBundleTask>("releaseBundle") {
    group = "distribution"
    description = "Creates the deterministic, checksummed CraftRelay release bundle."
    dependsOn(verifyReleaseArtifacts, verifyRuntimeLicenses, cyclonedxBom)
    releaseVersion.set(project.version.toString())
    pluginJars.from(pluginJarFiles)
    rawSbom.set(cyclonedxOutput)
    noticesFile.set(verifyRuntimeLicenses.flatMap(VerifyRuntimeLicensesTask::noticesFile))
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
    bundleDirectory.set(layout.buildDirectory.dir("release/${project.version}"))
}

tasks.register("releaseCheck") {
    group = "verification"
    description = "Runs all checks required before creating a release bundle."
    dependsOn("apiCompatibility", verifyReleaseArtifacts, verifyRuntimeLicenses, verifyApiPublication)
}

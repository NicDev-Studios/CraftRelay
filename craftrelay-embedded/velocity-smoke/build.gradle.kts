import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

val generatedEntryPointSources =
    layout.buildDirectory.dir("generated/sources/embeddedSmokeEntryPoint/java/main")

val generateEntryPoint = tasks.register<Sync>("generateEntryPoint") {
    val properties = mapOf(
        "version" to project.version.toString(),
        "authors" to project.extra["craftrelayAuthorsJavaLiteral"],
    )
    inputs.properties(properties)
    from("src/main/templates") {
        expand(properties)
    }
    into(generatedEntryPointSources)
}

sourceSets.main {
    java.srcDir(generatedEntryPointSources)
}

dependencies {
    implementation(project(path = ":craftrelay-embedded", configuration = "shadowRuntimeElements"))
    compileOnly(project(":craftrelay-api"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.velocity.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    dependsOn(generateEntryPoint)
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.sourcesJar {
    dependsOn(generateEntryPoint)
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName = "craftrelay-embedded-velocity-smoke"
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

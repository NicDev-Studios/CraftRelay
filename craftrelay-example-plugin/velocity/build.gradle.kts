import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

val generatedEntryPointSources =
    layout.buildDirectory.dir("generated/sources/craftrelayEntryPoint/java/main")

val generateExampleVelocityEntryPoint =
    tasks.register<Sync>("generateExampleVelocityEntryPoint") {
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
    implementation(project(":craftrelay-example-plugin"))

    compileOnly(project(":craftrelay-api"))
    compileOnly(project(":craftrelay-platform-velocity"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":craftrelay-api"))
    testImplementation(libs.velocity.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    dependsOn(generateExampleVelocityEntryPoint)
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.sourcesJar {
    dependsOn(generateExampleVelocityEntryPoint)
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName = "craftrelay-example-velocity"
    archiveClassifier = ""
    dependencies {
        exclude(dependency("de.nicdevtv:craftrelay-api"))
        exclude(dependency("de.nicdevtv:craftrelay-platform-velocity"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

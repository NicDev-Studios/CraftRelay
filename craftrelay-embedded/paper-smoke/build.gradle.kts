import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(path = ":craftrelay-embedded", configuration = "shadowRuntimeElements"))
    compileOnly(project(":craftrelay-api"))
    compileOnly(libs.paper.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version.toString(),
        "authors" to project.extra["craftrelayAuthorsListLiteral"],
    )
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName = "craftrelay-embedded-paper-smoke"
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

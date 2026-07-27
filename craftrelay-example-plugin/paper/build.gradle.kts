import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":craftrelay-example-plugin"))

    compileOnly(project(":craftrelay-api"))
    compileOnly(libs.paper.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":craftrelay-api"))
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
    archiveBaseName = "craftrelay-example-paper"
    archiveClassifier = ""
    dependencies {
        exclude(dependency("de.nicdevtv:craftrelay-api"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

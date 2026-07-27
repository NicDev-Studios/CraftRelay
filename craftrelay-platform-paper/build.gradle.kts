import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":craftrelay-api"))
    implementation(project(":craftrelay-common"))
    implementation(project(":craftrelay-transport-redis"))

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
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    exclude("META-INF/LICENSE", "META-INF/NOTICE")
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")

    relocate("io.lettuce", "tv.nicdev.craftrelay.internal.lib.lettuce")
    relocate("io.netty", "tv.nicdev.craftrelay.internal.lib.netty")
    relocate("tools.jackson", "tv.nicdev.craftrelay.internal.lib.jackson")
    relocate("org.snakeyaml.engine", "tv.nicdev.craftrelay.internal.lib.snakeyaml")
    relocate("reactor", "tv.nicdev.craftrelay.internal.lib.reactor")
    relocate("org.reactivestreams", "tv.nicdev.craftrelay.internal.lib.reactivestreams")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

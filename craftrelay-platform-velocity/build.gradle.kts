import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

val generatedVersionSources =
    layout.buildDirectory.dir("generated/sources/craftrelayVersion/java/main")

val generateCraftRelayVersion = tasks.register<Sync>("generateCraftRelayVersion") {
    val properties = mapOf("version" to project.version.toString())
    inputs.properties(properties)
    from("src/main/templates") {
        expand(properties)
    }
    into(generatedVersionSources)
}

sourceSets.main {
    java.srcDir(generatedVersionSources)
}

dependencies {
    implementation(project(":craftrelay-api"))
    implementation(project(":craftrelay-common"))
    implementation(project(":craftrelay-transport-redis"))

    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.velocity.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    dependsOn(generateCraftRelayVersion)
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.sourcesJar {
    dependsOn(generateCraftRelayVersion)
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

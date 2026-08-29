import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication
plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.japicmp)
    id("com.vanniktech.maven.publish")
}

extensions.extraProperties["craftrelayPublicationArtifactId"] = "craftrelay-embedded"
extensions.extraProperties["craftrelayPublicationName"] = "CraftRelay Embedded SDK"
extensions.extraProperties["craftrelayPublicationDescription"] =
    "Embedded, platform-neutral CraftRelay node for Minecraft plugins"
apply(from = "../gradle/maven-publishing.gradle.kts")

val embeddedRuntimeConfiguration = configurations.register("embeddedRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Keep implementation dependencies out of the published POM. The SDK ships them in its
    // classifierless Shadow JAR; compileOnly supplies the public source classpath while the
    // dedicated configuration below supplies the exact runtime inputs to Shadow.
    compileOnly(project(":craftrelay-api"))
    compileOnly(project(":craftrelay-common"))
    compileOnly(project(":craftrelay-transport-redis"))
    add("embeddedRuntime", project(path = ":craftrelay-api", configuration = "runtimeElements"))
    add("embeddedRuntime", project(path = ":craftrelay-common", configuration = "runtimeElements"))
    add("embeddedRuntime", project(path = ":craftrelay-transport-redis", configuration = "runtimeElements"))

    testImplementation(project(":craftrelay-api"))
    testImplementation(project(":craftrelay-common"))
    testImplementation(project(":craftrelay-transport-redis"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    configurations.set(setOf(embeddedRuntimeConfiguration.get()))
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    exclude("META-INF/LICENSE", "META-INF/NOTICE")
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")

    relocate(
        "tv.nicdev.craftrelay.common",
        "tv.nicdev.craftrelay.embedded.internal.common",
    )
    relocate(
        "tv.nicdev.craftrelay.transport.redis",
        "tv.nicdev.craftrelay.embedded.internal.redis",
    )
    relocate("io.lettuce", "tv.nicdev.craftrelay.embedded.internal.lib.lettuce")
    relocate(
        "redis.clients",
        "tv.nicdev.craftrelay.embedded.internal.lib.redis.clients",
    )
    relocate("org.jctools", "tv.nicdev.craftrelay.embedded.internal.lib.jctools")
    relocate("io.netty", "tv.nicdev.craftrelay.embedded.internal.lib.netty")
    relocate("org.slf4j", "tv.nicdev.craftrelay.embedded.internal.lib.slf4j")
    relocate(
        "com.fasterxml.jackson",
        "tv.nicdev.craftrelay.embedded.internal.lib.jackson.annotations",
    )
    relocate("tools.jackson", "tv.nicdev.craftrelay.embedded.internal.lib.jackson")
    relocate(
        "org.snakeyaml.engine",
        "tv.nicdev.craftrelay.embedded.internal.lib.snakeyaml",
    )
    relocate("reactor", "tv.nicdev.craftrelay.embedded.internal.lib.reactor")
    relocate(
        "org.reactivestreams",
        "tv.nicdev.craftrelay.embedded.internal.lib.reactivestreams",
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(tasks.named<ShadowJar>("shadowJar")) {
            classifier = ""
        }
    }
}


// Shadow is the published main artifact. Gradle Module Metadata would otherwise expose the
// disabled plain Java variant as a second, unusable runtime variant.
tasks.matching { it.name == "generateMetadataFileForMavenPublication" }.configureEach {
    enabled = false
}

// Vannik's Java component contributes the shared project dependencies and marks this
// publication as a POM-only component because the plain jar is disabled. Normalize the final
// POM after that convention has run: the embedded Shadow JAR is the actual Maven main artifact.
afterEvaluate {
    publishing.publications.named<MavenPublication>("maven") {
        pom.withXml {
            val root = asNode()
            root.children().filterIsInstance<groovy.util.Node>()
                .filter { it.name().toString().substringAfterLast('}') == "dependencies" }
                .toList()
                .forEach { root.remove(it) }
            val packaging = root.children().filterIsInstance<groovy.util.Node>()
                .firstOrNull { it.name().toString().substringAfterLast('}') == "packaging" }
            if (packaging == null) {
                root.appendNode("packaging", "jar")
            } else {
                packaging.setValue("jar")
            }
        }
    }
}

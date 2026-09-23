plugins {
    id("com.vanniktech.maven.publish")
    alias(libs.plugins.japicmp)
}

extensions.extraProperties["craftrelayPublicationArtifactId"] = "craftrelay-api"
extensions.extraProperties["craftrelayPublicationName"] = "CraftRelay API"
extensions.extraProperties["craftrelayPublicationDescription"] =
    "Public API for the CraftRelay Minecraft network library"
apply(from = "../gradle/maven-publishing.gradle.kts")

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

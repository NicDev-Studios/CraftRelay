dependencies {
    testImplementation(project(":craftrelay-api"))
    testImplementation(project(":craftrelay-common"))
    testImplementation(project(":craftrelay-transport-redis"))
    testImplementation(project(":craftrelay-platform-paper"))
    testImplementation(project(":craftrelay-platform-velocity"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.toxiproxy)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require Docker."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

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
}

dependencies {
    api(project(":craftrelay-api"))

    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Javadoc>().configureEach {
    exclude("tv/nicdev/craftrelay/common/internal/**")
}

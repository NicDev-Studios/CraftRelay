dependencies {
    api(project(":craftrelay-common"))
    implementation(libs.lettuce.core)
    implementation(libs.snakeyaml.engine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Javadoc>().configureEach {
    exclude("tv/nicdev/craftrelay/transport/redis/**")
}

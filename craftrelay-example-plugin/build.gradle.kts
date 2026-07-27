dependencies {
    compileOnly(project(":craftrelay-api"))

    testImplementation(project(":craftrelay-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencies {
    compileOnly(project(":craftrelay-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

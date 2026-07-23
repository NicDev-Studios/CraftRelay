dependencies {
    api(project(":craftrelay-api"))

    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

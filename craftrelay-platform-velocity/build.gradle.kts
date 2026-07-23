dependencies {
    implementation(project(":craftrelay-api"))
    implementation(project(":craftrelay-common"))
    implementation(project(":craftrelay-transport-redis"))

    compileOnly(libs.velocity.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

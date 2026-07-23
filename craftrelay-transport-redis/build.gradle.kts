dependencies {
    implementation(project(":craftrelay-api"))
    implementation(project(":craftrelay-common"))
    implementation(libs.lettuce.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

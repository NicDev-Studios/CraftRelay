plugins {
    alias(libs.plugins.maven.publish)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = "craftrelay-api",
        version = project.version.toString(),
    )

    pom {
        name = "CraftRelay API"
        description = "Public API for the CraftRelay Minecraft network library"
        inceptionYear = "2026"
        url = "https://github.com/NicDev-Studios/CraftRelay"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "NicDevTV"
                name = "Niclas"
                url = "https://github.com/NicDevTV"
            }
        }

        scm {
            url = "https://github.com/NicDev-Studios/CraftRelay"
            connection = "scm:git:https://github.com/NicDev-Studios/CraftRelay.git"
            developerConnection =
                "scm:git:ssh://git@github.com/NicDev-Studios/CraftRelay.git"
        }
    }
}

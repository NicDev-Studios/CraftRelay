import org.gradle.api.Action
import org.gradle.api.publish.maven.MavenPom

val publicationArtifactId =
    project.findProperty("craftrelayPublicationArtifactId")?.toString()
        ?: error("craftrelayPublicationArtifactId is required")
val publicationName =
    project.findProperty("craftrelayPublicationName")?.toString()
        ?: error("craftrelayPublicationName is required")
val publicationDescription =
    project.findProperty("craftrelayPublicationDescription")?.toString()
        ?: error("craftrelayPublicationDescription is required")

// The Maven plugin is applied by the consuming project. External Kotlin scripts are compiled
// before that plugin's implementation classpath is available, so use the stable extension name
// and Java reflection here rather than duplicating this metadata in every publication module.
val mavenPublishing = extensions.getByName("mavenPublishing")
mavenPublishing.javaClass.getMethod("publishToMavenCentral").invoke(mavenPublishing)
mavenPublishing.javaClass.getMethod("signAllPublications").invoke(mavenPublishing)
mavenPublishing.javaClass
    .getMethod("coordinates", String::class.java, String::class.java, String::class.java)
    .invoke(mavenPublishing, "de.nicdevtv", publicationArtifactId, project.version.toString())

val configurePom = Action<MavenPom> {
    name.set(publicationName)
    description.set(publicationDescription)
    inceptionYear.set("2026")
    url.set("https://github.com/NicDev-Studios/CraftRelay")

    licenses {
        license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
        }
    }

    developers {
        developer {
            id.set("NicDevTV")
            name.set("Niclas")
            url.set("https://github.com/NicDevTV")
        }
    }

    scm {
        url.set("https://github.com/NicDev-Studios/CraftRelay")
        connection.set("scm:git:https://github.com/NicDev-Studios/CraftRelay.git")
        developerConnection.set("scm:git:ssh://git@github.com/NicDev-Studios/CraftRelay.git")
    }
}
mavenPublishing.javaClass.getMethod("pom", Action::class.java)
    .invoke(mavenPublishing, configurePom)

import java.util.Properties

plugins {
    id("com.vanniktech.maven.publish")
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val versionName: String = versionProps.getProperty("VERSION_NAME")
    ?: error("VERSION_NAME missing from version.properties")

// Credentials and signing key come from Gradle properties, never from files in the repo:
//   mavenCentralUsername / mavenCentralPassword   (Central Portal user token)
//   signingInMemoryKey / signingInMemoryKeyId / signingInMemoryKeyPassword
// Locally: ~/.gradle/gradle.properties. On CI: ORG_GRADLE_PROJECT_<name> environment variables.
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.keyId").isPresent

// Central deployments are left in "validated" state for a manual release in the Central Portal
// unless -PmavenCentralAutomaticRelease=true is passed (CI does this).
val automaticRelease = providers.gradleProperty("mavenCentralAutomaticRelease").orNull == "true"

mavenPublishing {
    coordinates(
        groupId = "com.eternal.kits",
        artifactId = project.name,
        version = versionName,
    )

    publishToMavenCentral(automaticRelease = automaticRelease)
    // Central requires signatures; mavenLocal publishing works without a key.
    if (hasSigningKey) signAllPublications()

    pom {
        name.set(project.findProperty("publishingName") as String? ?: "Live Telemetry")
        description.set(
            project.findProperty("publishingDescription") as String?
                ?: "In-app, real-time performance overlay for Android: FPS, frame timing, memory, CPU, GC and network."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Zomato/live-telemetry")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("firefinchdev")
                name.set("Anirudh Gupta")
                url.set("https://github.com/firefinchdev")
            }
            developer {
                id.set("brockster2809")
                name.set("Anshul Goyal")
                url.set("https://github.com/brockster2809")
            }
            developer {
                id.set("zomato")
                name.set("Zomato")
                url.set("https://github.com/Zomato")
            }
        }

        scm {
            url.set("https://github.com/Zomato/live-telemetry")
            connection.set("scm:git:git://github.com/Zomato/live-telemetry.git")
            developerConnection.set("scm:git:ssh://git@github.com/Zomato/live-telemetry.git")
        }
    }
}

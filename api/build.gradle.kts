plugins {
    id("maven-publish")

}


java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "playerapi"
            pom {
                name.set("playerapi")
                description.set("manage players")
                url.set("https://einjojo.it/")


                // Füge Entwicklerinformationen hinzu
                developers {
                    developer {
                        id.set("einjojo")
                        name.set("Johannes")
                        email.set("johannes@einjojo.it")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            val snapshotsUri = uri("https://repo.einjojo.it/snapshots")
            val releasesUri = uri("https://repo.einjojo.it/releases")
            // Determine repository based on version string
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUri else releasesUri
            credentials {
                username = System.getenv("REPO_USERNAME")
                password = System.getenv("REPO_PASSWORD")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

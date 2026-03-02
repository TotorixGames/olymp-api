import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar


plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0"
    id("me.qoomon.git-versioning") version "6.4.4"
}

version = "0.0.0-SNAPSHOT"  // Will be overridden by git-versioning

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.gradleup.shadow")
    apply(plugin = "me.qoomon.git-versioning")
    group = "it.einjojo"
    version = rootProject.version

    gitVersioning.apply {
        // Use describe strategy for shallow clones (works with fetch-depth: 1)
        // This will gracefully degrade when tags are not available
        describeTagPattern = "v(?<version>.*)"
        describeTagFirstParent = true

        refs {
            // Release tags: v1.2.3 -> 1.2.3 (Maven Central compliant)
            tag("v(?<version>\\d+\\.\\d+\\.\\d+)") {
                version = "\${ref.version}"
            }

            // Pre-release tags: v1.2.3-alpha.1 -> 1.2.3-alpha.1 (Maven Central compliant)
            tag("v(?<version>\\d+\\.\\d+\\.\\d+-.+)") {
                version = "\${ref.version}"
            }

            // Main/master branch: SNAPSHOT versions for development
            branch("main|master") {
                version = "\${describe.tag.version:0.0.0}-SNAPSHOT"
            }

            // Feature branches: include branch name for clarity
            branch(".+") {
                version = "\${describe.tag.version:0.0.0}-\${ref.slug}-SNAPSHOT"
            }
        }

        // Fallback for untagged commits or shallow clones without tags
        // This handles the case where git describe fails in shallow clones
        rev {
            version = "0.0.0-\${commit.short}-SNAPSHOT"
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }




    dependencies {
        compileOnly("org.jetbrains:annotations:26.0.2-1")
    }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile> {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    tasks.named("assemble") {
        dependsOn(tasks.named("shadowJar"))
    }

    if (project.name == "api" || project.name == "playerapi") {
        tasks.named("shadowJar", ShadowJar::class) {
            enabled = false;
        }
    } else if (project.name == "velocity" || project.name == "paper") {
        tasks.named("shadowJar", ShadowJar::class) {
            destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
            archiveClassifier.set("")
            archiveBaseName.set("playerapi-${project.name}")
            relocate("io.grpc", "it.einjojo.playerapi.libs.grpc")
            relocate("com.google.protobuf", "it.einjojo.playerapi.libs.protobuf")
            relocate("io.lettuce", "it.einjojo.playerapi.libs.lettuce")
            mergeServiceFiles()
        }
    }
}

tasks {
    shadowJar {
        enabled = false;
    }
    jar {
        enabled = false;
    }
}


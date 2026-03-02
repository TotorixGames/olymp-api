import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar


plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0"
    id("me.qoomon.git-versioning") version "6.4.4"
}

version = "1.5.3"
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.gradleup.shadow")
    apply(plugin = "me.qoomon.git-versioning")
    group = "it.einjojo"
    version = rootProject.version
    extra["tagged"] = false
    gitVersioning.apply {
        refs {
            tag("v(?<version>.*)") {
                extra["tagged"] = true
                version = if (name == "api") {
                    "\${ref.version.major}.\${ref.version.minor}"
                } else {
                    "\${ref.version}"
                }
            }
        }
        rev {
            version = if (name == "api") {
                "\${version.major}.\${version.minor}-\${commit.short}"
            } else {
                "\${version}-\${commit.short}"
            }
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


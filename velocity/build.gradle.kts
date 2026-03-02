dependencies {
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation("io.lettuce:lettuce-core:6.8.1.RELEASE")

}


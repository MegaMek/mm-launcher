plugins {
    application
}

group = "org.megamek.launcher"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass = "org.megamek.launcher.Main"
}

tasks.test {
    useJUnitPlatform()
}

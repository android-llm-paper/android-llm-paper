plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "moe.reimu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.soot-oss:soot:4.5.0")
    implementation("de.fraunhofer.sit.sse.flowdroid:soot-infoflow:2.13.0")
    implementation("de.fraunhofer.sit.sse.flowdroid:soot-infoflow-android:2.13.0")

    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")

    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    implementation("io.github.skylot:jadx-core:1.5.0")
    implementation("io.github.skylot:jadx-smali-input:1.5.0")
    implementation("io.github.skylot:jadx-dex-input:1.5.0")
    implementation("io.github.skylot:jadx-java-input:1.5.0")
    implementation("io.github.skylot:jadx-kotlin-metadata:1.5.0")

    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("org.mongodb:mongodb-driver-kotlin-sync:5.2.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.0")
    implementation("org.mongodb:bson-kotlinx:5.2.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.create("runApp", type = JavaExec::class) {
    mainClass = "moe.reimu.MainKt"
    classpath = sourceSets.main.get().runtimeClasspath
}

kotlin {
    jvmToolchain(11)
}
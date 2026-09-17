import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    application
}

group = "com.counters"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"
val exposedVersion = "0.56.0"
val postgresVersion = "42.7.4"
val hikariVersion = "5.1.0"
val logbackVersion = "1.5.12"
val slf4jVersion = "2.0.16"
val testcontainersVersion = "1.20.4"
val liquibaseVersion = "4.29.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-swagger:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.liquibase:liquibase-core:$liquibaseVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-network-tls-certificates:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Self-signed dev certificate for local HTTPS. Generated once with the JDK's keytool and
// gitignored; `./gradlew run` provisions it automatically if it's missing.
val devKeystore = layout.projectDirectory.file("keystore.p12")

val keytoolName = if (Os.isFamily(Os.FAMILY_WINDOWS)) "bin/keytool.exe" else "bin/keytool"
val keytool = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.file(keytoolName).asFile.absolutePath }

val generateDevKeystore by tasks.registering(Exec::class) {
    group = "application"
    description = "Generates keystore.p12 (self-signed, CN=localhost) for local HTTPS if it does not exist"
    onlyIf { !devKeystore.asFile.exists() }

    executable(keytool.get())
    args(
        "-genkeypair",
        "-alias", "counters",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "3650",
        "-storetype", "PKCS12",
        "-keystore", devKeystore.asFile.absolutePath,
        "-dname", "CN=localhost, OU=Counters, O=Counters",
        // Browsers match the hostname against SAN only (CN is ignored since 2017).
        "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        "-storepass", "changeit",
        "-keypass", "changeit",
    )
}

// The certificate's public half, for clients that want to trust it explicitly
// (curl --cacert counters.crt, a Java truststore, the OS store) instead of skipping verification.
val devCertificate = layout.projectDirectory.file("counters.crt")

val exportDevCertificate by tasks.registering(Exec::class) {
    group = "application"
    description = "Exports the dev certificate (PEM) from keystore.p12 to counters.crt if it does not exist"
    dependsOn(generateDevKeystore)
    onlyIf { !devCertificate.asFile.exists() }

    executable(keytool.get())
    args(
        "-exportcert", "-rfc",
        "-alias", "counters",
        "-keystore", devKeystore.asFile.absolutePath,
        "-storepass", "changeit",
        "-file", devCertificate.asFile.absolutePath,
    )
}

tasks.named("run") {
    dependsOn(exportDevCertificate)
}

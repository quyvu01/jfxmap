plugins {
    `java-library`
}

description = "FxMap for Java: the Azure Service Bus transport, compatible with FxMap.Azure.ServiceBus"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":jfxmap-core"))
    api(libs.azure.servicebus)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    // The integration tests run against the real Azure Service Bus emulator in Docker
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.azure)
    testImplementation(libs.testcontainers.mssqlserver)
    // The emulator keeps its state in SQL Server, which Testcontainers waits for over JDBC
    testRuntimeOnly(libs.mssql.jdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
}

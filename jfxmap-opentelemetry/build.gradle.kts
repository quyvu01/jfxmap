plugins {
    `java-library`
}

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
    api(platform(libs.opentelemetry.bom))
    api(libs.opentelemetry.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    // The integration tests carry the trace over each transport, against the real brokers in Docker
    testImplementation(project(":jfxmap-nats"))
    testImplementation(project(":jfxmap-rabbitmq"))
    testImplementation(project(":jfxmap-kafka"))
    testImplementation(project(":jfxmap-sqs"))
    testImplementation(project(":jfxmap-azure-servicebus"))
    testImplementation(project(":jfxmap-grpc"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.azure)
    testImplementation(libs.testcontainers.mssqlserver)
    testRuntimeOnly(libs.mssql.jdbc)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/jfxmap-version.properties") {
        expand("version" to version)
    }
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

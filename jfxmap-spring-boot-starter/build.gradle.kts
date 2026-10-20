plugins {
    `java-library`
}

description = "FxMap for Java: Spring Boot auto-configuration of the mapper, handlers and transports"

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
    implementation(platform(libs.spring.boot.dependencies))
    api(project(":jfxmap-core"))
    api(libs.spring.boot.autoconfigure)
    // Each one turns on its part of the auto-configuration when the application adds it
    compileOnly(project(":jfxmap-jpa"))
    compileOnly(project(":jfxmap-grpc"))
    compileOnly(project(":jfxmap-mongodb"))
    compileOnly(project(":jfxmap-nats"))
    compileOnly(project(":jfxmap-rabbitmq"))
    compileOnly(project(":jfxmap-kafka"))
    compileOnly(project(":jfxmap-sqs"))
    compileOnly(project(":jfxmap-azure-servicebus"))
    compileOnly(project(":jfxmap-graphql"))
    compileOnly(project(":jfxmap-opentelemetry"))
    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly(libs.spring.graphql)
    compileOnly(libs.spring.web)
    compileOnly(libs.reactor.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.spring.boot.test)
    testImplementation(project(":jfxmap-jpa"))
    testImplementation(project(":jfxmap-grpc"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.hibernate.core)
    testRuntimeOnly(libs.h2)
    testImplementation(project(":jfxmap-mongodb"))
    testImplementation(project(":jfxmap-nats"))
    testImplementation(project(":jfxmap-rabbitmq"))
    testImplementation(project(":jfxmap-kafka"))
    testImplementation(project(":jfxmap-sqs"))
    testImplementation(project(":jfxmap-azure-servicebus"))
    testImplementation(project(":jfxmap-graphql"))
    testImplementation(project(":jfxmap-opentelemetry"))
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.spring.boot.graphql)
    testImplementation(libs.spring.web)
    // The integration tests run the transports against real brokers in Docker
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.azure)
    testImplementation(libs.testcontainers.mssqlserver)
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

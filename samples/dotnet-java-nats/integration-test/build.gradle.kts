plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(project(":samples:dotnet-java-nats:sales-service"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The command that starts catalog-service (.NET), e.g.
    // "dotnet samples/dotnet-java-nats/build/catalog-service/CatalogService.dll"; without it the test is skipped
    inputs.property("catalogService", providers.environmentVariable("JFXMAP_SAMPLE_CATALOG").orElse(""))
    // catalog-service is run again whenever it is asked for: it is not in Gradle's inputs
    outputs.upToDateWhen { System.getenv("JFXMAP_SAMPLE_CATALOG").isNullOrBlank() }
}

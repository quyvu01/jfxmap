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
    testImplementation(project(":jfxmap-nats"))
    testImplementation(project(":jfxmap-rabbitmq"))
    testImplementation(project(":jfxmap-kafka"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.kafka)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The command that runs the .NET probe (dotnet-probe/), e.g. "dotnet interop/dotnet-probe/out/DotnetProbe.dll";
    // without it the interop tests are skipped
    inputs.property("dotnetProbe", providers.environmentVariable("JFXMAP_DOTNET_PROBE").orElse(""))
    // The probe is run again whenever it is asked for: its result is not in Gradle's inputs
    outputs.upToDateWhen { System.getenv("JFXMAP_DOTNET_PROBE").isNullOrBlank() }
}

// JMH benchmarks; not published, and not run by `./gradlew build` (only their test is).
//   ./gradlew :benchmarks:jmhJar && java -jar benchmarks/build/libs/benchmarks-jmh.jar [regex] [JMH options]
//   ./gradlew :benchmarks:jmh -PjmhIncludes=ExpressionParser
plugins {
    java
    alias(libs.plugins.jmh)
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
    jmhImplementation(project(":jfxmap-core"))
    jmhImplementation(project(":jfxmap-expression"))

    // The test runs each benchmark's code once and checks its result, so a benchmark never measures a failure
    testImplementation(sourceSets.jmh.get().output)
    testImplementation(project(":jfxmap-core"))
    testImplementation(project(":jfxmap-expression"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation("org.openjdk.jmh:jmh-core:${libs.versions.jmh.get()}")
}

jmh {
    jmhVersion = libs.versions.jmh
    // The test depends on the benchmarks, not the other way round
    includeTests = false
    providers.gradleProperty("jmhIncludes").orNull?.let { includes = listOf(it) }
    resultFormat = "JSON"
}

// A path that does not change with the version
tasks.jmhJar {
    archiveFileName = "benchmarks-jmh.jar"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -processing: the JMH annotation processor has no claim on the other annotations
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

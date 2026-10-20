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
    implementation(platform(libs.spring.boot.dependencies))
    api(project(":jfxmap-core"))
    api(libs.spring.boot.autoconfigure)
    // Each one turns on its part of the auto-configuration when the application adds it
    compileOnly(project(":jfxmap-jpa"))
    compileOnly(project(":jfxmap-grpc"))

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

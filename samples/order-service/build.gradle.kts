plugins {
    java
    alias(libs.plugins.spring.boot)
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
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":jfxmap-spring-boot-starter"))
    implementation(project(":jfxmap-jpa"))
    implementation(project(":jfxmap-grpc"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    // Checks the expressions of OrderResponseProfile when it compiles (-Xplugin:JfxMapExpressions below)
    annotationProcessor(project(":jfxmap-validation"))

    testImplementation(project(":jfxmap-validation"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Werror"))
}

tasks.compileJava {
    // An invalid expression in a profile fails the build: OFX001, as FxMap.Analyzers reports it
    options.compilerArgs.add("-Xplugin:JfxMapExpressions")
}

tasks.test {
    useJUnitPlatform()
}

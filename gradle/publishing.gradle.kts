// Maven Central publishing of every library module (each `jfxmap-*` project, the BOM included); samples, interop
// and benchmarks are left out. Applied from the root build, so a new `jfxmap-*` module is published with no extra line.
//
//   ./gradlew publishToMavenLocal                  into ~/.m2
//   ./gradlew publishAllPublicationsToStagingRepository
//                                                  into build/staging-deploy, laid out as Maven Central wants it
//   ./gradlew centralBundle verifyCentralBundle -Pversion=0.1.0
//                                                  build/central-bundle.zip, the file uploaded to the Central Portal
//                                                  (.github/workflows/release.yml does this on a `v*` tag)
//
// The artifacts are signed only when a key is given, e.g. with the environment variables
// ORG_GRADLE_PROJECT_signingInMemoryKey (an ASCII-armored secret key) and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword.

val stagingDirectory = layout.buildDirectory.dir("staging-deploy")

configure(subprojects.filter { it.name.startsWith("jfxmap-") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val publishing = extensions.getByType<PublishingExtension>()

    publishing.repositories {
        maven {
            name = "staging"
            url = uri(stagingDirectory)
        }
    }

    plugins.withId("java-library") {
        publishing.publications.create<MavenPublication>("maven") {
            from(components["java"])
            // The versions that come from a platform (Spring Boot, gRPC) are written into the POM
            versionMapping {
                allVariants {
                    fromResolutionResult()
                }
            }
        }
    }
    plugins.withId("java-platform") {
        publishing.publications.create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }

    publishing.publications.withType<MavenPublication>().configureEach {
        pom {
            name = project.name
            description = provider { project.description ?: "jfxmap module ${project.name}" }
            url = "https://github.com/quyvu01/jfxmap"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "quyvu01"
                    name = "Quy Vu"
                    url = "https://github.com/quyvu01"
                }
            }
            scm {
                url = "https://github.com/quyvu01/jfxmap"
                connection = "scm:git:https://github.com/quyvu01/jfxmap.git"
                developerConnection = "scm:git:ssh://git@github.com/quyvu01/jfxmap.git"
            }
            issueManagement {
                system = "GitHub"
                url = "https://github.com/quyvu01/jfxmap/issues"
            }
        }
    }

    val signingKey = findProperty("signingInMemoryKey") as String?
    if (!signingKey.isNullOrBlank()) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(
                findProperty("signingInMemoryKeyId") as String?,
                signingKey,
                findProperty("signingInMemoryKeyPassword") as String? ?: "",
            )
            sign(publishing.publications)
        }
    }
}

// The staging directory keeps what earlier runs published; the bundle takes only this version's files
val cleanStaging by tasks.registering(Delete::class) {
    delete(stagingDirectory)
}
val libraryProjects = subprojects.filter { it.name.startsWith("jfxmap-") }
libraryProjects.forEach { library ->
    library.tasks.matching { it.name == "publishAllPublicationsToStagingRepository" }.configureEach {
        dependsOn(cleanStaging)
    }
}

val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zips the signed artifacts of every jfxmap module into the bundle the Central Portal takes."
    libraryProjects.forEach { dependsOn("${it.path}:publishAllPublicationsToStagingRepository") }
    from(stagingDirectory)
    // The Central Portal builds maven-metadata.xml itself
    exclude("**/maven-metadata.xml*")
    archiveFileName = "central-bundle.zip"
    destinationDirectory = layout.buildDirectory
    val release = version.toString()
    val signed = !(findProperty("signingInMemoryKey") as String?).isNullOrBlank()
    doFirst {
        if (release.endsWith("-SNAPSHOT")) {
            throw GradleException("Maven Central takes no SNAPSHOT: give the release version, e.g. -Pversion=0.1.0")
        }
        if (!signed) {
            throw GradleException("Maven Central takes signed artifacts only: set ORG_GRADLE_PROJECT_signingInMemoryKey")
        }
    }
}

// Opens the bundle and fails when a module misses a file Maven Central asks for: the POM, the jar with its sources
// and javadoc (not for the BOM), a .asc signature, an .md5 and a .sha1 for each, all under this group and version.
tasks.register("verifyCentralBundle") {
    group = "verification"
    description = "Checks that build/central-bundle.zip holds every jfxmap module, signed and with checksums."
    dependsOn(centralBundle)
    val bundle = centralBundle.flatMap { it.archiveFile }
    val modules = libraryProjects.associate { it.name to it.plugins.hasPlugin("java-platform") }
    val groupPath = subprojects.first().group.toString().replace('.', '/')
    val release = version.toString()
    inputs.file(bundle)
    doLast {
        val entries = java.util.zip.ZipFile(bundle.get().asFile).use { zip -> zip.entries().toList().map { it.name } }
            .filterNot { it.endsWith("/") }.toSet()
        val failures = mutableListOf<String>()
        val expected = modules.flatMap { (module, platform) ->
            val base = "$groupPath/$module/$release/$module-$release"
            val files = if (platform) listOf("$base.pom") else listOf("$base.pom", "$base.jar", "$base-sources.jar", "$base-javadoc.jar")
            files.flatMap { listOf(it, "$it.asc", "$it.md5", "$it.sha1") }
        }
        expected.filterNot { it in entries }.forEach { failures += "missing $it" }
        entries.filterNot { entry -> modules.keys.any { entry.startsWith("$groupPath/$it/$release/") } }
            .forEach { failures += "not part of this release: $it" }
        if (failures.isNotEmpty()) {
            throw GradleException("central-bundle.zip is not ready for Maven Central:\n" + failures.joinToString("\n"))
        }
        logger.lifecycle("central-bundle.zip: ${modules.size} modules, ${entries.size} files, version $release")
    }
}

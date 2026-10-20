// Maven Central publishing of every library module (each `jfxmap-*` project, the BOM included); samples, interop
// and benchmarks are left out. Applied from the root build, so a new `jfxmap-*` module is published with no extra line.
//
//   ./gradlew publishToMavenLocal                  into ~/.m2
//   ./gradlew publishAllPublicationsToStagingRepository
//                                                  into build/staging-deploy, the bundle uploaded to the Central Portal
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

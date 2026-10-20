import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    `java-platform`
    base
}

description = "The versions of every jfxmap module, to import as a platform (Gradle) or a BOM (Maven)"

// Every library module: the `jfxmap-*` projects but this one (samples, interop and benchmarks are not jfxmap-*)
val libraries = rootProject.subprojects.filter { it.name.startsWith("jfxmap-") && it != project }.sortedBy { it.name }

dependencies {
    constraints {
        libraries.forEach { api(it) }
    }
}

// Reads the POMs that would be published and fails when one misses what Maven Central asks for, when the BOM misses
// a module, or when a dependency has the wrong scope. Part of `check`, so `./gradlew build` runs it.
val verifyPoms by tasks.registering {
    group = "verification"
    description = "Checks the generated POMs of the BOM and of every library module."
    val pomTask = "generatePomFileForMavenPublication"
    dependsOn(tasks.named(pomTask))
    libraries.forEach { dependsOn("${it.path}:$pomTask") }

    val bomPom = layout.buildDirectory.file("publications/maven/pom-default.xml")
    val libraryPoms = libraries.associate { it.name to it.layout.buildDirectory.file("publications/maven/pom-default.xml") }
    val groupId = project.group.toString()
    val version = project.version.toString()
    inputs.file(bomPom)
    libraryPoms.values.forEach { inputs.file(it) }
    val marker = layout.buildDirectory.file("verifyPoms/ok")
    outputs.file(marker)

    doLast {
        fun read(file: File): Element =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

        fun Element.children(name: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.tagName == name }

        fun Element.text(path: String): String? =
            path.split('/').fold(listOf(this)) { nodes, name -> nodes.flatMap { it.children(name) } }
                .firstOrNull()?.textContent?.trim()

        // module name -> scope of each dependency, per POM
        fun Element.dependencies(): Map<String, String?> =
            children("dependencies").flatMap { it.children("dependency") }
                .associate { "${it.text("groupId")}:${it.text("artifactId")}" to it.text("scope") }

        val failures = mutableListOf<String>()
        fun check(condition: Boolean, message: () -> String) {
            if (!condition) failures += message()
        }

        val poms = libraryPoms.mapValues { read(it.value.get().asFile) } + (project.name to read(bomPom.get().asFile))
        poms.forEach { (name, pom) ->
            check(pom.text("groupId") == groupId && pom.text("artifactId") == name && pom.text("version") == version) {
                "$name: coordinates are ${pom.text("groupId")}:${pom.text("artifactId")}:${pom.text("version")}"
            }
            listOf(
                "name", "description", "url", "licenses/license/name", "licenses/license/url",
                "developers/developer/id", "scm/url", "scm/connection", "scm/developerConnection",
            ).forEach { path -> check(!pom.text(path).isNullOrBlank()) { "$name: <$path> is missing" } }
            pom.children("dependencies").flatMap { it.children("dependency") }.forEach { dependency ->
                check(!dependency.text("version").isNullOrBlank()) {
                    "$name: ${dependency.text("artifactId")} has no version"
                }
            }
        }

        val managed = poms.getValue(project.name).children("dependencyManagement").flatMap { it.children("dependencies") }
            .flatMap { it.children("dependency") }
            .associate { it.text("artifactId") to "${it.text("groupId")}:${it.text("version")}" }
        check(managed == libraryPoms.keys.associateWith { "$groupId:$version" }) {
            "jfxmap-bom manages $managed, not every library module at $groupId:$version (${libraryPoms.keys})"
        }

        // jfxmap-core uses jfxmap-expression internally: runtime, not compile
        poms["jfxmap-core"]?.dependencies()?.let { core ->
            check(core["$groupId:jfxmap-expression"] == "runtime") { "jfxmap-core: jfxmap-expression is not runtime: $core" }
        }
        // The starter's transports are compileOnly: the application adds the ones it uses
        poms["jfxmap-spring-boot-starter"]?.dependencies()?.let { starter ->
            check(starter["$groupId:jfxmap-core"] == "compile") { "jfxmap-spring-boot-starter: jfxmap-core is not compile" }
            val leaked = starter.keys.filter { it.startsWith("$groupId:") && it != "$groupId:jfxmap-core" }
            check(leaked.isEmpty()) { "jfxmap-spring-boot-starter: compileOnly modules leak into the POM: $leaked" }
        }

        if (failures.isNotEmpty()) throw GradleException("The POMs are not ready to publish:\n" + failures.joinToString("\n"))
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("${poms.keys}\n")
    }
}

tasks.check {
    dependsOn(verifyPoms)
}

package build.raft.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

/**
 * Configurations a Kotlin Multiplatform module (Android + iOS) resolves. Android compile/runtime
 * classpaths cover common + android dependencies; the per-target klib and shared-metadata
 * configurations cover the iOS/native graph, which can differ (native-only variants).
 */
val kmpAndroidIosConfigurations: List<String> =
    listOf(
        // The single Android variant of com.android.kotlin.multiplatform.library (AGP 9).
        "androidCompileClasspath",
        "androidRuntimeClasspath",
        "iosArm64CompileKlibraries",
        "iosSimulatorArm64CompileKlibraries",
        "metadataCommonMainCompileClasspath",
        "metadataIosMainCompileClasspath",
    )

/**
 * Registers [taskName], which fails when this module's `src` tree or the resolved dependency graph
 * of [checkedConfigurations] references Tencent Kuikly (`com.tencent.kuikly.*` sources,
 * `com.tencent.kuikly-open*` modules). KuiklyBase libraries that do not depend on the Kuikly UI
 * runtime (NetworkKMM, datetime, resource-core) are allowed.
 *
 * The task runs before every compilation of the module and from `check`. Resolved module
 * coordinates (not artifacts) are the dependency input: a version bump or a new transitive module
 * reruns the check; an unchanged graph and unchanged sources leave it up to date.
 */
fun Project.registerNoKuiklyGuard(
    taskName: String,
    checkedConfigurations: List<String> = kmpAndroidIosConfigurations,
): TaskProvider<Task> {
    val moduleLabel = path
    val guard =
        tasks.register(taskName) {
            description = "Fails if $moduleLabel sources or (transitive) dependencies reference Tencent Kuikly."
            group = "verification"
            val sourceDir = layout.projectDirectory.dir("src")
            val projectDirectory = projectDir
            val resolvedModules =
                provider {
                    checkedConfigurations.flatMap { name ->
                        configurations.getByName(name).incoming.resolutionResult.allComponents
                            .mapNotNull { component -> component.moduleVersion }
                            .map { module -> "$name: ${module.group}:${module.name}:${module.version}" }
                    }.sorted()
                }
            inputs.dir(sourceDir).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.property("resolvedModules", resolvedModules)
            val marker = layout.buildDirectory.file("$taskName/passed.txt")
            outputs.file(marker).withPropertyName("marker")
            doLast {
                val pattern = Regex("""\bcom\.tencent\.kuikly\.""")
                val sourceViolations =
                    sourceDir.asFile.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .flatMap { file ->
                            file.readLines().mapIndexedNotNull { index, line ->
                                if (pattern.containsMatchIn(line)) {
                                    "${file.relativeTo(projectDirectory)}:${index + 1}: ${line.trim()}"
                                } else {
                                    null
                                }
                            }
                        }
                        .toList()
                val modules = resolvedModules.get()
                val dependencyViolations =
                    modules.filter { entry ->
                        val group = entry.substringAfter(": ").substringBefore(':')
                        group == "com.tencent.kuikly-open" || group.startsWith("com.tencent.kuikly-open.")
                    }
                check(sourceViolations.isEmpty() && dependencyViolations.isEmpty()) {
                    "$moduleLabel must not depend on Kuikly.\n" +
                        (sourceViolations + dependencyViolations).joinToString("\n")
                }
                marker.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(
                        "checked ${modules.size} resolved modules across ${checkedConfigurations.size} configurations\n"
                    )
                }
            }
        }
    tasks.named("check").configure { dependsOn(guard) }
    tasks.matching { it.name == "preBuild" || it.name.startsWith("compileKotlin") }.configureEach {
        dependsOn(guard)
    }
    return guard
}

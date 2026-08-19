// Task #141 pre-PUT consumer-resolution gate.
//
// Applied with -I to Mobile's REAL OHOS build (compose/shared/build.ohos.gradle.kts
// via settings.ohos.gradle.kts). Injects the coordinate under test for this
// process only: no Mobile file is edited, committed or pushed.
//
// Repository boundary ruled in #Kuiklybase:8d8d3ee7. The three module sets are
// disjoint so the staging and Raft filters cannot shadow each other:
//
//   staging (45 frozen files) exclusive : kotlinx-coroutines-test{,-ohosarm64}
//   Raft exclusive                      : kotlinx-coroutines-core{,-ohosarm64},
//                                         atomicfu{,-ohosarm64},
//                                         kotlin-stdlib, kotlin-stdlib-common
//   Tencent                             : ONLY out-of-family coordinates that
//                                         Raft genuinely lacks. This is
//                                         out-of-family compatibility for the
//                                         existing Mobile graph -- it is NOT a
//                                         whole-graph Raft-only claim.
//
// kotlin-stdlib-common is in the Raft-exclusive set deliberately: it is Tencent
// 200 / Raft 404 at 2.0.21-KBA-001, so leaving it uncovered would let a KBA-001
// request resolve from Tencent and the whole gate would still go green.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val stagingDir: String = System.getenv("TASK141_STAGING_DIR")
    ?: throw GradleException("TASK141_STAGING_DIR is required")
val raftUrl = "https://maven.artifacts.botiverse.dev"
val testCoordinate = "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0-KBA-002"

// Optional forced red-arm: request a coordinate that must NOT be resolvable.
// Used to prove the gate can actually catch KBA-001, since the ordinary run is
// expected never to request it -- "nobody asked" and "the gate cannot catch it"
// are indistinguishable in a green run.
val forcedCoordinate: String? = System.getenv("TASK141_FORCE_COORDINATE")

val stagedModules = listOf("kotlinx-coroutines-test", "kotlinx-coroutines-test-ohosarm64")
val raftModules = mapOf(
    "org.jetbrains.kotlinx" to listOf(
        "kotlinx-coroutines-core",
        "kotlinx-coroutines-core-ohosarm64",
        "atomicfu",
        "atomicfu-ohosarm64",
    ),
    "org.jetbrains.kotlin" to listOf(
        "kotlin-stdlib",
        "kotlin-stdlib-common",
    ),
)

settingsEvaluated {
    // Runs AFTER Mobile's settings script, so this replaces the repository list
    // Mobile declared rather than being re-added over by it.
    dependencyResolutionManagement.repositories.apply {
        val existing = map { it.name }
        println("TASK141_REPOS_BEFORE ${existing.joinToString(",")}")

        // Staging is exclusive for the coordinates under test: if the frozen
        // bytes are absent, nothing else may satisfy them.
        exclusiveContent {
            forRepository {
                maven {
                    name = "task141Staging"
                    url = uri("file://$stagingDir")
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                    }
                }
            }
            filter {
                stagedModules.forEach { includeModule("org.jetbrains.kotlinx", it) }
            }
        }

        // Raft is exclusive for the rest of the family under test, so a
        // kotlin-stdlib{,-common}:2.0.21-KBA-001 request has nowhere to go.
        exclusiveContent {
            forRepository {
                maven {
                    name = "task141Raft"
                    url = uri(raftUrl)
                }
            }
            filter {
                raftModules.forEach { (group, modules) ->
                    modules.forEach { includeModule(group, it) }
                }
            }
        }
    }
}

gradle.afterProject {
    if (project.path != ":shared") return@afterProject

    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        // Mobile declares its test dependencies on commonTest, and the OHOS test
        // compilation consumes commonTest through dependsOn. Injecting at the
        // same place Mobile itself would edit keeps the graph faithful.
        project.dependencies.add("commonTestImplementation", testCoordinate)
        println("TASK141_INJECTED $testCoordinate -> commonTestImplementation")
        forcedCoordinate?.let {
            project.dependencies.add("commonTestImplementation", it)
            println("TASK141_INJECTED_FORCED $it -> commonTestImplementation")
        }
    }

    // Primary evidence: what the compile task itself consumes.
    //
    // "the coordinate appears in some resolvable configuration's graph" and
    // "compileTestKotlinOhosArm64 actually takes it as input" are different
    // claims. Injecting into a configuration the compile task does not consume
    // would satisfy the first and still be a false green, so bind the proof to
    // the task's real `libraries` input.
    project.tasks.matching { it.name == "compileTestKotlinOhosArm64" }.configureEach {
        doFirst {
            val librariesProperty = this::class.members.firstOrNull { it.name == "libraries" }
            val files = runCatching {
                (librariesProperty?.call(this) as? FileCollection)?.files.orEmpty()
            }.getOrElse { emptySet() }

            println("TASK141_COMPILE_TASK_INPUTS task=$name count=${files.size}")
            files.sortedBy { it.name }.forEach {
                println("TASK141_COMPILE_INPUT ${it.name} ${it.absolutePath}")
            }
            val consumed = files.filter { it.name.contains("kotlinx-coroutines-test") }
            if (consumed.isEmpty()) {
                println(
                    "TASK141_COMPILE_INPUT_MISSING kotlinx-coroutines-test is NOT an input of " +
                        "$name — the injection did not reach the real compilation"
                )
            } else {
                consumed.forEach { println("TASK141_COMPILE_INPUT_CONFIRMED ${it.absolutePath}") }
            }
        }
    }

    project.tasks.register("task141ReportOhosTestGraph") {
        doLast {
            // Report the configuration compileTestKotlinOhosArm64 actually
            // resolves. A green compile alone is not evidence: injecting into a
            // configuration the compile task does not resolve would produce a
            // convincing false green.
            val candidates = project.configurations.filter {
                it.isCanBeResolved && it.name.startsWith("ohosArm64Test")
            }
            println("TASK141_CANDIDATE_CONFIGURATIONS ${candidates.joinToString(",") { it.name }}")

            candidates.forEach { configuration ->
                val components = runCatching {
                    configuration.incoming.resolutionResult.allComponents
                        .map { it.id }
                        .filterIsInstance<ModuleComponentIdentifier>()
                        .map { "${it.group}:${it.module}:${it.version}" }
                        .sorted()
                }.getOrElse { error ->
                    println("TASK141_UNRESOLVABLE ${configuration.name} ${error.message}")
                    return@forEach
                }
                println("TASK141_GRAPH_BEGIN ${configuration.name} size=${components.size}")
                components.forEach { println("TASK141_NODE ${configuration.name} $it") }
                println("TASK141_GRAPH_END ${configuration.name}")

                components.filter { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib") }
                    .forEach { println("TASK141_SELECTED_STDLIB ${configuration.name} $it") }
                components.filter { it.contains("kotlinx-coroutines-test") }
                    .forEach { println("TASK141_TEST_COORDINATE ${configuration.name} $it") }
                components.filter { it.contains("kotlinx-coroutines-core") }
                    .forEach { println("TASK141_CORE_COORDINATE ${configuration.name} $it") }
            }
        }
    }
}

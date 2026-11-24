plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.builtin.kotlin) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.roborazzi) apply false
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// CMP-10674 (iOS crash on a re-entrant FlushCoroutineDispatcher.flush() while typing): every
// configuration of every project that resolves the Compose UI iOS platform klibs at the patch base
// version gets the patched build instead (third_party/compose-ui-1.12.2/README.md). Kotlin
// Multiplatform reaches these modules through the root `org.jetbrains.compose.ui:ui` module's
// `available-at` redirect; that redirect is an ordinary dependency edge in Gradle's graph, so this
// rule rewrites it like a direct dependency. The root `ui` module and everything else stay on the
// official release. The versions and klib SHA-256s live in gradle.properties
// (raft.composeUiPatch.*); scripts/ci/check_compose_ui_patch_pin.py fails CI once composePlugin
// moves off the base. `:verifyComposeUiPatch` (below) proves the rule took effect.
val raftComposeUiPatchReadme = "third_party/compose-ui-1.12.2/README.md"
val raftComposeUiPatchBase = providers.gradleProperty("raft.composeUiPatch.base").orNull
val raftComposeUiPatchVersion = providers.gradleProperty("raft.composeUiPatch.version").orNull
val raftComposeUiPatchOn = raftComposeUiPatchBase != null && raftComposeUiPatchVersion != null
// Kotlin/Native target name -> the Compose UI platform module (patched klib) it links.
val raftComposeUiPatchModules = mapOf(
    "ios_arm64" to "ui-iosarm64",
    "ios_simulator_arm64" to "ui-iossimulatorarm64",
)
if (raftComposeUiPatchOn) {
    val patchedModules = raftComposeUiPatchModules.values.toSet()
    allprojects {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.compose.ui" &&
                    requested.name in patchedModules &&
                    requested.version == raftComposeUiPatchBase
                ) {
                    useVersion(raftComposeUiPatchVersion!!)
                    because("CMP-10674 fix, see $raftComposeUiPatchReadme")
                }
            }
        }
    }
}

// Fails the build unless every iOS klib configuration and iOS link task resolves the patched
// Compose UI klibs, byte for byte. Per Kotlin Multiplatform project with iOS targets, a
// `verifyComposeUiPatch` task resolves, for each iOS target:
// - every compilation's klib configuration (`<target>CompileKlibraries` for main,
//   `<target>TestCompileKlibraries` for test): org.jetbrains.compose.ui:ui-ios* may resolve only
//   at raft.composeUiPatch.version, the target's module must be there whenever the root `ui` is
//   (always in :shared), and its klib must match raft.composeUiPatch.sha256.<module>;
// - the libraries and export libraries of every binary's link task (the cocoapods `pod`
//   framework: linkPod{Debug,Release}FrameworkIos*): the klib files the linker gets must be the
//   patched ones (file name and SHA-256), and in :shared the target's klib must be among them.
// Every iOS link task depends on it, so no framework links without the check. With the pin
// properties absent it only logs "pin off". CI also runs `:verifyComposeUiPatch` explicitly in
// test.yml (iOS compile), ios-release.yml and ios-simulator-app.yml.
tasks.register("verifyComposeUiPatch") {
    group = "verification"
    description = "Checks that iOS builds resolve the CMP-10674 patched Compose UI klibs ($raftComposeUiPatchReadme)."
    val pinOn = raftComposeUiPatchOn
    doLast {
        if (!pinOn) logger.lifecycle("verifyComposeUiPatch: pin off (no raft.composeUiPatch.* in gradle.properties)")
    }
}
if (raftComposeUiPatchOn) {
    val patchedVersion = raftComposeUiPatchVersion!!
    val readme = raftComposeUiPatchReadme
    val targetModules = raftComposeUiPatchModules
    val expectedSha256 = targetModules.values.associateWith { module ->
        val key = "raft.composeUiPatch.sha256.$module"
        val value = providers.gradleProperty(key).orNull?.trim()?.lowercase()
        require(value != null && Regex("[0-9a-f]{64}").matches(value)) {
            "gradle.properties: $key must be the 64-hex SHA-256 of $module-$patchedVersion.klib ($readme)"
        }
        value
    }
    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    allprojects {
        val projectPath = path
        // :shared links the app framework, so every iOS configuration / link task there must
        // carry the patched klib; elsewhere only projects that resolve the root `ui` must.
        val anchor = projectPath == ":shared"
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val task = tasks.register("verifyComposeUiPatch") {
                group = "verification"
                description = "Checks this project's iOS klibs are the CMP-10674 patched Compose UI ($readme)."
                // (label, module this target must link, configuration)
                val configs = mutableListOf<Triple<String, String, Configuration>>()
                // (label, module this target must link, klib files the link task passes on)
                val links = mutableListOf<Triple<String, String, FileCollection>>()
                val kotlin = project.extensions.getByType(
                    org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java
                )
                kotlin.targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java)
                    .forEach { target ->
                        val module = targetModules[target.konanTarget.name] ?: return@forEach
                        target.compilations.forEach { compilation ->
                            val name = compilation.compileDependencyConfigurationName
                            configs += Triple("$projectPath:$name", module, project.configurations.getByName(name))
                        }
                        target.binaries.forEach { binary ->
                            val link = binary.linkTaskProvider.get()
                            val linked = project.files(link.libraries, link.exportLibraries)
                            // Run after whatever produces those files (e.g. downloadKotlinNativeDistribution,
                            // other projects' klibs); reading them earlier is an error in Gradle 9.
                            dependsOn(linked)
                            links += Triple("$projectPath:${link.name}", module, linked)
                        }
                    }
                doLast {
                    val errors = mutableListOf<String>()
                    var checked = 0
                    fun checkKlib(label: String, module: String, file: File) {
                        val actual = sha256(file)
                        if (actual != expectedSha256.getValue(module)) {
                            errors += "$label: $module klib ${file.name} has SHA-256 $actual, expected " +
                                "${expectedSha256.getValue(module)} (raft.composeUiPatch.sha256.$module). The artifact " +
                                "repository served different bytes than the published patched build."
                        }
                    }
                    configs.forEach { (label, module, configuration) ->
                        checked++
                        val components = configuration.incoming.resolutionResult.allComponents
                            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                            .filter { it.group == "org.jetbrains.compose.ui" }
                        val hasRootUi = components.any { it.module == "ui" }
                        val platform = components.filter { it.module in targetModules.values }
                        platform.filter { it.version != patchedVersion }.forEach {
                            errors += "$label resolved ${it.displayName}, not $patchedVersion: the CMP-10674 pin did not apply."
                        }
                        if ((anchor || hasRootUi) && platform.none { it.module == module }) {
                            errors += "$label has no org.jetbrains.compose.ui:$module at all" +
                                (if (hasRootUi) " although it resolves the root ui module" else "") + "."
                        }
                        // Only the two platform modules' klibs: nothing else is downloaded, and
                        // project artifacts (task outputs not built yet) are never touched.
                        configuration.incoming.artifactView {
                            componentFilter {
                                it is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                                    it.group == "org.jetbrains.compose.ui" && it.module in targetModules.values
                            }
                        }.artifacts.artifacts.forEach { artifact ->
                            val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                            if (id.version == patchedVersion) checkKlib(label, id.module, artifact.file)
                        }
                    }
                    // A link task only sees files. Gradle's module cache keeps a klib at
                    // .../<group>/<module>/<version>/<hash>/<file>; the file is named after the
                    // .module's file name (ui-iosArm64Main-<version>.klib), not the URL.
                    val looksLikeUiKlib = Regex("""ui-ios(arm64|simulatorarm64)(main)?-.*""")
                    links.forEach { (label, module, files) ->
                        checked++
                        var found = false
                        files.files.forEach { file ->
                            val versionDir: File? = file.parentFile?.parentFile
                            val moduleDir: File? = versionDir?.parentFile
                            val cachedModule: String? = moduleDir?.name
                                ?.takeIf { moduleDir?.parentFile?.name == "org.jetbrains.compose.ui" }
                                ?.takeIf { it in targetModules.values }
                            if (cachedModule != null) {
                                val version = versionDir?.name
                                if (version != patchedVersion) {
                                    errors += "$label links org.jetbrains.compose.ui:$cachedModule:$version " +
                                        "(${file.name}), not $patchedVersion: the CMP-10674 pin did not apply."
                                } else if (!file.isFile) {
                                    errors += "$label links ${file.path}, which is not a klib file."
                                } else {
                                    checkKlib(label, cachedModule, file)
                                    if (cachedModule == module) found = true
                                }
                            } else if (looksLikeUiKlib.matches(file.name.lowercase())) {
                                errors += "$label links ${file.path}, a Compose UI iOS klib outside Gradle's " +
                                    "module cache layout; cannot tell its version."
                            }
                        }
                        if (anchor && !found) {
                            errors += "$label does not link org.jetbrains.compose.ui:$module:$patchedVersion."
                        }
                    }
                    if (errors.isNotEmpty()) {
                        throw GradleException(
                            "CMP-10674 Compose UI patch check failed ($readme):\n" +
                                errors.joinToString("\n") { "  - $it" }
                        )
                    }
                    logger.lifecycle("verifyComposeUiPatch $projectPath: $checked iOS configurations/link tasks use $patchedVersion")
                }
            }
            rootProject.tasks.named("verifyComposeUiPatch") { dependsOn(task) }
            // Every iOS link (Xcode's nested syncFramework included) runs the check first.
            project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
                .targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java)
                .configureEach {
                    if (konanTarget.name in targetModules) {
                        binaries.configureEach { linkTaskProvider.configure { dependsOn(task) } }
                    }
                }
        }
    }
}

// Detached-node crash on Android ("Cannot read CompositionLocal because the Modifier node is not
// currently attached"): every configuration of every project that resolves
// androidx.compose.foundation:foundation-android at the patch base version gets the patched AAR
// instead (third_party/compose-foundation-1.12.1/README.md). Compose Multiplatform reaches it through
// the root androidx.compose.foundation:foundation module's `available-at` redirect, and the androidx
// Compose BOM constrains it; like a direct dependency, both are edges this rule rewrites. iOS and
// desktop use the JetBrains foundation and never request this module. The versions and the AAR's
// SHA-256 live in gradle.properties (raft.composeFoundationPatch.*).
// `:verifyComposeFoundationPatch` (below) proves the rule took effect and fails the build once
// foundation-android resolves to anything else, as it does after a Compose Multiplatform upgrade.
val raftComposeFoundationPatchReadme = "third_party/compose-foundation-1.12.1/README.md"
val raftComposeFoundationPatchBase = providers.gradleProperty("raft.composeFoundationPatch.base").orNull
val raftComposeFoundationPatchVersion = providers.gradleProperty("raft.composeFoundationPatch.version").orNull
val raftComposeFoundationPatchOn = raftComposeFoundationPatchBase != null && raftComposeFoundationPatchVersion != null
val raftComposeFoundationPatchGroup = "androidx.compose.foundation"
val raftComposeFoundationPatchModule = "foundation-android"
if (raftComposeFoundationPatchOn) {
    allprojects {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == raftComposeFoundationPatchGroup &&
                    requested.name == raftComposeFoundationPatchModule &&
                    requested.version == raftComposeFoundationPatchBase
                ) {
                    useVersion(raftComposeFoundationPatchVersion!!)
                    because("detached-node crash fix, see $raftComposeFoundationPatchReadme")
                }
            }
        }
    }
}

// Fails the build unless the Android runtime classpaths resolve the patched foundation AAR, byte for
// byte. In :androidApp (each build type's runtime classpath: it holds every module's dependencies,
// so it is what the APK and bundle contain; the connected tests' and the debug unit tests') and
// :shared (debug, release, and its debug unit and connected tests', the Robolectric suite among them),
// a `verifyComposeFoundationPatch` task resolves those classpaths and fails when:
// - androidx.compose.foundation:foundation-android resolves at any version but
//   raft.composeFoundationPatch.version: the patch is built from raft.composeFoundationPatch.base
//   only, so a Compose upgrade has left it behind (re-make it for the new version, or remove the pin);
// - debugRuntimeClasspath, releaseRuntimeClasspath or debugUnitTestRuntimeClasspath is missing or
//   has no foundation-android at all;
// - the AAR is not raft.composeFoundationPatch.sha256.foundation-android.
// Every Android build of those projects runs it first (`preBuild` depends on it), so no APK, bundle
// or unit test runs without the check. With the pin properties absent it only logs "pin off". CI
// also runs `:verifyComposeFoundationPatch` explicitly in test.yml (Android gate),
// android-release.yml and android-alpha.yml.
tasks.register("verifyComposeFoundationPatch") {
    group = "verification"
    description = "Checks that Android builds resolve the patched Compose foundation AAR ($raftComposeFoundationPatchReadme)."
    val pinOn = raftComposeFoundationPatchOn
    doLast {
        if (!pinOn) logger.lifecycle("verifyComposeFoundationPatch: pin off (no raft.composeFoundationPatch.* in gradle.properties)")
    }
}
if (raftComposeFoundationPatchOn) {
    val patchedVersion = raftComposeFoundationPatchVersion!!
    val baseVersion = raftComposeFoundationPatchBase!!
    val readme = raftComposeFoundationPatchReadme
    val foundationGroup = raftComposeFoundationPatchGroup
    val foundationModule = raftComposeFoundationPatchModule
    val shaKey = "raft.composeFoundationPatch.sha256.$foundationModule"
    val expectedSha256: String =
        providers.gradleProperty(shaKey).orNull?.trim()?.lowercase()
            ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            ?: throw GradleException(
                "gradle.properties: $shaKey must be the 64-hex SHA-256 of $foundationModule-$patchedVersion.aar ($readme)"
            )
    // The Android runtime classpaths each project checks: every build type's (what the APKs and
    // bundles contain), the connected tests', and the debug unit tests' (CI's Robolectric suite).
    val androidClasspaths = mapOf(
        ":androidApp" to Regex("(?!.*UnitTest).+RuntimeClasspath|debugUnitTestRuntimeClasspath"),
        ":shared" to Regex("(debug|release)RuntimeClasspath|debug(UnitTest|AndroidTest)RuntimeClasspath"),
    )
    // Present in both projects; they must hold foundation-android (the app builds, the unit tests).
    val requiredClasspaths = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath")
    fun aarSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    allprojects {
        val projectPath = path
        val classpathPattern = androidClasspaths[projectPath] ?: return@allprojects
        val task = tasks.register("verifyComposeFoundationPatch") {
            group = "verification"
            description = "Checks this project's Android classpaths use the patched Compose foundation AAR ($readme)."
            // Listed when the task runs: the Android plugin adds the variants' configurations after
            // the build scripts are evaluated, and a script may realize this task before that.
            val projectConfigurations = project.configurations
            doLast {
                val errors = mutableListOf<String>()
                val classpaths = projectConfigurations.filter { it.isCanBeResolved && classpathPattern.matches(it.name) }
                requiredClasspaths.filter { name -> classpaths.none { it.name == name } }.forEach { name ->
                    errors += "$projectPath:$name not found among ${classpaths.map { it.name }}: the Android " +
                        "plugin's configuration names changed; update this check."
                }
                classpaths.forEach { configuration ->
                    val label = "$projectPath:${configuration.name}"
                    val resolved = configuration.incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                        .filter { it.group == foundationGroup && it.module == foundationModule }
                    resolved.filter { it.version != patchedVersion }.forEach {
                        val why =
                            if (it.version == baseVersion) {
                                "The pin did not apply: a request or constraint for $baseVersion was not rewritten."
                            } else {
                                "The patch is built from $foundationModule $baseVersion only, so a Compose upgrade " +
                                    "left it behind: re-make it for ${it.version} (a new third_party entry and -raft.N " +
                                    "build) or remove the pin (raft.composeFoundationPatch.* and its wiring, 'When to " +
                                    "remove' in $readme)."
                            }
                        errors += "$label resolved ${it.displayName}, not $patchedVersion. $why"
                    }
                    if (configuration.name in requiredClasspaths && resolved.isEmpty()) {
                        errors += "$label has no $foundationGroup:$foundationModule at all."
                    }
                    // Only foundation-android's own files: nothing else is downloaded, and project
                    // artifacts (task outputs not built yet) are never touched.
                    var aars = 0
                    configuration.incoming.artifactView {
                        componentFilter {
                            it is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                                it.group == foundationGroup && it.module == foundationModule
                        }
                    }.artifacts.artifacts.forEach { artifact ->
                        val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                        if (id.version == patchedVersion && artifact.file.name.endsWith(".aar")) {
                            aars++
                            val actual = aarSha256(artifact.file)
                            if (actual != expectedSha256) {
                                errors += "$label: ${artifact.file.name} ($foundationModule $patchedVersion) has SHA-256 " +
                                    "$actual, expected $expectedSha256 ($shaKey). The artifact repository served " +
                                    "different bytes than the published patched build."
                            }
                        }
                    }
                    if (resolved.any { it.version == patchedVersion } && aars == 0) {
                        errors += "$label resolved $foundationModule $patchedVersion but no .aar file for it."
                    }
                }
                if (errors.isNotEmpty()) {
                    throw GradleException(
                        "Compose foundation patch check failed ($readme):\n" + errors.joinToString("\n") { "  - $it" }
                    )
                }
                logger.lifecycle(
                    "verifyComposeFoundationPatch $projectPath: ${classpaths.size} Android runtime classpaths use " +
                        "$foundationModule $patchedVersion (AAR SHA-256 $expectedSha256)"
                )
            }
        }
        rootProject.tasks.named("verifyComposeFoundationPatch") { dependsOn(task) }
        // Every Android build of this project (assemble, bundle, unit tests) runs the check first.
        tasks.configureEach { if (name == "preBuild") dependsOn(task) }
    }
}

// Task #32: per-test hang watchdog for JVM unit tests. A hung test must fail
// loud and named (HANG DETECTED + thread dump + fork exit 73) instead of
// burning the job timeout in silence. Config via Gradle JVM -D properties:
// raft.test.watchdog.{timeout.ms,heartbeat.ms,enabled}.
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        addTestListener(build.raft.ci.HangWatchdogTestListener())
        // Robolectric downloads its android-all jars when a test first needs them, from
        // repo1.maven.org by default. Maven Central answers busy CI runners with HTTP 429
        // (2026-11-22: about 480 Robolectric classes failed per Android CI run with "Failed to
        // fetch maven artifact org.robolectric:android-all-instrumented"), so fetch them from
        // Google's mirror of Maven Central, which serves the same paths.
        systemProperty("robolectric.dependency.repo.id", "google-maven-central")
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    }
}

// The :shared and :androidApp host unit tests (Robolectric) are the longest step of the Android
// CI job. Spread their test classes over up to 3 JVMs: 3 on the 4-vCPU GitHub runner, fewer on
// smaller machines. Override with -Praft.unitTest.maxParallelForks=N (1 = old serial run).
val raftUnitTestForks =
    providers.gradleProperty("raft.unitTest.maxParallelForks").map(String::toInt)
        .getOrElse((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3))
listOf(":shared", ":androidApp").forEach { path ->
    project(path) {
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
            .matching { it.name.endsWith("UnitTest") }
            .configureEach { maxParallelForks = raftUnitTestForks }
    }
}

// Ordinary PR tier (artin 2026-11-22: "PRs run only a little, like slock"). On ordinary pull requests
// test.yml passes -Praft.unitTest.skipRobolectric=true (planner android_unit_scope=jvm): the unit-test
// tasks of :shared and :androidApp then skip every test class JUnit runs on Robolectric, every :shared
// screenshot test among them, and run the plain JVM classes. The classes come from the test sources,
// so a new Robolectric test needs nothing extra: a top-level class whose @RunWith names
// RobolectricTestRunner, ParameterizedRobolectricTestRunner or AndroidJUnit4 (Robolectric off a
// device), or that extends a class or runner of these sources that does (@RunWith is inherited).
// Comments and string literals do not count. The full tier (main and release-* pushes, nightly,
// full-ci, scope=full, the merge queue, record_screenshots) and local runs never set the property;
// without it nothing below runs. The other modules' host tests have no Robolectric on the classpath.
val raftSkipRobolectric =
    providers.gradleProperty("raft.unitTest.skipRobolectric")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

// [source] without comments (Kotlin block comments nest) and with string, char and backtick literals
// emptied; line breaks outside literals stay, so line-anchored patterns still see every line start.
fun raftCodeOnly(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (source.startsWith("//", i)) {
            while (i < source.length && source[i] != '\n') i++
        } else if (source.startsWith("/*", i)) {
            var depth = 0
            while (i < source.length) {
                if (source.startsWith("/*", i)) {
                    depth++
                    i += 2
                } else if (source.startsWith("*/", i)) {
                    depth--
                    i += 2
                    if (depth == 0) break
                } else {
                    if (source[i] == '\n') out.append('\n')
                    i++
                }
            }
        } else if (source.startsWith("\"\"\"", i)) {
            val end = source.indexOf("\"\"\"", i + 3)
            i = if (end < 0) source.length else end + 3
            out.append("\"\"")
        } else if (c == '"' || c == '\'' || c == '`') {
            var j = i + 1
            while (j < source.length && source[j] != c && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
            i = if (j < source.length && source[j] == c) j + 1 else j
            out.append(c).append(c)
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

// Fully qualified names of the classes under [roots] that JUnit runs on Robolectric (see above).
fun raftRobolectricTestClasses(roots: List<File>): Set<String> {
    class Declared(val fqcn: String, val name: String, val runWith: String?, val supertypes: Set<String>)
    // Annotations at the start of a line, and a declaration's modifiers.
    val annotations = """(?:@[\w.]+(?:\([^)\n]*\))?[ \t]+)*"""
    val modifiers =
        """(?:(?:public|internal|private|protected|open|abstract|final|sealed|data|enum|annotation|value|inner|expect|actual)[ \t]+)*"""
    val runWithPattern = Regex("""^$annotations@(?:org\.junit\.runner\.)?RunWith\s*\(([^)]*)\)""", RegexOption.MULTILINE)
    val declarationPattern = Regex("""^$annotations$modifiers(?:class|interface|object)[ \t]+(\w+)""", RegexOption.MULTILINE)
    val packagePattern = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    val parensPattern = Regex("""\((?:[^()]|\([^()]*\))*\)""")
    val wordPattern = Regex("""\w+""")
    val declared = mutableListOf<Declared>()
    roots.filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        .forEach { source ->
            val code = raftCodeOnly(source.readText())
            val pkg = packagePattern.find(code)?.groupValues?.get(1)
            val runWiths = runWithPattern.findAll(code).toList()
            val classes = declarationPattern.findAll(code).toList()
            // A top-level class's annotations lie between the previous top-level declaration and it.
            var regionStart = 0
            classes.forEachIndexed { index, match ->
                val name = match.groupValues[1]
                val headerStart = match.range.last + 1
                val next = classes.getOrNull(index + 1)?.range?.first ?: code.length
                val brace = code.indexOf('{', headerStart)
                val headerEnd = if (brace < 0 || brace > next) next else brace
                // Its supertypes are among the header's words once the parameter lists are gone.
                val header = code.substring(headerStart, headerEnd).replace(parensPattern, " ")
                declared += Declared(
                    fqcn = if (pkg == null) name else "$pkg.$name",
                    name = name,
                    runWith = runWiths.lastOrNull { it.range.first in regionStart..match.range.first }?.groupValues?.get(1),
                    supertypes = wordPattern.findAll(header).map { it.value }.toSet(),
                )
                regionStart = headerStart
            }
        }
    val robolectric = mutableSetOf("RobolectricTestRunner", "ParameterizedRobolectricTestRunner", "AndroidJUnit4")
    val skipped = sortedSetOf<String>()
    do {
        val before = skipped.size
        declared.filter { it.fqcn !in skipped }.forEach { declaration ->
            val runner = declaration.runWith?.let { arg -> wordPattern.findAll(arg).any { it.value in robolectric } } == true
            if (runner || declaration.supertypes.any { it in robolectric }) {
                skipped += declaration.fqcn
                robolectric += declaration.name
            }
        }
    } while (skipped.size != before)
    return skipped
}

if (raftSkipRobolectric) {
    mapOf(
        ":shared" to listOf("src/commonTest", "src/androidUnitTest"),
        ":androidApp" to listOf("src/test"),
    ).forEach { (path, roots) ->
        project(path) {
            val robolectricClasses by lazy {
                raftRobolectricTestClasses(roots.map { file(it) }).also { classes ->
                    logger.lifecycle("raft.unitTest.skipRobolectric: $path unit tests skip ${classes.size} Robolectric test classes")
                }
            }
            tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .matching { it.name.endsWith("UnitTest") }
                .configureEach {
                    filter {
                        robolectricClasses.forEach { excludeTestsMatching(it) }
                        // A module whose tests are all Robolectric is not misconfigured on this tier.
                        isFailOnNoMatchingTests = false
                    }
                    failOnNoDiscoveredTests.set(false)
                }
        }
    }
}

// PR CI Roborazzi / screenshot golden split (#proj-mobile:#340, artin 2026-11-24): screenshot
// golden verification runs post-merge (main, release-*, nightly, merge queue, full-ci, scope=full,
// record_screenshots), never on ordinary PRs. When test.yml passes -Praft.unitTest.skipScreenshots=true,
// :designsystem:desktopTest skips every vcase / screenshot golden class (subclasses of VCaseDesktopTest,
// @RunWith(VCaseRunner), or *ScreenshotTest) while keeping all commonTest unit tests and desktopTest
// Compose UI logic / semantics / interaction tests, and :shared skips every RaftRobolectricScreenshot /
// *ScreenshotTest class while keeping non-screenshot Robolectric + Compose UI logic tests (used on
// build/deps PRs where skipRobolectric is off). Unset on local runs and on the full tier.
val raftSkipScreenshots =
    providers.gradleProperty("raft.unitTest.skipScreenshots")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

// Fully qualified names of screenshot golden test classes under [roots]: subclasses of VCaseDesktopTest,
// classes with @RunWith(VCaseRunner), classes whose body calls RaftRobolectricScreenshot / captureRoboImage,
// or classes named *ScreenshotTest (plus subclasses of any of those).
fun raftScreenshotTestClasses(roots: List<File>): Set<String> {
    class Declared(
        val fqcn: String,
        val name: String,
        val runWith: String?,
        val supertypes: Set<String>,
        val body: String,
    )
    val annotations = """(?:@[\w.]+(?:\([^)\n]*\))?[ \t]+)*"""
    val modifiers =
        """(?:(?:public|internal|private|protected|open|abstract|final|sealed|data|enum|annotation|value|inner|expect|actual)[ \t]+)*"""
    val runWithPattern = Regex("""^$annotations@(?:org\.junit\.runner\.)?RunWith\s*\(([^)]*)\)""", RegexOption.MULTILINE)
    val declarationPattern = Regex("""^$annotations$modifiers(?:class|interface|object)[ \t]+(\w+)""", RegexOption.MULTILINE)
    val packagePattern = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    val parensPattern = Regex("""\((?:[^()]|\([^()]*\))*\)""")
    val wordPattern = Regex("""\w+""")
    val declared = mutableListOf<Declared>()
    roots.filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        .forEach { source ->
            val code = raftCodeOnly(source.readText())
            val pkg = packagePattern.find(code)?.groupValues?.get(1)
            val runWiths = runWithPattern.findAll(code).toList()
            val classes = declarationPattern.findAll(code).toList()
            var regionStart = 0
            classes.forEachIndexed { index, match ->
                val name = match.groupValues[1]
                val headerStart = match.range.last + 1
                val next = classes.getOrNull(index + 1)?.range?.first ?: code.length
                val brace = code.indexOf('{', headerStart)
                val headerEnd = if (brace < 0 || brace > next) next else brace
                val header = code.substring(headerStart, headerEnd).replace(parensPattern, " ")
                declared += Declared(
                    fqcn = if (pkg == null) name else "$pkg.$name",
                    name = name,
                    runWith = runWiths.lastOrNull { it.range.first in regionStart..match.range.first }?.groupValues?.get(1),
                    supertypes = wordPattern.findAll(header).map { it.value }.toSet(),
                    body = code.substring(match.range.first, next),
                )
                regionStart = headerStart
            }
        }
    val screenshotBases = mutableSetOf("VCaseDesktopTest", "VCaseRunner")
    val screenshotHelpers = setOf("RaftRobolectricScreenshot", "captureRoboImage")
    val skipped = sortedSetOf<String>()
    do {
        val before = skipped.size
        declared.filter { it.fqcn !in skipped && it.name !in screenshotHelpers }.forEach { declaration ->
            val runner = declaration.runWith?.let { arg -> wordPattern.findAll(arg).any { it.value in screenshotBases } } == true
            val supertype = declaration.supertypes.any { it in screenshotBases }
            val callsHelper = wordPattern.findAll(declaration.body).any { it.value in screenshotHelpers }
            if (runner || supertype || callsHelper || declaration.name.endsWith("ScreenshotTest")) {
                skipped += declaration.fqcn
                screenshotBases += declaration.name
            }
        }
    } while (skipped.size != before)
    return skipped
}

if (raftSkipScreenshots) {
    val roborazziTask = gradle.startParameter.taskNames.firstOrNull { name ->
        val simple = name.substringAfterLast(':')
        simple.startsWith("verifyRoborazzi") ||
            simple.startsWith("recordRoborazzi") ||
            simple.startsWith("compareRoborazzi")
    }
    if (roborazziTask != null) {
        throw GradleException(
            "raft.unitTest.skipScreenshots=true cannot be combined with Roborazzi task $roborazziTask"
        )
    }
    project(":designsystem") {
        val screenshotClasses by lazy {
            raftScreenshotTestClasses(
                listOf("src/commonTest", "src/desktopTest", "src/desktopVcase").map { file(it) }
            ).also { classes ->
                logger.lifecycle("raft.unitTest.skipScreenshots: :designsystem desktopTest skips ${classes.size} screenshot test classes")
            }
        }
        afterEvaluate {
            tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .matching { it.name == "desktopTest" }
                .configureEach {
                    systemProperty("roborazzi.test.verify", "false")
                    filter {
                        screenshotClasses.forEach { excludeTestsMatching(it) }
                        isFailOnNoMatchingTests = false
                    }
                    failOnNoDiscoveredTests.set(false)
                }
        }
    }
    project(":shared") {
        val screenshotClasses by lazy {
            raftScreenshotTestClasses(
                listOf("src/commonTest", "src/androidUnitTest").map { file(it) }
            ).also { classes ->
                logger.lifecycle("raft.unitTest.skipScreenshots: :shared unit tests skip ${classes.size} screenshot test classes")
            }
        }
        afterEvaluate {
            tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                .matching { it.name.endsWith("UnitTest") }
                .configureEach {
                    systemProperty("roborazzi.test.verify", "false")
                    filter {
                        screenshotClasses.forEach { excludeTestsMatching(it) }
                        isFailOnNoMatchingTests = false
                    }
                    failOnNoDiscoveredTests.set(false)
                }
        }
    }
}

// PR CI changed-file unit test selection (dm:@artin:#159): on ordinary PRs
// (`android_unit_scope=jvm`), test.yml runs `scripts/ci/select_changed_tests.py` and passes
// `-Praft.unitTest.includeClassesFile=<path>` listing `:<project>:<fqcn>` entries for impacted
// test classes plus the always-run smoke set (`scripts/ci/always_run_smoke_tests.txt`).
// Gradle intersects `includeTestsMatching` with `excludeTestsMatching`, so `skipRobolectric`
// and `skipScreenshots` continue to exclude Robolectric and screenshot classes cleanly.
// Unset on local runs, post-merge main/release pushes, nightly, merge queue, and full-tier PRs.
val raftUnitTestIncludeClassesFile =
    providers.gradleProperty("raft.unitTest.includeClassesFile")
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

if (raftUnitTestIncludeClassesFile != null) {
    val roborazziTask = gradle.startParameter.taskNames.firstOrNull { name ->
        val simple = name.substringAfterLast(':')
        simple.startsWith("verifyRoborazzi") ||
            simple.startsWith("recordRoborazzi") ||
            simple.startsWith("compareRoborazzi")
    }
    if (roborazziTask != null) {
        throw GradleException(
            "raft.unitTest.includeClassesFile cannot be combined with Roborazzi task $roborazziTask"
        )
    }
    val includeFile = file(raftUnitTestIncludeClassesFile)
    if (!includeFile.isFile) {
        throw GradleException("raft.unitTest.includeClassesFile does not exist: ${includeFile.absolutePath}")
    }
    val classesByProject = linkedMapOf<String, MutableList<String>>()
    includeFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val sep = line.indexOf(':', startIndex = 1)
            if (!line.startsWith(":") || sep <= 1 || sep == line.lastIndex) {
                throw GradleException("Invalid raft.unitTest.includeClassesFile entry (expected :project:fqcn): $line")
            }
            val projectPath = line.substring(0, sep)
            val fqcn = line.substring(sep + 1)
            classesByProject.getOrPut(projectPath) { mutableListOf() }.add(fqcn)
        }
    classesByProject.forEach { (projectPath, classes) ->
        if (classes.isEmpty()) return@forEach
        project(projectPath) {
            afterEvaluate {
                logger.lifecycle(
                    "raft.unitTest.includeClassesFile: $projectPath unit tests include ${classes.size} selected classes"
                )
                tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
                    .matching { it.name.endsWith("UnitTest") }
                    .configureEach {
                        filter {
                            classes.forEach { includeTestsMatching(it) }
                            isFailOnNoMatchingTests = false
                        }
                        failOnNoDiscoveredTests.set(false)
                    }
            }
        }
    }
}

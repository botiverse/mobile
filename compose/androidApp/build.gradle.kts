import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 built-in Kotlin replaces org.jetbrains.kotlin.android.
    alias(libs.plugins.android.builtin.kotlin)
    alias(libs.plugins.kotlin.compose)
}

val handsAndroidSdkMetadataFile = layout.projectDirectory.file("../config/hands-android-sdk.json").asFile
val handsAndroidSdkMetadata =
    (JsonSlurper().parse(handsAndroidSdkMetadataFile) as? Map<*, *>)
        ?: throw GradleException("Invalid Hands Android SDK metadata: $handsAndroidSdkMetadataFile")

fun handsAndroidSdkMetadataString(key: String): String =
    handsAndroidSdkMetadata[key]
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Hands Android SDK metadata is missing '$key'")

val handsAndroidSdkGroup = handsAndroidSdkMetadataString("group")
val handsAndroidSdkArtifact = handsAndroidSdkMetadataString("artifact")
val handsAndroidSdkVersion = handsAndroidSdkMetadataString("version")
val handsAndroidSdkModule = "$handsAndroidSdkGroup:$handsAndroidSdkArtifact"
val handsAndroidSdkCoordinate = "$handsAndroidSdkModule:$handsAndroidSdkVersion"
val handsAndroidSdkSourceRepo = handsAndroidSdkMetadataString("sourceRepo")
val handsAndroidSdkSourceCommit = handsAndroidSdkMetadataString("sourceCommit")
// Workflow-dispatched releases are pinned by sourceCommit + publishRunId without a tag.
val handsAndroidSdkSourceTag = handsAndroidSdkMetadata["sourceTag"]?.toString()?.trim().orEmpty()
val handsAndroidSdkPublishRunId = handsAndroidSdkMetadataString("publishRunId")
val handsAndroidSdkNativeSymbolsArtifactId = handsAndroidSdkMetadataString("nativeSymbolsArtifactId")
val handsAndroidSdkAarSha256 = handsAndroidSdkMetadataString("aarSha256")
val handsAndroidSdkNativeSymbolsSha256 = handsAndroidSdkMetadataString("nativeSymbolsSha256")

val handsAlphaDeltaMetadataFile = layout.projectDirectory.file("../config/hands-alpha-delta.json").asFile
val handsAlphaDeltaMetadata =
    (JsonSlurper().parse(handsAlphaDeltaMetadataFile) as? Map<*, *>)
        ?: throw GradleException("Invalid Hands Alpha delta metadata: $handsAlphaDeltaMetadataFile")

fun handsAlphaDeltaMetadataString(key: String): String =
    handsAlphaDeltaMetadata[key]
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Hands Alpha delta metadata is missing '$key'")

val handsAlphaDeltaBuildType = handsAlphaDeltaMetadataString("enabledBuildType")
val handsAlphaDeltaApplicationId = handsAlphaDeltaMetadataString("expectedApplicationId")
val handsAlphaDeltaAppId = handsAlphaDeltaMetadataString("handsAppId")
val handsAlphaDeltaAppSlug = handsAlphaDeltaMetadataString("handsAppSlug")
val handsAlphaDeltaChannelId = handsAlphaDeltaMetadataString("handsChannelId")
val handsAlphaDeltaChannelSlug = handsAlphaDeltaMetadataString("handsChannelSlug")
val handsAlphaDeltaDeviceGroupId = handsAlphaDeltaMetadataString("handsDeviceGroupId")
val handsAlphaDeltaDeviceGroupName = handsAlphaDeltaMetadataString("handsDeviceGroupName")
val raftAndroidApplicationId = "build.raft.app"
val handsAlphaDeltaApplicationIdSuffix = ".alpha"
val raftDexUseLegacyPackaging =
    providers.gradleProperty("raftDexUseLegacyPackaging")
        .map { value ->
            value.toBooleanStrictOrNull()
                ?: throw GradleException(
                    "raftDexUseLegacyPackaging must be exactly true or false: $value"
                )
        }
val raftJniUseLegacyPackaging =
    providers.gradleProperty("raftJniUseLegacyPackaging")
        .map { value ->
            value.toBooleanStrictOrNull()
                ?: throw GradleException(
                    "raftJniUseLegacyPackaging must be exactly true or false: $value"
                )
        }

if (handsAlphaDeltaMetadata["schemaVersion"]?.toString() != "1") {
    throw GradleException("Unsupported Hands Alpha delta metadata schema")
}
if (handsAlphaDeltaBuildType != "alpha") {
    throw GradleException("Hands delta updates must be bound only to the alpha build type")
}
if (handsAlphaDeltaApplicationId != "$raftAndroidApplicationId$handsAlphaDeltaApplicationIdSuffix") {
    throw GradleException(
        "Hands delta applicationId must be exactly build.raft.app.alpha: $handsAlphaDeltaApplicationId"
    )
}
if (handsAlphaDeltaAppSlug == "raft-android" || handsAlphaDeltaChannelSlug in setOf("main", "debug")) {
    throw GradleException("Hands Alpha delta must use a dedicated non-production app and channel")
}
listOf(
    "handsAppId" to handsAlphaDeltaAppId,
    "handsChannelId" to handsAlphaDeltaChannelId,
    "handsDeviceGroupId" to handsAlphaDeltaDeviceGroupId
).forEach { (key, value) ->
    if (!value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
        throw GradleException("Hands Alpha delta $key must be a lowercase UUID")
    }
}
if (!handsAlphaDeltaAppSlug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) ||
    !handsAlphaDeltaChannelSlug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) ||
    handsAlphaDeltaDeviceGroupName != "Artin test devices"
) {
    throw GradleException("Invalid Hands Alpha delta app/channel/device-group binding")
}

if (handsAndroidSdkMetadata["schemaVersion"]?.toString() != "1") {
    throw GradleException("Unsupported Hands Android SDK metadata schema")
}
if (handsAndroidSdkModule != "build.hands:hands-android-sdk") {
    throw GradleException("Unsupported Hands Android SDK module: $handsAndroidSdkModule")
}
if (!handsAndroidSdkVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
    throw GradleException("Hands Android SDK version must be an exact SemVer release")
}
if (handsAndroidSdkSourceRepo != "https://github.com/botiverse/hands") {
    throw GradleException("Unsupported Hands Android SDK source repository: $handsAndroidSdkSourceRepo")
}
if (!handsAndroidSdkSourceCommit.matches(Regex("[0-9a-f]{40}"))) {
    throw GradleException("Hands Android SDK sourceCommit must be a full lowercase Git SHA")
}
if (handsAndroidSdkSourceTag.isNotEmpty() &&
    handsAndroidSdkSourceTag != "android-sdk-v$handsAndroidSdkVersion"
) {
    throw GradleException(
        "Hands Android SDK sourceTag must match version: " +
            "expected=android-sdk-v$handsAndroidSdkVersion actual=$handsAndroidSdkSourceTag"
    )
}
if (!handsAndroidSdkPublishRunId.matches(Regex("[1-9][0-9]*")) ||
    !handsAndroidSdkNativeSymbolsArtifactId.matches(Regex("[1-9][0-9]*"))
) {
    throw GradleException("Hands Android SDK publish run and symbols artifact IDs must be positive integers")
}
listOf(
    "aarSha256" to handsAndroidSdkAarSha256,
    "nativeSymbolsSha256" to handsAndroidSdkNativeSymbolsSha256
).forEach { (key, value) ->
    if (!value.matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException("Hands Android SDK $key must be a lowercase SHA-256")
    }
}

fun java.io.File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val handsAndroidSdkAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val handsNativeSymbols by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val copyHandsNativeSymbols by tasks.registering(Sync::class) {
    from(handsNativeSymbols)
    into(layout.buildDirectory.dir("outputs/hands-native-symbols"))
    inputs.file(handsAndroidSdkMetadataFile).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(handsAndroidSdkAar)

    doLast {
        val aars = handsAndroidSdkAar.files.filter { file -> file.isFile && file.extension == "aar" }
        if (aars.size != 1) {
            throw GradleException("Expected one Hands Android SDK AAR, found ${aars.map { it.name }}")
        }
        val actualAarSha256 = aars.single().sha256()
        if (actualAarSha256 != handsAndroidSdkAarSha256) {
            throw GradleException(
                "Hands Android SDK AAR SHA-256 mismatch: " +
                    "expected=$handsAndroidSdkAarSha256 actual=$actualAarSha256"
            )
        }

        val archives = destinationDir.listFiles { file -> file.isFile && file.extension == "zip" }?.toList().orEmpty()
        if (archives.size != 1) {
            throw GradleException("Expected one Hands native-symbols ZIP, found ${archives.map { it.name }}")
        }
        val archive = archives.single()
        val actualNativeSymbolsSha256 = archive.sha256()
        if (actualNativeSymbolsSha256 != handsAndroidSdkNativeSymbolsSha256) {
            throw GradleException(
                "Hands native-symbols SHA-256 mismatch: " +
                    "expected=$handsAndroidSdkNativeSymbolsSha256 actual=$actualNativeSymbolsSha256"
            )
        }

        val manifest =
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("manifest.json")
                    ?: throw GradleException("Hands native-symbols manifest.json is missing")
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    JsonSlurper().parse(reader) as? Map<*, *>
                }
            } ?: throw GradleException("Hands native-symbols manifest.json is invalid")
        val source = manifest["source"] as? Map<*, *>
            ?: throw GradleException("Hands native-symbols source metadata is missing")
        if (manifest["sdk_version"]?.toString() != handsAndroidSdkVersion) {
            throw GradleException(
                "Hands native-symbols SDK version mismatch: expected=$handsAndroidSdkVersion actual=${manifest["sdk_version"]}"
            )
        }
        if (source["repo"]?.toString() != handsAndroidSdkSourceRepo ||
            source["commit"]?.toString() != handsAndroidSdkSourceCommit
        ) {
            throw GradleException(
                "Hands native-symbols source mismatch: expected=$handsAndroidSdkSourceRepo@$handsAndroidSdkSourceCommit " +
                    "actual=${source["repo"]}@${source["commit"]}"
            )
        }
        logger.lifecycle(
            "Verified Hands Android SDK $handsAndroidSdkVersion native symbols: " +
                "aar_sha256=$actualAarSha256 symbols_sha256=$actualNativeSymbolsSha256 " +
                "source=$handsAndroidSdkSourceCommit publish_run=$handsAndroidSdkPublishRunId " +
                "symbols_artifact=$handsAndroidSdkNativeSymbolsArtifactId"
        )
    }
}

val googleServicesConfigFiles =
    listOf(
        file("google-services.json"),
        file("src/debug/google-services.json"),
        file("src/connectedTest/google-services.json"),
        file("src/release/google-services.json"),
        file("src/play/google-services.json"),
        file("src/alpha/google-services.json")
    )

val productionBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(":").let { task ->
        task.contains("release", ignoreCase = true) || task.contains("play", ignoreCase = true)
    }
}
if (productionBuildRequested &&
    (!file("src/release/google-services.json").isFile || !file("src/play/google-services.json").isFile)
) {
    throw GradleException(
        "Firebase google-services.json is required for release and Play builds; provide " +
            "androidApp/src/release/google-services.json and androidApp/src/play/google-services.json"
    )
}

if (googleServicesConfigFiles.any { it.isFile }) {
    pluginManager.apply("com.google.gms.google-services")
    tasks.configureEach {
        if (name == "processConnectedTestGoogleServices") enabled = false
    }
} else {
    logger.lifecycle("Firebase google-services.json not found; skipping Google Services Gradle plugin for this local build.")
}

val googleServerClientId =
    providers.gradleProperty("SLOCK_GOOGLE_SERVER_CLIENT_ID")
        .orElse(providers.environmentVariable("SLOCK_GOOGLE_SERVER_CLIENT_ID"))
        .getOrElse("")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun raftBuildConfigString(name: String, fallback: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .getOrElse(fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun raftBuildConfigRawString(name: String, vararg aliases: String): String? =
    sequenceOf(name, *aliases)
        .mapNotNull { key ->
            providers.gradleProperty(key)
                .orElse(providers.environmentVariable(key))
                .orNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

fun raftBuildConfigInt(name: String, fallback: Int, vararg aliases: String): Int =
    raftBuildConfigRawString(name, *aliases)?.toIntOrNull() ?: fallback

fun raftBuildConfigBoolean(name: String, fallback: Boolean): Boolean =
    raftBuildConfigRawString(name)?.lowercase()?.toBooleanStrictOrNull() ?: fallback

fun raftComputeAndroidVersionCode(major: Int, minor: Int, patch: Int, build: Int): Int =
    // Mirrors scripts/ci/version_code.sh (task #321): major*10M + minor*100k + patch*1k + build.
    // Kept as a Kotlin-level formula so no shell invocation is needed during the build.
    major * 10_000_000 + minor * 100_000 + patch * 1_000 + build

val raftAndroidVersionMajor = raftBuildConfigInt("versionX", 1, "SLOCK_ANDROID_VERSION_MAJOR")
val raftAndroidVersionMinor = raftBuildConfigInt("versionY", 0, "SLOCK_ANDROID_VERSION_MINOR")
val raftAndroidVersionPatch = raftBuildConfigInt("versionZ", 0, "SLOCK_ANDROID_VERSION_PATCH")
val raftAndroidVersionBuild = raftBuildConfigInt("versionBuild", 0, "SLOCK_ANDROID_VERSION_BUILD")
val raftAndroidVersionCode =
    raftBuildConfigRawString("versionCode", "SLOCK_ANDROID_VERSION_CODE")?.toIntOrNull()
        ?: raftComputeAndroidVersionCode(
            raftAndroidVersionMajor,
            raftAndroidVersionMinor,
            raftAndroidVersionPatch,
            raftAndroidVersionBuild
        )
val raftAndroidVersionSuffix = raftBuildConfigRawString("versionSuffix", "SLOCK_ANDROID_VERSION_SUFFIX")
val raftAndroidVersionName =
    raftBuildConfigRawString("versionName", "SLOCK_ANDROID_VERSION_NAME")
        ?: buildString {
            append(raftAndroidVersionMajor)
            append(".")
            append(raftAndroidVersionMinor)
            append(".")
            append(raftAndroidVersionPatch)
            raftAndroidVersionSuffix?.let { append("-").append(it) }
            if (raftAndroidVersionSuffix != null) append("+").append(raftGitCommit())
            if (raftWorkingTreeDirty()) append("-dirty")
        }

fun raftBuildTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
}

fun raftGitCommit(): String =
    try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.exitValue() == 0 && output.isNotBlank()) output else "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

fun raftWorkingTreeDirty(): Boolean =
    try {
        val process = ProcessBuilder("git", "status", "--porcelain")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)
        process.exitValue() == 0 && output.isNotBlank()
    } catch (_: Throwable) {
        false
    }

data class OssLicenseOverride(
    val id: String,
    val displayName: String?,
    val licenseName: String,
    val licenseUrl: String?,
    val sourceUrl: String?,
    val reason: String?
)

data class OssLicenseEntry(
    val id: String,
    val group: String,
    val name: String,
    val version: String,
    val displayName: String,
    val licenseName: String,
    val licenseUrl: String?,
    val sourceUrl: String?
)

abstract class GenerateOssLicensesTask : DefaultTask() {
    @get:Input
    abstract val sourceConfigurations: ListProperty<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val sourceConfigurationLabel: Property<String>

    @get:Input
    abstract val requiredModules: ListProperty<String>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overridesFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val configurationPaths = sourceConfigurations.get()
        val moduleIds =
            configurationPaths
                .flatMap { path ->
                    path.toConfiguration().incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? ModuleComponentIdentifier }
                }
                .distinctBy { it.coordinateKey() }
                .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))

        val moduleKeys = moduleIds.flatMap { listOf(it.coordinateKey(), it.moduleKey()) }.toSet()
        val missingRequiredModules = requiredModules.get().filterNot { it in moduleKeys }
        if (missingRequiredModules.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Missing required OSS disclosure dependencies:")
                    missingRequiredModules.forEach { appendLine(" - $it") }
                    appendLine("Checked configurations:")
                    configurationPaths.forEach { appendLine(" - $it") }
                }
            )
        }

        val pomFiles =
            project.dependencies
                .createArtifactResolutionQuery()
                .forComponents(moduleIds)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                .execute()
                .resolvedComponents
                .mapNotNull { component ->
                    val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                    val pom =
                        component.getArtifacts(MavenPomArtifact::class.java)
                            .filterIsInstance<ResolvedArtifactResult>()
                            .firstOrNull()
                            ?.file
                    id.coordinateKey() to pom
                }
                .toMap()

        val overrides = readOverrides(overridesFile.orNull?.asFile)
        val missing = mutableListOf<String>()
        val entries =
            moduleIds.map { id ->
                val override = overrides.firstOrNull { it.matches(id) }
                val pomLicense = pomFiles[id.coordinateKey()]?.readPomLicense()
                val licenseName = override?.licenseName ?: pomLicense?.first.orEmpty()
                val licenseUrl = override?.licenseUrl ?: pomLicense?.second
                if (licenseName.isBlank() || licenseName.equals("unknown", ignoreCase = true)) {
                    missing += id.coordinateKey()
                }
                OssLicenseEntry(
                    id = id.coordinateKey(),
                    group = id.group,
                    name = id.module,
                    version = id.version,
                    displayName = override?.displayName?.ifBlank { null } ?: "${id.group}:${id.module}",
                    licenseName = licenseName,
                    licenseUrl = licenseUrl?.ifBlank { null },
                    sourceUrl = override?.sourceUrl?.ifBlank { null }
                )
            }

        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Missing license metadata for OSS disclosure dependencies:")
                    missing.forEach { appendLine(" - $it") }
                    appendLine("Add an entry to ${overridesFile.orNull?.asFile?.relativeToOrSelf(project.rootDir)?.path ?: "the OSS license override file"} or fix the published POM metadata.")
                }
            )
        }

        val json =
            linkedMapOf(
                "schemaVersion" to 1,
                "variant" to variantName.get(),
                "sourceConfiguration" to sourceConfigurationLabel.get(),
                "sourceConfigurations" to configurationPaths,
                "items" to entries.map { entry ->
                    linkedMapOf(
                        "id" to entry.id,
                        "group" to entry.group,
                        "name" to entry.name,
                        "version" to entry.version,
                        "displayName" to entry.displayName,
                        "licenseName" to entry.licenseName,
                        "licenseUrl" to entry.licenseUrl,
                        "sourceUrl" to entry.sourceUrl
                    ).filterValues { it != null }
                }
            )
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)) + "\n")
        }
    }

    private fun String.toConfiguration(): org.gradle.api.artifacts.Configuration {
        val separator = lastIndexOf(':')
        val (projectPath, configurationName) =
            if (separator > 0) {
                substring(0, separator) to substring(separator + 1)
            } else {
                project.path to this
            }
        val sourceProject = project.rootProject.project(projectPath)
        return sourceProject.configurations.getByName(configurationName)
    }

    private fun readOverrides(file: File?): List<OssLicenseOverride> {
        if (file == null || !file.isFile) return emptyList()
        val parsed = JsonSlurper().parse(file) as? Map<*, *> ?: return emptyList()
        val items = parsed["overrides"] as? List<*> ?: return emptyList()
        return items.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val id = item["id"]?.toString()?.trim().orEmpty()
            val licenseName = item["licenseName"]?.toString()?.trim().orEmpty()
            if (id.isBlank() || licenseName.isBlank()) return@mapNotNull null
            OssLicenseOverride(
                id = id,
                displayName = item["displayName"]?.toString()?.trim(),
                licenseName = licenseName,
                licenseUrl = item["licenseUrl"]?.toString()?.trim(),
                sourceUrl = item["sourceUrl"]?.toString()?.trim(),
                reason = item["reason"]?.toString()?.trim()
            )
        }
    }

    private fun OssLicenseOverride.matches(id: ModuleComponentIdentifier): Boolean =
        this.id == id.coordinateKey() ||
            this.id == "${id.group}:${id.module}" ||
            this.id == "${id.group}:${id.module}:*" ||
            this.id == "${id.group}:*"

    private fun ModuleComponentIdentifier.coordinateKey(): String = "$group:$module:$version"

    private fun ModuleComponentIdentifier.moduleKey(): String = "$group:$module"

    private fun File.readPomLicense(): Pair<String, String?>? =
        runCatching {
            val document =
                DocumentBuilderFactory
                    .newInstance()
                    .apply {
                        isNamespaceAware = false
                        isExpandEntityReferences = false
                    }
                    .newDocumentBuilder()
                    .parse(this)
            val licenses = document.getElementsByTagName("license")
            for (index in 0 until licenses.length) {
                val license = licenses.item(index) as? Element ?: continue
                val name = license.childText("name")
                val url = license.childText("url")
                if (name.isNotBlank() || url.isNotBlank()) {
                    return@runCatching (name.ifBlank { url } to url.ifBlank { null })
                }
            }
            null
        }.getOrNull()

    private fun Element.childText(tag: String): String =
        getElementsByTagName(tag)
            .let { nodes ->
                (0 until nodes.length)
                    .asSequence()
                    .mapNotNull { nodes.item(it)?.textContent?.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
}

android {
    namespace = "build.raft.app"
    compileSdk {
        version = release(37)
    }
    testBuildType = "connectedTest"

    defaultConfig {
        applicationId = raftAndroidApplicationId
        minSdk = 29
        targetSdk = 36
        versionCode = raftAndroidVersionCode
        versionName = raftAndroidVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "Boolean",
            "SHOW_TEST_BUILD_BADGE",
            raftBuildConfigBoolean("SLOCK_SHOW_TEST_BUILD_BADGE", false).toString()
        )
        buildConfigField(
            "Boolean",
            "PERFORMANCE_TRACE_RING_ENABLED",
            raftBuildConfigBoolean("SLOCK_PERFORMANCE_TRACE_RING_ENABLED", false).toString()
        )
        buildConfigField("Boolean", "READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED", "false")
        buildConfigField("Boolean", "QA_SESSION_BOOTSTRAP_ENABLED", "false")
        buildConfigField("Boolean", "SCREENSHOT_FEEDBACK_ENABLED", "false")
        buildConfigField("Boolean", "HANDS_APK_UPDATE_ENABLED", "true")
        buildConfigField("Boolean", "HANDS_DELTA_UPDATE_ENABLED", "false")
        buildConfigField("String", "HANDS_DELTA_APP_SLUG", "\"\"")
        buildConfigField("String", "HANDS_DELTA_CHANNEL", "\"\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"raft\"")
        manifestPlaceholders["oauthCallbackScheme"] = "raft"
        buildConfigField("String", "BUILD_TIME", "\"${raftBuildTime()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${raftGitCommit()}\"")
        buildConfigField("String", "HANDS_BASE_URL", "\"${raftBuildConfigString("SLOCK_HANDS_BASE_URL", "https://hands.build")}\"")
        buildConfigField("String", "HANDS_APP_SLUG", "\"${raftBuildConfigString("SLOCK_HANDS_APP_SLUG", "raft-android")}\"")
        buildConfigField("String", "HANDS_CHANNEL", "\"${raftBuildConfigString("SLOCK_HANDS_CHANNEL", "main")}\"")
        buildConfigField("String", "HANDS_CLIENT_KEY", "\"${raftBuildConfigString("SLOCK_HANDS_CLIENT_KEY", "qk_40c9c9ab0b91665c85ca6278f3b0268c08d5afa3")}\"")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "HANDS_CHANNEL", "\"${raftBuildConfigString("SLOCK_HANDS_CHANNEL", "debug")}\"")
            buildConfigField("Boolean", "READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED", "true")
        }
        create("alpha") {
            initWith(getByName("debug"))
            applicationIdSuffix = handsAlphaDeltaApplicationIdSuffix
            buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")
            buildConfigField("Boolean", "PERFORMANCE_TRACE_RING_ENABLED", "true")
            buildConfigField("Boolean", "READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED", "true")
            buildConfigField("Boolean", "QA_SESSION_BOOTSTRAP_ENABLED", "true")
            buildConfigField("Boolean", "SCREENSHOT_FEEDBACK_ENABLED", "true")
            buildConfigField("String", "HANDS_CHANNEL", "\"debug\"")
            buildConfigField("Boolean", "HANDS_DELTA_UPDATE_ENABLED", "true")
            buildConfigField("String", "HANDS_DELTA_APP_SLUG", "\"$handsAlphaDeltaAppSlug\"")
            buildConfigField("String", "HANDS_DELTA_CHANNEL", "\"$handsAlphaDeltaChannelSlug\"")
            matchingFallbacks += listOf("debug")
        }
        create("connectedTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".connectedtest"
            buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")
            buildConfigField("Boolean", "READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED", "true")
            buildConfigField("Boolean", "QA_SESSION_BOOTSTRAP_ENABLED", "true")
            buildConfigField("String", "HANDS_CHANNEL", "\"${raftBuildConfigString("SLOCK_HANDS_CHANNEL", "debug")}\"")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("play") {
            initWith(getByName("release"))
            buildConfigField("Boolean", "HANDS_APK_UPDATE_ENABLED", "false")
            matchingFallbacks += listOf("release")
        }
        configureEach {
            val callbackScheme = if (name == "release" || name == "play") "raft" else "raft-${name.lowercase()}"
            buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"$callbackScheme\"")
            manifestPlaceholders["oauthCallbackScheme"] = callbackScheme
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Robolectric tests (AndroidUnreadNotificationPosterPreviewTest) resolve the app's own strings.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        dex {
            useLegacyPackaging = raftDexUseLegacyPackaging.orNull
        }
        jniLibs {
            useLegacyPackaging = raftJniUseLegacyPackaging.orNull ?: true
        }
    }
    sourceSets {
        getByName("connectedTest") {
            res.srcDir("src/connectedTest/res")
        }
    }
}

// AGP 9 rejects Provider-backed directories in android.sourceSets; the generated licence asset
// directory is added through the variant API instead (preBuild still runs the generator).
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            layout.buildDirectory.dir("generated/assets/ossLicenses/main").get().asFile.absolutePath
        )
    }
}

val generateReleaseOssLicenses by tasks.registering(GenerateOssLicensesTask::class) {
    group = "verification"
    description = "Generates the Open Source Licenses asset from Android and shared KMP dependencies."
    sourceConfigurations.set(
        listOf(
            ":androidApp:releaseRuntimeClasspath",
            ":shared:commonMainImplementationDependenciesMetadata",
            ":shared:releaseRuntimeClasspath"
        )
    )
    variantName.set("release")
    sourceConfigurationLabel.set("Android release runtime + shared KMP metadata")
    requiredModules.set(
        listOf(
            "com.tencent.kuiklybase:network",
            "com.tencent.kuiklybase:network-android",
            "com.tencent.kuiklybase:network-android-curl-runtime",
            "com.tencent.kuiklybase:resource-core",
            "com.tencent.kuiklybase:resource-core-android",
            handsAndroidSdkModule
        )
    )
    overridesFile.set(layout.projectDirectory.file("../config/oss-license-overrides.json"))
    outputFile.set(layout.buildDirectory.file("generated/assets/ossLicenses/main/oss_licenses.json"))
}

tasks.named("preBuild") {
    dependsOn(generateReleaseOssLicenses)
}

tasks.named("check") {
    dependsOn(generateReleaseOssLicenses)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Keep the custom connectedTest target on the production dependency graph. Forcing newer
// lifecycle/savedstate atomic groups here previously upgraded only its Compose runtime to 1.9.x,
// while the app is compiled against the production Compose runtime.
val verifyConnectedTestComposeRuntimeAlignment by tasks.registering {
    group = "verification"
    description =
        "Fails when the connectedTest target resolves a different AndroidX Compose runtime than debug."

    doLast {
        fun composeRuntimeVersions(configurationName: String): Set<String> =
            configurations.getByName(configurationName)
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
                .filter { component -> component.group == "androidx.compose.runtime" }
                .map { component -> component.version }
                .toSet()

        val debugVersions = composeRuntimeVersions("debugRuntimeClasspath")
        val connectedTestVersions = composeRuntimeVersions("connectedTestRuntimeClasspath")
        if (debugVersions.size != 1) {
            throw GradleException(
                "Expected one production Compose runtime version in debugRuntimeClasspath, " +
                    "resolved=$debugVersions"
            )
        }
        if (connectedTestVersions != debugVersions) {
            throw GradleException(
                "connectedTest Compose runtime must match production debug runtime: " +
                    "debug=$debugVersions connectedTest=$connectedTestVersions"
            )
        }
        logger.lifecycle(
            "connectedTest Compose runtime aligned with debug: ${debugVersions.single()}"
        )
    }
}

tasks.matching {
    it.name == "assembleConnectedTest" || it.name == "assembleConnectedTestAndroidTest"
}.configureEach {
    dependsOn(verifyConnectedTestComposeRuntimeAlignment)
}

dependencies {
    add(handsAndroidSdkAar.name, "$handsAndroidSdkCoordinate@aar")

    // Shared app (pages, data layer) on Compose Multiplatform.
    implementation(project(":shared"))
    // CMP page root/router/host contracts (RaftCmpActivity); see ui-cmp/build.gradle.kts.
    implementation(project(":ui-cmp"))
    // Play services (auth/base/messaging) only declare androidx.fragment 1.1.0 transitively; the
    // activity lint (InvalidFragmentVersionForActivityResult) judges that declaration and fails
    // lintVitalRelease. Declare a current Fragment directly.
    implementation(libs.androidx.fragment)
    implementation(project(":designsystem"))
    runtimeOnly("com.tencent.kuiklybase:network-android-curl-runtime:0.1.0-raft.47")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.window)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    // Pinned to the AndroidX Compose runtime line CMP 1.12.x is built against.
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(handsAndroidSdkCoordinate)
    add(handsNativeSymbols.name, "$handsAndroidSdkCoordinate:native-symbols@zip")
    implementation(libs.socket.io.client)
    implementation(libs.androidsvg)
    implementation(libs.mmkv)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Builds real notifications in JVM unit tests.
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "connectedTestImplementation"(libs.androidx.compose.ui.tooling)
    "connectedTestImplementation"(libs.androidx.compose.ui.test.manifest)
}

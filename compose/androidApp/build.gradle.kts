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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val googleServicesConfigFiles =
    listOf(
        file("google-services.json"),
        file("src/debug/google-services.json"),
        file("src/connectedTest/google-services.json"),
        file("src/release/google-services.json")
    )

if (googleServicesConfigFiles.any { it.isFile }) {
    pluginManager.apply("com.google.gms.google-services")
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

fun slockBuildConfigString(name: String, fallback: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .getOrElse(fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun slockBuildConfigRawString(name: String, vararg aliases: String): String? =
    sequenceOf(name, *aliases)
        .mapNotNull { key ->
            providers.gradleProperty(key)
                .orElse(providers.environmentVariable(key))
                .orNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

fun slockBuildConfigInt(name: String, fallback: Int, vararg aliases: String): Int =
    slockBuildConfigRawString(name, *aliases)?.toIntOrNull() ?: fallback

fun slockBuildConfigBoolean(name: String, fallback: Boolean): Boolean =
    slockBuildConfigRawString(name)?.lowercase()?.toBooleanStrictOrNull() ?: fallback

val slockAndroidVersionMajor = slockBuildConfigInt("versionX", 1, "SLOCK_ANDROID_VERSION_MAJOR")
val slockAndroidVersionMinor = slockBuildConfigInt("versionY", 0, "SLOCK_ANDROID_VERSION_MINOR")
val slockAndroidVersionPatch = slockBuildConfigInt("versionZ", 0, "SLOCK_ANDROID_VERSION_PATCH")
val slockAndroidVersionBuild = slockBuildConfigInt("versionBuild", 0, "SLOCK_ANDROID_VERSION_BUILD")
val slockAndroidVersionCode =
    slockBuildConfigRawString("versionCode", "SLOCK_ANDROID_VERSION_CODE")?.toIntOrNull()
        ?: (slockAndroidVersionMajor * 1_000_000 +
            slockAndroidVersionMinor * 10_000 +
            slockAndroidVersionPatch * 100 +
            slockAndroidVersionBuild)
val slockAndroidVersionSuffix = slockBuildConfigRawString("versionSuffix", "SLOCK_ANDROID_VERSION_SUFFIX")
val slockAndroidVersionName =
    slockBuildConfigRawString("versionName", "SLOCK_ANDROID_VERSION_NAME")
        ?: buildString {
            append(slockAndroidVersionMajor)
            append(".")
            append(slockAndroidVersionMinor)
            append(".")
            append(slockAndroidVersionPatch)
            slockAndroidVersionSuffix?.let { append("-").append(it) }
        }

fun slockBuildTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
}

fun slockGitCommit(): String =
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
        version = release(36)
    }
    testBuildType = "connectedTest"

    defaultConfig {
        applicationId = "build.raft.app"
        minSdk = 24
        targetSdk = 36
        versionCode = slockAndroidVersionCode
        versionName = slockAndroidVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "Boolean",
            "SHOW_TEST_BUILD_BADGE",
            slockBuildConfigBoolean("SLOCK_SHOW_TEST_BUILD_BADGE", false).toString()
        )
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        buildConfigField("String", "BUILD_TIME", "\"${slockBuildTime()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${slockGitCommit()}\"")
        buildConfigField("String", "QUIVER_BASE_URL", "\"${slockBuildConfigString("SLOCK_HANDS_BASE_URL", slockBuildConfigString("SLOCK_QUIVER_BASE_URL", "https://hands.build"))}\"")
        buildConfigField("String", "QUIVER_APP_SLUG", "\"${slockBuildConfigString("SLOCK_QUIVER_APP_SLUG", "raft-android")}\"")
        buildConfigField("String", "QUIVER_CHANNEL", "\"${slockBuildConfigString("SLOCK_QUIVER_CHANNEL", "main")}\"")
        buildConfigField("String", "QUIVER_CLIENT_KEY", "\"${slockBuildConfigString("SLOCK_QUIVER_CLIENT_KEY", "qk_40c9c9ab0b91665c85ca6278f3b0268c08d5afa3")}\"")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "QUIVER_CHANNEL", "\"${slockBuildConfigString("SLOCK_QUIVER_CHANNEL", "debug")}\"")
        }
        create("alpha") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".alpha"
            buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")
            buildConfigField("String", "QUIVER_CHANNEL", "\"debug\"")
            matchingFallbacks += listOf("debug")
        }
        create("connectedTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".connectedtest"
            buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")
            buildConfigField("String", "QUIVER_CHANNEL", "\"${slockBuildConfigString("SLOCK_QUIVER_CHANNEL", "debug")}\"")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/assets/ossLicenses/main"))
        }
        getByName("connectedTest") {
            res.srcDir("src/connectedTest/res")
        }
    }
}

val generateReleaseOssLicenses by tasks.registering(GenerateOssLicensesTask::class) {
    group = "verification"
    description = "Generates the Open Source Licenses asset from Android and shared KMP dependencies."
    sourceConfigurations.set(
        listOf(
            ":androidApp:releaseRuntimeClasspath",
            ":shared:commonMainImplementationDependenciesMetadata",
            ":shared:androidReleaseRuntimeClasspath"
        )
    )
    variantName.set("release")
    sourceConfigurationLabel.set("Android release runtime + shared KMP metadata")
    requiredModules.set(
        listOf(
            "com.tencent.kuiklybase:network",
            "com.tencent.kuiklybase:network-android",
            "com.tencent.kuiklybase:resource-core",
            "com.tencent.kuiklybase:resource-core-android",
            "com.tencent.kuikly-open:compose",
            "com.tencent.kuikly-open:compose-android",
            "build.hands:hands-android-sdk"
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

configurations
    .matching { it.name.contains("ConnectedTest", ignoreCase = true) }
    .configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "androidx.lifecycle" -> {
                    useVersion("2.9.4")
                    because("Keep connectedTest on the lifecycle artifacts already used and cached by the app build.")
                }

                requested.group == "androidx.savedstate" -> {
                    useVersion("1.3.3")
                    because("Keep connectedTest on the savedstate artifacts already used and cached by the app build.")
                }
            }
        }
    }

dependencies {
    // Kuikly Compose shared module
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
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
    implementation("build.hands:hands-android-sdk:0.10.1")
    implementation(libs.socket.io.client)
    implementation(libs.androidsvg)
    implementation(libs.mmkv)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "connectedTestImplementation"(libs.androidx.compose.ui.tooling)
    "connectedTestImplementation"(libs.androidx.compose.ui.test.manifest)
}

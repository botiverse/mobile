import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val googleServerClientId =
    providers.gradleProperty("SLOCK_GOOGLE_SERVER_CLIENT_ID")
        .orElse(providers.environmentVariable("SLOCK_GOOGLE_SERVER_CLIENT_ID"))
        .getOrElse("")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val defaultBuglyAndroidAppId = "1277318f53"
val defaultBuglyAndroidAppKey = "a1b03176-0694-4167-ad18-05755b0b36e4"

val buglyAndroidAppId =
    providers.gradleProperty("SLOCK_BUGLY_ANDROID_APP_ID")
        .orElse(providers.environmentVariable("SLOCK_BUGLY_ANDROID_APP_ID"))
        .getOrElse(defaultBuglyAndroidAppId)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val buglyAndroidAppKey =
    providers.gradleProperty("SLOCK_BUGLY_ANDROID_APP_KEY")
        .orElse(providers.environmentVariable("SLOCK_BUGLY_ANDROID_APP_KEY"))
        .getOrElse(defaultBuglyAndroidAppKey)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

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

android {
    namespace = "ai.slock.android"
    compileSdk {
        version = release(36)
    }
    testBuildType = "connectedTest"

    defaultConfig {
        applicationId = "ai.slock.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "false")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        buildConfigField("String", "BUGLY_ANDROID_APP_ID", "\"$buglyAndroidAppId\"")
        buildConfigField("String", "BUGLY_ANDROID_APP_KEY", "\"$buglyAndroidAppKey\"")
        buildConfigField("String", "BUILD_TIME", "\"${slockBuildTime()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${slockGitCommit()}\"")
    }

    buildTypes {
        create("connectedTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".alpha"
            buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")
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
        getByName("connectedTest") {
            res.srcDir("src/connectedTest/res")
        }
    }
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.socket.io.client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidsvg)
    implementation(libs.mmkv)
    implementation(libs.bugly.pro) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    }
    implementation(libs.bugly.diagnose.logger)
    implementation(libs.bugly.diagnose)
    testImplementation(libs.ktor.client.mock)
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

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

val buglyAndroidAppId =
    providers.gradleProperty("SLOCK_BUGLY_ANDROID_APP_ID")
        .orElse(providers.environmentVariable("SLOCK_BUGLY_ANDROID_APP_ID"))
        .getOrElse("")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val buglyAndroidAppKey =
    providers.gradleProperty("SLOCK_BUGLY_ANDROID_APP_KEY")
        .orElse(providers.environmentVariable("SLOCK_BUGLY_ANDROID_APP_KEY"))
        .getOrElse("")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

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
    implementation(libs.markdown.renderer.android)
    implementation(libs.markdown.renderer.m3.android)
    implementation(libs.markdown.renderer.code)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidsvg)
    implementation(libs.bugly.pro) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    }
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

plugins {
    kotlin("multiplatform")
}

fun ohosSqliteProviderLibDir(): File {
    providers.environmentVariable("SLOCK_OHOS_SQLITE_LIB_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file(it) }
    providers.environmentVariable("SLOCK_OHOS_SQLITE_WORKDIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file("$it/ohos-arm64/lib") }
    providers.environmentVariable("SLOCK_THIRD_PARTY_WORKDIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file("$it/ohos-sqlite/ohos-arm64/lib") }
    return rootProject.file("../third_party/.workdirs/ohos-sqlite/ohos-arm64/lib")
}

kotlin {
    ohosArm64 {
        binaries.sharedLib {
            baseName = "shared"
            // Preserve lightweight K/N debug metadata so OHOS crash stacks can be symbolicated.
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts("--build-id=sha1")
            linkerOpts("-L${ohosSqliteProviderLibDir().absolutePath}")
            linkerOpts("-L${rootProject.file("shared/src/libs/arm64-v8a").absolutePath}")
            linkerOpts("-lmmkv_c_wrapper")
            // The shared implementation's resource KLIB references these symbols.
            linkerOpts("-lresource_compose")
        }
    }

    sourceSets {
        val ohosArm64Main by getting {
            dependencies {
                implementation(project(":shared"))
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.endsWith("OhosArm64")) {
        dependsOn(":shared:prepareOhosSqliteProvider")
    }
}

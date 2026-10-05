plugins {
    kotlin("multiplatform")
    id("com.tencent.kuiklybase.knoi.plugin")
}

knoi {
    ignoreTypeAssert = true
    moduleName = "slockshared"
    tsGenDir = layout.buildDirectory.dir("generated/knoi-ts").get().asFile.absolutePath
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

fun ohosLlvmReadelf(): File {
    val sdkRoots = listOfNotNull(
        providers.environmentVariable("SLOCK_DEVECO_SDK_HOME").orNull,
        providers.environmentVariable("DEVECO_SDK_HOME").orNull,
        providers.environmentVariable("OHOS_SDK_HOME").orNull,
    ).filter { it.isNotBlank() }
    val candidates = sdkRoots.flatMap { sdkRoot ->
        listOf(
            file("$sdkRoot/default/openharmony/native/llvm/bin/llvm-readelf"),
            file("$sdkRoot/native/llvm/bin/llvm-readelf"),
        )
    } + file("/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/llvm-readelf")
    return candidates.firstOrNull(File::isFile)
        ?: error("Unable to locate the OpenHarmony llvm-readelf tool")
}

fun runAndCapture(vararg command: String): String {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) {
        "Command failed: ${command.joinToString(" ")}\n$output"
    }
    return output
}

kotlin {
    ohosArm64 {
        binaries.sharedLib {
            baseName = "shared"
            // Preserve lightweight K/N debug metadata so OHOS crash stacks can be symbolicated.
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts("--build-id=sha1")
            linkerOpts("-L${ohosSqliteProviderLibDir().absolutePath}")
            linkerOpts("-lsqlite3")
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

fun verifyOhosBridgeNativeContracts(outputDirectory: String) {
    val output = layout.buildDirectory.dir("bin/ohosArm64/$outputDirectory").get().asFile
    val library = output.resolve("libshared.so")
    val apiHeader = output.resolve("libshared_api.h")
    check(library.isFile) { "Missing linked OHOS bridge library: $library" }
    check(apiHeader.isFile) { "Missing generated OHOS bridge header: $apiHeader" }

    val apiHeaderText = apiHeader.readText()
    val apiHeaderLineCount = apiHeader.useLines { lines -> lines.count() }
    check("raftInstallKnoiAsyncLog" in apiHeaderText) {
        "OHOS bridge header does not expose the pre-KNOI logger installer"
    }
    check("raftInitKuikly" in apiHeaderText) {
        "OHOS bridge header does not expose raftInitKuikly"
    }
    check("raftUpdateProcessNetworkVisibility" in apiHeaderText) {
        "OHOS bridge header does not expose raftUpdateProcessNetworkVisibility"
    }
    check(apiHeaderLineCount <= 500) {
        "OHOS bridge C API unexpectedly expanded to $apiHeaderLineCount lines"
    }

    val readelf = ohosLlvmReadelf().absolutePath
    val symbols = runAndCapture(readelf, "-s", "--wide", library.absolutePath)
    check(symbols.lineSequence().any {
        "com_tencent_tmm_knoi_initBridge" in it && " UND " !in it
    }) {
        "OHOS bridge library does not export com_tencent_tmm_knoi_initBridge"
    }
    check(symbols.lineSequence().none {
        "sqlite3_db_config" in it && " UND " in it
    }) {
        "OHOS bridge library leaves sqlite3_db_config unresolved"
    }
    check(symbols.lineSequence().none { "mmkv_c_sync" in it }) {
        "OHOS bridge library must not force synchronous MMKV flushes from generic key-value writes"
    }

    val dynamicSection = runAndCapture(readelf, "-d", library.absolutePath)
    check("libmmkv_c_wrapper.so" in dynamicSection) {
        "OHOS bridge library does not depend on libmmkv_c_wrapper.so"
    }
}

val verifyDebugSharedOhosArm64NativeContracts by tasks.registering {
    group = "verification"
    description = "Verifies the debug OHOS bridge native dependencies and KNOI exports."
    doLast { verifyOhosBridgeNativeContracts("debugShared") }
}

val verifyReleaseSharedOhosArm64NativeContracts by tasks.registering {
    group = "verification"
    description = "Verifies the release OHOS bridge native dependencies and KNOI exports."
    doLast { verifyOhosBridgeNativeContracts("releaseShared") }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>("linkDebugSharedOhosArm64") {
    dependsOn(":shared:prepareOhosSqliteProvider")
    finalizedBy(verifyDebugSharedOhosArm64NativeContracts)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>("linkReleaseSharedOhosArm64") {
    dependsOn(":shared:prepareOhosSqliteProvider")
    finalizedBy(verifyReleaseSharedOhosArm64NativeContracts)
}

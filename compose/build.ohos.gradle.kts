plugins {
    //trick: for the same plugin versions in all sub-modules
    id("com.android.application").version("7.4.2").apply(false)
    id("com.android.library").version("7.4.2").apply(false)
    kotlin("android").version("2.0.21-KBA-010").apply(false)
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
    kotlin("plugin.serialization").version("2.0.21-KBA-010").apply(false)
    id("com.google.devtools.ksp").version("2.0.21-1.0.27").apply(false)
    id("org.jetbrains.compose").version("1.7.3").apply(false)
    kotlin("plugin.compose").version("2.0.21-KBA-010").apply(false)
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.3").apply(false)
    id("com.tencent.kuiklybase.knoi.plugin").version("0.0.4").apply(false)
    // Syncs libpbcurlwrapper/libopenssl/libc++_shared runtime .so files for the
    // NetworkKMM OHOS transport (task #6): ./gradlew copyNetworkOhosRuntimeLibs
    id("com.tencent.kuiklybase.network.ohos-runtime").version("0.1.0-raft.16-ohos")
}

networkOhosRuntime {
    outputDir.set(layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a"))
}

// KmmResource native bridge (task #19): libshared.so carries a DT_NEEDED on
// libresource_compose.so (see shared/build.ohos.gradle.kts linkerOpts), so the
// committed binary must reach the HAP's native libs next to the network
// runtime .so files. Hooked onto copyNetworkOhosRuntimeLibs so every existing
// sync workflow picks it up unchanged.
val copyResourceComposeLib = tasks.register<Copy>("copyResourceComposeLib") {
    from(layout.projectDirectory.file("shared/src/libs/arm64-v8a/libresource_compose.so"))
    into(layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a"))
}
tasks.matching { it.name == "copyNetworkOhosRuntimeLibs" }.configureEach {
    finalizedBy(copyResourceComposeLib)
}

// KmmResource generated MR resources (task #19): the resource generator's
// mergeOhosResources collects slock_* string/color/plural JSONs from the
// shared klibs into mergeRes, but nothing shipped them into the HAP — the
// entry target's resource.directories references ./mr-res, synced here.
// Hung off mergeOhosResources itself (the Kuikly host flow only runs the
// shared link, never copyNetworkOhosRuntimeLibs), so any link refreshes it.
// restool rejects the generator's BCP-47 locale dirs ("Invalid qualifier
// key 'zh-CN'"), so locale segments are rewritten to qualifier form.
val copyMrResourcesForOhosEntry = tasks.register<Sync>("copyMrResourcesForOhosEntry") {
    dependsOn(":shared:mergeOhosResources")
    from(project(":shared").layout.buildDirectory.dir("generated/tmm-res/ohosArm64Main/mergeRes"))
    into(layout.projectDirectory.dir("ohosApp/entry/mr-res"))
    includeEmptyDirs = false
    eachFile {
        // Only locale qualifier directories (zh-CN, en-US, sr-Latn-RS...)
        // are rewritten; hyphenated asset filenames pass through untouched.
        val localeDir = Regex("^[a-z]{2,3}(-[A-Za-z]{2,8})+${'$'}")
        relativePath = RelativePath(
            true,
            *relativePath.segments.map {
                if (localeDir.matches(it)) it.replace('-', '_') else it
            }.toTypedArray()
        )
    }
    preserve { include(".gitkeep") }
}
project(":shared").tasks.matching { it.name == "mergeOhosResources" }.configureEach {
    finalizedBy(copyMrResourcesForOhosEntry)
}

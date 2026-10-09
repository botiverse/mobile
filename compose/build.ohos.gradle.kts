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
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.4").apply(false)
    id("com.tencent.kuiklybase.knoi.plugin").version("0.0.4").apply(false)
    // Syncs libpbcurlwrapper/libopenssl/libc++_shared runtime .so files for the
    // NetworkKMM OHOS transport (task #6): ./gradlew copyNetworkOhosRuntimeLibs
    id("com.tencent.kuiklybase.network.ohos-runtime").version("0.1.0-raft.41-ohos")
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

// KmmResource generated MR resources (task #19, incident task #44):
// generateMRohosArm64Main writes this app's slock_* string/color/plural JSONs
// into res, but nothing shipped them into the HAP — the
// entry target's resource.directories references ./mr-res, synced here.
// Hung off generateMRohosArm64Main itself (the Kuikly host flow invokes that
// generator directly), so every DevEco/Hvigor build refreshes the staging
// roots without depending on the standalone mergeOhosResources task, which is
// not initialized in this host build.
// restool rejects the generator's BCP-47 locale dirs ("Invalid qualifier
// key 'zh-CN'"), so locale segments are rewritten to qualifier form.
val copyMrResourcesForOhosEntry = tasks.register<Sync>("copyMrResourcesForOhosEntry") {
    dependsOn(":shared:generateMRohosArm64Main")
    from(project(":shared").layout.buildDirectory.dir("generated/tmm-res/ohosArm64Main/res"))
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

// Kuikly's OHOS assets:// loader resolves against the HAP resfile root, while
// KmmResource intentionally generates MR assets under rawfile/assets. Mirror
// every generated MR asset into a separate resource root so the MR locator can
// be consumed by Kuikly without changing either subsystem's global contract.
val copyMrKuiklyAssetsForOhosEntry = tasks.register<Sync>("copyMrKuiklyAssetsForOhosEntry") {
    dependsOn(":shared:generateMRohosArm64Main")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(layout.projectDirectory.dir("ohosApp/entry/mr-kuikly-assets"))
    from(
        project(":shared")
            .layout
            .buildDirectory
            .dir("generated/tmm-res/ohosArm64Main/res/rawfile/assets")
    ) {
        into("resfile/assets")
    }
    includeEmptyDirs = false
    preserve { include(".gitkeep") }
}
project(":shared").tasks.matching { it.name == "generateMRohosArm64Main" }.configureEach {
    finalizedBy(copyMrResourcesForOhosEntry, copyMrKuiklyAssetsForOhosEntry)
}

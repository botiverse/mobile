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

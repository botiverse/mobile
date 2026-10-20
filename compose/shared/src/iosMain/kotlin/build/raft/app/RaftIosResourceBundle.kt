package build.raft.app

import com.tencent.tmm.kmmresource.resource.utils.isBundleSearchLogEnabled
import com.tencent.tmm.kmmresource.resource.utils.isDemoTest
import com.tencent.tmm.kmmresource.resource.utils.podName
import platform.Foundation.NSBundle

/**
 * task #423: make the moko/TMM kmmresource `MR` bundle resolvable on iOS.
 *
 * On iOS the generated `MR` object resolves its packed resource bundle via
 * `NSBundle.loadableBundle("build.raft.app.resources.MR")`. Both that call's
 * primary lookup and its fallback locate the bundle *relative to*
 * `<podName>.bundle` in the app (see kmmresource `NSBundleExt.kt`): if
 * `<podName>.bundle` is absent, `kmmBundle` is null and the whole thing throws
 * `IllegalArgumentException`, which Kotlin/Native surfaces as
 * `FileFailedToInitializeException` the first time any `MR.*` is touched
 * (reaction sprite atlas + every `MR.strings` key). The default `podName` is
 * "QLMM"; our CocoaPods framework/pod is "shared", so we must point it there —
 * mirrors the plugin's own reference app, which sets `podName = "shared_template"`.
 *
 * We set two knobs to be robust to whichever layout the build's copy step
 * produces:
 *  - `podName = "shared"` — if the MR bundle is nested inside `shared.bundle`,
 *    the loader's primary `getBundleWithIdentifier` path finds it there.
 *  - `isDemoTest = true` — if the MR bundle is copied flat into the `.app`
 *    (what `copyFrameworkResourcesToApp` does), the fallback `loadBundle`
 *    enumerates the whole app bundle and matches by `bundleIdentifier`
 *    ("build.raft.app.resources.MR"), independent of the bundle's name/nesting.
 *
 * This must be called once, before any `MR.*` access, from the iOS app entry
 * point (see iOSApp.swift). It also enables the loader's bundle-search log and
 * prints the `.bundle` files actually present in the app, so a visual-capture
 * console log confirms the runtime layout (and reveals the correct settings if
 * this hypothesis is wrong).
 */
fun raftConfigureIosResourceBundle() {
    podName = "shared"
    isDemoTest = true
    isBundleSearchLogEnabled = true

    val bundles = NSBundle.mainBundle.pathsForResourcesOfType("bundle", null)
    println("SLOCK_MR_DIAG podName=shared isDemoTest=true mainBundle.bundles=$bundles")
}

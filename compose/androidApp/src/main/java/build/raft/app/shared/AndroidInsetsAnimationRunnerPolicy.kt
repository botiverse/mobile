package build.raft.app.shared

/**
 * Selects the narrowly scoped Android 12 workaround for the ALT-AL10 BLAST teardown race.
 *
 * Android 12 offloads system-driven Insets animations when the view tree has no animation
 * callback. That runner cancels asynchronously, so an already queued frame can outlive ViewRoot
 * surface teardown. The affected Huawei build has crashed in that exact runner while releasing a
 * BLAST buffer. Installing a callback keeps Insets animation control on the main thread, where
 * teardown cancellation is synchronous.
 */
internal fun shouldUseMainThreadInsetsAnimationRunner(
    manufacturer: String,
    model: String,
    sdkInt: Int,
): Boolean =
    manufacturer == HUAWEI_MANUFACTURER &&
        model == HUAWEI_ALT_AL10_MODEL &&
        sdkInt in ANDROID_12_SDK_INT..ANDROID_12L_SDK_INT

private const val HUAWEI_MANUFACTURER = "HUAWEI"
private const val HUAWEI_ALT_AL10_MODEL = "ALT-AL10"
private const val ANDROID_12_SDK_INT = 31
private const val ANDROID_12L_SDK_INT = 32

package build.raft.app.shared

import androidx.lifecycle.Lifecycle

/**
 * A host Activity that gets a new launch Intent rebuilds itself on it (`recreate()`). Android
 * delivers `onNewIntent` to an Activity that is paused or stopped, never resumed: a server's
 * window that already exists in the background (Multiple app windows: the switch reuses its
 * document task, `documentLaunchMode="intoExisting"`) is stopped when the switch brings its task
 * to the front. Recreated right there, while the task is still coming forward, the rebuilt
 * Activity could stay below resumed with its window never shown: the page composed but the
 * screen stayed white until the person sent the app to the background and back (Hands f0be77a8,
 * HUAWEI CMU-AL10, Android 16). So the rebuild waits for the resume that follows every new
 * Intent and runs from a resumed, visible Activity, the state `recreate()` is made for.
 */
internal fun raftHostRecreatesOnNewIntentNow(state: Lifecycle.State): Boolean =
    state.isAtLeast(Lifecycle.State.RESUMED)

/** Which way the shell window got to its first frame, for [raftNewWindowFirstFrameLine]. */
internal enum class RaftNewWindowPath(val wireName: String) {
    /** A new Activity (a new document task). */
    Created("created"),

    /** An existing window of that server, rebuilt on the new Intent. */
    Reused("reused"),
}

/**
 * The diagnostics line for the first drawn frame of a shell window that a server switch opened:
 * how long after the tap ([tapEpochMillis], wall clock, from the page data) the window first drew.
 * Null without a tap (any other window or page) or for a clock that went backwards.
 */
internal fun raftNewWindowFirstFrameLine(
    tapEpochMillis: Long,
    nowEpochMillis: Long,
    path: RaftNewWindowPath,
    page: String,
): String? {
    if (tapEpochMillis <= 0L) return null
    val elapsed = nowEpochMillis - tapEpochMillis
    if (elapsed < 0L) return null
    return "app_shell_new_window_first_frame ms=$elapsed path=${path.wireName} page=$page"
}

/**
 * The host window's colour before the page draws: the cream splash colour only behind the light
 * Home cold-start splash; otherwise the canvas the page paints (light or dark, the server theme's).
 */
internal fun raftHostWindowBackgroundArgb(
    dark: Boolean,
    homeSplash: Boolean,
    canvasArgb: Int,
    splashArgb: Int,
): Int = if (!dark && homeSplash) splashArgb else canvasArgb

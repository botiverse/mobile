package build.raft.app

import android.os.SystemClock
import android.util.Log

/**
 * Android sink of [RaftDraftTimingMarker]: one synchronous logcat line per event (not the
 * diagnostics queue), stamped with `SystemClock.uptimeMillis()` so the acceptance can compare it
 * with ActivityManager's kill line on the same device clock.
 */
object RaftDraftTimingLogcat {
    const val TAG = "RaftDraftTiming"

    /** Call from Application.onCreate; installs nothing unless both flags are true. */
    fun install(buildConfigDebug: Boolean, appDebuggable: Boolean): Boolean =
        RaftDraftTimingMarker.installIfEnabled(buildConfigDebug, appDebuggable) {
            { line -> Log.i(TAG, "$line uptimeMs=${SystemClock.uptimeMillis()}") }
        }
}

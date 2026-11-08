package build.raft.app.shared

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Records which build each process ran, in the process's own exit record.
 *
 * `ApplicationExitInfo` carries no app version, so every process writes its build into
 * [ActivityManager.setProcessStateSummary] at start ([stampProcess]); the system keeps it in
 * the exit record, and the next launch reads the exact build that exited ([decodeStamp]).
 *
 * Readers: the Hands SDK's ANR reporter (0.13.1+ accepts this `raft-build` format; its own
 * rewrite to `hands-build` is turned off in RaftApplication via
 * `HandsCrash.setAnrBuildStampingEnabled(false)`) and AndroidSystemKillReporter. Keep the
 * format stable: exits stamped by older builds are still read with it.
 */
internal object RaftBuildStamp {
    private const val TAG = "RaftBuildStamp"

    /** ActivityManager.setProcessStateSummary rejects more than 128 bytes. */
    internal const val MAX_STAMP_BYTES = 128
    private const val STAMP_PREFIX = "raft-build:1\n"

    internal data class StampedBuild(val versionName: String, val versionCode: Long)

    /** `raft-build:1\n<versionCode>\n<versionName>`, the name cut to fit [MAX_STAMP_BYTES]. */
    internal fun encodeStamp(versionName: String, versionCode: Long): ByteArray {
        val head = "$STAMP_PREFIX$versionCode\n".toByteArray(Charsets.UTF_8)
        var name = versionName.trim()
        while (head.size + name.toByteArray(Charsets.UTF_8).size > MAX_STAMP_BYTES) {
            name = name.dropLast(1)
        }
        return head + name.toByteArray(Charsets.UTF_8)
    }

    /** The build in an exit record's summary, or null when it was not written by [encodeStamp]. */
    internal fun decodeStamp(summary: ByteArray?): StampedBuild? {
        if (summary == null || summary.size > MAX_STAMP_BYTES) return null
        val text = summary.toString(Charsets.UTF_8)
        if (!text.startsWith(STAMP_PREFIX)) return null
        val lines = text.removePrefix(STAMP_PREFIX).split('\n', limit = 2)
        if (lines.size != 2) return null
        val code = lines[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val name = lines[1].trim().takeIf(String::isNotEmpty) ?: return null
        return StampedBuild(name, code)
    }

    /** version_name/version_code for one exit's ticket; empty when the exit carries no stamp. */
    internal fun versionExtras(build: StampedBuild?): Map<String, Any> = buildMap {
        build?.let {
            put("version_name", it.versionName)
            put("version_code", it.versionCode)
        }
    }

    /** Record this process's build in its future exit record. Call early in every process. */
    fun stampProcess(context: Context, versionName: String, versionCode: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.setProcessStateSummary(encodeStamp(versionName, versionCode))
        }.onFailure { Log.w(TAG, "Could not stamp process build", it) }
    }
}

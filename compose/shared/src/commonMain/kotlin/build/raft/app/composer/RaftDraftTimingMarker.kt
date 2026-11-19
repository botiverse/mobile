package build.raft.app

import kotlin.concurrent.Volatile

/**
 * QA timing marker for composer drafts (task #246): the draft-kill acceptance
 * (`scripts/qa/android_draft_kill_timing.mjs`) measures, on the device's own clock, how long
 * after the composer took the last typed text the process was killed, and whether that text was
 * already written to the durable draft.
 *
 * Lines carry a sequence number and the text length only, never text. Nothing is emitted unless
 * a platform installs a sink: Android installs one in debuggable builds (debug, Alpha) only, and
 * writes each line synchronously to logcat (tag `RaftDraftTiming`) with `SystemClock.uptimeMillis()`.
 * Release, Play and iOS builds install none.
 */
object RaftDraftTimingMarker {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** The installed sink, or null (no lines); read by [SharedThreadComposerDraftPersistSlice]. */
    internal val installedSink: ((String) -> Unit)?
        get() = sink

    /**
     * Installs the sink [sinkFactory] makes when [buildConfigDebug] and [appDebuggable] are both
     * true, and removes any sink otherwise. Returns whether a sink is installed.
     */
    fun installIfEnabled(
        buildConfigDebug: Boolean,
        appDebuggable: Boolean,
        sinkFactory: () -> (String) -> Unit,
    ): Boolean {
        sink = if (raftDraftTimingMarkerEnabled(buildConfigDebug, appDebuggable)) sinkFactory() else null
        return sink != null
    }
}

/** Debuggable builds only: the build type is debuggable and so is the installed app. */
fun raftDraftTimingMarkerEnabled(buildConfigDebug: Boolean, appDebuggable: Boolean): Boolean =
    buildConfigDebug && appDebuggable

/**
 * The durable draft write of a typed change as a timing line (task #246):
 * `draft_written seq=<change seq> len=<text length> path=<background|flush> costUs=<µs>`,
 * [seq] being the `change_received` seq of the newest change the write carries. [path] says which
 * write made it durable: `background` is the interval write (at once after a quiet period, then at
 * most every 40 ms while typing: `DefaultChangeWriteIntervalMillis`), `flush` a synchronous flush
 * (backgrounding, Home). costUs is how long that write took on
 * its own thread, from the moment it started running (lock waits included, queueing on the
 * dispatcher before it ran not included). Never the text.
 */
internal fun raftDraftWrittenLine(seq: Long, textLength: Int, path: String, cost: kotlin.time.Duration): String =
    "draft_written seq=$seq len=$textLength path=$path costUs=${cost.inWholeMicroseconds}"

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
 * The crash journal's input-thread write cost as a timing line (task #246):
 * `draft_journal_written path=sync costUs=<µs> len=<text length>`. Never the text.
 */
internal fun raftDraftJournalCostLine(cost: kotlin.time.Duration, textLength: Int): String =
    "draft_journal_written path=sync costUs=${cost.inWholeMicroseconds} len=$textLength"

package build.raft.app.shared

import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/*
 * Crash diagnostics for the emulator composer tests (#310). RaftComposerFirstCharacterBaselineTest
 * and ThreadComposerTextSelectionConnectedTest crash now and then with "LayoutNode should be
 * attached to an owner" in a draw-time measure, once with a suppressed "Detected multithreaded
 * access to SnapshotStateObserver: previousThreadId=...". These helpers name the thread behind
 * such an id and every thread that wrote snapshot state off main. Test-only and self-contained:
 * once the crash is understood, delete this file and the rule, @Before and @After that use it.
 */

private const val TAG = "RaftThreadDiagnostics"
private const val FRAMES_PER_THREAD = 20
private const val STATUS_KEY = "raftThreadDump"

// Instrumentation output per failure, in chars. Each block is its own sendStatus: a status the
// binder rejects makes Instrumentation drop its watcher, and every later test result with it.
private const val STATUS_CHAR_BUDGET = 120_000

/**
 * When a test fails, dumps [offMainWrites]' first off-main snapshot writes and every live thread
 * (id, name, state, top [FRAMES_PER_THREAD] frames) to logcat (tag [TAG]) and to the
 * instrumentation output, which is all CI keeps for these tests.
 *
 * The ids are Thread.getId(), the ids Compose prints as `previousThreadId=` and
 * `currentThread={id=...}`. Make it the outermost rule, so that a failure the compose rule raises
 * while tearing down is dumped too.
 */
internal class RaftThreadDumpOnFailure(
    private val offMainWrites: RaftOffMainSnapshotWriteLog,
) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        // A diagnostic must not add a failure of its own to the test's.
        try {
            dump(e, description)
        } catch (t: Throwable) {
            Log.w(TAG, "thread dump failed", t)
        }
    }

    private fun dump(e: Throwable, description: Description) {
        val writes = offMainWrites.firstWrites()
        val threads = Thread.getAllStackTraces().entries.sortedBy { (thread, _) -> thread.raftThreadId() }
        val blocks = buildList {
            add(
                "THREAD_DUMP ${description.displayName} failed with ${e.javaClass.name}: " +
                    "${writes.size} threads wrote snapshot state off main, ${threads.size} threads live"
            )
            addAll(writes)
            for ((thread, frames) in threads) {
                add(
                    "thread id=${thread.raftThreadId()} name=\"${thread.name}\" state=${thread.state}" +
                        frames.asList().raftTopFrames()
                )
            }
        }
        blocks.forEach { block -> Log.w(TAG, block) }
        var budget = STATUS_CHAR_BUDGET
        for (block in blocks) {
            if (block.length > budget) {
                sendStatus("THREAD_DUMP truncated; the whole dump is in logcat, tag $TAG")
                return
            }
            sendStatus(block)
            budget -= block.length
        }
    }

    private fun sendStatus(text: String) {
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString(STATUS_KEY, text) })
    }
}

/**
 * Logs the first global-snapshot write of each thread other than main, with the writer's stack.
 * Snapshot state the UI reads is written on main (AGENTS.md "Main-thread-only APIs"); a thread
 * that writes it while main measures is the first suspect. [RaftThreadDumpOnFailure] repeats the
 * entries in its dump.
 *
 * The observer runs on the writing thread inside the snapshot lock, so until a thread's first
 * write it only checks which thread it is on. [register] in `@Before`, [dispose] in `@After`.
 */
internal class RaftOffMainSnapshotWriteLog {
    private val firstWriteByThread = ConcurrentHashMap<Long, String>()
    private var handle: ObserverHandle? = null

    fun register() {
        firstWriteByThread.clear()
        handle = Snapshot.registerGlobalWriteObserver(::onWrite)
    }

    fun dispose() {
        handle?.dispose()
        handle = null
    }

    /** The first off-main write of each thread since [register]; still there after [dispose]. */
    fun firstWrites(): List<String> = firstWriteByThread.values.toList()

    private fun onWrite(state: Any) {
        if (Looper.getMainLooper().isCurrentThread) return
        val thread = Thread.currentThread()
        val threadId = thread.raftThreadId()
        if (firstWriteByThread.containsKey(threadId)) return
        // Start at the writer: skip this observer and the snapshot system's write path.
        val frames =
            Throwable().stackTrace.asList().dropWhile { frame ->
                frame.className.startsWith(OBSERVER_CLASS) || frame.className.startsWith(SNAPSHOT_PACKAGE)
            }
        val entry =
            "OFF_MAIN_SNAPSHOT_WRITE thread id=$threadId name=\"${thread.name}\" " +
                "state=${state.javaClass.name}" + frames.raftTopFrames()
        if (firstWriteByThread.putIfAbsent(threadId, entry) == null) Log.w(TAG, entry)
    }

    private companion object {
        val OBSERVER_CLASS: String = RaftOffMainSnapshotWriteLog::class.java.name
        const val SNAPSHOT_PACKAGE = "androidx.compose.runtime.snapshots."
    }
}

// Thread.getId() is what Compose's SnapshotStateObserver prints; Thread.threadId() is API 36+.
@Suppress("DEPRECATION")
private fun Thread.raftThreadId(): Long = id

private fun List<StackTraceElement>.raftTopFrames(): String =
    buildString {
        for (frame in this@raftTopFrames.take(FRAMES_PER_THREAD)) append("\n    at ").append(frame)
        if (this@raftTopFrames.size > FRAMES_PER_THREAD) {
            append("\n    ... ").append(this@raftTopFrames.size - FRAMES_PER_THREAD).append(" more")
        }
    }

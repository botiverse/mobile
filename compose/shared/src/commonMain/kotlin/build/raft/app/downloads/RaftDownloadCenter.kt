package build.raft.app.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Lifecycle of one download-centre entry. */
@Serializable
enum class RaftDownloadStatus {
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled;

    val isActive: Boolean get() = this == Downloading || this == Paused
}

/** Why a [RaftDownloadStatus.Failed] entry failed, for localized copy. */
@Serializable
enum class RaftDownloadErrorKind {
    /** The host reported a failure; [RaftDownloadEntry.errorMessage] may carry its words. */
    Failed,

    /** The app process ended while the download was running. */
    Interrupted
}

/**
 * One row of the download centre. Identity only plus transfer facts; no URL or token is ever
 * stored. [ownerKey] is the attachment principal (environment + account + server) the download
 * belongs to: the centre only shows the current principal's rows.
 */
@Serializable
data class RaftDownloadEntry(
    val id: String,
    val ownerKey: String,
    val attachmentId: String,
    val fileName: String,
    val mimeType: String? = null,
    val status: RaftDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    /** Bytes are being copied into the public Downloads folder after the transfer. */
    val saving: Boolean = false,
    val errorKind: RaftDownloadErrorKind? = null,
    val errorMessage: String? = null,
    /** Host location of the finished file (Android `content://` URI in Downloads), if known. */
    val localUri: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    /** 0..100 when the total is known, else null. */
    val percent: Int?
        get() {
            if (status == RaftDownloadStatus.Completed) return 100
            val total = totalBytes?.takeIf { it > 0L } ?: return null
            return ((downloadedBytes.coerceIn(0L, total) * 100L) / total).toInt()
        }
}

/** What a [RaftDownloadTask] is downloading (identity only). */
data class RaftDownloadDescriptor(
    val id: String,
    val ownerKey: String,
    val attachmentId: String,
    val fileName: String,
    val mimeType: String? = null,
    val totalBytes: Long? = null
)

/** One progress sample reported by a running [RaftDownloadTask]. */
data class RaftDownloadProgressUpdate(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long? = null,
    val paused: Boolean = false,
    val saving: Boolean = false
)

/** Terminal result of one run of a [RaftDownloadTask]. [hostStatus] is the host's raw status word. */
sealed interface RaftDownloadOutcome {
    val hostStatus: String

    /** [localUri]: where the host put the file (Android: the public Downloads content URI), when it says. */
    data class Completed(
        override val hostStatus: String = "saved",
        val localUri: String? = null,
        val sizeBytes: Long? = null
    ) : RaftDownloadOutcome

    data class Cancelled(override val hostStatus: String = "cancelled") : RaftDownloadOutcome

    data class Failed(override val hostStatus: String, val message: String? = null) : RaftDownloadOutcome
}

/** Host control actions forwarded to a running task. */
enum class RaftDownloadControl { Pause, Resume, Cancel }

/**
 * The transfer itself. [run] may be called again for a retry; it must re-resolve whatever
 * short-lived authority it needs. [control] must return quickly and never suspend.
 */
interface RaftDownloadTask {
    suspend fun run(attempt: Int, onProgress: (RaftDownloadProgressUpdate) -> Unit): RaftDownloadOutcome

    fun control(action: RaftDownloadControl) {}
}

data class RaftDownloadCenterState(
    /** Newest first. */
    val entries: List<RaftDownloadEntry> = emptyList()
) {
    fun forOwner(ownerKey: String?): List<RaftDownloadEntry> =
        if (ownerKey.isNullOrBlank()) emptyList() else entries.filter { it.ownerKey == ownerKey }
}

/**
 * App-scoped owner of attachment downloads (the download centre).
 *
 * A download started here runs in [scope], which outlives any page: leaving the file preview
 * no longer cancels it. Pages and the Settings › Storage centre only read [state] (or the
 * per-entry [entry] selector) and send intents ([start], [cancel], [pause], [resume], [retry],
 * [delete], [clearFinished]).
 *
 * - Single flight per entry id: [start] while the same id is active returns the running result.
 * - Started and terminal transitions are persisted through [RaftDownloadHistoryRepository];
 *   progress samples are not. An entry that was active when the process died loads as
 *   [RaftDownloadStatus.Failed] / [RaftDownloadErrorKind.Interrupted].
 * - Main-thread confined: call it from the UI thread; tasks report progress there too.
 * - Retry needs the task of this process ([canRetry]); a row restored from disk can only be
 *   deleted or downloaded again from its message.
 */
class RaftDownloadCenter internal constructor(
    private val scope: CoroutineScope,
    private val repository: RaftDownloadHistoryRepository,
    private val nowMillis: () -> Long
) {
    private val running = mutableMapOf<String, Running>()
    private val tasks = mutableMapOf<String, Pair<RaftDownloadDescriptor, RaftDownloadTask>>()
    private val attempts = mutableMapOf<String, Int>()
    private val _state = MutableStateFlow(RaftDownloadCenterState(repository.load().map(::restored)))

    val state: StateFlow<RaftDownloadCenterState> = _state.asStateFlow()

    /** Selector for one entry: emits only when that entry changes. */
    fun entry(id: String): Flow<RaftDownloadEntry?> =
        state.map { snapshot -> snapshot.entries.firstOrNull { it.id == id } }.distinctUntilChanged()

    /**
     * Selector for the newest row of one attachment of one principal (a message card shows the
     * progress of a download started on the file page); emits only when that row changes.
     */
    fun attachmentEntry(ownerKey: String, attachmentId: String): Flow<RaftDownloadEntry?> =
        state
            .map { snapshot -> snapshot.entries.firstOrNull { it.ownerKey == ownerKey && it.attachmentId == attachmentId } }
            .distinctUntilChanged()

    /** Selector for one principal's rows. */
    fun entries(ownerKey: String?): Flow<List<RaftDownloadEntry>> =
        state.map { it.forOwner(ownerKey) }.distinctUntilChanged()

    fun isActive(id: String): Boolean = running.containsKey(id)

    fun canRetry(id: String): Boolean = tasks.containsKey(id) && !running.containsKey(id)

    /** Starts [task] for [descriptor], or joins the run already active for the same id. */
    fun start(descriptor: RaftDownloadDescriptor, task: RaftDownloadTask): Deferred<RaftDownloadOutcome> {
        running[descriptor.id]?.let { return it.result }
        tasks[descriptor.id] = descriptor to task
        return launch(descriptor, task)
    }

    fun retry(id: String): Deferred<RaftDownloadOutcome>? {
        if (running.containsKey(id)) return running[id]?.result
        val (descriptor, task) = tasks[id] ?: return null
        return launch(descriptor, task)
    }

    fun pause(id: String) {
        val active = running[id] ?: return
        if (current(id)?.status != RaftDownloadStatus.Downloading) return
        active.task.control(RaftDownloadControl.Pause)
        patch(id) { it.copy(status = RaftDownloadStatus.Paused, bytesPerSecond = null, updatedAtMillis = nowMillis()) }
    }

    fun resume(id: String) {
        val active = running[id] ?: return
        if (current(id)?.status != RaftDownloadStatus.Paused) return
        active.task.control(RaftDownloadControl.Resume)
        patch(id) { it.copy(status = RaftDownloadStatus.Downloading, updatedAtMillis = nowMillis()) }
    }

    fun cancel(id: String) {
        val active = running[id] ?: return
        active.task.control(RaftDownloadControl.Cancel)
        active.job.cancel(CancellationException("download cancelled"))
    }

    /** Removes the row; an active download is cancelled first. */
    fun delete(id: String) {
        cancel(id)
        tasks.remove(id)
        attempts.remove(id)
        update { entries -> entries.filterNot { it.id == id } }
        persist()
    }

    /** Removes every finished row (completed, failed, cancelled) of [ownerKey]. */
    fun clearFinished(ownerKey: String) {
        val removed = _state.value.entries.filter { it.ownerKey == ownerKey && !it.status.isActive }.map { it.id }.toSet()
        if (removed.isEmpty()) return
        removed.forEach { tasks.remove(it); attempts.remove(it) }
        update { entries -> entries.filterNot { it.id in removed } }
        persist()
    }

    private fun launch(descriptor: RaftDownloadDescriptor, task: RaftDownloadTask): Deferred<RaftDownloadOutcome> {
        val attempt = (attempts[descriptor.id] ?: 0) + 1
        attempts[descriptor.id] = attempt
        val now = nowMillis()
        val previous = current(descriptor.id)
        val entry =
            RaftDownloadEntry(
                id = descriptor.id,
                ownerKey = descriptor.ownerKey,
                attachmentId = descriptor.attachmentId,
                fileName = descriptor.fileName,
                mimeType = descriptor.mimeType,
                status = RaftDownloadStatus.Downloading,
                totalBytes = descriptor.totalBytes ?: previous?.totalBytes,
                createdAtMillis = previous?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
        update { entries -> listOf(entry) + entries.filterNot { it.id == descriptor.id } }
        persist()
        val result = CompletableDeferred<RaftDownloadOutcome>()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val outcome =
                    try {
                        task.run(attempt) { progress -> onProgress(descriptor.id, attempt, progress) }
                    } catch (cancelled: CancellationException) {
                        RaftDownloadOutcome.Cancelled()
                    } catch (error: Throwable) {
                        RaftDownloadOutcome.Failed(hostStatus = "failed", message = error.message)
                    }
                finish(descriptor.id, attempt, outcome)
                result.complete(outcome)
            }
        running[descriptor.id] = Running(attempt, task, job, result)
        job.invokeOnCompletion { cause ->
            if (cause != null && !result.isCompleted) {
                // Cancelled before the body ran (or while it unwound): still settle the row.
                finish(descriptor.id, attempt, RaftDownloadOutcome.Cancelled())
                result.complete(RaftDownloadOutcome.Cancelled())
            }
        }
        job.start()
        return result
    }

    private fun onProgress(id: String, attempt: Int, progress: RaftDownloadProgressUpdate) {
        if (running[id]?.attempt != attempt) return
        patch(id) { entry ->
            if (!entry.status.isActive) return@patch entry
            entry.copy(
                status = if (progress.paused) RaftDownloadStatus.Paused else entry.status,
                downloadedBytes = progress.downloadedBytes.coerceAtLeast(0L),
                totalBytes = progress.totalBytes?.takeIf { it > 0L } ?: entry.totalBytes,
                bytesPerSecond = progress.bytesPerSecond,
                saving = progress.saving,
                updatedAtMillis = nowMillis()
            )
        }
    }

    private fun finish(id: String, attempt: Int, outcome: RaftDownloadOutcome) {
        val active = running[id]
        if (active == null || active.attempt != attempt) return
        running.remove(id)
        patch(id) { entry ->
            when (outcome) {
                is RaftDownloadOutcome.Completed ->
                    entry.copy(
                        status = RaftDownloadStatus.Completed,
                        totalBytes = outcome.sizeBytes?.takeIf { it > 0L } ?: entry.totalBytes,
                        downloadedBytes = outcome.sizeBytes?.takeIf { it > 0L } ?: entry.totalBytes ?: entry.downloadedBytes,
                        localUri = outcome.localUri ?: entry.localUri,
                        bytesPerSecond = null,
                        saving = false,
                        errorKind = null,
                        errorMessage = null,
                        updatedAtMillis = nowMillis()
                    )
                is RaftDownloadOutcome.Cancelled ->
                    entry.copy(
                        status = RaftDownloadStatus.Cancelled,
                        bytesPerSecond = null,
                        saving = false,
                        updatedAtMillis = nowMillis()
                    )
                is RaftDownloadOutcome.Failed ->
                    entry.copy(
                        status = RaftDownloadStatus.Failed,
                        bytesPerSecond = null,
                        saving = false,
                        errorKind = RaftDownloadErrorKind.Failed,
                        errorMessage = outcome.message?.takeIf { it.isNotBlank() },
                        updatedAtMillis = nowMillis()
                    )
            }
        }
        persist()
    }

    private fun current(id: String): RaftDownloadEntry? = _state.value.entries.firstOrNull { it.id == id }

    private fun patch(id: String, transform: (RaftDownloadEntry) -> RaftDownloadEntry) {
        update { entries ->
            entries.map { entry -> if (entry.id == id) transform(entry) else entry }
        }
    }

    private inline fun update(transform: (List<RaftDownloadEntry>) -> List<RaftDownloadEntry>) {
        val previous = _state.value
        val next = transform(previous.entries)
        if (next != previous.entries) _state.value = previous.copy(entries = next)
    }

    private fun persist() {
        repository.save(_state.value.entries)
    }

    private fun restored(entry: RaftDownloadEntry): RaftDownloadEntry =
        if (entry.status.isActive) {
            entry.copy(
                status = RaftDownloadStatus.Failed,
                bytesPerSecond = null,
                saving = false,
                errorKind = RaftDownloadErrorKind.Interrupted,
                errorMessage = null
            )
        } else {
            entry
        }

    private class Running(
        val attempt: Int,
        val task: RaftDownloadTask,
        val job: Job,
        val result: CompletableDeferred<RaftDownloadOutcome>
    )
}

/** Stable centre row id of one attachment for one principal: downloading it again reuses the row. */
fun raftDownloadCenterEntryId(ownerKey: String, attachmentId: String, fileName: String): String =
    "${ownerKey.take(16)}:$attachmentId:$fileName"

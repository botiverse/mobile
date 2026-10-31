package build.raft.app

import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.MviDispatchReceipt
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviPersistSliceTransitionSnapshot
import build.raft.app.data.MviStoreRuntime
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.InboxItemKind
import build.raft.app.model.TaskSummary
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock

internal data class SharedThreadComposerDraftState(
    val instanceId: String = "",
    val draftKey: String? = null,
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    /** Session-only image policy; deliberately excluded from persisted drafts. */
    val originalImage: Boolean = false,
    val syncNonce: Int = 0,
    val editorCommandNonce: Int = 0,
    val editorCommand: ThreadComposerEditorCommand? = null,
    val persistBoundaryNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val sending: Boolean = false,
    val failureLabel: String? = null,
    val removedAttachmentIds: Set<String> = emptySet(),
    val uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    /**
     * Process-local ownership witnesses for local attachment rows.  These are deliberately
     * state-only (never persisted): a generation is minted every time a local row is admitted,
     * so a remove -> re-add of the same id cannot be mistaken for the old upload attempt.
     */
    val nextAttachmentAdmissionGeneration: Long = 0L,
    val attachmentAdmissionsByLocalId: Map<String, SharedThreadComposerAttachmentAdmission> = emptyMap(),
    val externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList(),
    val pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList(),
    val mentionActionNoticeTitle: String? = null,
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: Set<String> = emptySet(),
    val executingMentionActionIds: Set<String> = emptySet(),
    val executingMentionActionStates: Map<String, String> = emptyMap(),
    /**
     * Session-only: the persist slice's account epoch this composer was started under.  A
     * composer started for one signed-in account never writes its text after that account
     * signed out or another one signed in (see [SharedThreadComposerDraftPersistSlice]).
     */
    val accountEpoch: Long = 0L
)

/**
 * Exact identity of one local attachment admission.  The local id alone is not an ownership
 * boundary: picker retries and editor replacements can reuse it while changing the underlying
 * file.  Bind the monotonic generation to the platform handle, source, and exact replacement
 * slot so a stale or wrong-row result cannot publish into a successor row.
 */
internal data class SharedThreadComposerAttachmentAdmission(
    val localAttachmentId: String,
    val generation: Long,
    val fileRef: String?,
    val source: String?,
    /** Exact local placeholder or pre-existing remote row targeted by this upload. */
    val replacementAttachmentId: String = ""
)

internal data class SharedThreadComposerPersistedDraft(
    val text: String = "",
    val asTask: Boolean = false,
    val uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    val externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList()
)

@Serializable
internal data class SharedThreadComposerExternalShareSource(
    val token: String,
    val accountId: String,
    val serverId: String
)

internal data class SharedThreadComposerDraftRecord(
    val key: String,
    val sendChannelId: String,
    val parentMessageId: String? = null,
    val mentionChannelId: String? = null
) {
    val isRootComposer: Boolean
        get() = parentMessageId.isNullOrBlank()

    fun matchesRootConversation(conversationId: String): Boolean =
        isRootComposer && sendChannelId == conversationId

    fun matchesReplyThread(parentMessageId: String): Boolean =
        !isRootComposer && this.parentMessageId == parentMessageId

    fun matchesReplyThreadIdentity(other: SharedThreadComposerDraftRecord): Boolean =
        if (isRootComposer || other.isRootComposer) {
            false
        } else {
            val parent = parentMessageId?.takeIf { it.isNotBlank() }
            val otherParent = other.parentMessageId?.takeIf { it.isNotBlank() }
            val mention = mentionChannelId?.takeIf { it.isNotBlank() }
            val otherMention = other.mentionChannelId?.takeIf { it.isNotBlank() }
            parent != null && parent == otherParent && mention != null && mention == otherMention
        }
}

/** One active draft as the Drafts list reads it: where it was typed, its text and its last edit. */
internal data class SharedThreadComposerDraftListing(
    val record: SharedThreadComposerDraftRecord,
    val text: String,
    /** Null for a draft saved before edit times were kept. */
    val editedAtEpochMillis: Long?
)

/** Exact key/value image used to undo a multi-key draft transition. */
internal data class SharedThreadComposerDraftStorageSnapshot(
    val values: Map<String, String?>
)

/** Post-write image used to make a non-transactional rollback compare-and-swap safe. */
private data class SharedThreadComposerDraftNonTransactionalMutation(
    val before: SharedThreadComposerDraftStorageSnapshot,
    val after: SharedThreadComposerDraftStorageSnapshot,
    val key: String
)

internal class SharedThreadComposerDraftPersistenceTransaction(
    internal val owner: SharedThreadComposerDraftPersistence,
    val durable: SharedThreadComposerDraftStorageSnapshot
) : MviPersistSliceTransitionSnapshot {
    /**
     * A transition can replace a row more than once (for example a fallback migration followed
     * by a send reset).  Keep one retirement bucket per draft key so finalize cannot invoke the
     * native seam repeatedly for the same path.  The public image is sorted below; persistence
     * callbacks therefore observe deterministic key/ref ordering regardless of reducer order.
     */
    private val deferredRetirementsByKey = mutableMapOf<String, MutableSet<String>>()
    private val potentiallyMissingRefs = linkedSetOf<String>()
    private var prepared = false
    /**
     * Irreversible-finalization fence.  This is distinct from [finalized]: a finalizer can move
     * one or more native files and then throw, so a failed finalizer is still an unknown outcome
     * and must never make a later rollback restore the old file references.
     */
    private var finalizeAttempted = false
    private var finalized = false
    private var completed = false
    private var rolledBack = false

    fun deferRetirement(key: String, refs: Set<String>) {
        val normalizedKey = key.trim()
        val normalizedRefs = refs.map(String::trim).filter(String::isNotBlank)
        if (normalizedKey.isBlank() || normalizedRefs.isEmpty()) return
        deferredRetirementsByKey.getOrPut(normalizedKey) { linkedSetOf() } += normalizedRefs
    }

    fun deferredRetirements(): List<Pair<String, Set<String>>> =
        deferredRetirementsByKey
            .entries
            .sortedBy { (key, _) -> key }
            // `toSortedSet` is JVM-only in this project's common source set.  Keep the
            // deterministic ordering without relying on the platform collection extension.
            .map { (key, refs) -> key to refs.toList().sorted().toSet() }

    internal fun recordPotentiallyMissingRefs(refs: Set<String>) {
        potentiallyMissingRefs += refs
    }

    internal fun potentiallyMissingRefs(): Set<String> = potentiallyMissingRefs.toSet()

    /** Whether the irreversible finalization boundary has been entered. */
    internal fun isFinalizeAttempted(): Boolean = finalizeAttempted

    /**
     * Every ref planned for retirement by this transaction.  Once finalization is attempted we
     * conservatively treat the whole planned set as potentially missing, even if the native seam
     * failed before it could return a per-ref receipt.
     */
    internal fun deferredRetirementRefs(): Set<String> =
        deferredRetirementsByKey.values
            .flatMap { refs -> refs.asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    internal fun isPrepared(): Boolean = prepared

    internal fun isFinalized(): Boolean = finalized

    internal fun save(
        key: String,
        text: String,
        asTask: Boolean,
        uploadedAttachments: List<SharedThreadComposerAttachment>,
        externalShareSources: List<SharedThreadComposerExternalShareSource>
    ): Set<String> = owner.saveInTransaction(
        transaction = this,
        key = key,
        text = text,
        asTask = asTask,
        uploadedAttachments = uploadedAttachments,
        externalShareSources = externalShareSources
    )

    internal fun clear(key: String): Set<String> = owner.clearInTransaction(this, key)

    internal fun stageExternalShare(
        key: String,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult =
        owner.stageExternalShareInTransaction(this, key, text, attachments, source)

    override fun commit() {
        if (completed || rolledBack || prepared) return
        owner.commitTransition(this)
        prepared = true
    }

    override fun finalize() {
        if (completed || rolledBack || finalized) return
        check(prepared) { "thread composer draft persistence must be prepared before finalize" }
        check(finalizeAttempted) {
            "thread composer draft persistence must mark finalize attempted before finalize"
        }
        owner.finalizeTransition(this)
        finalized = true
    }

    override fun markFinalizeAttempted() {
        if (completed || rolledBack || finalized || finalizeAttempted) return
        check(prepared) { "thread composer draft persistence must be prepared before finalize" }
        // Set the fence before invoking any owner/native code.  If a subsequent finalizer throws,
        // a caller which tries to compensate manually still receives fail-closed rollback.
        finalizeAttempted = true
    }

    override fun complete() {
        if (completed || rolledBack) return
        check(finalized) { "thread composer draft persistence must be finalized before complete" }
        completed = true
    }

    /**
     * Gate release is intentionally separate from [complete].  The runtime may have already
     * completed a sibling snapshot when another completion reports an error; releasing here is
     * therefore idempotent and must never attempt to roll back this transaction.
     */
    override fun release() {
        owner.releaseTransition(this)
    }

    override fun rollback() {
        if (rolledBack || completed) return
        try {
            owner.rollbackTransition(this)
        } finally {
            // Even when restoring a broken storage backend fails, the shared registry gate must
            // not remain held forever. The owner clears it in its own finally block; this flag
            // prevents repeated rollback attempts from masking the primary transition error.
            rolledBack = true
        }
    }
}

internal sealed interface ExternalShareComposerDraftStageResult {
    data object Staged : ExternalShareComposerDraftStageResult
    data class Rejected(val code: String) : ExternalShareComposerDraftStageResult
}

internal class SharedThreadComposerDraftPersistenceFailure(
    val operation: String,
    val key: String,
    val code: String,
    val affectedFileRefs: Set<String> = emptySet(),
    cause: Throwable? = null
) : IllegalStateException(
    "thread_composer_draft_${operation}_failed key=$key code=$code",
    cause
)

internal data class SharedThreadComposerDraftProjection(
    private val rootConversationIds: Set<String> = emptySet(),
    private val replyParentMessageIds: Set<String> = emptySet()
) {
    fun hasRootDraft(conversationId: String): Boolean =
        conversationId in rootConversationIds

    fun hasReplyDraft(parentMessageId: String?): Boolean =
        parentMessageId?.takeIf { it.isNotBlank() } in replyParentMessageIds

    fun hasDraftForInboxItem(item: InboxItem): Boolean =
        when (item.kind) {
            InboxItemKind.Thread -> hasReplyDraft(item.parentMessageId?.value)
            InboxItemKind.Channel,
            InboxItemKind.DirectMessage,
            InboxItemKind.MentionAction,
            InboxItemKind.System -> hasRootDraft(item.channelId.value)
            InboxItemKind.Object -> false
        }

    fun hasDraftForTask(task: TaskSummary): Boolean =
        hasReplyDraft(task.parentMessageId?.value)
            || hasReplyDraft(task.messageId?.value)

    companion object {
        val Empty = SharedThreadComposerDraftProjection()
    }
}

internal fun Set<SharedThreadComposerDraftRecord>.toSharedThreadComposerDraftProjection(): SharedThreadComposerDraftProjection =
    SharedThreadComposerDraftProjection(
        rootConversationIds = mapNotNull { record ->
            record.sendChannelId.takeIf { record.isRootComposer }
        }.toSet(),
        replyParentMessageIds = mapNotNull { record ->
            record.parentMessageId?.takeIf { !record.isRootComposer && it.isNotBlank() }
        }.toSet()
    )

internal fun InboxItem.withComposerDraftProjection(
    draftProjection: SharedThreadComposerDraftProjection
): InboxItem =
    copy(hasDraft = draftProjection.hasDraftForInboxItem(this))

internal fun InboxSnapshot.withComposerDraftProjection(
    draftProjection: SharedThreadComposerDraftProjection
): InboxSnapshot =
    copy(items = items.map { item -> item.withComposerDraftProjection(draftProjection) })

/**
 * A preference store is the synchronization domain for composer drafts.  A page can construct
 * more than one persistence facade around the same app-scope store (for example Home and Thread,
 * or two remounted surfaces), so an instance-local lock is not sufficient for the active-key
 * index and attachment rows.  Match stores by object identity: wrappers are deliberately not
 * treated as equivalent merely because they expose the same interface.
 *
 * The registry is process-lived just like the app-scope preference store.  Keeping the lock after
 * a facade is released is intentional: dropping it would permit an ABA where a waiter still owns
 * the old lock while a later facade obtains a fresh lock for the same store.
 */
private class SharedThreadComposerDraftStoreLockEntry(
    val store: SharedPreferenceStore,
    val lock: ReentrantLock
) {
    /**
     * A transition is a store-wide gate, not a facade-local detail.  Home and Thread can wrap
     * the same preference store with different persistence facades; keeping the active owner in
     * this entry prevents one facade from silently joining (or clobbering) another facade's
     * rollback image.
     */
    var activeTransition: SharedThreadComposerDraftPersistenceTransaction? = null
    var activeOwner: SharedThreadComposerDraftPersistence? = null

    /**
     * Refs reserved for a native retirement whose callback is executing outside [lock].  A
     * concurrent draft write must not re-introduce one of these paths while the callback is in
     * flight; otherwise the callback could delete bytes which the new draft just claimed.
     */
    val retiringRefs: MutableSet<String> = linkedSetOf()
}

private object SharedThreadComposerDraftStoreLockRegistry {

    private val registryLock = SynchronizedObject()
    private val entries = mutableListOf<SharedThreadComposerDraftStoreLockEntry>()

    fun entryFor(store: SharedPreferenceStore): SharedThreadComposerDraftStoreLockEntry =
        synchronized(registryLock) {
            entries.firstOrNull { entry -> entry.store === store }
                ?: SharedThreadComposerDraftStoreLockEntry(
                    store = store,
                    lock = reentrantLock()
                ).also { entry -> entries += entry }
        }
}

internal class SharedThreadComposerDraftPersistence(
    private val store: SharedPreferenceStore,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    /**
     * Retire only cache-owned temporary refs which leave the durable draft.  The callback is
     * deliberately injected at the host/provider seam so this persistence class never guesses
     * how a platform deletes a file.  This legacy Unit callback remains available to focused
     * tests and source-compatible callers; production uses [temporaryFileRetirement] below.
     */
    private val retireTemporaryFileRefs: ((String, Set<String>) -> Unit)? = null,
    /**
     * Typed synchronous retirement seam.  The default registry path is fail-closed when no
     * platform handler is installed; it never silently treats a missing handler as success.
     */
    private val temporaryFileRetirement: ThreadComposerTemporaryFileRetirementHandler =
        ThreadComposerTemporaryFileRetirementHandler { sessionId, refs ->
            ThreadComposerTemporaryFileRetirement.retire(sessionId, refs)
        },
    /** Test seam for the write→retirement race; production leaves it null. */
    private val beforeTemporaryFileRefsRetirementFence: (() -> Unit)? = null,
    /** When a draft's text was last edited ([EditedAtSuffix]); tests pass a fixed clock. */
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    /** Shared with every facade which wraps the same [store]. */
    private val lockEntry = SharedThreadComposerDraftStoreLockRegistry.entryFor(store)
    private val lock = lockEntry.lock

    /**
     * Capture every durable row which a draft transition can touch.  The active-key index is
     * always included, and active rows are included even when the caller only names a new key;
     * this closes the fallback/clear multi-key window on rollback.
     */
    internal fun beginTransition(
        extraKeys: Iterable<String>
    ): SharedThreadComposerDraftPersistenceTransaction {
        val requestedKeys = extraKeys.toList()
        return lock.withLock {
        val active = lockEntry.activeTransition
        if (active != null) {
            throw transitionConflict(
                key = requestedKeys.firstOrNull()?.trim().orEmpty().ifBlank { "<multiple>" },
                activeOwner = lockEntry.activeOwner
            )
        }
        SharedThreadComposerDraftPersistenceTransaction(
            owner = this,
            durable = captureTransitionSnapshotUnlocked(requestedKeys)
        ).also {
            lockEntry.activeTransition = it
            lockEntry.activeOwner = this
        }
        }
    }

    /** Restore a captured durable image under the same process-wide store lock. */
    internal fun restoreTransitionSnapshot(
        snapshot: SharedThreadComposerDraftStorageSnapshot,
        missingRefs: Set<String> = emptySet()
    ) {
        lock.withLock {
            val image =
                if (missingRefs.isEmpty()) snapshot
                else sanitizeSnapshotForMissingRefs(snapshot, missingRefs)
            var firstFailure: Throwable? = null
            image.values.forEach { (key, value) ->
                try {
                    store.putString(key, value)
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                }
            }
            firstFailure?.let { throw it }
        }
    }

    internal fun commitTransition(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        lock.withLock {
            checkOwnsTransitionLocked(transaction)
            // Commit is deliberately only the reversible prepare phase.  Temporary files are
            // quarantined in [finalizeTransition], after every persistence slice has prepared.
            safeDiagnostics(
                "thread_composer_draft_transition_prepared keys=${transaction.deferredRetirements().size}"
            )
        }
    }

    internal fun finalizeTransition(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        val deferred = lock.withLock {
            checkOwnsTransitionLocked(transaction)
            transaction.deferredRetirements().toList()
        }
        // Native retirement deliberately runs outside the preference lock.  The transaction gate
        // remains active while this method is in flight, and each ref is marked in
        // [lockEntry.retiringRefs] before the lock is released, so another facade cannot reclaim
        // the path during the check -> callback window.
        deferred.forEach { (key, refs) ->
            retireTemporaryFileRefsSafely(key, refs, transaction)
        }
        safeDiagnostics("thread_composer_draft_transition_finalized keys=${deferred.size}")
    }

    /**
     * Release the store-wide transaction gate after a committed MVI transition.  This method is
     * deliberately idempotent and fail-safe: if this transaction has already released the gate,
     * or a later owner has acquired it, do nothing rather than throwing from post-commit cleanup.
     */
    internal fun releaseTransition(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        lock.withLock {
            if (lockEntry.activeTransition === transaction && lockEntry.activeOwner === this) {
                clearActiveTransitionLocked(transaction)
                safeDiagnostics("thread_composer_draft_transition_completed")
            }
        }
    }

    internal fun rollbackTransition(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        lock.withLock {
            if (lockEntry.activeTransition !== transaction) return
            try {
                // A finalizer may have crossed the native seam and failed before reporting which
                // paths it moved.  Once the marker is set, include the complete deferred plan in
                // the missing-ref set as a conservative union with the per-ref reservations;
                // restoring any of those fileRefs could otherwise publish a dangling path.
                val potentiallyMissing =
                    buildSet {
                        addAll(transaction.potentiallyMissingRefs())
                        if (transaction.isFinalizeAttempted()) {
                            addAll(transaction.deferredRetirementRefs())
                        }
                    }
                val snapshot =
                    if (potentiallyMissing.isEmpty()) {
                        transaction.durable
                    } else {
                        sanitizeSnapshotForMissingRefs(
                            snapshot = transaction.durable,
                            missingRefs = potentiallyMissing
                        )
                    }
                restoreTransitionSnapshotUnlocked(snapshot)
            } finally {
                // A broken preference backend must not strand the process-wide gate.  The
                // rollback error is propagated to MviStoreRuntime as suppressed evidence.
                clearActiveTransitionLocked(transaction)
            }
        }
    }

    /**
     * If a native retirement moved (or may have moved) a path before a later transition step
     * failed, restoring the old attachment JSON would create a durable row pointing at a vacant
     * pathname.  Repair the captured image instead: preserve text/task/share fields, remove every
     * affected attachment, and drop active-index entries which no longer contain any draft data.
     */
    private fun sanitizeSnapshotForMissingRefs(
        snapshot: SharedThreadComposerDraftStorageSnapshot,
        missingRefs: Set<String>
    ): SharedThreadComposerDraftStorageSnapshot {
        if (missingRefs.isEmpty()) return snapshot
        val values = snapshot.values.toMutableMap()
        val attachmentKeys =
            values.keys.filter { storageKey ->
                storageKey.startsWith(DraftStoragePrefix) && storageKey.endsWith(AttachmentsSuffix)
            }
        val affectedDraftKeys = linkedSetOf<String>()
        attachmentKeys.forEach { storageKey ->
            val draftKey = storageKey.removePrefix(DraftStoragePrefix).removeSuffix(AttachmentsSuffix)
            val encoded = values[storageKey]
            if (encoded == null) return@forEach
            val decoded =
                runCatching {
                    ComposerDraftJson.decodeFromString<List<SharedThreadComposerAttachment>>(encoded)
                }.getOrNull()
            if (decoded == null) {
                // A malformed row is already unusable; keep the repair fail-closed and remove it.
                values[storageKey] = null
                affectedDraftKeys += draftKey
                return@forEach
            }
            val retained = decoded.filterNot { attachment ->
                attachment.fileRef?.trim()?.takeIf(String::isNotBlank) in missingRefs
            }
            if (retained.size != decoded.size) {
                values[storageKey] =
                    retained.takeIf { it.isNotEmpty() }
                        ?.let { ComposerDraftJson.encodeToString(it) }
                affectedDraftKeys += draftKey
            }
        }

        val activeIndex = values[ActiveIndexKey]
        if (activeIndex != null) {
            val retainedActiveKeys =
                activeIndex.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .filter { draftKey ->
                        draftKey.toSharedThreadComposerDraftRecord() != null &&
                            hasDurableContentInSnapshot(values, draftKey)
                    }
                    .distinct()
                    .toList()
            values[ActiveIndexKey] = retainedActiveKeys
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n")
        }
        return SharedThreadComposerDraftStorageSnapshot(values = values)
    }

    /** A row is active only when it still contains at least one usable durable field. */
    private fun hasDurableContentInSnapshot(
        values: Map<String, String?>,
        draftKey: String
    ): Boolean {
        if (values[textKey(draftKey)]?.isNotBlank() == true) return true
        if (values[asTaskKey(draftKey)] == "true") return true
        val attachments =
            values[attachmentsKey(draftKey)]
                ?.let { encoded ->
                    runCatching {
                        ComposerDraftJson.decodeFromString<List<SharedThreadComposerAttachment>>(encoded)
                            .persistableThreadComposerDraftAttachments()
                    }.getOrNull()
                }
        if (!attachments.isNullOrEmpty()) return true
        val sources =
            values[sourcesKey(draftKey)]
                ?.let { encoded ->
                    runCatching {
                        ComposerDraftJson.decodeFromString<List<SharedThreadComposerExternalShareSource>>(encoded)
                            .validExternalShareComposerSources()
                    }.getOrNull()
                }
        return !sources.isNullOrEmpty()
    }

    private fun checkOwnsTransitionLocked(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        check(lockEntry.activeTransition === transaction && lockEntry.activeOwner === this) {
            "thread composer draft persistence transition is not owned by this facade"
        }
    }

    private fun clearActiveTransitionLocked(transaction: SharedThreadComposerDraftPersistenceTransaction) {
        if (lockEntry.activeTransition === transaction) {
            lockEntry.activeTransition = null
            lockEntry.activeOwner = null
        }
    }

    private fun transitionConflict(
        key: String,
        activeOwner: SharedThreadComposerDraftPersistence?
    ): SharedThreadComposerDraftPersistenceFailure =
        SharedThreadComposerDraftPersistenceFailure(
            operation = "transition",
            key = key,
            code = "transition_conflict"
        ).also {
            safeDiagnostics(
                "thread_composer_draft_transition_conflict key=$key " +
                    "activeOwner=${activeOwner?.let { it.hashCode().toString(16) }.orEmpty()}"
            )
        }

    private fun safeDiagnostics(message: String) {
        runCatching { diagnosticsLogger?.invoke(message) }
    }

    private fun captureTransitionSnapshotUnlocked(
        extraKeys: Iterable<String>
    ): SharedThreadComposerDraftStorageSnapshot {
        val keys =
            (setOf(ActiveIndexKey) +
                activeKeys().flatMap(::storageKeysForDraft) +
                extraKeys.flatMap(::storageKeysForDraft))
                .toSet()
        return SharedThreadComposerDraftStorageSnapshot(
            values = keys.sorted().associateWith { key -> store.getString(key) }
        )
    }

    /** Capture a durable image while holding the process-wide preference-store lock. */
    private fun captureTransitionSnapshot(
        extraKeys: Iterable<String>
    ): SharedThreadComposerDraftStorageSnapshot = lock.withLock {
        captureTransitionSnapshotUnlocked(extraKeys)
    }

    private fun restoreTransitionSnapshotUnlocked(snapshot: SharedThreadComposerDraftStorageSnapshot) {
        var firstFailure: Throwable? = null
        snapshot.values.forEach { (key, value) ->
            try {
                store.putString(key, value)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    internal fun storageKeysForDraft(key: String): Set<String> =
        setOf(textKey(key), asTaskKey(key), attachmentsKey(key), sourcesKey(key), editedAtKey(key))

    fun read(key: String): SharedThreadComposerPersistedDraft? = lock.withLock {
        val text = store.getString(textKey(key)) ?: return@withLock null
        SharedThreadComposerPersistedDraft(
            text = text,
            asTask = store.getString(asTaskKey(key)) == "true",
            uploadedAttachments = readUploadedAttachments(key),
            externalShareSources = readExternalShareSources(key)
        )
    }

    fun readWithReplyFallback(key: String): Pair<String, SharedThreadComposerPersistedDraft>? = lock.withLock {
        readUnlocked(key)?.let { draft -> return@withLock key to draft }
        val currentRecord = key.toSharedThreadComposerDraftRecord()?.takeUnless { it.isRootComposer }
            ?: return@withLock null
        activeDraftRecordsUnlocked()
            .filter { record -> record.key != key && record.matchesReplyThreadIdentity(currentRecord) }
            .sortedBy { record -> record.key }
            .mapNotNull { record -> readUnlocked(record.key)?.let { draft -> record.key to draft } }
            .firstOrNull()
    }

    fun save(
        key: String,
        text: String,
        asTask: Boolean,
        uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
        externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList()
    ) {
        var durableSnapshot: SharedThreadComposerDraftStorageSnapshot? = null
        var mutation: SharedThreadComposerDraftNonTransactionalMutation? = null
        var refsToRetire = emptySet<String>()
        var transactionOwned = false
        try {
            lock.withLock {
                val transaction = lockEntry.activeTransition
                if (transaction != null) {
                    if (transaction.owner !== this) {
                        throw transitionConflict(key, lockEntry.activeOwner)
                    }
                    transaction.save(
                        key = key,
                        text = text,
                        asTask = asTask,
                        uploadedAttachments = uploadedAttachments,
                        externalShareSources = externalShareSources
                    )
                    transactionOwned = true
                    return@withLock
                }

                // Snapshot and the complete row/index write are one critical section.  A
                // different facade may still write an unrelated key while the native retirement
                // callback below is blocked, but it can never slip a write between this snapshot
                // and the mutation and later be erased by rollback.
                durableSnapshot = captureTransitionSnapshotUnlocked(setOf(key))
                refsToRetire = saveUnlocked(
                    key = key,
                    text = text,
                    asTask = asTask,
                    uploadedAttachments = uploadedAttachments,
                    externalShareSources = externalShareSources
                )
                mutation =
                    SharedThreadComposerDraftNonTransactionalMutation(
                        before = requireNotNull(durableSnapshot),
                        after = captureTransitionSnapshotUnlocked(setOf(key)),
                        key = key
                    )
            }
            if (transactionOwned) return
            retireTemporaryFileRefsSafely(key, refsToRetire)
        } catch (error: Throwable) {
            // A transaction-owned save already has a slice-level undo image; only restore the
            // non-transactional path which captured its image above.
            mutation?.let { changed ->
                runCatching {
                    restoreNonTransactionalMutation(
                        mutation = changed,
                        missingRefs = (error as? SharedThreadComposerDraftPersistenceFailure)
                            ?.affectedFileRefs
                            .orEmpty()
                    )
                }
                    .onFailure { rollback -> error.addSuppressed(rollback) }
            }
            throw error
        }
    }

    internal fun saveInTransaction(
        transaction: SharedThreadComposerDraftPersistenceTransaction,
        key: String,
        text: String,
        asTask: Boolean,
        uploadedAttachments: List<SharedThreadComposerAttachment>,
        externalShareSources: List<SharedThreadComposerExternalShareSource>
    ): Set<String> = lock.withLock {
        checkOwnsTransitionLocked(transaction)
        saveUnlocked(
            key = key,
            text = text,
            asTask = asTask,
            uploadedAttachments = uploadedAttachments,
            externalShareSources = externalShareSources
        ).also { refs -> transaction.deferRetirement(key, refs) }
    }

    internal fun clearInTransaction(
        transaction: SharedThreadComposerDraftPersistenceTransaction,
        key: String
    ): Set<String> = lock.withLock {
        checkOwnsTransitionLocked(transaction)
        clearUnlocked(key, previous = readUnlocked(key)).also { refs ->
            transaction.deferRetirement(key, refs)
        }
    }

    private fun saveUnlocked(
        key: String,
        text: String,
        asTask: Boolean,
        uploadedAttachments: List<SharedThreadComposerAttachment>,
        externalShareSources: List<SharedThreadComposerExternalShareSource>
    ): Set<String> {
        val previous = readUnlocked(key)
        val persistedAttachments = uploadedAttachments.persistableThreadComposerDraftAttachments()
        val persistedSources = externalShareSources.validExternalShareComposerSources()
        safeDiagnostics(
            "thread_composer_draft_persist_save key=$key textLen=${text.length} " +
                "textBlank=${text.isBlank()} asTask=$asTask attachments=${persistedAttachments.size} " +
                "activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        if (text.isBlank() && !asTask && persistedAttachments.isEmpty() && persistedSources.isEmpty()) {
            return clearUnlocked(key, previous = previous)
        }
        val claimedWhileRetiring =
            persistedAttachments.claimedLocalFileRefs() intersect lockEntry.retiringRefs
        if (claimedWhileRetiring.isNotEmpty()) {
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "temporary_file_retirement",
                key = key,
                code = "temporary_file_retirement_in_progress",
                affectedFileRefs = claimedWhileRetiring
            )
        }
        val refsToRetire =
            temporaryRefsLeavingDraft(
                key = key,
                previous = previous?.uploadedAttachments.orEmpty(),
                next = persistedAttachments
            )
        val before = captureTransitionSnapshotUnlocked(setOf(key))
        try {
            store.putString(textKey(key), text)
            store.putString(asTaskKey(key), asTask.toString())
            store.putString(
                attachmentsKey(key),
                persistedAttachments
                    .takeIf { it.isNotEmpty() }
                    ?.let { attachments -> ComposerDraftJson.encodeToString(attachments) }
            )
            store.putString(
                sourcesKey(key),
                persistedSources.takeIf { it.isNotEmpty() }
                    ?.let { sources -> ComposerDraftJson.encodeToString(sources) }
            )
            // Drafts (drafts_section_v0): the text's last edit, beside the draft's other rows. A save
            // that leaves the text as it was (a flush, an attachment change) keeps the old time; a
            // draft saved before this row existed has none until its text changes.
            store.putString(
                editedAtKey(key),
                if (previous != null && previous.text == text) {
                    store.getString(editedAtKey(key))
                } else {
                    nowEpochMillis().toString()
                }
            )
            addActiveKey(key)
        } catch (error: Throwable) {
            runCatching { restoreTransitionSnapshotUnlocked(before) }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "save",
                key = key,
                code = "storage_write_failed",
                cause = error
            )
        }
        safeDiagnostics(
            "thread_composer_draft_persist_saved key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        return refsToRetire
    }

    /*
     * Keep the old implementation's shape in one place: all writes go through saveUnlocked so
     * callers can compute ownership changes while holding the same storage lock.  In particular,
     * a replacement must not retire a ref which is still referenced by a sibling row or another
     * draft key.
     */
    private fun temporaryRefsLeavingDraft(
        key: String,
        previous: List<SharedThreadComposerAttachment>,
        next: List<SharedThreadComposerAttachment>
    ): Set<String> {
        val candidates = previous.temporaryFileRefs() - next.temporaryFileRefs()
        if (candidates.isEmpty()) return emptySet()
        val referencedElsewhere =
            activeKeys()
                .filterNot { activeKey -> activeKey == key }
                .flatMap { activeKey -> readUnlocked(activeKey)?.uploadedAttachments.orEmpty() }
                .temporaryFileRefs()
        return candidates - referencedElsewhere
    }

    private fun retireTemporaryFileRefsSafely(
        key: String,
        fileRefs: Set<String>,
        transaction: SharedThreadComposerDraftPersistenceTransaction? = null,
    ) {
        val normalized = fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        if (normalized.isEmpty()) return

        // Phase 1: confirm durable ownership and reserve the exact paths while holding the shared
        // lock.  The reservation is the fence: a concurrent save which tries to claim one of
        // these refs fails closed instead of racing the native callback.
        val reserved = lock.withLock {
            val stillUnreferenced =
                normalized -
                    activeKeys()
                        .flatMap { activeKey -> readUnlocked(activeKey)?.uploadedAttachments.orEmpty() }
                        .temporaryFileRefs()
            if (stillUnreferenced.isEmpty()) return@withLock emptySet<String>()
            val alreadyRetiring = stillUnreferenced intersect lockEntry.retiringRefs
            if (alreadyRetiring.isNotEmpty()) {
                throw SharedThreadComposerDraftPersistenceFailure(
                    operation = "temporary_file_retirement",
                    key = key,
                    code = "temporary_file_retirement_in_progress",
                    affectedFileRefs = alreadyRetiring
                )
            }
            lockEntry.retiringRefs += stillUnreferenced
            // Record the attempted set before crossing the platform seam.  A handler may
            // quarantine zero, some, or all refs and then fail; every attempted path is therefore
            // unsafe to put back into a durable attachment row during rollback.
            transaction?.recordPotentiallyMissingRefs(stillUnreferenced)
            stillUnreferenced
        }
        if (reserved.isEmpty()) return

        // Phase 2: the fence is installed, so release the preference lock before invoking native
        // code.  This is important both for deadlock avoidance and for proving that the callback
        // cannot observe a lock held by the persistence layer.  Keep the test seam inside the
        // guarded region: a fence callback is allowed to reject a sibling claim, and that
        // rejection must still release the reservation in the `finally` below.
        try {
            beforeTemporaryFileRefsRetirementFence?.invoke()
            if (retireTemporaryFileRefs != null) {
                retireTemporaryFileRefs.invoke(key, reserved)
            } else {
                val result = temporaryFileRetirement.retire(key, reserved)
                if (!result.succeeded) {
                    throw SharedThreadComposerDraftPersistenceFailure(
                        operation = "temporary_file_retirement",
                        key = key,
                        code = result.errorCode
                            ?: "temporary_file_retirement_incomplete",
                        affectedFileRefs = reserved
                    )
                }
            }
        } catch (error: Throwable) {
            safeDiagnostics(
                "thread_composer_draft_temp_retire_failed refs=${reserved.size} " +
                    "error=${error::class.simpleName.orEmpty()}"
            )
            if (error is SharedThreadComposerDraftPersistenceFailure) throw error
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "temporary_file_retirement",
                key = key,
                code = "temporary_file_retirement_handler_failed",
                affectedFileRefs = reserved,
                cause = error
            )
        } finally {
            // Phase 3: release the fence only after the callback has returned.  Re-acquiring the
            // lock here also gives rollback/next transition a consistent view of the reservation
            // lifecycle, while never holding the lock across native code.
            lock.withLock { lockEntry.retiringRefs.removeAll(reserved) }
        }
    }

    /**
     * Atomically merges an External Share handoff into the canonical conversation draft. The
     * route contains only conversation identity; text and private file references enter through
     * this store-owned persistence boundary before navigation.
     */
    fun stageExternalShare(
        key: String,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult = lock.withLock {
        lockEntry.activeTransition?.let { transaction ->
            if (transaction.owner !== this) {
                throw transitionConflict(key, lockEntry.activeOwner)
            }
            return@withLock transaction.stageExternalShare(
                key = key,
                text = text,
                attachments = attachments,
                source = source
            )
        }
        stageExternalShareUnlocked(key, text, attachments, source)
    }

    internal fun stageExternalShareInTransaction(
        transaction: SharedThreadComposerDraftPersistenceTransaction,
        key: String,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult = lock.withLock {
        checkOwnsTransitionLocked(transaction)
        stageExternalShareUnlocked(key, text, attachments, source)
    }

    private fun stageExternalShareUnlocked(
        key: String,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult {
        val record = key.toSharedThreadComposerDraftRecord()
            ?: return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_key_invalid"
            )
        if (!record.isRootComposer) {
            return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_reply_composer_not_supported"
            )
        }
        val normalizedAttachments = attachments.persistableThreadComposerDraftAttachments()
        val claimedWhileRetiring =
            normalizedAttachments.claimedLocalFileRefs() intersect lockEntry.retiringRefs
        if (claimedWhileRetiring.isNotEmpty()) {
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "temporary_file_retirement",
                key = key,
                code = "temporary_file_retirement_in_progress",
                affectedFileRefs = claimedWhileRetiring
            )
        }
        if (listOf(source).validExternalShareComposerSources().size != 1) {
            return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_source_invalid"
            )
        }
        if (
            normalizedAttachments.size != attachments.size ||
            normalizedAttachments.any { attachment -> !attachment.isValidExternalShareQueuedAttachment() }
        ) {
            return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_attachment_invalid"
            )
        }
        val current = readUnlocked(key)
        val nextText =
            listOf(current?.text.orEmpty(), text.trim())
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n\n")
        val nextAttachments =
            (current?.uploadedAttachments.orEmpty() + normalizedAttachments)
                .distinctBy { attachment -> attachment.id }
                .persistableThreadComposerDraftAttachments()
        val nextSources =
            (current?.externalShareSources.orEmpty() + source)
                .validExternalShareComposerSources()
        if (nextText.isBlank() && nextAttachments.isEmpty()) {
            return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_draft_empty"
            )
        }
        val before = captureTransitionSnapshotUnlocked(setOf(key))
        try {
            store.putString(textKey(key), nextText)
            store.putString(asTaskKey(key), (current?.asTask == true).toString())
            store.putString(
                attachmentsKey(key),
                nextAttachments.takeIf { it.isNotEmpty() }?.let { ComposerDraftJson.encodeToString(nextAttachments) }
            )
            store.putString(
                sourcesKey(key),
                ComposerDraftJson.encodeToString(nextSources)
            )
            addActiveKey(key)
        } catch (error: Throwable) {
            runCatching { restoreTransitionSnapshotUnlocked(before) }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "external_share_stage",
                key = key,
                code = "storage_write_failed",
                cause = error
            )
        }
        safeDiagnostics(
            "thread_composer_external_share_staged key=$key textLen=${nextText.length} " +
                "attachments=${normalizedAttachments.size}"
        )
        return ExternalShareComposerDraftStageResult.Staged
    }

    fun clear(key: String) {
        var durableSnapshot: SharedThreadComposerDraftStorageSnapshot? = null
        var mutation: SharedThreadComposerDraftNonTransactionalMutation? = null
        var refsToRetire = emptySet<String>()
        var transactionOwned = false
        try {
            lock.withLock {
                val transaction = lockEntry.activeTransition
                if (transaction != null) {
                    if (transaction.owner !== this) {
                        throw transitionConflict(key, lockEntry.activeOwner)
                    }
                    refsToRetire = transaction.clear(key)
                    transactionOwned = true
                    return@withLock
                }

                // Keep the clear snapshot and all four row removals plus active-index update in
                // one lock section for the same reason as [save] above.
                durableSnapshot = captureTransitionSnapshotUnlocked(setOf(key))
                refsToRetire = clearUnlocked(key, previous = readUnlocked(key))
                mutation =
                    SharedThreadComposerDraftNonTransactionalMutation(
                        before = requireNotNull(durableSnapshot),
                        after = captureTransitionSnapshotUnlocked(setOf(key)),
                        key = key
                    )
            }
            if (transactionOwned) return
            retireTemporaryFileRefsSafely(key, refsToRetire)
        } catch (error: Throwable) {
            mutation?.let { changed ->
                runCatching {
                    restoreNonTransactionalMutation(
                        mutation = changed,
                        missingRefs = (error as? SharedThreadComposerDraftPersistenceFailure)
                            ?.affectedFileRefs
                            .orEmpty()
                    )
                }
                    .onFailure { rollback -> error.addSuppressed(rollback) }
            }
            throw error
        }
    }

    /**
     * Undo only the rows which this non-transactional operation still owns.  Native retirement
     * runs without the preference lock, so another facade may legitimately add an unrelated
     * draft while the callback is blocked.  Restoring the old active-key string wholesale would
     * erase that draft; compare-and-swap each changed row and merge this key's membership into the
     * current index instead.
     */
    private fun restoreNonTransactionalMutation(
        mutation: SharedThreadComposerDraftNonTransactionalMutation,
        missingRefs: Set<String>
    ) {
        lock.withLock {
            val image =
                if (missingRefs.isEmpty()) mutation.before
                else sanitizeSnapshotForMissingRefs(mutation.before, missingRefs)
            val current = captureTransitionSnapshotUnlocked(setOf(mutation.key))
            val ownedKeys = storageKeysForDraft(mutation.key)
            val ownsRows = ownedKeys.all { storageKey ->
                current.values[storageKey] == mutation.after.values[storageKey]
            }
            if (ownsRows) {
                ownedKeys.forEach { storageKey ->
                    store.putString(storageKey, image.values[storageKey])
                }
                val beforeIndex = parseActiveKeys(image.values[ActiveIndexKey])
                val currentIndex = parseActiveKeys(current.values[ActiveIndexKey]).toMutableSet()
                if (mutation.key in beforeIndex) {
                    currentIndex += mutation.key
                } else {
                    currentIndex -= mutation.key
                }
                store.putString(
                    ActiveIndexKey,
                    currentIndex.sorted().joinToString(separator = "\n").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    fun activeDraftRecords(): Set<SharedThreadComposerDraftRecord> = lock.withLock {
        activeDraftRecordsUnlocked()
    }

    /** Every active draft with its text and last edit, for the Drafts list. */
    fun draftListings(): List<SharedThreadComposerDraftListing> = lock.withLock {
        activeDraftRecordsUnlocked().mapNotNull { record ->
            val text = store.getString(textKey(record.key)) ?: return@mapNotNull null
            SharedThreadComposerDraftListing(
                record = record,
                text = text,
                editedAtEpochMillis = store.getString(editedAtKey(record.key))?.toLongOrNull()
            )
        }
    }

    internal fun recordForKey(key: String): SharedThreadComposerDraftRecord? =
        key.toSharedThreadComposerDraftRecord()

    private fun readUnlocked(key: String): SharedThreadComposerPersistedDraft? {
        val text = store.getString(textKey(key)) ?: return null
        return SharedThreadComposerPersistedDraft(
            text = text,
            asTask = store.getString(asTaskKey(key)) == "true",
            uploadedAttachments = readUploadedAttachments(key),
            externalShareSources = readExternalShareSources(key)
        )
    }

    private fun clearUnlocked(
        key: String,
        previous: SharedThreadComposerPersistedDraft? = readUnlocked(key)
    ): Set<String> {
        val before = captureTransitionSnapshotUnlocked(setOf(key))
        safeDiagnostics(
            "thread_composer_draft_persist_clear key=$key activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        try {
            store.putString(textKey(key), null)
            store.putString(asTaskKey(key), null)
            store.putString(attachmentsKey(key), null)
            store.putString(sourcesKey(key), null)
            store.putString(editedAtKey(key), null)
            removeActiveKey(key)
        } catch (error: Throwable) {
            runCatching { restoreTransitionSnapshotUnlocked(before) }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            throw SharedThreadComposerDraftPersistenceFailure(
                operation = "clear",
                key = key,
                code = "storage_write_failed",
                cause = error
            )
        }
        safeDiagnostics(
            "thread_composer_draft_persist_cleared key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        return temporaryRefsLeavingDraft(
            key = key,
            previous = previous?.uploadedAttachments.orEmpty(),
            next = emptyList()
        )
    }

    private fun activeDraftRecordsUnlocked(): Set<SharedThreadComposerDraftRecord> {
        val listed = activeKeys()
        val validKeys =
            listed
                .filter { key -> key.toSharedThreadComposerDraftRecord() != null }
                .filter { key -> hasDurableContentUnlocked(key) }
                .toSet()
        if (validKeys != listed) {
            // Repair malformed/stale index entries at the same lock boundary used for reads. This
            // keeps projections deterministic after process death without inventing content for
            // a key whose rows are missing or malformed.
            writeActiveKeys(validKeys)
        }
        return validKeys.mapNotNull { key -> key.toSharedThreadComposerDraftRecord() }.toSet()
    }

    private fun hasDurableContentUnlocked(key: String): Boolean =
        hasDurableContentInSnapshot(
            values = storageKeysForDraft(key).associateWith { storageKey -> store.getString(storageKey) },
            draftKey = key
        )

    private fun textKey(key: String): String = "$DraftStoragePrefix$key$TextSuffix"
    private fun asTaskKey(key: String): String = "$DraftStoragePrefix$key$AsTaskSuffix"
    private fun attachmentsKey(key: String): String = "$DraftStoragePrefix$key$AttachmentsSuffix"
    private fun sourcesKey(key: String): String = "$DraftStoragePrefix$key$SourcesSuffix"
    private fun editedAtKey(key: String): String = "$DraftStoragePrefix$key$EditedAtSuffix"

    private fun readExternalShareSources(key: String): List<SharedThreadComposerExternalShareSource> =
        store.getString(sourcesKey(key))
            ?.let { encoded ->
                runCatching {
                    ComposerDraftJson.decodeFromString<List<SharedThreadComposerExternalShareSource>>(encoded)
                }.getOrNull()
            }
            .orEmpty()
            .validExternalShareComposerSources()

    private fun readUploadedAttachments(key: String): List<SharedThreadComposerAttachment> =
        store.getString(attachmentsKey(key))
            ?.let { encoded ->
                runCatching {
                    ComposerDraftJson.decodeFromString<List<SharedThreadComposerAttachment>>(encoded)
                }.getOrNull()
            }
            .orEmpty()
            .persistableThreadComposerDraftAttachments()
            .map { attachment ->
                if (
                    (attachment.source == "external_share" ||
                        attachment.source in ThreadComposerOwnedTemporarySources) &&
                        attachment.status == "uploading"
                ) {
                    attachment.copy(
                        status = "queued",
                        errorCode = null,
                        uploadTransfer = null
                    )
                } else {
                    attachment
                }
            }

    private fun activeKeys(): Set<String> =
        readActiveKeysFromStore()

    private fun readActiveKeysFromStore(): Set<String> =
        parseActiveKeys(store.getString(ActiveIndexKey))

    private fun parseActiveKeys(encoded: String?): Set<String> =
        encoded
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun addActiveKey(key: String) {
        writeActiveKeys(activeKeys() + key)
    }

    private fun removeActiveKey(key: String) {
        writeActiveKeys(activeKeys() - key)
    }

    private fun writeActiveKeys(keys: Set<String>) {
        store.putString(ActiveIndexKey, keys.sorted().joinToString(separator = "\n").takeIf { it.isNotBlank() })
    }

    private fun String.toSharedThreadComposerDraftRecord(): SharedThreadComposerDraftRecord? {
        val values =
            split('|')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    part.substring(0, separator) to part.substring(separator + 1).threadComposerDraftKeyDecodePart()
                }
                .toMap()
        val sendChannelId = values["send"]?.takeIf { it.isNotBlank() } ?: return null
        return SharedThreadComposerDraftRecord(
            key = this,
            sendChannelId = sendChannelId,
            parentMessageId = values["parent"]?.takeIf { it.isNotBlank() && it != "_" },
            mentionChannelId = values["mention"]?.takeIf { it.isNotBlank() && it != "_" }
        )
    }

    private fun String.threadComposerDraftKeyDecodePart(): String =
        replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")

    /**
     * The persistence facade's own key layout.  In production the facade wraps an
     * account-scoped view ([SharedThreadComposerDraftAccountStore]) which maps these keys onto
     * one account's rows; the layout itself never names an account.
     */
    internal companion object {
        const val ActiveIndexKey = "threadComposerDraft:v1:activeKeys"
        const val DraftStoragePrefix = "threadComposerDraft:v1:"
        const val TextSuffix = ":text"
        const val AsTaskSuffix = ":asTask"
        const val AttachmentsSuffix = ":attachments"
        const val SourcesSuffix = ":externalShareSources"
        /** Epoch millis of the text's last edit (Drafts); absent on drafts saved before it existed. */
        const val EditedAtSuffix = ":editedAt"
        private val ComposerDraftJson = Json { ignoreUnknownKeys = true }

        /** Every row one draft key owns (the active index aside). */
        fun draftRowKeys(key: String): List<String> =
            listOf(TextSuffix, AsTaskSuffix, AttachmentsSuffix, SourcesSuffix, EditedAtSuffix)
                .map { suffix -> "$DraftStoragePrefix$key$suffix" }
    }
}

internal fun interface SharedThreadComposerDraftPersistenceCancellation {
    fun cancel()
}

internal fun interface SharedThreadComposerDraftPersistenceScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): SharedThreadComposerDraftPersistenceCancellation
}

internal class CoroutineThreadComposerDraftPersistenceScheduler(
    private val scope: CoroutineScope
) : SharedThreadComposerDraftPersistenceScheduler {
    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        val job: Job = scope.launch {
            delay(delayMillis)
            block()
        }
        return SharedThreadComposerDraftPersistenceCancellation(job::cancel)
    }
}

private object ImmediateThreadComposerDraftPersistenceScheduler : SharedThreadComposerDraftPersistenceScheduler {
    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        block()
        return SharedThreadComposerDraftPersistenceCancellation {}
    }
}

private data class PendingThreadComposerDraftWrite(
    val generation: Long,
    val state: SharedThreadComposerDraftState,
    var cancellation: SharedThreadComposerDraftPersistenceCancellation? = null
)

/** Immutable copy used by a transition snapshot; cancellation handles are one-way and cannot be restored. */
private data class PendingThreadComposerDraftWriteSnapshot(
    val generation: Long,
    val state: SharedThreadComposerDraftState
)

internal class SharedThreadComposerDraftPersistSlice(
    private val persistence: SharedThreadComposerDraftPersistence,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val scheduler: SharedThreadComposerDraftPersistenceScheduler = ImmediateThreadComposerDraftPersistenceScheduler,
    private val changeDebounceMillis: Long = DefaultChangeDebounceMillis
) : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
    val activeDraftRecordsState: StateFlow<Set<SharedThreadComposerDraftRecord>> =
        SharedActiveDraftRecordsState.asStateFlow()

    /** The durable drafts with their text and last edit (the Drafts list); see [SharedThreadComposerDraftPersistence.draftListings]. */
    fun draftListings(): List<SharedThreadComposerDraftListing> = persistence.draftListings()
    private val stagedUploadedAttachmentsByDraftKey =
        mutableMapOf<String, List<SharedThreadComposerAttachment>>()
    private val fallbackDraftSourceKeyByCurrentKey = mutableMapOf<String, String>()
    /** Single re-entrant lock for every slice-local map and the shared active-record projection. */
    private val stateLock = reentrantLock()
    /** Secondary write ordering lock; always acquired after [stateLock]. */
    private val persistenceWriteLock = reentrantLock()
    private val pendingWritesByDraftKey = mutableMapOf<String, PendingThreadComposerDraftWrite>()
    private val writeGenerationByDraftKey = mutableMapOf<String, Long>()
    /** Advanced each time the signed-in account changes; guarded by [stateLock]. */
    private var accountEpoch = 0L

    /**
     * Slice-local undo record.  Keeping this type inside the slice is intentional: rollback must
     * restore the slice's private pending-write/staging maps and the shared active-record flow,
     * while the durable transaction restores the preference-store rows.
     */
    private inner class SharedThreadComposerDraftMutableSnapshot(
        val stagedUploadedAttachmentsByDraftKey: Map<String, List<SharedThreadComposerAttachment>>,
        val fallbackDraftSourceKeyByCurrentKey: Map<String, String>,
        val pendingWritesByDraftKey: Map<String, PendingThreadComposerDraftWriteSnapshot>,
        val writeGenerationByDraftKey: Map<String, Long>,
        val activeDraftRecords: Set<SharedThreadComposerDraftRecord>,
        val durableTransaction: SharedThreadComposerDraftPersistenceTransaction
    ) : MviPersistSliceTransitionSnapshot {
        private var restored = false

        override fun commit() {
            durableTransaction.commit()
        }

        override fun finalize() {
            durableTransaction.finalize()
        }

        override fun markFinalizeAttempted() {
            durableTransaction.markFinalizeAttempted()
        }

        override fun complete() {
            durableTransaction.complete()
        }

        override fun release() {
            durableTransaction.release()
        }

        override fun rollback() {
            if (restored) return
            try {
                restoreMutableSnapshot(this)
            } finally {
                // A failed durable restore is still a completed rollback attempt.  The owner
                // transaction has released its shared gate in a finally block; retrying here
                // would only re-run a potentially destructive platform restore.
                restored = true
            }
        }
    }

    init {
        refreshActiveDraftRecords(reason = "init")
    }

    override fun captureTransitionState(
        previous: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent,
        reduced: SharedThreadComposerDraftState
    ): MviPersistSliceTransitionSnapshot = stateLock.withLock {
        // Keep the local maps, active-record projection, and durable transaction admission in one
        // lock order: stateLock -> persistence lock.  No persistence method calls back into this
        // slice, so the inverse order cannot deadlock.  Staged keys are included even though they
        // have no durable row yet; a rollback must still fence a concurrent key migration.
        val pendingKeys = pendingWritesByDraftKey.keys.toSet()
        val stagedKeys = stagedUploadedAttachmentsByDraftKey.keys.toSet()
        val fallbackKeys =
            (fallbackDraftSourceKeyByCurrentKey.keys + fallbackDraftSourceKeyByCurrentKey.values).toSet()
        val keys =
            buildSet {
                previous.draftKey?.let(::add)
                reduced.draftKey?.let(::add)
                addAll(pendingKeys)
                addAll(stagedKeys)
                addAll(fallbackKeys)
            }
        // Establish the durable transaction before asking the persistence facade for its active
        // projection.  `activeDraftRecords()` repairs malformed/stale active-index entries as a
        // read; doing that first would write the repair outside the rollback image and leave a
        // hidden mutation when a later slice rejects this transition (or when the transition is a
        // structural no-op).  `beginTransition` captures the raw active index and every raw row,
        // so the subsequent repair is fully reversible.
        val durableTransaction = persistence.beginTransition(keys)
        try {
            persistence.activeDraftRecords()
            val pendingSnapshot =
                pendingWritesByDraftKey.mapValues { (_, pending) ->
                    PendingThreadComposerDraftWriteSnapshot(
                        generation = pending.generation,
                        state = pending.state
                    )
                }
            SharedThreadComposerDraftMutableSnapshot(
                stagedUploadedAttachmentsByDraftKey = stagedUploadedAttachmentsByDraftKey.mapValues { (_, value) -> value.toList() },
                fallbackDraftSourceKeyByCurrentKey = fallbackDraftSourceKeyByCurrentKey.toMap(),
                pendingWritesByDraftKey = pendingSnapshot,
                writeGenerationByDraftKey = writeGenerationByDraftKey.toMap(),
                activeDraftRecords = SharedActiveDraftRecordsState.value,
                durableTransaction = durableTransaction
            )
        } catch (error: Throwable) {
            // This method is itself part of transition admission; if the projection read fails,
            // MviStoreRuntime has not received a snapshot and therefore cannot perform the normal
            // rollback pass. Release the transaction gate here while preserving the read failure
            // as the primary error.
            runCatching { durableTransaction.rollback() }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            runCatching { durableTransaction.release() }
                .onFailure { release -> error.addSuppressed(release) }
            throw error
        }
    }

    private fun restoreMutableSnapshot(snapshot: SharedThreadComposerDraftMutableSnapshot) {
        var firstFailure: Throwable? = null
        val pendingToRearm = stateLock.withLock {
            try {
                // Match capture's lock order (stateLock -> persistence lock).  The durable image
                // is restored while local maps are frozen, so a callback cannot observe a mixed
                // pre/post-transition view.
                snapshot.durableTransaction.rollback()
            } catch (error: Throwable) {
                firstFailure = error
            }
            try {
                pendingWritesByDraftKey.values.forEach { pending -> pending.cancellation?.cancel() }
                stagedUploadedAttachmentsByDraftKey.clear()
                stagedUploadedAttachmentsByDraftKey.putAll(snapshot.stagedUploadedAttachmentsByDraftKey)
                fallbackDraftSourceKeyByCurrentKey.clear()
                fallbackDraftSourceKeyByCurrentKey.putAll(snapshot.fallbackDraftSourceKeyByCurrentKey)
                pendingWritesByDraftKey.clear()
                writeGenerationByDraftKey.clear()
                writeGenerationByDraftKey.putAll(snapshot.writeGenerationByDraftKey)
                // Never put the original pending objects back. Their canceled callback may still
                // run after rollback; a fresh identity and generation make that callback stale.
                snapshot.pendingWritesByDraftKey
                    .entries
                    .sortedBy { (key, _) -> key }
                    .forEach { (key, pending) ->
                        val restoredGeneration =
                            maxOf(
                                pending.generation,
                                writeGenerationByDraftKey[key] ?: 0L
                            ) + 1L
                        writeGenerationByDraftKey[key] = restoredGeneration
                        pendingWritesByDraftKey[key] =
                            PendingThreadComposerDraftWrite(
                                generation = restoredGeneration,
                                state = pending.state
                            )
                    }
                SharedActiveDraftRecordsState.value = snapshot.activeDraftRecords
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
            pendingWritesByDraftKey.toMap()
        }
        // Cancellation handles are one-way (a Job cannot be uncancelled), so arm only the fresh
        // entries installed above.  The identity check in schedulePendingCallback prevents a
        // synchronous scheduler from attaching a handle to an entry already consumed by itself.
        pendingToRearm.entries.sortedBy { (key, _) -> key }.forEach { (key, pending) ->
            schedulePendingCallback(key, pending)
        }
        firstFailure?.let { throw it }
    }

    override fun reduce(
        previous: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent,
        reduced: SharedThreadComposerDraftState
    ): SharedThreadComposerDraftState {
        return stateLock.withLock {
        when (intent) {
            is SharedThreadComposerDraftIntent.Start -> {
                val draftKey = intent.draftKey?.takeIf { it.isNotBlank() }
                previous.draftKey?.takeIf { it != draftKey }?.let(::flushPendingWrite)
                draftKey?.let(::flushPendingWrite)
                val sameDraftKey = draftKey != null && previous.draftKey == draftKey
                val stagedUploadedAttachments = consumeStagedUploadedAttachments(draftKey)
                val sessionAttachments =
                    if (sameDraftKey) {
                        previous.visibleSessionThreadComposerDraftAttachments()
                    } else {
                        emptyList()
                    }
                val removedAttachmentIds =
                    if (sameDraftKey) {
                        previous.removedAttachmentIds
                    } else {
                        emptySet()
                    }
                val restoredDraft =
                    draftKey
                        ?.let(persistence::readWithReplyFallback)
                if (draftKey != null && restoredDraft != null && restoredDraft.first != draftKey) {
                    fallbackDraftSourceKeyByCurrentKey[draftKey] = restoredDraft.first
                }
                val restored =
                    restoredDraft
                        ?.second
                        ?.let { persistedDraft ->
                            reduced.copy(
                                seedText = persistedDraft.text,
                                seedAsTask = persistedDraft.asTask,
                                uploadedAttachments = persistedDraft.uploadedAttachments,
                                externalShareSources = persistedDraft.externalShareSources
                            )
                        }
                        ?: reduced
                val withStagedAttachments =
                    if (stagedUploadedAttachments.isEmpty() && sessionAttachments.isEmpty() && removedAttachmentIds.isEmpty()) {
                        restored
                    } else {
                        restored.copy(
                            removedAttachmentIds = removedAttachmentIds,
                            uploadedAttachments =
                                (restored.uploadedAttachments + sessionAttachments + stagedUploadedAttachments)
                                    .distinctBy { attachment -> attachment.id }
                                    .filterNot { attachment -> attachment.id in removedAttachmentIds }
                        )
                    }
                val synchronized =
                    if (withStagedAttachments.requiresComposerHostSync(reduced)) {
                        withStagedAttachments.copy(syncNonce = reduced.syncNonce + 1)
                    } else {
                        withStagedAttachments
                    }
                (if (withStagedAttachments.requiresComposerEditorReplace(reduced)) {
                    synchronized.withThreadComposerEditorCommand(
                        kind = ThreadComposerEditorCommandKind.Replace,
                        text = withStagedAttachments.seedText,
                        asTask = withStagedAttachments.seedAsTask,
                        expectedText = reduced.seedText,
                        expectedAsTask = reduced.seedAsTask,
                    )
                } else {
                    synchronized
                }).copy(accountEpoch = accountEpoch)
            }
            else -> reduced
        }
        }
    }

    override fun onTransition(
        previous: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent,
        next: SharedThreadComposerDraftState
    ) {
        stateLock.withLock {
        when (intent) {
            is SharedThreadComposerDraftIntent.Start ->
                next.draftKey?.let { key ->
                    val fallbackSourceKey = stateLock.withLock {
                        fallbackDraftSourceKeyByCurrentKey.remove(key)
                    }
                    if (fallbackSourceKey != null && fallbackSourceKey != key && isCurrentAccount(next)) {
                        persistenceWriteLock.withLock {
                            persistence.save(
                                key = key,
                                text = next.seedText,
                                asTask = next.seedAsTask,
                                uploadedAttachments = next.visiblePersistableThreadComposerDraftAttachments(),
                                externalShareSources = next.externalShareSources
                            )
                            persistence.clear(fallbackSourceKey)
                            refreshActiveDraftRecords(reason = "StartFallbackRestore")
                        }
                    }
                }
            is SharedThreadComposerDraftIntent.Change ->
                next.draftKey?.takeIf { isCurrentAccount(next) }?.let { key ->
                    schedulePendingWrite(key, next)
                }
            is SharedThreadComposerDraftIntent.InsertMention,
            is SharedThreadComposerDraftIntent.AppendQuote,
            is SharedThreadComposerDraftIntent.RestoreAfterFailure,
            is SharedThreadComposerDraftIntent.AddUploadedAttachments,
            is SharedThreadComposerDraftIntent.MarkAttachmentsFailed,
            is SharedThreadComposerDraftIntent.RemoveAttachment ->
                next.draftKey?.let { key ->
                    cancelPendingWrite(key)
                    persistState(key, next, intent.toThreadComposerDraftIntentDiagnosticsLabel())
                }
            SharedThreadComposerDraftIntent.Flush ->
                next.draftKey?.let(::flushPendingWrite)
            SharedThreadComposerDraftIntent.BeginSend ->
                previous.draftKey?.let { key ->
                    cancelPendingWrite(key)
                    persistState(key, previous, "BeginSend")
                }
            is SharedThreadComposerDraftIntent.CompleteSend ->
                if (previous.sending && isCurrentAccount(next)) {
                    next.draftKey?.let { key ->
                        cancelPendingWrite(key)
                        persistenceWriteLock.withLock {
                            persistence.clear(key)
                            refreshActiveDraftRecords(reason = "CompleteSend")
                        }
                    }
                }
            else -> Unit
        }
        }
    }

    private fun schedulePendingWrite(
        key: String,
        state: SharedThreadComposerDraftState
    ) {
        val (pending, previous) = stateLock.withLock {
            val next =
                PendingThreadComposerDraftWrite(
                    generation = advanceWriteGenerationLocked(key),
                    state = state
                )
            next to pendingWritesByDraftKey.put(key, next)
        }
        previous?.cancellation?.cancel()
        refreshActiveDraftRecords(reason = "ChangeProjected")
        schedulePendingCallback(key, pending)
    }

    private fun schedulePendingCallback(
        key: String,
        pending: PendingThreadComposerDraftWrite
    ): SharedThreadComposerDraftPersistenceCancellation {
        // The immediate scheduler is used by a number of synchronous tests and can invoke the
        // callback before schedule() returns.  Do not recursively re-arm in that case; leave the
        // pending row available for an explicit Flush/next change.  Real delayed schedulers are
        // re-armed after a failed write.
        var scheduling = true
        val cancellation =
            scheduler.schedule(changeDebounceMillis) {
                val claimed = stateLock.withLock {
                    if (pendingWritesByDraftKey[key] === pending) {
                        pendingWritesByDraftKey.remove(key)
                        pending.cancellation = null
                        pending
                    } else {
                        null
                    }
                }
                if (claimed != null) {
                    try {
                        persistStateIfGenerationCurrent(
                            key = key,
                            generation = claimed.generation,
                            state = claimed.state
                        )
                    } catch (error: Throwable) {
                        restorePendingWriteAfterFailure(
                            key = key,
                            pending = claimed,
                            rearm = !scheduling,
                            error = error
                        )
                    }
                }
            }
        scheduling = false
        // A scheduler is allowed to run the block synchronously before returning.  Only retain
        // the returned cancellation handle when this exact pending object is still current;
        // otherwise cancel it immediately so a stale callback cannot be armed after rollback.
        val stillCurrent = stateLock.withLock {
            if (pendingWritesByDraftKey[key] === pending) {
                pending.cancellation = cancellation
                true
            } else {
                false
            }
        }
        if (!stillCurrent) cancellation.cancel()
        return cancellation
    }

    /**
     * Persistence is deliberately fail-closed, but a debounced callback must not turn a durable
     * write error into silent data loss.  Put the exact generation back in the pending map only
     * when no newer Change has won the key; stale callbacks never overwrite a newer generation.
     */
    private fun restorePendingWriteAfterFailure(
        key: String,
        pending: PendingThreadComposerDraftWrite,
        rearm: Boolean,
        error: Throwable
    ) {
        val restored = stateLock.withLock {
            val currentGeneration = writeGenerationByDraftKey[key]
            if (
                currentGeneration == pending.generation &&
                pendingWritesByDraftKey[key] == null
            ) {
                pendingWritesByDraftKey[key] = pending
                true
            } else {
                false
            }
        }
        if (!restored) return
        refreshActiveDraftRecords(reason = "ChangeDebouncedPersistenceFailure")
        safeDiagnostics(
            "thread_composer_draft_pending_write_failed key=$key generation=${pending.generation} " +
                "error=${error::class.simpleName.orEmpty()}"
        )
        if (rearm) {
            schedulePendingCallback(key, pending)
        }
    }

    private fun flushPendingWrite(key: String) {
        stateLock.withLock {
            persistenceWriteLock.withLock {
                flushPendingWriteLocked(key)
            }
        }
    }

    /** Flush while [persistenceWriteLock] is held; used by compound stage operations. */
    private fun flushPendingWriteLocked(key: String) {
        val pending = stateLock.withLock {
            pendingWritesByDraftKey.remove(key).also {
                advanceWriteGenerationLocked(key)
            }
        } ?: return
        pending.cancellation?.cancel()
        try {
            persistStateLocked(key, pending.state, "ChangeFlush")
        } catch (error: Throwable) {
            // A synchronous Flush must report the primary persistence failure to the transition,
            // but it must also retain the latest draft for a later retry.  The generation is
            // advanced above to fence the canceled callback; restore the pending row under a new
            // generation so a stale callback can never replay it over newer input.
            val restored = stateLock.withLock {
                val nextGeneration = advanceWriteGenerationLocked(key)
                if (pendingWritesByDraftKey[key] == null) {
                    pendingWritesByDraftKey[key] =
                        PendingThreadComposerDraftWrite(
                            generation = nextGeneration,
                            state = pending.state
                        )
                    true
                } else {
                    false
                }
            }
            if (restored) {
                refreshActiveDraftRecords(reason = "ChangeFlushPersistenceFailure")
                safeDiagnostics(
                    "thread_composer_draft_flush_failed key=$key generation=${pending.generation} " +
                        "error=${error::class.simpleName.orEmpty()}"
                )
            }
            throw error
        }
    }

    private fun cancelPendingWrite(key: String) {
        stateLock.withLock {
            pendingWritesByDraftKey.remove(key).also {
                advanceWriteGenerationLocked(key)
            }
        }?.cancellation?.cancel()
    }

    private fun advanceWriteGenerationLocked(key: String): Long {
        val next = (writeGenerationByDraftKey[key] ?: 0L) + 1L
        writeGenerationByDraftKey[key] = next
        return next
    }

    private fun persistState(
        key: String,
        state: SharedThreadComposerDraftState,
        reason: String
    ) {
        stateLock.withLock {
            persistenceWriteLock.withLock {
                persistStateLocked(key, state, reason)
            }
        }
    }

    private fun persistStateLocked(
        key: String,
        state: SharedThreadComposerDraftState,
        reason: String
    ) {
        if (!isCurrentAccount(state)) {
            // Text typed for an account that has since signed out (or been replaced) is dropped,
            // never written into whoever is signed in now.
            safeDiagnostics("thread_composer_draft_persist_skipped key=$key reason=account_retired")
            return
        }
        persistence.save(
            key = key,
            text = state.seedText,
            asTask = state.seedAsTask,
            uploadedAttachments = state.visiblePersistableThreadComposerDraftAttachments(),
            externalShareSources = state.externalShareSources
        )
        refreshActiveDraftRecords(reason = reason)
    }

    private fun persistStateIfGenerationCurrent(
        key: String,
        generation: Long,
        state: SharedThreadComposerDraftState
    ) {
        stateLock.withLock {
            persistenceWriteLock.withLock {
                val isCurrent = writeGenerationByDraftKey[key] == generation
                if (!isCurrent) return@withLock
                persistStateLocked(key, state, "ChangeDebounced")
            }
        }
    }

    private fun safeDiagnostics(message: String) {
        runCatching { diagnosticsLogger?.invoke(message) }
    }

    private fun refreshActiveDraftRecords(reason: String) {
        stateLock.withLock {
            val projected = persistence.activeDraftRecords().associateBy { it.key }.toMutableMap()
            pendingWritesByDraftKey.mapValues { (_, pending) -> pending.state }
                .forEach { (key, state) ->
                    val record = persistence.recordForKey(key)
                    if (state.hasPersistableThreadComposerDraft() && record != null) {
                        projected[key] = record
                    } else {
                        projected.remove(key)
                    }
                }
            SharedActiveDraftRecordsState.value = projected.values.toSet()
            val current = SharedActiveDraftRecordsState.value
            safeDiagnostics(
                "thread_composer_draft_active_records reason=$reason count=${current.size} " +
                    "records=${current.toThreadComposerDraftDiagnosticsSummary()}"
            )
        }
    }

    fun stageUploadedAttachments(
        draftKey: String?,
        attachments: List<SharedThreadComposerAttachment>
    ) {
        val key = draftKey?.takeIf { it.isNotBlank() } ?: return
        if (attachments.isEmpty()) return
        stateLock.withLock {
            stagedUploadedAttachmentsByDraftKey[key] =
                (stagedUploadedAttachmentsByDraftKey[key].orEmpty() + attachments)
                    .distinctBy { attachment -> attachment.id }
        }
    }

    fun stageExternalShareDraft(
        draftKey: String?,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult {
        val key = draftKey?.takeIf(String::isNotBlank)
            ?: return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_key_missing"
            )
        val result = stateLock.withLock {
            persistenceWriteLock.withLock {
                // External-share staging is a compound write: flush the latest ordinary draft first,
                // then merge the handoff without allowing a debounced save to overwrite it between
                // those two operations.
                flushPendingWriteLocked(key)
                persistence.stageExternalShare(key, text, attachments, source)
            }.also { result ->
                if (result is ExternalShareComposerDraftStageResult.Staged) {
                    refreshActiveDraftRecords(reason = "external_share_stage")
                }
            }
        }
        return result
    }

    private fun isCurrentAccount(state: SharedThreadComposerDraftState): Boolean =
        stateLock.withLock { state.accountEpoch == accountEpoch }

    /**
     * The signed-in account changed (sign-out, session expiry, account or environment switch).
     * Every in-memory draft of the previous account is dropped, never flushed: pending debounced
     * writes, staged attachments, reply fallbacks.  Composers started before this call can no
     * longer write.  [rebind] switches the durable view to the successor while the slice is
     * frozen, and the shared active-draft projection is then re-read for the successor.
     */
    fun retireAccountSession(rebind: () -> Unit) {
        val cancelled = stateLock.withLock {
            accountEpoch += 1L
            val pending = pendingWritesByDraftKey.toMap()
            pending.keys.forEach(::advanceWriteGenerationLocked)
            pendingWritesByDraftKey.clear()
            stagedUploadedAttachmentsByDraftKey.clear()
            fallbackDraftSourceKeyByCurrentKey.clear()
            persistenceWriteLock.withLock { rebind() }
            pending.values
        }
        cancelled.forEach { pending -> pending.cancellation?.cancel() }
        refreshActiveDraftRecords(reason = "account_changed")
        safeDiagnostics("thread_composer_draft_account_retired droppedPending=${cancelled.size}")
    }

    private fun consumeStagedUploadedAttachments(draftKey: String?): List<SharedThreadComposerAttachment> {
        val key = draftKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return stateLock.withLock { stagedUploadedAttachmentsByDraftKey.remove(key).orEmpty() }
    }

    private companion object {
        const val DefaultChangeDebounceMillis = 250L
        val SharedActiveDraftRecordsState = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
    }
}

private fun SharedThreadComposerDraftState.hasPersistableThreadComposerDraft(): Boolean =
    seedText.isNotBlank() ||
        seedAsTask ||
        visiblePersistableThreadComposerDraftAttachments().isNotEmpty() ||
        externalShareSources.isNotEmpty()

private fun SharedThreadComposerDraftState.requiresComposerHostSync(
    previous: SharedThreadComposerDraftState
): Boolean =
    seedText != previous.seedText ||
        seedAsTask != previous.seedAsTask ||
        removedAttachmentIds != previous.removedAttachmentIds ||
        uploadedAttachments != previous.uploadedAttachments

private fun SharedThreadComposerDraftState.visibleSessionThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    uploadedAttachments
        .filterNot { attachment -> attachment.id in removedAttachmentIds }
        .distinctBy { attachment -> attachment.id }

private fun normalizeThreadComposerAttachmentIdentityPart(value: String?): String? =
    value?.trim()?.takeIf { it.isNotBlank() }

private fun SharedThreadComposerAttachmentAdmission.normalized(): SharedThreadComposerAttachmentAdmission =
    copy(
        localAttachmentId = localAttachmentId.trim(),
        fileRef = normalizeThreadComposerAttachmentIdentityPart(fileRef),
        source = normalizeThreadComposerAttachmentIdentityPart(source),
        replacementAttachmentId =
            replacementAttachmentId.trim().ifBlank { localAttachmentId.trim() }
    )

private fun SharedThreadComposerAttachment.admissionIdentity(): SharedThreadComposerAttachmentAdmission =
    SharedThreadComposerAttachmentAdmission(
        localAttachmentId = id.trim(),
        generation = 0L,
        fileRef = normalizeThreadComposerAttachmentIdentityPart(fileRef),
        source = normalizeThreadComposerAttachmentIdentityPart(source),
        replacementAttachmentId = id.trim()
    )

private fun List<SharedThreadComposerAttachment>.areValidLocalAttachmentAdmissionInputs(): Boolean {
    if (isEmpty()) return false
    val ids = map { attachment -> attachment.id.trim() }
    return ids.all(String::isNotBlank) && ids.toSet().size == ids.size
}

private fun SharedThreadComposerDraftState.matchesAttachmentAdmission(
    admission: SharedThreadComposerAttachmentAdmission,
    requireUploading: Boolean = true
): Boolean {
    val normalized = admission.normalized()
    val id = normalized.localAttachmentId
    if (id.isBlank() || normalized.generation <= 0L) return false
    if (id in removedAttachmentIds) return false
    if (attachmentAdmissionsByLocalId[id]?.normalized() != normalized) return false
    val rows = uploadedAttachments.filter { attachment -> attachment.id == id }
    if (rows.size != 1) return false
    val row = rows.single()
    if (requireUploading && row.status != "uploading") return false
    return normalizeThreadComposerAttachmentIdentityPart(row.fileRef) == normalized.fileRef &&
        normalizeThreadComposerAttachmentIdentityPart(row.source) == normalized.source
}

/**
 * Validate the complete Gallery aggregate before deriving any visible/server projection.  This
 * intentionally returns false for a partially supplied witness: callers must either use the
 * legacy unbound intent (kept for old persisted/test fixtures) or provide every field of the
 * strict aggregate contract.
 */
private fun SharedThreadComposerDraftState.isAdmissibleUploadedAttachmentProjection(
    intent: SharedThreadComposerDraftIntent.AddUploadedAttachments
): Boolean {
    // Supplying any draft/ownership witness opts the callback into the strict aggregate
    // contract.  In particular, an expected draft key by itself must not silently fall back to
    // the legacy unbound reducer path: a caller which claims to fence a callback must provide the
    // complete admission, replacement-slot, and server-id maps or be rejected unchanged.
    val strict =
        intent.expectedDraftKey != null ||
            intent.expectedAdmissions.isNotEmpty() ||
            intent.expectedServerAttachmentIdsByLocalId.isNotEmpty() ||
            intent.expectedReplacementAttachmentIdsByLocalId.isNotEmpty()
    if (!strict) return true

    val expectedKey = normalizeThreadAttachmentUploadDraftKey(intent.expectedDraftKey) ?: return false
    if (normalizeThreadAttachmentUploadDraftKey(draftKey) != expectedKey) return false

    val expectedAdmissions =
        intent.expectedAdmissions
            .mapKeys { (id, _) -> id.trim() }
            .mapValues { (_, admission) -> admission.normalized() }
    if (
        expectedAdmissions.isEmpty() ||
            expectedAdmissions.keys.any(String::isBlank) ||
            expectedAdmissions.keys.size != intent.expectedAdmissions.size
    ) {
        return false
    }
    val replacedAttachmentIds = intent.replacedAttachmentIds.map(String::trim).filter(String::isNotBlank).toSet()
    if (
        replacedAttachmentIds != expectedAdmissions.keys ||
            replacedAttachmentIds.size != intent.replacedAttachmentIds.size
    ) {
        return false
    }
    if (
        expectedAdmissions.any { (id, admission) ->
            admission.localAttachmentId != id || !matchesAttachmentAdmission(admission)
        } ||
            expectedAdmissions.values.map { admission -> admission.generation }.toSet().size !=
                expectedAdmissions.size
    ) {
        return false
    }

    val expectedServerByLocal =
        intent.expectedServerAttachmentIdsByLocalId
            .mapKeys { (id, _) -> id.trim() }
            .mapValues { (_, ids) -> ids.map(String::trim).toSet() }
    if (
        expectedServerByLocal.keys != expectedAdmissions.keys ||
            expectedServerByLocal.size != intent.expectedServerAttachmentIdsByLocalId.size ||
            expectedServerByLocal.values.any { ids -> ids.isEmpty() || ids.any(String::isBlank) } ||
            intent.expectedServerAttachmentIdsByLocalId.any { (id, ids) ->
                expectedServerByLocal[id.trim()]?.size != ids.size
            }
    ) return false

    // The replacement slot is a separate identity from the admitted local id.  Ordinary picker
    // uploads replace their local placeholder; editor uploads replace the pre-existing remote
    // row which opened the editor.  Carry the exact slot in the witness instead of deriving it
    // from the local id, otherwise a valid editor completion is indistinguishable from a stale
    // callback (and an incorrect remote slot could silently overwrite a sibling).
    val expectedReplacementByLocal =
        intent.expectedReplacementAttachmentIdsByLocalId
            .mapKeys { (id, _) -> id.trim() }
            .mapValues { (_, slot) -> slot.trim() }
    if (
        expectedReplacementByLocal.keys != expectedAdmissions.keys ||
            expectedReplacementByLocal.size != intent.expectedReplacementAttachmentIdsByLocalId.size ||
            expectedReplacementByLocal.values.any(String::isBlank) ||
            expectedReplacementByLocal.values.toSet().size != expectedReplacementByLocal.size
    ) {
        return false
    }
    if (
        expectedAdmissions.any { (localId, admission) ->
            admission.normalized().let { normalized ->
                normalized.replacementAttachmentId != expectedReplacementByLocal.getValue(localId)
            }
        }
    ) {
        return false
    }

    val outputIds = intent.attachments.map { attachment -> attachment.id.trim() }
    if (outputIds.any(String::isBlank) || outputIds.toSet().size != outputIds.size) return false
    if (outputIds.toSet().intersect(expectedAdmissions.keys).isNotEmpty()) return false
    val expectedOutputIds = expectedServerByLocal.values.flatten()
    if (expectedOutputIds.toSet().size != expectedOutputIds.size || expectedOutputIds.toSet() != outputIds.toSet()) {
        return false
    }
    if (
        intent.attachments.any { attachment ->
            attachment.status != "ready" || attachment.errorCode != null
        }
    ) {
        return false
    }

    val replacementMap =
        intent.replacedAttachmentIdsByUploadedId
            .mapKeys { (id, _) -> id.trim() }
            .mapValues { (_, slot) -> slot.trim() }
    if (
        replacementMap.keys != outputIds.toSet() ||
            replacementMap.size != intent.replacedAttachmentIdsByUploadedId.size ||
            replacementMap.values.any(String::isBlank)
    ) {
        return false
    }

    val currentIds = uploadedAttachments.map { attachment -> attachment.id.trim() }
    if (
        currentIds.any(String::isBlank) ||
            currentIds.toSet().size != currentIds.size ||
            outputIds.toSet().intersect(currentIds.toSet()).isNotEmpty()
    ) {
        return false
    }
    val currentIdSet = currentIds.toSet()
    val removedIds = removedAttachmentIds.map(String::trim).toSet()
    val expectedReplacementSlots = expectedReplacementByLocal.values.toSet()
    // The mapping must describe exactly one target slot per admitted local row.  This rejects a
    // stale/forged extra slot while still allowing several server receipts to replace one local
    // row (the legitimate multi-result case).
    if (replacementMap.values.toSet() != expectedReplacementSlots) return false
    expectedAdmissions.keys.forEach { localId ->
        val expectedReplacementSlot = expectedReplacementByLocal.getValue(localId)
        val slotIsOwnLocal = expectedReplacementSlot == localId
        // A remote editor slot must be a current row.  A local slot is the exact admitted row;
        // another admitted local row is never a valid target for this one.
        if (
            expectedReplacementSlot in removedIds ||
                (!slotIsOwnLocal && expectedReplacementSlot in expectedAdmissions.keys) ||
                (slotIsOwnLocal && expectedReplacementSlot !in expectedAdmissions.keys) ||
                (!slotIsOwnLocal && expectedReplacementSlot !in currentIdSet) ||
                (!slotIsOwnLocal && currentIdSet.count { it == expectedReplacementSlot } != 1)
        ) {
            return false
        }
        val mappedOutputIds =
            replacementMap
                .filterValues { slot -> slot == expectedReplacementSlot }
                .keys
                .toSet()
        if (mappedOutputIds != expectedServerByLocal.getValue(localId)) return false
    }
    return true
}

private fun SharedThreadComposerDraftState.isAdmissibleAttachmentFailure(
    intent: SharedThreadComposerDraftIntent.MarkAttachmentsFailed
): Boolean {
    val strict = intent.expectedDraftKey != null || intent.expectedAdmissions.isNotEmpty()
    if (!strict) return true
    val expectedKey = normalizeThreadAttachmentUploadDraftKey(intent.expectedDraftKey) ?: return false
    if (normalizeThreadAttachmentUploadDraftKey(draftKey) != expectedKey) return false
    val admissions = intent.expectedAdmissions.mapKeys { (id, _) -> id.trim() }
    val ids = intent.attachmentIds.map(String::trim).filter(String::isNotBlank).toSet()
    if (ids.isEmpty() || ids != admissions.keys || admissions.keys.size != intent.expectedAdmissions.size) return false
    return admissions.all { (id, admission) ->
        admission.localAttachmentId.trim() == id && matchesAttachmentAdmission(admission)
    }
}

private fun SharedThreadComposerDraftState.isAdmissibleAttachmentProgress(
    intent: SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress
): Boolean {
    val strict = intent.expectedDraftKey != null || intent.expectedAdmission != null
    if (!strict) return true
    val expectedKey = normalizeThreadAttachmentUploadDraftKey(intent.expectedDraftKey) ?: return false
    if (normalizeThreadAttachmentUploadDraftKey(draftKey) != expectedKey) return false
    val admission = intent.expectedAdmission ?: return false
    return admission.localAttachmentId.trim() == intent.attachmentId.trim() &&
        matchesAttachmentAdmission(admission)
}

private fun SharedThreadComposerDraftIntent.toThreadComposerDraftIntentDiagnosticsLabel(): String =
    when (this) {
        is SharedThreadComposerDraftIntent.Start -> "Start"
        is SharedThreadComposerDraftIntent.Change -> "Change"
        is SharedThreadComposerDraftIntent.SetOriginalImage -> "SetOriginalImage"
        is SharedThreadComposerDraftIntent.InsertMention -> "InsertMention"
        is SharedThreadComposerDraftIntent.AppendQuote -> "AppendQuote"
        SharedThreadComposerDraftIntent.Flush -> "Flush"
        is SharedThreadComposerDraftIntent.BeginSend -> "BeginSend"
        is SharedThreadComposerDraftIntent.RestoreAfterFailure -> "RestoreAfterFailure"
        is SharedThreadComposerDraftIntent.CompleteInlineSendFailure -> "CompleteInlineSendFailure"
        is SharedThreadComposerDraftIntent.CompleteSend -> "CompleteSend"
        is SharedThreadComposerDraftIntent.ApplyPendingMentionActions -> "ApplyPendingMentionActions"
        is SharedThreadComposerDraftIntent.DismissMentionAction -> "DismissMentionAction"
        is SharedThreadComposerDraftIntent.BeginMentionAction -> "BeginMentionAction"
        is SharedThreadComposerDraftIntent.BeginMentionActionBatch -> "BeginMentionActionBatch"
        is SharedThreadComposerDraftIntent.FinishMentionAction -> "FinishMentionAction"
        is SharedThreadComposerDraftIntent.FinishMentionActionBatch -> "FinishMentionActionBatch"
        SharedThreadComposerDraftIntent.ClearMentionActionNotice -> "ClearMentionActionNotice"
        is SharedThreadComposerDraftIntent.MarkMentionActionRemoving -> "MarkMentionActionRemoving"
        is SharedThreadComposerDraftIntent.RemoveMentionAction -> "RemoveMentionAction"
        is SharedThreadComposerDraftIntent.AddUploadedAttachments -> "AddUploadedAttachments"
        is SharedThreadComposerDraftIntent.AddLocalAttachments -> "AddLocalAttachments"
        is SharedThreadComposerDraftIntent.MarkAttachmentsFailed -> "MarkAttachmentsFailed"
        is SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress -> "UpdateAttachmentUploadProgress"
        is SharedThreadComposerDraftIntent.RemoveAttachment -> "RemoveAttachment"
        is SharedThreadComposerDraftIntent.RetryAttachment -> "RetryAttachment"
    }

internal fun Set<SharedThreadComposerDraftRecord>.toThreadComposerDraftDiagnosticsSummary(): String =
    sortedWith(
        compareBy<SharedThreadComposerDraftRecord> { it.sendChannelId }
            .thenBy { it.parentMessageId.orEmpty() }
            .thenBy { it.mentionChannelId.orEmpty() }
            .thenBy { it.key }
    ).joinToString(separator = ",") { record ->
        "send=${record.sendChannelId};parent=${record.parentMessageId ?: "_"};mention=${record.mentionChannelId ?: "_"}"
    }

internal sealed interface SharedThreadComposerDraftIntent {
    data class Start(
        val composer: RaftThreadComposerPayload,
        val draftKey: String? = null
    ) : SharedThreadComposerDraftIntent
    data class Change(val seedText: String? = null, val seedAsTask: Boolean? = null) : SharedThreadComposerDraftIntent
    data class SetOriginalImage(val enabled: Boolean) : SharedThreadComposerDraftIntent
    data class InsertMention(val handle: String) : SharedThreadComposerDraftIntent
    /** Appends a Markdown quote after the current draft (Reply on a partial text selection). */
    data class AppendQuote(val quote: String) : SharedThreadComposerDraftIntent
    data object Flush : SharedThreadComposerDraftIntent
    data object BeginSend : SharedThreadComposerDraftIntent
    data class RestoreAfterFailure(
        val seedText: String,
        val seedAsTask: Boolean,
        val failureLabel: String
    ) : SharedThreadComposerDraftIntent
    data object CompleteInlineSendFailure : SharedThreadComposerDraftIntent
    data class CompleteSend(
        val pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList()
    ) : SharedThreadComposerDraftIntent
    data class ApplyPendingMentionActions(
        val pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList()
    ) : SharedThreadComposerDraftIntent
    data class DismissMentionAction(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class BeginMentionAction(
        val resolutionId: String,
        val state: String
    ) : SharedThreadComposerDraftIntent
    data class BeginMentionActionBatch(
        val resolutionIds: List<String>
    ) : SharedThreadComposerDraftIntent
    data class FinishMentionAction(
        val resolutionId: String,
        val notice: String,
        val delivered: Boolean,
        val state: String
    ) : SharedThreadComposerDraftIntent
    data class FinishMentionActionBatch(
        val requestedIds: List<String>,
        val deliveredIds: Set<String>,
        val noticeTitle: String?,
        val notice: String?
    ) : SharedThreadComposerDraftIntent
    data object ClearMentionActionNotice : SharedThreadComposerDraftIntent
    data class MarkMentionActionRemoving(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class RemoveMentionAction(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class AddUploadedAttachments(
        val attachments: List<SharedThreadComposerAttachment>,
        val replacedAttachmentIds: Set<String> = emptySet(),
        val replacedAttachmentIdsByUploadedId: Map<String, String> = emptyMap(),
        /** Reject a late upload callback after the mounted composer moved to another draft. */
        val expectedDraftKey: String? = null,
        /**
         * Optional serialized ownership witness.  When present, the reducer treats this as a
         * strict aggregate commit: every expected local row, generation, fileRef/source pair and
         * local -> server mapping must still match, or the whole intent is rejected unchanged.
        */
        val expectedAdmissions: Map<String, SharedThreadComposerAttachmentAdmission> = emptyMap(),
        val expectedServerAttachmentIdsByLocalId: Map<String, Set<String>> = emptyMap(),
        /** Exact target slot for each admitted local row (local placeholder or remote editor row). */
        val expectedReplacementAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ) : SharedThreadComposerDraftIntent
    data class AddLocalAttachments(
        val attachments: List<SharedThreadComposerAttachment>,
        /** Reject a picker result which arrived after the mounted composer changed drafts. */
        val expectedDraftKey: String? = null,
        /** Exact local/remote replacement slot for every admitted local row. */
        val replacementAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ) : SharedThreadComposerDraftIntent
    data class MarkAttachmentsFailed(
        val attachmentIds: Set<String>,
        val errorCode: String,
        /** Scope failure marking to the draft which owned the upload attempt. */
        val expectedDraftKey: String? = null,
        /** Scope failure marking to the exact local-row generations that failed. */
        val expectedAdmissions: Map<String, SharedThreadComposerAttachmentAdmission> = emptyMap()
    ) : SharedThreadComposerDraftIntent
    /** The upload attempt's meter state ([transfer]), published by the attempt that owns the meter. */
    data class UpdateAttachmentUploadProgress(
        val attachmentId: String,
        val transfer: RaftTransferProgress,
        /** Scope progress to the draft and local-row generation which emitted it. */
        val expectedDraftKey: String? = null,
        val expectedAdmission: SharedThreadComposerAttachmentAdmission? = null
    ) : SharedThreadComposerDraftIntent
    data class RemoveAttachment(val id: String) : SharedThreadComposerDraftIntent
    data class RetryAttachment(val id: String) : SharedThreadComposerDraftIntent
}

internal class SharedThreadComposerDraftStore(
    persistSlices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>> = emptyList(),
    scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
) {
    private val runtime =
        MviStoreRuntime(
            initialState = SharedThreadComposerDraftState(),
            scope = scope,
            reduce = ::reduce,
            persistSlices = persistSlices
        )
    val state: StateFlow<SharedThreadComposerDraftState> = runtime.state

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        runtime.accept(intent)
    }

    /**
     * Synchronous serialized admission used by callbacks which already run on an upload
     * publication lane.  Unlike [dispatch], this returns the reducer's acceptance bit so an
     * external-resource owner can retire its local handle only after the projection was accepted.
     */
    fun accept(intent: SharedThreadComposerDraftIntent): Boolean = runtime.accept(intent)

    /** Exact post-transition snapshot for synchronous upload publication callbacks. */
    fun acceptWithState(
        intent: SharedThreadComposerDraftIntent
    ): MviDispatchReceipt<SharedThreadComposerDraftState> = runtime.acceptWithState(intent)

    /**
     * Admit an intent through the serialized reducer lane and return the post-transition
     * snapshot.  External-resource owners (Gallery direct-upload sessions in particular) must
     * use this receipt instead of treating [dispatch] as proof that a projection was accepted.
     */
    suspend fun acceptQueued(
        intent: SharedThreadComposerDraftIntent
    ): MviDispatchReceipt<SharedThreadComposerDraftState> =
        runtime.acceptQueuedWithState(intent)

    private fun reduce(
        current: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent
    ): SharedThreadComposerDraftState =
        when (intent) {
            is SharedThreadComposerDraftIntent.Start -> {
                val nextDraftKey = intent.draftKey?.takeIf { it.isNotBlank() }
                val sameSession =
                    current.instanceId == intent.composer.instanceId &&
                        current.draftKey == nextDraftKey
                val replaceMountedEditor =
                    sameSession &&
                        (current.seedText != intent.composer.seedText || current.seedAsTask != intent.composer.seedAsTask)
                val commandId =
                    when {
                        replaceMountedEditor -> current.editorCommandNonce + 1
                        sameSession -> current.editorCommandNonce
                        else -> 0
                    }
                SharedThreadComposerDraftState(
                    instanceId = intent.composer.instanceId,
                    draftKey = nextDraftKey,
                    seedText = intent.composer.seedText,
                    seedAsTask = intent.composer.seedAsTask,
                    // This preference belongs to the mounted composer session. A remount of the
                    // same instance keeps the user's choice; a new instance starts compressed.
                    originalImage = if (sameSession) current.originalImage else false,
                    // Keep the monotonic counter across a remount so a reused draft key cannot
                    // recreate an old admission generation.  The active witness map belongs to
                    // the mounted session and is retained only for the exact same instance/key.
                    nextAttachmentAdmissionGeneration = current.nextAttachmentAdmissionGeneration,
                    attachmentAdmissionsByLocalId =
                        if (sameSession) current.attachmentAdmissionsByLocalId else emptyMap(),
                    syncNonce = intent.composer.syncNonce,
                    editorCommandNonce = commandId,
                    editorCommand =
                        if (replaceMountedEditor) {
                            ThreadComposerEditorCommand(
                                id = commandId,
                                kind = ThreadComposerEditorCommandKind.Replace,
                                text = intent.composer.seedText,
                                asTask = intent.composer.seedAsTask,
                                expectedText = current.seedText,
                                expectedAsTask = current.seedAsTask,
                            )
                        } else if (sameSession) {
                            current.editorCommand
                        } else {
                            null
                        },
                    focusRequestNonce = intent.composer.focusRequestNonce
                )
            }
            is SharedThreadComposerDraftIntent.Change ->
                (intent.seedText ?: current.seedText).let { nextText ->
                    current.copy(
                        seedText = nextText,
                        seedAsTask = intent.seedAsTask ?: current.seedAsTask,
                        sending = false,
                        failureLabel = null,
                        externalShareSources =
                            if (
                                nextText.isBlank() &&
                                current.visibleSessionThreadComposerDraftAttachments().none { attachment ->
                                    attachment.source == "external_share"
                                }
                            ) {
                                emptyList()
                            } else {
                                current.externalShareSources
                            }
                    )
                }
            is SharedThreadComposerDraftIntent.SetOriginalImage ->
                current.copy(originalImage = intent.enabled)
            is SharedThreadComposerDraftIntent.InsertMention ->
                current.copy(
                    seedText = current.seedText.withInsertedThreadMention(intent.handle),
                    syncNonce = current.syncNonce + 1,
                    editorCommandNonce = current.editorCommandNonce + 1,
                    editorCommand =
                        ThreadComposerEditorCommand(
                            id = current.editorCommandNonce + 1,
                            kind = ThreadComposerEditorCommandKind.Replace,
                            text = current.seedText.withInsertedThreadMention(intent.handle),
                            asTask = current.seedAsTask,
                            expectedText = current.seedText,
                            expectedAsTask = current.seedAsTask,
                        ),
                    focusRequestNonce = current.focusRequestNonce + 1,
                    sending = false,
                    failureLabel = null
                )
            is SharedThreadComposerDraftIntent.AppendQuote -> {
                val nextText = appendThreadQuoteToComposer(current.seedText, intent.quote)
                if (nextText == current.seedText) {
                    current
                } else {
                    current.copy(
                        seedText = nextText,
                        syncNonce = current.syncNonce + 1,
                        editorCommandNonce = current.editorCommandNonce + 1,
                        editorCommand =
                            ThreadComposerEditorCommand(
                                id = current.editorCommandNonce + 1,
                                kind = ThreadComposerEditorCommandKind.Replace,
                                text = nextText,
                                asTask = current.seedAsTask,
                                expectedText = current.seedText,
                                expectedAsTask = current.seedAsTask,
                            ),
                        // Select Text > Quote: the reader goes on typing under the quote.
                        focusRequestNonce = current.focusRequestNonce + 1,
                        sending = false,
                        failureLabel = null
                    )
                }
            }
            SharedThreadComposerDraftIntent.Flush ->
                current.copy(persistBoundaryNonce = current.persistBoundaryNonce + 1)
            SharedThreadComposerDraftIntent.BeginSend ->
                current.copy(
                    seedText = "",
                    seedAsTask = false,
                    syncNonce = current.syncNonce + 1,
                    editorCommandNonce = current.editorCommandNonce + 1,
                    editorCommand =
                        ThreadComposerEditorCommand(
                            id = current.editorCommandNonce + 1,
                            kind = ThreadComposerEditorCommandKind.Clear,
                            expectedText = current.seedText,
                            expectedAsTask = current.seedAsTask,
                        ),
                    // Sending clears the current value, but it remains an active composer
                    // interaction. Reassert focus at the same synchronous boundary so a send
                    // click cannot turn the host clear into an editor blur/IME dismissal. Do not
                    // defer this to CompleteSend: that callback may arrive after the user has
                    // deliberately tapped outside the composer.
                    focusRequestNonce = current.focusRequestNonce + 1,
                    sending = true,
                    failureLabel = null,
                    // Keep the choice until the send attempt reaches a terminal completion.
                    originalImage = current.originalImage,
                    removedAttachmentIds = emptySet(),
                    uploadedAttachments = emptyList(),
                    attachmentAdmissionsByLocalId = emptyMap(),
                    externalShareSources = emptyList(),
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            is SharedThreadComposerDraftIntent.RestoreAfterFailure ->
                current.copy(
                    seedText = intent.seedText,
                    seedAsTask = intent.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    editorCommandNonce = current.editorCommandNonce + 1,
                    editorCommand =
                        ThreadComposerEditorCommand(
                            id = current.editorCommandNonce + 1,
                            kind = ThreadComposerEditorCommandKind.Replace,
                            text = intent.seedText,
                            asTask = intent.seedAsTask,
                            expectedText = current.seedText,
                            expectedAsTask = current.seedAsTask,
                        ),
                    sending = false,
                    failureLabel = intent.failureLabel
                )
            SharedThreadComposerDraftIntent.CompleteInlineSendFailure -> {
                val shouldClearComposer = current.sending
                current.copy(
                    seedText = if (shouldClearComposer) "" else current.seedText,
                    seedAsTask = if (shouldClearComposer) false else current.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    removedAttachmentIds = if (shouldClearComposer) emptySet() else current.removedAttachmentIds,
                    uploadedAttachments = if (shouldClearComposer) emptyList() else current.uploadedAttachments,
                    attachmentAdmissionsByLocalId =
                        if (shouldClearComposer) emptyMap() else current.attachmentAdmissionsByLocalId,
                    externalShareSources = if (shouldClearComposer) emptyList() else current.externalShareSources,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            }
            is SharedThreadComposerDraftIntent.CompleteSend -> {
                val shouldClearComposer = current.sending
                current.copy(
                    seedText = if (shouldClearComposer) "" else current.seedText,
                    seedAsTask = if (shouldClearComposer) false else current.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    removedAttachmentIds = if (shouldClearComposer) emptySet() else current.removedAttachmentIds,
                    uploadedAttachments = if (shouldClearComposer) emptyList() else current.uploadedAttachments,
                    attachmentAdmissionsByLocalId =
                        if (shouldClearComposer) emptyMap() else current.attachmentAdmissionsByLocalId,
                    externalShareSources = if (shouldClearComposer) emptyList() else current.externalShareSources,
                    pendingMentionActions = intent.pendingMentionActions,
                    originalImage = if (shouldClearComposer) false else current.originalImage,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            }
            is SharedThreadComposerDraftIntent.ApplyPendingMentionActions ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = intent.pendingMentionActions,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            is SharedThreadComposerDraftIntent.DismissMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId },
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    mentionActionStates = current.mentionActionStates - intent.resolutionId,
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.BeginMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    executingMentionActionIds = current.executingMentionActionIds + intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates + (intent.resolutionId to intent.state)
                )
            is SharedThreadComposerDraftIntent.BeginMentionActionBatch -> {
                val resolutionIds = intent.resolutionIds.map(String::trim).filter(String::isNotEmpty).distinct()
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = null,
                    executingMentionActionIds = current.executingMentionActionIds + resolutionIds,
                    executingMentionActionStates =
                        current.executingMentionActionStates + resolutionIds.associateWith { "added" }
                )
            }
            is SharedThreadComposerDraftIntent.FinishMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions,
                    mentionActionNoticeTitle = null,
                    mentionActionNotice = if (intent.delivered) null else intent.notice,
                    mentionActionStates =
                        if (intent.delivered) {
                            current.mentionActionStates + (intent.resolutionId to intent.state)
                        } else {
                            current.mentionActionStates - intent.resolutionId
                        },
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.FinishMentionActionBatch -> {
                val requestedIds = intent.requestedIds.map(String::trim).filter(String::isNotEmpty).toSet()
                val deliveredIds = intent.deliveredIds.intersect(requestedIds)
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions =
                        current.pendingMentionActions.filterNot { it.resolutionId.trim() in deliveredIds },
                    mentionActionNoticeTitle = intent.noticeTitle,
                    mentionActionNotice = intent.notice,
                    mentionActionStates = current.mentionActionStates - deliveredIds,
                    removingMentionActionIds = current.removingMentionActionIds - requestedIds,
                    executingMentionActionIds = current.executingMentionActionIds - requestedIds,
                    executingMentionActionStates = current.executingMentionActionStates - requestedIds
                )
            }
            SharedThreadComposerDraftIntent.ClearMentionActionNotice ->
                current.copy(mentionActionNoticeTitle = null, mentionActionNotice = null)
            is SharedThreadComposerDraftIntent.MarkMentionActionRemoving ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    removingMentionActionIds = current.removingMentionActionIds + intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.RemoveMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId },
                    mentionActionStates = current.mentionActionStates - intent.resolutionId,
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.AddUploadedAttachments -> {
                val expectedDraftKey = normalizeThreadAttachmentUploadDraftKey(intent.expectedDraftKey)
                if (expectedDraftKey != null && normalizeThreadAttachmentUploadDraftKey(current.draftKey) != expectedDraftKey) {
                    current
                } else if (!current.isAdmissibleUploadedAttachmentProjection(intent)) {
                    // A strict aggregate carries a complete ownership witness.  Validate every
                    // member before deriving a visible list; filtering removed ids here would
                    // otherwise let one stale sibling publish the rest of the server receipts.
                    current
                } else {
                    val visibleAttachments =
                        intent.attachments
                            .filterNot { attachment ->
                                attachment.id in current.removedAttachmentIds ||
                                    intent.replacedAttachmentIdsByUploadedId[attachment.id] in current.removedAttachmentIds
                            }
                    val replacedVisibleAttachmentIds =
                        visibleAttachments
                            .mapNotNull { attachment -> intent.replacedAttachmentIdsByUploadedId[attachment.id] }
                            .toSet()
                    val nextUploadedAttachments =
                        if (intent.replacedAttachmentIdsByUploadedId.isEmpty()) {
                            current.uploadedAttachments + visibleAttachments
                        } else {
                            val replacementsBySourceId =
                                visibleAttachments
                                    .mapNotNull { attachment ->
                                        intent.replacedAttachmentIdsByUploadedId[attachment.id]
                                            ?.let { sourceId -> sourceId to attachment }
                                    }
                                    .groupBy(
                                        keySelector = { (sourceId, _) -> sourceId },
                                        valueTransform = { (_, attachment) -> attachment }
                                    )
                            val unmappedAttachments =
                                visibleAttachments.filter { attachment ->
                                    attachment.id !in intent.replacedAttachmentIdsByUploadedId
                                }
                            current.uploadedAttachments.flatMap { attachment ->
                                replacementsBySourceId[attachment.id] ?: listOf(attachment)
                            } + unmappedAttachments
                        }
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        failureLabel = null,
                        removedAttachmentIds =
                            current.removedAttachmentIds +
                                intent.replacedAttachmentIds +
                                replacedVisibleAttachmentIds,
                        uploadedAttachments =
                            nextUploadedAttachments
                                .distinctBy { attachment -> attachment.id },
                        attachmentAdmissionsByLocalId =
                            if (intent.expectedAdmissions.isEmpty()) {
                                current.attachmentAdmissionsByLocalId -
                                    intent.replacedAttachmentIds
                            } else {
                                current.attachmentAdmissionsByLocalId -
                                    intent.expectedAdmissions.keys
                            },
                    )
                }
            }
            is SharedThreadComposerDraftIntent.AddLocalAttachments -> {
                val expectedDraftKey = normalizeThreadAttachmentUploadDraftKey(intent.expectedDraftKey)
                if (expectedDraftKey != null && normalizeThreadAttachmentUploadDraftKey(current.draftKey) != expectedDraftKey) {
                    current
                } else if (!intent.attachments.areValidLocalAttachmentAdmissionInputs()) {
                    current
                } else {
                    val localAttachmentIds = intent.attachments.map { attachment -> attachment.id }.toSet()
                    if (
                        current.nextAttachmentAdmissionGeneration < 0L ||
                        intent.attachments.size.toLong() >
                            Long.MAX_VALUE - current.nextAttachmentAdmissionGeneration
                    ) {
                        current
                    } else {
                        val replacementSlots =
                            if (intent.replacementAttachmentIdsByLocalId.isEmpty()) {
                                localAttachmentIds.associateWith { localId -> localId }
                            } else {
                                intent.replacementAttachmentIdsByLocalId
                                    .mapKeys { (id, _) -> id.trim() }
                                    .mapValues { (_, slot) -> slot.trim() }
                            }
                        val currentIds = current.uploadedAttachments.map { attachment -> attachment.id.trim() }.toSet()
                        val replacementInputsValid =
                            replacementSlots.keys == localAttachmentIds &&
                                (
                                    intent.replacementAttachmentIdsByLocalId.isEmpty() ||
                                        replacementSlots.size == intent.replacementAttachmentIdsByLocalId.size
                                )
                        val replacementTargetsValid =
                            replacementSlots.values.all(String::isNotBlank) &&
                                replacementSlots.values.toSet().size == replacementSlots.size &&
                                replacementSlots.all { (localId, slot) ->
                                    slot == localId ||
                                        (slot in currentIds &&
                                            slot !in current.removedAttachmentIds &&
                                            slot !in localAttachmentIds)
                                }
                        if (!replacementInputsValid || !replacementTargetsValid) {
                            current
                        } else {
                            var nextGeneration = current.nextAttachmentAdmissionGeneration
                            val nextAdmissions = current.attachmentAdmissionsByLocalId.toMutableMap()
                            intent.attachments.forEach { attachment ->
                                // A monotonically increasing process-local generation is the ABA fence
                                // for remove -> re-add of the same id. Overflow is rejected above rather
                                // than wrapping to an old witness.
                                nextGeneration += 1L
                                nextAdmissions[attachment.id] =
                                    SharedThreadComposerAttachmentAdmission(
                                        localAttachmentId = attachment.id,
                                        generation = nextGeneration,
                                        fileRef = attachment.fileRef,
                                        source = attachment.source,
                                        replacementAttachmentId = replacementSlots.getValue(attachment.id)
                                    )
                            }
                            current.copy(
                                syncNonce = current.syncNonce + 1,
                                sending = false,
                                failureLabel = null,
                                removedAttachmentIds = current.removedAttachmentIds - localAttachmentIds,
                                uploadedAttachments =
                                    (current.uploadedAttachments.map { attachment ->
                                        intent.attachments.firstOrNull { replacement -> replacement.id == attachment.id }
                                            ?: attachment
                                    } + intent.attachments.filter { attachment ->
                                        current.uploadedAttachments.none { existing -> existing.id == attachment.id }
                                    })
                                        .distinctBy { attachment -> attachment.id },
                                nextAttachmentAdmissionGeneration = nextGeneration,
                                attachmentAdmissionsByLocalId = nextAdmissions
                            )
                        }
                    }
                }
            }
            is SharedThreadComposerDraftIntent.MarkAttachmentsFailed -> {
                if (!current.isAdmissibleAttachmentFailure(intent)) {
                    current
                } else {
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        sending = false,
                        failureLabel = null,
                        uploadedAttachments =
                            current.uploadedAttachments.map { attachment ->
                                if (attachment.id in intent.attachmentIds) {
                                    attachment.copy(status = "failed", errorCode = intent.errorCode)
                                } else {
                                    attachment
                                }
                            }
                    )
                }
            }
            is SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress -> {
                if (!current.isAdmissibleAttachmentProgress(intent)) {
                    current
                } else {
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        uploadedAttachments =
                            current.uploadedAttachments.map { attachment ->
                                if (
                                    attachment.id == intent.attachmentId &&
                                    attachment.status == "uploading"
                                ) {
                                    // Samples hop dispatchers on their way here: never rewind.
                                    attachment.copy(
                                        uploadTransfer = attachment.uploadTransfer?.followedBy(intent.transfer) ?: intent.transfer
                                    )
                                } else {
                                    attachment
                                }
                            }
                    )
                }
            }
            is SharedThreadComposerDraftIntent.RemoveAttachment ->
                (current.removedAttachmentIds + intent.id).let { removedIds ->
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        sending = false,
                        removedAttachmentIds = removedIds,
                        attachmentAdmissionsByLocalId =
                            current.attachmentAdmissionsByLocalId - intent.id,
                        externalShareSources =
                            if (
                                current.seedText.isBlank() &&
                                current.uploadedAttachments.none { attachment ->
                                    attachment.source == "external_share" && attachment.id !in removedIds
                                }
                            ) {
                                emptyList()
                            } else {
                                current.externalShareSources
                            }
                    )
                }
            is SharedThreadComposerDraftIntent.RetryAttachment ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    removedAttachmentIds = current.removedAttachmentIds - intent.id,
                    // Retry starts a new local admission.  The subsequent AddLocal intent mints
                    // the next generation; retaining this witness would let a late callback from
                    // the failed attempt pass an identity check before that happens.
                    attachmentAdmissionsByLocalId =
                        current.attachmentAdmissionsByLocalId - intent.id
                )
        }
}

private fun SharedThreadComposerDraftState.withThreadComposerEditorCommand(
    kind: ThreadComposerEditorCommandKind,
    text: String,
    asTask: Boolean,
    expectedText: String,
    expectedAsTask: Boolean,
): SharedThreadComposerDraftState {
    val nextId = editorCommandNonce + 1
    return copy(
        editorCommandNonce = nextId,
        editorCommand =
            ThreadComposerEditorCommand(
                id = nextId,
                kind = kind,
                text = text,
                asTask = asTask,
                expectedText = expectedText,
                expectedAsTask = expectedAsTask,
            ),
    )
}

private fun SharedThreadComposerDraftState.requiresComposerEditorReplace(
    previous: SharedThreadComposerDraftState,
): Boolean = seedText != previous.seedText || seedAsTask != previous.seedAsTask

private fun SharedThreadComposerDraftState.visiblePersistableThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    uploadedAttachments
        .filterNot { attachment -> attachment.id in removedAttachmentIds }
        .persistableThreadComposerDraftAttachments()

private fun List<SharedThreadComposerAttachment>.persistableThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    asSequence()
        .filter { attachment -> attachment.id.isNotBlank() }
        .filter { attachment ->
            when {
                attachment.source in ThreadComposerOwnedTemporarySources ->
                    attachment.status in setOf("ready", "queued", "uploading", "failed") &&
                        attachment.hasOwnedTemporaryFileRef()
                attachment.status == "ready" -> true
                attachment.isValidExternalShareQueuedAttachment() -> true
                else -> false
            }
        }
        .distinctBy { attachment -> attachment.id }
        .toList()

/** A local retry row is durable only while its platform upload handle remains non-blank. */
private fun SharedThreadComposerAttachment.hasOwnedTemporaryFileRef(): Boolean =
    !fileRef?.trim().isNullOrBlank()

/**
 * Generic process-local ownership predicate used by draft replacement/clear cleanup.  The
 * callback retires temporary bytes of every admitted local attachment, including videos and
 * ordinary files; image/video classification is intentionally kept in the image-compression
 * admission path and must not strand non-image picker handles here.
 */
private fun SharedThreadComposerAttachment.isOwnedTemporaryFileRef(): Boolean =
    source in ThreadComposerOwnedTemporarySources && hasOwnedTemporaryFileRef()

private fun List<SharedThreadComposerAttachment>.temporaryFileRefs(): Set<String> =
    asSequence()
        .filter(SharedThreadComposerAttachment::isOwnedTemporaryFileRef)
        .mapNotNull { attachment -> attachment.fileRef?.trim()?.takeIf(String::isNotBlank) }
        .toSet()

/**
 * Every durable local handle participates in the in-flight retirement fence, even when another
 * subsystem (for example External Share) owns its eventual cleanup.  Retirement itself remains
 * restricted to [temporaryFileRefs], but allowing an External Share row to claim the same path
 * while native code is quarantining it would persist a reference to bytes which are about to
 * disappear.
 */
private fun List<SharedThreadComposerAttachment>.claimedLocalFileRefs(): Set<String> =
    asSequence()
        .mapNotNull { attachment -> attachment.fileRef?.trim()?.takeIf(String::isNotBlank) }
        .toSet()

private fun SharedThreadComposerAttachment.isValidExternalShareQueuedAttachment(): Boolean =
    source == "external_share" &&
        status in setOf("queued", "uploading", "failed") &&
        !fileRef.isNullOrBlank() &&
        !externalShareToken.isNullOrBlank() &&
        !externalShareAccountId.isNullOrBlank() &&
        !externalShareServerId.isNullOrBlank()

private fun List<SharedThreadComposerExternalShareSource>.validExternalShareComposerSources(): List<SharedThreadComposerExternalShareSource> =
    asSequence()
        .map { source ->
            source.copy(
                token = source.token.trim(),
                accountId = source.accountId.trim(),
                serverId = source.serverId.trim()
            )
        }
        .filter { source ->
            source.token.isNotBlank() && source.accountId.isNotBlank() && source.serverId.isNotBlank()
        }
        .distinctBy { source -> source.token }
        .toList()

internal fun String.withInsertedThreadMention(handle: String): String {
    val token = handle.toThreadMentionToken() ?: return this
    val mention = "@$token "
    if (isBlank()) return mention
    return if (last().isWhitespace()) {
        this + mention
    } else {
        "$this $mention"
    }
}

internal fun String.toThreadMentionToken(): String? =
    trim()
        .removePrefix("@")
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            raw.takeIf { value -> value.none { char -> char.isWhitespace() } }
                ?: raw.lowercase()
                    .replace(Regex("""\s+"""), "-")
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        }
        ?.takeIf { it.isNotBlank() }

internal class SharedThreadComposerDraftViewModel(
    persistSlices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>> = emptyList()
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadComposerDraftStore(persistSlices = persistSlices, scope = viewModelScope)
        }
    private val store: SharedThreadComposerDraftStore by storeDelegate
    val state: StateFlow<SharedThreadComposerDraftState>
        get() = store.state

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        store.dispatch(intent)
    }

    /** Synchronous serialized admission for an already-running upload publication callback. */
    fun accept(intent: SharedThreadComposerDraftIntent): Boolean = store.accept(intent)

    /** Exact post-transition snapshot for an already-running upload publication callback. */
    fun acceptWithState(
        intent: SharedThreadComposerDraftIntent
    ): MviDispatchReceipt<SharedThreadComposerDraftState> = store.acceptWithState(intent)

    /** Serialized draft transition receipt for callers that own external resources. */
    suspend fun acceptQueued(
        intent: SharedThreadComposerDraftIntent
    ): MviDispatchReceipt<SharedThreadComposerDraftState> =
        store.acceptQueued(intent)
}

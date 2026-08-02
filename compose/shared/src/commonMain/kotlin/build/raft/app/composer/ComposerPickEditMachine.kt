/*
 * Task #116 — shared composer pick→edit→result behavior machine (step 1 skeleton).
 * Pure core: no pager/eventBus/store dependencies — wiring lands per consumer cut.
 * References (never copies) the existing resolver and group reconcile (task #113).
 */
package build.raft.app.composer

import build.raft.app.data.PlatformFile
import build.raft.app.SlockImageEditGroupReconcile
import build.raft.app.SlockImageEditGroupState
import build.raft.app.ThreadPickedAttachmentComposerAction
import build.raft.app.resolveThreadPickedAttachmentComposerAction
import build.raft.app.slockComposerImagePickerFilesAreStrictImages
import kotlin.random.Random

internal data class ComposerPickEditSession(
    val sessionId: String,
    val groupId: String,
    val identity: ComposerSourceIdentity,
    val generation: ComposerGenerationToken,
    val limits: ComposerLimitsPolicy,
    val expectedCount: Int,
    val groupState: SlockImageEditGroupState,
    val filesByIndex: Map<Int, PlatformFile>,
)

internal sealed interface ComposerFoldOutcome {
    data class Pending(val session: ComposerPickEditSession) : ComposerFoldOutcome
    data class ReadyToCommit(val session: ComposerPickEditSession) : ComposerFoldOutcome
    data object Ignored : ComposerFoldOutcome
    data class Failed(val reason: String) : ComposerFoldOutcome
}

/** One decoded edit event (parse boundary lives in the wiring layer per surface). */
internal data class ComposerImageEditEvent(
    val sessionId: String,
    val groupId: String,
    val index: Int,
    val total: Int,
    val itemKey: String,
    val file: PlatformFile,
)

/**
 * The pick→edit→result behavior machine (v1.3 frozen design).
 *
 * Gate discipline: the surface injects [currentSessionValidator]; the machine consults
 * it after EVERY async boundary and immediately before accept — static fields only
 * pre-filter inside fold. Cancellation is a real terminal: [cancel] persists the
 * tombstone FIRST, then returns; every late event/promotion callback checks it.
 */
internal class ComposerPickEditMachine(
    private val authority: ComposerSessionAuthority,
    private val currentSessionValidator: (ComposerSourceIdentity, ComposerGenerationToken) -> Boolean,
    private val sessionIdFactory: () -> String = {
        "composer-edit-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"
    },
) {
    /** Gate decision — delegates to the existing shared resolver (never a copy). */
    fun resolvePickedAction(
        request: ComposerPickRequest,
        outcome: ComposerPickOutcome,
    ): ThreadPickedAttachmentComposerAction {
        val normalized =
            if (request.mediaPolicy == ComposerMediaPolicy.ImageOnly) outcome.files else outcome.files
        return resolveThreadPickedAttachmentComposerAction(
            action = if (request.mediaPolicy == ComposerMediaPolicy.ImageOnly) "attachImage" else "attachFile",
            fileCount = normalized.size,
            imageAnnotationEnabled =
                request.mediaPolicy == ComposerMediaPolicy.ImageOnly && request.annotationEnabled,
            allFilesAreImages = slockComposerImagePickerFilesAreStrictImages(normalized),
        )
    }

    /** Freeze the edit session: fresh group id, expected count, authority gates. */
    fun beginSession(
        request: ComposerPickRequest,
        files: List<PlatformFile>,
    ): ComposerPickEditSession? {
        if (files.isEmpty()) return null
        if (!currentSessionValidator(request.identity, request.generation)) return null
        val sessionId = sessionIdFactory()
        val groupId = "$sessionId-group"
        return ComposerPickEditSession(
            sessionId = sessionId,
            groupId = groupId,
            identity = request.identity,
            generation = request.generation,
            limits = request.limits,
            expectedCount = files.size,
            groupState =
                SlockImageEditGroupState(
                    expectedGroupId = groupId,
                    expectedCount = files.size,
                ),
            filesByIndex = files.mapIndexed { index, file -> index to file }.toMap(),
        )
    }

    /**
     * Fold one decoded edit event. Wrong-session events are ignored; terminal sessions
     * reject everything (post-cancel late Done, post-accept replay); fold correctness
     * (group/total/range/idempotent replay/conflict) is the shared reconcile's.
     */
    fun foldEvent(
        session: ComposerPickEditSession,
        event: ComposerImageEditEvent,
    ): ComposerFoldOutcome {
        if (event.sessionId != session.sessionId) return ComposerFoldOutcome.Ignored
        if (authority.terminalOf(session.sessionId) != null) return ComposerFoldOutcome.Ignored
        if (!currentSessionValidator(session.identity, session.generation)) {
            return ComposerFoldOutcome.Ignored
        }
        val sizeBefore = session.groupState.itemsByIndex.size
        val next =
            SlockImageEditGroupReconcile.fold(
                state = session.groupState,
                actualGroupId = event.groupId,
                index = event.index,
                total = event.total,
                itemKey = event.itemKey,
            )
        val accepted = next.itemsByIndex.size > sizeBefore
        val nextSession =
            if (accepted) {
                session.copy(
                    groupState = next,
                    filesByIndex = session.filesByIndex + (event.index to event.file),
                )
            } else {
                session.copy(groupState = next)
            }
        return when {
            next.rejects.isNotEmpty() && !next.isCommittable && next.isComplete ->
                ComposerFoldOutcome.Failed("group_rejected:${next.rejects.first()}")
            next.isCommittable -> ComposerFoldOutcome.ReadyToCommit(nextSession)
            else -> ComposerFoldOutcome.Pending(nextSession)
        }
    }

    /**
     * Real terminal cancel: persists the tombstone FIRST, then returns. All late
     * events and late promotion completions must observe it and do nothing.
     */
    fun cancel(session: ComposerPickEditSession, atEpochMs: Long) {
        authority.tryTerminate(
            ComposerSessionAuthorityRecord(
                sessionId = session.sessionId,
                identity = session.identity,
                generation = session.generation,
                terminal = ComposerSessionTerminal.Cancelled,
                terminalAtEpochMs = atEpochMs,
            ),
        )
    }

    /**
     * Final commit pipeline (v1.3): validator + terminal checks → durable promotion
     * (host capability) → RE-VALIDATE terminal + session after the promotion suspend
     * → first-terminal CAS(Active→Accepted) BEFORE any SSOT write → only a Won CAS
     * invokes the acceptor (the persistence authority). Cancel-during-accept therefore
     * loses the CAS and produces ZERO draft writes + released refs. Every non-Accepted
     * path after promotion releases the refs via the host capability.
     *
     * Host contract: the acceptor's Rejected decision must be computable before the
     * CAS; post-CAS the acceptor is the persistence authority and a Rejected there is
     * a host contract violation — the machine still releases refs and reports Failed,
     * and the Accepted tombstone stands (immutable first-terminal; refs are released
     * and the tombstone is GC'd past the replay horizon).
     */
    suspend fun commitSession(
        session: ComposerPickEditSession,
        capabilities: ComposerHostCapabilities,
        acceptor: ComposerEditResultAcceptor,
        atEpochMs: Long,
    ): ComposerEditResult {
        if (authority.terminalOf(session.sessionId) != null) {
            return ComposerEditResult.Cancelled("session_terminal")
        }
        if (!currentSessionValidator(session.identity, session.generation)) {
            return ComposerEditResult.Cancelled("stale_generation")
        }
        val editedFiles = (0 until session.expectedCount).mapNotNull { session.filesByIndex[it] }
        if (editedFiles.size != session.expectedCount || !session.groupState.isCommittable) {
            return ComposerEditResult.Failed("group_not_committable")
        }
        val promotion = capabilities.promoteToDurable(editedFiles, session.sessionId)
        val refs =
            when (promotion) {
                is ComposerPromotionOutcome.Promoted -> promotion.refs
                is ComposerPromotionOutcome.Failed ->
                    return ComposerEditResult.Failed("promotion_failed:${promotion.reason}")
            }
        // Post-suspend re-validation: terminal AND live session state must both still hold.
        if (authority.terminalOf(session.sessionId) != null) {
            capabilities.releaseDurableRefs(refs, session.sessionId, "session_terminal")
            return ComposerEditResult.Cancelled("session_terminal")
        }
        if (!currentSessionValidator(session.identity, session.generation)) {
            capabilities.releaseDurableRefs(refs, session.sessionId, "stale_generation")
            return ComposerEditResult.Cancelled("stale_generation")
        }
        // CAS-first: only the Accepted winner may write SSOT via the acceptor.
        return when (
            authority.tryTerminate(
                ComposerSessionAuthorityRecord(
                    sessionId = session.sessionId,
                    identity = session.identity,
                    generation = session.generation,
                    terminal = ComposerSessionTerminal.Accepted,
                    terminalAtEpochMs = atEpochMs,
                ),
            )
        ) {
            ComposerTerminalCasOutcome.Won ->
                when (acceptor.acceptEditResult(session.generation, refs)) {
                    ComposerAcceptDecision.Accepted -> ComposerEditResult.Complete(refs)
                    ComposerAcceptDecision.Rejected -> {
                        capabilities.releaseDurableRefs(refs, session.sessionId, "accept_rejected")
                        ComposerEditResult.Failed("accept_rejected_post_cas")
                    }
                }
            ComposerTerminalCasOutcome.SameTerminalReplay,
            ComposerTerminalCasOutcome.OppositeTerminalConflict -> {
                capabilities.releaseDurableRefs(refs, session.sessionId, "terminal_lost")
                ComposerEditResult.Cancelled("session_terminal")
            }
        }
    }
}

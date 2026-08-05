package build.raft.app

/**
 * Pure, surface-owned viewport policy. Each lazy surface owns one state instance;
 * domain stores and routes never persist or transport this pixel-level state.
 */
internal sealed interface LazyViewportIntent {
    data object FollowingLatest : LazyViewportIntent

    data object PinnedTop : LazyViewportIntent

    data class Anchored(
        val target: LazyViewportAnchorTarget,
        val offset: Int,
        val layoutGeneration: Long,
        val placement: LazyViewportAnchorPlacement,
        val renderedTailMessageId: String? = null,
        val renderedTailAnchorIdentity: String? = null,
        val fallback: LazyViewportAnchorFallback,
        val provenance: LazyViewportCommandProvenance
    ) : LazyViewportIntent

    data object UserDragging : LazyViewportIntent
}

/**
 * Who issued a viewport command.
 *
 * task #990: the controller could not tell a stale correction from the focus
 * positioning that must still apply, so a handoff had no way to invalidate the
 * former without also destroying the latter. Deliberately without a default —
 * a caller that forgets to say who it is fails to compile, rather than
 * defaulting into the class that survives retirement.
 */
internal sealed interface LazyViewportCommandProvenance {
    /**
     * The focus identity this command belongs to, or null when it belongs to no
     * focus at all. Carried by the type rather than beside it: an enum plus a
     * nullable identity admits `FocusedSessionReanchor` with no identity, which
     * compiles and then silently escapes retirement.
     */
    val ownerIdentity: String?

    /** Initial focus positioning. Survives a handoff: it is what put the target in place. */
    data class FocusedInitial(override val ownerIdentity: String) : LazyViewportCommandProvenance

    /** Drift compensation from the focused watch loop. Retired with its session. */
    data class FocusedSessionReanchor(override val ownerIdentity: String) : LazyViewportCommandProvenance

    /** Late correction after origin hydration. Retired with its session. */
    data class FocusedOriginCorrection(override val ownerIdentity: String) : LazyViewportCommandProvenance

    /** The composer's own correction for a keyboard shrink. */
    data class ComposerIme(override val ownerIdentity: String) : LazyViewportCommandProvenance

    /** Anything not part of the focused/composer handoff. */
    data object Other : LazyViewportCommandProvenance {
        override val ownerIdentity: String? = null
    }
}

internal sealed interface LazyViewportAnchorTarget {
    data class Message(val messageId: String) : LazyViewportAnchorTarget

    data class StableItem(val key: String) : LazyViewportAnchorTarget
}

internal enum class LazyViewportAnchorFallback {
    Free,
    PinnedTop,
    FollowingLatest
}

internal enum class LazyViewportAnchorPlacement {
    ExactOffset,
    Focused,
    RenderedBottom
}

internal enum class LazyViewportMutation {
    Prepend,
    Append,
    HeightChange,
    InsetChange
}

internal data class LazyViewportAnchorSnapshot(
    val epoch: Long,
    val target: LazyViewportAnchorTarget,
    val offset: Int,
    val layoutGeneration: Long
)

internal data class LazyViewportCommandId(
    val epoch: Long,
    val sequence: Long
)

internal sealed interface LazyViewportCommandTarget {
    data class Top(val animated: Boolean) : LazyViewportCommandTarget

    data object Latest : LazyViewportCommandTarget

    data class Anchor(
        val target: LazyViewportAnchorTarget,
        val offset: Int,
        val layoutGeneration: Long,
        val placement: LazyViewportAnchorPlacement = LazyViewportAnchorPlacement.ExactOffset,
        val renderedTailMessageId: String? = null,
        val renderedTailAnchorIdentity: String? = null
    ) : LazyViewportCommandTarget
}

internal data class LazyViewportCommand(
    val id: LazyViewportCommandId,
    val target: LazyViewportCommandTarget,
    val provenance: LazyViewportCommandProvenance
)

internal data class LazyViewportControllerState(
    val intent: LazyViewportIntent = LazyViewportIntent.FollowingLatest,
    val epoch: Long = 0L,
    val nextSequence: Long = 0L,
    val pendingCommand: LazyViewportCommand? = null,
    /**
     * task #990: receipt for the last command this controller actually
     * consumed. A waiter that starts observing after the command has already
     * been taken sees only an empty pending slot, which is indistinguishable
     * from a command that was dropped — so "did mine land" is answered from a
     * record the owner writes, rather than inferred from a gap in observation.
     *
     * Only [LazyViewportEvent.CommandConsumed] writes it. The other paths that
     * clear the pending slot — anchor unavailable, rendered-tail invalidation,
     * intent transitions — are not consumption and deliberately leave it alone.
     */
    val lastConsumedCommandId: LazyViewportCommandId? = null,
    /**
     * task #990: focus identity whose correction owners have been retired.
     *
     * Dropping the pending command is not enough on its own. The intent is what
     * generates commands, and it survives — so any later content mutation
     * regenerates the same correction from it, with the same provenance and
     * owner, and nothing re-issues a retirement because the handoff has already
     * happened. The race then reappears inside the very window the handoff was
     * meant to protect. Retirement therefore has to be a fact the controller can
     * keep applying, not a one-shot edit.
     *
     * Identity-scoped and never cleared: a focus identity is `messageId:nonce`,
     * so a new focus request never reuses a retired one.
     */
    val retiredCorrectionOwnerIdentity: String? = null
)

internal sealed interface LazyViewportEvent {
    data class FollowLatest(
        val canOverrideUserDragging: Boolean
    ) : LazyViewportEvent

    data object PinTop : LazyViewportEvent

    data class Anchor(
        val target: LazyViewportAnchorTarget,
        val offset: Int,
        val layoutGeneration: Long,
        val placement: LazyViewportAnchorPlacement = LazyViewportAnchorPlacement.ExactOffset,
        val renderedTailMessageId: String? = null,
        val renderedTailAnchorIdentity: String? = null,
        val fallback: LazyViewportAnchorFallback,
        // No default: a dispatch that does not say who issued it must not
        // silently join the class that survives a handoff.
        val provenance: LazyViewportCommandProvenance
    ) : LazyViewportEvent

    /**
     * task #990: a handoff ends the focused session and its late correction, so
     * whatever they already put in flight must stop being current. Only their
     * commands are retired — the initial focus positioning is left alone,
     * because it is the command that succeeded and must still apply.
     */
    data class RetireFocusedCorrections(val ownerIdentity: String) : LazyViewportEvent

    data object PointerDown : LazyViewportEvent

    data object PointerMove : LazyViewportEvent

    data class PointerSettled(
        val epoch: Long,
        val atRenderedBottom: Boolean,
        val anchorMessageId: String?,
        val anchorOffset: Int,
        val layoutGeneration: Long
    ) : LazyViewportEvent

    data class ContentMutated(val mutation: LazyViewportMutation) : LazyViewportEvent

    data class NewerPageSettled(
        val expectedViewportEpoch: Long,
        val expectedViewportIntent: LazyViewportIntent,
        val requestAnchor: LazyViewportAnchorTarget?,
        val requestAnchorOffset: Int,
        val layoutGeneration: Long
    ) : LazyViewportEvent

    data class AnchorUnavailable(val commandId: LazyViewportCommandId) : LazyViewportEvent

    data class RenderedTailCommandInvalidated(
        val commandId: LazyViewportCommandId,
        val currentLayoutGeneration: Long
    ) : LazyViewportEvent

    data class CommandConsumed(val commandId: LazyViewportCommandId) : LazyViewportEvent
}

internal fun reduceLazyViewport(
    state: LazyViewportControllerState,
    event: LazyViewportEvent
): LazyViewportControllerState =
    when (event) {
        is LazyViewportEvent.FollowLatest ->
            if (state.intent == LazyViewportIntent.UserDragging && !event.canOverrideUserDragging) {
                state
            } else {
                state.transitionTo(LazyViewportIntent.FollowingLatest, emitCommand = true)
            }
        LazyViewportEvent.PinTop ->
            state.transitionTo(
                LazyViewportIntent.PinnedTop,
                emitCommand = true,
                animateTop = true
            )
        is LazyViewportEvent.Anchor ->
            state.transitionTo(
                LazyViewportIntent.Anchored(
                    target = event.target,
                    offset = event.offset,
                    layoutGeneration = event.layoutGeneration,
                    placement = event.placement,
                    renderedTailMessageId = event.renderedTailMessageId,
                    renderedTailAnchorIdentity = event.renderedTailAnchorIdentity,
                    fallback = event.fallback,
                    provenance = event.provenance
                ),
                emitCommand = true
            )
        LazyViewportEvent.PointerDown ->
            state.transitionTo(LazyViewportIntent.UserDragging, emitCommand = false)
        LazyViewportEvent.PointerMove -> state
        is LazyViewportEvent.PointerSettled ->
            if (event.epoch != state.epoch || state.intent != LazyViewportIntent.UserDragging) {
                state
            } else if (event.atRenderedBottom) {
                state.transitionTo(LazyViewportIntent.FollowingLatest, emitCommand = false)
            } else {
                event.anchorMessageId
                    ?.takeIf(String::isNotBlank)
                    ?.let { messageId ->
                        state.transitionTo(
                            LazyViewportIntent.Anchored(
                                target = LazyViewportAnchorTarget.Message(messageId),
                                offset = event.anchorOffset,
                                layoutGeneration = event.layoutGeneration,
                                placement = LazyViewportAnchorPlacement.ExactOffset,
                                fallback = LazyViewportAnchorFallback.Free,
                                // Drag settlement, not part of the focused
                                // handoff: nothing here may be retired by it.
                                provenance = LazyViewportCommandProvenance.Other
                            ),
                            emitCommand = false
                        )
                    } ?: state.transitionTo(LazyViewportIntent.UserDragging, emitCommand = false)
            }
        is LazyViewportEvent.ContentMutated -> state.onContentMutation(event.mutation)
        is LazyViewportEvent.NewerPageSettled -> state.onNewerPageSettled(event)
        is LazyViewportEvent.AnchorUnavailable -> state.onAnchorUnavailable(event.commandId)
        is LazyViewportEvent.RenderedTailCommandInvalidated ->
            state.onRenderedTailCommandInvalidated(event)
        is LazyViewportEvent.RetireFocusedCorrections -> state.retireFocusedCorrections(event.ownerIdentity)
        is LazyViewportEvent.CommandConsumed ->
            if (state.pendingCommand?.id == event.commandId) {
                state.copy(pendingCommand = null, lastConsumedCommandId = event.commandId)
            } else {
                state
            }
    }

internal fun LazyViewportControllerState.accepts(command: LazyViewportCommand): Boolean =
    pendingCommand?.id == command.id &&
        epoch == command.id.epoch &&
        when (val currentIntent = intent) {
            LazyViewportIntent.FollowingLatest -> command.target == LazyViewportCommandTarget.Latest
            LazyViewportIntent.PinnedTop -> command.target is LazyViewportCommandTarget.Top
            is LazyViewportIntent.Anchored ->
                command.target ==
                    LazyViewportCommandTarget.Anchor(
                        target = currentIntent.target,
                        offset = currentIntent.offset,
                        layoutGeneration = currentIntent.layoutGeneration,
                        placement = currentIntent.placement,
                        renderedTailMessageId = currentIntent.renderedTailMessageId,
                        renderedTailAnchorIdentity = currentIntent.renderedTailAnchorIdentity
                    )
            LazyViewportIntent.UserDragging -> false
        }

internal fun LazyViewportControllerState.anchorSnapshotOrNull(): LazyViewportAnchorSnapshot? =
    (intent as? LazyViewportIntent.Anchored)?.let { anchored ->
        LazyViewportAnchorSnapshot(
            epoch = epoch,
            target = anchored.target,
            offset = anchored.offset,
            layoutGeneration = anchored.layoutGeneration
        )
    }

internal fun LazyViewportControllerState.acceptsRenderedTailCommand(
    command: LazyViewportCommand,
    currentLayoutGeneration: Long,
    currentTailMessageId: String?,
    currentAnchorIdentity: String?
): Boolean {
    if (!accepts(command)) return false
    val anchor = command.target as? LazyViewportCommandTarget.Anchor ?: return true
    val expectedTailMessageId = anchor.renderedTailMessageId ?: return true
    return anchor.layoutGeneration == currentLayoutGeneration &&
        expectedTailMessageId == currentTailMessageId &&
        anchor.renderedTailAnchorIdentity != null &&
        anchor.renderedTailAnchorIdentity == currentAnchorIdentity
}

private fun LazyViewportControllerState.onRenderedTailCommandInvalidated(
    event: LazyViewportEvent.RenderedTailCommandInvalidated
): LazyViewportControllerState {
    if (pendingCommand?.id != event.commandId) return this
    val anchored = intent as? LazyViewportIntent.Anchored ?: return copy(pendingCommand = null)
    if (anchored.renderedTailMessageId == null) return copy(pendingCommand = null)
    return transitionTo(
        nextIntent =
            anchored.copy(
                offset = 0,
                layoutGeneration = event.currentLayoutGeneration,
                placement = LazyViewportAnchorPlacement.Focused,
                renderedTailMessageId = null,
                renderedTailAnchorIdentity = null
            ),
        emitCommand = false
    )
}

/**
 * Drops an in-flight correction belonging to a retired focused session.
 *
 * The epoch is advanced so the executor's `accepts` check rejects the command
 * mid-flight — it already re-checks around every suspension point, so this is
 * enough to stop a correction that is partway through applying. The intent is
 * left alone: retiring a correction is not a statement about where the list
 * should be, only about who may still move it.
 *
 * [LazyViewportCommandProvenance.FocusedInitial] is deliberately excluded. If a
 * handoff lands while focus positioning is still in flight, that command must
 * still apply — it is the one that succeeded in both the healthy and the white
 * capture, and dropping it would leave the target unplaced.
 */
private fun LazyViewportCommandProvenance.isRetirableCorrection(): Boolean =
    when (this) {
        is LazyViewportCommandProvenance.FocusedSessionReanchor,
        is LazyViewportCommandProvenance.FocusedOriginCorrection -> true
        is LazyViewportCommandProvenance.FocusedInitial,
        is LazyViewportCommandProvenance.ComposerIme,
        LazyViewportCommandProvenance.Other -> false
    }

private fun LazyViewportControllerState.retireFocusedCorrections(
    ownerIdentity: String
): LazyViewportControllerState {
    // Recorded whether or not something is pending right now: the intent can
    // regenerate a command later, and that replay is the part that outlives a
    // one-shot clear.
    val retired = copy(retiredCorrectionOwnerIdentity = ownerIdentity)
    val pending = pendingCommand ?: return retired
    val retiring =
        pending.provenance.ownerIdentity == ownerIdentity &&
            pending.provenance.isRetirableCorrection()
    return if (retiring) retired.copy(epoch = epoch + 1L, pendingCommand = null) else retired
}

private fun LazyViewportControllerState.transitionTo(
    nextIntent: LazyViewportIntent,
    emitCommand: Boolean,
    animateTop: Boolean = false
): LazyViewportControllerState {
    val nextState =
        copy(
            intent = nextIntent,
            epoch = epoch + 1L,
            pendingCommand = null
        )
    return if (emitCommand) nextState.withCommandForIntent(animateTop = animateTop) else nextState
}

private fun LazyViewportControllerState.onContentMutation(
    mutation: LazyViewportMutation
): LazyViewportControllerState =
    when (intent) {
        LazyViewportIntent.UserDragging -> copy(pendingCommand = null)
        LazyViewportIntent.PinnedTop -> if (mutation == LazyViewportMutation.Append) this else withCommandForIntent()
        is LazyViewportIntent.Anchored ->
            if (mutation == LazyViewportMutation.Append && intent.renderedTailMessageId != null) {
                transitionTo(
                    nextIntent =
                        intent.copy(
                            offset = 0,
                            placement = LazyViewportAnchorPlacement.Focused,
                            renderedTailMessageId = null,
                            renderedTailAnchorIdentity = null
                        ),
                    emitCommand = false
                )
            } else if (mutation == LazyViewportMutation.Append) {
                this
            } else {
                withCommandForIntent()
            }
        LazyViewportIntent.FollowingLatest -> withCommandForIntent()
    }

private fun LazyViewportControllerState.onNewerPageSettled(
    event: LazyViewportEvent.NewerPageSettled
): LazyViewportControllerState {
    if (epoch != event.expectedViewportEpoch || intent != event.expectedViewportIntent) return this
    return when (intent) {
        LazyViewportIntent.UserDragging -> copy(pendingCommand = null)
        LazyViewportIntent.PinnedTop -> pendingCommand?.let { this } ?: withCommandForIntent()
        is LazyViewportIntent.Anchored -> pendingCommand?.let { this } ?: withCommandForIntent()
        LazyViewportIntent.FollowingLatest ->
            event.requestAnchor?.let { target ->
                transitionTo(
                    LazyViewportIntent.Anchored(
                        target = target,
                        offset = event.requestAnchorOffset,
                        layoutGeneration = event.layoutGeneration,
                        placement = LazyViewportAnchorPlacement.ExactOffset,
                        fallback = LazyViewportAnchorFallback.Free,
                        // Newer-page settlement requesting its own anchor.
                        provenance = LazyViewportCommandProvenance.Other
                    ),
                    emitCommand = true
                )
            } ?: withCommandForIntent()
    }
}

private fun LazyViewportControllerState.onAnchorUnavailable(
    commandId: LazyViewportCommandId
): LazyViewportControllerState {
    val anchored = intent as? LazyViewportIntent.Anchored ?: return this
    if (pendingCommand?.id != commandId || commandId.epoch != epoch) return this
    return when (anchored.fallback) {
        LazyViewportAnchorFallback.Free ->
            transitionTo(LazyViewportIntent.UserDragging, emitCommand = false)
        LazyViewportAnchorFallback.PinnedTop ->
            transitionTo(LazyViewportIntent.PinnedTop, emitCommand = true)
        LazyViewportAnchorFallback.FollowingLatest ->
            transitionTo(LazyViewportIntent.FollowingLatest, emitCommand = true)
    }
}

private fun LazyViewportControllerState.withCommandForIntent(
    animateTop: Boolean = false
): LazyViewportControllerState {
    val target =
        when (val currentIntent = intent) {
            LazyViewportIntent.FollowingLatest -> LazyViewportCommandTarget.Latest
            LazyViewportIntent.PinnedTop -> LazyViewportCommandTarget.Top(animated = animateTop)
            is LazyViewportIntent.Anchored ->
                LazyViewportCommandTarget.Anchor(
                    target = currentIntent.target,
                    offset = currentIntent.offset,
                    layoutGeneration = currentIntent.layoutGeneration,
                    placement = currentIntent.placement,
                    renderedTailMessageId = currentIntent.renderedTailMessageId,
                    renderedTailAnchorIdentity = currentIntent.renderedTailAnchorIdentity
                )
            LazyViewportIntent.UserDragging -> return copy(pendingCommand = null)
        }
    val anchored = intent as? LazyViewportIntent.Anchored
    if (
        anchored != null &&
        anchored.provenance.isRetirableCorrection() &&
        anchored.provenance.ownerIdentity != null &&
        anchored.provenance.ownerIdentity == retiredCorrectionOwnerIdentity
    ) {
        // A retired correction's intent must not be replayed. Reaching here
        // means a content mutation asked this intent to reissue — during a
        // keyboard shrink that is exactly the remeasure window the handoff
        // exists to protect, and the reissued command would carry the
        // pre-shrink offset.
        return copy(pendingCommand = null)
    }
    val sequence = nextSequence + 1L
    return copy(
        nextSequence = sequence,
        pendingCommand =
            LazyViewportCommand(
                id = LazyViewportCommandId(epoch = epoch, sequence = sequence),
                target = target,
                provenance = anchored?.provenance ?: LazyViewportCommandProvenance.Other
            )
    )
}

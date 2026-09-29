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
        val fallback: LazyViewportAnchorFallback
    ) : LazyViewportIntent

    data object UserDragging : LazyViewportIntent
}

// task #232 diagnostics: compact intent tag for viewport transition logs.
internal fun LazyViewportIntent.logTag(): String =
    when (this) {
        LazyViewportIntent.FollowingLatest -> "following"
        LazyViewportIntent.PinnedTop -> "pinned_top"
        LazyViewportIntent.UserDragging -> "user_dragging"
        is LazyViewportIntent.Anchored -> "anchored:${target}"
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
    val target: LazyViewportCommandTarget
)

internal data class LazyViewportControllerState(
    val intent: LazyViewportIntent = LazyViewportIntent.FollowingLatest,
    val epoch: Long = 0L,
    val nextSequence: Long = 0L,
    val pendingCommand: LazyViewportCommand? = null
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
        val fallback: LazyViewportAnchorFallback
    ) : LazyViewportEvent

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
                    fallback = event.fallback
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
                                fallback = LazyViewportAnchorFallback.Free
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
        is LazyViewportEvent.CommandConsumed ->
            if (state.pendingCommand?.id == event.commandId) state.copy(pendingCommand = null) else state
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
                        fallback = LazyViewportAnchorFallback.Free
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
    val sequence = nextSequence + 1L
    return copy(
        nextSequence = sequence,
        pendingCommand =
            LazyViewportCommand(
                id = LazyViewportCommandId(epoch = epoch, sequence = sequence),
                target = target
            )
    )
}

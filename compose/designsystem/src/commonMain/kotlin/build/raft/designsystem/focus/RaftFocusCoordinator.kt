package build.raft.designsystem.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Coarse UI regions for focus coordination (raft-design "Moving focus", #triage:#465).
 *
 * A passive/system focus request (default landing, data arrival, deferred completion) may fill
 * empty focus or move within its own region, never steal focus from another region holding text or
 * non-text keyboard focus.
 */
enum class RaftFocusRegion {
    TopBar,
    Navigation,
    ListPane,
    Conversation,
    SideThread,
    SupportingPane,
    Modal,
}

/** Who asked for focus to move (raft-design "Moving focus" rule 2). */
sealed interface RaftFocusRequestSource {
    /** Direct user action (tap, click, keyboard shortcut, explicit action). */
    data class User(
        val reason: String,
        val interactionEpoch: Long? = null,
    ) : RaftFocusRequestSource

    /**
     * Soft / automatic request (component mount, data arrival, async completion, close restore).
     *
     * Set [allowCrossRegionFromNonText] only when the mount itself was triggered directly by a user
     * navigation action (e.g. clicking or pressing Enter on a channel row in `ListPane` or on
     * "Reply in thread" in `Conversation`): it still yields to modals, menus/popovers, focused text
     * fields, touch-only hosts, and any user action or focus move during settle, while not letting
     * non-text focus left on the activating control block the newly opened conversation.
     */
    data class System(
        val reason: String,
        val allowCrossRegionFromNonText: Boolean = false,
    ) : RaftFocusRequestSource
}

/** Result of evaluating a [RaftFocusTicket] against the window's [RaftFocusCoordinator]. */
enum class RaftFocusDecision {
    Allow,

    /** The user tapped, clicked, or pressed a key after the ticket was issued (rule 3). */
    DropStaleInteraction,

    /** Focus moved to another owner/region after the ticket was issued (rule 3). */
    DropStaleFocus,

    /** A newer focus ticket was issued for the same target (rule 3). */
    DropSuperseded,

    /** An open dialog, menu, or popover holds the focus (rule 1). */
    DropOpenSurface,

    /** Another text field holds the focus (rule 1). */
    DropOtherTextField,

    /** Another region (including non-text keyboard focus) holds the focus (rule 2). */
    DropOtherRegionFocus,

    /** Focus did not fall to nowhere when restoring after a close (rule 4). */
    DropFocusNotLost,

    /** Touch-only device will not auto-focus a text field on a system request (rule 5). */
    DropTouchPrimary,
    ;

    val isAllowed: Boolean
        get() = this == Allow
}

/**
 * Captures the window's focus and user-interaction epochs when a focus request is created so that
 * any delayed execution (frame settle, async compression/upload, close restore) can drop itself if
 * the user acted or focus moved elsewhere in the meantime (rule 3).
 */
data class RaftFocusTicket(
    val id: Long,
    val targetKey: Any,
    val region: RaftFocusRegion?,
    val source: RaftFocusRequestSource,
    val isText: Boolean,
    val capturedFocusEpoch: Long,
    val capturedInteractionEpoch: Long,
    val modalToken: Any? = null,
)

/**
 * One window's focus coordinator (raft-design "Moving focus" rules 1–5; audit step 4 / #triage:#465).
 *
 * Tracks:
 * - [modalStack] (nested dialogs/sheets from `RaftWindowDialog`; only the topmost modal may receive
 *   focus requests while any modal is open),
 * - non-modal open popovers/menus (`RaftDropdownMenu`, `RaftFloatingOverlay`, which block `System`
 *   requests per Jasper's ruling in #triage:e5eea7eb without blocking explicit `User` actions),
 * - focused non-composer text fields (reported by `RaftTextField` / `RaftTextarea` and app editors),
 * - focused composers (kept apart so a composer never blocks itself and a yielding conversation
 *   composer may hand off to its opening side thread),
 * - focused regions and window-level non-text focus (so keyboard Tab onto a button/row is seen),
 * - monotonic [focusEpoch] (incremented when a new owner/region gains focus; never on blur/dispose
 *   or OS window refocus returning to a pre-modal region) and [interactionEpoch] (incremented on
 *   pointer-down / key-down at the window root, dialog, or menu popup).
 *
 * Main thread only. One per app window (`RaftWindowFocusScope`); compositions with no window scope
 * share [RaftAutofocusGuard.Default]'s coordinator.
 */
@Stable
class RaftFocusCoordinator {
    private var modalStack by mutableStateOf<List<Any>>(emptyList())
    private var openPopovers by mutableIntStateOf(0)
    private var focusedTextFields by mutableStateOf<Map<Any, RaftFocusRegion?>>(emptyMap())
    private var focusedRegions by mutableStateOf<Map<Any, RaftFocusRegion>>(emptyMap())
    private var focusedComposers by mutableStateOf<Map<Any, RaftFocusRegion?>>(emptyMap())
    private var windowFocusOwners by mutableStateOf<Set<Any>>(emptySet())

    // Region owners that held host-window focus just before the bottom-most modal opened; when that
    // modal closes and the OS returns window focus to the same host region without any new user
    // interaction, restoring that region must not spuriously bump focusEpoch and cancel a settling
    // navigation ticket (e.g. picking a result in HomeSearchOverlay).
    private var preModalRegionOwners: Set<Any> = emptySet()
    private var restoringPreModalRegionOwners: MutableSet<Any> = mutableSetOf()
    private var restoringPreModalInteractionEpoch: Long = -1L

    /** Increments whenever an element or region gains focus. */
    var focusEpoch: Long by mutableLongStateOf(0L)
        private set

    /** Increments on each pointer-down or key-down observed in this window. */
    var interactionEpoch: Long by mutableLongStateOf(0L)
        private set

    private var nextTicketId: Long = 0L
    private val latestTicketByTarget = mutableMapOf<Any, Long>()

    /** A dialog/sheet modal is currently open in this window. */
    val modalOpen: Boolean
        get() = modalStack.isNotEmpty()

    /** The token of the topmost open modal dialog, or null when no modal is open. */
    val topModalToken: Any?
        get() = modalStack.lastOrNull()

    /** A dialog, menu, or popover is currently open in this window. */
    val surfaceOpen: Boolean
        get() = modalStack.isNotEmpty() || openPopovers > 0

    /** A text field other than a conversation composer currently holds focus. */
    val otherTextFieldFocused: Boolean
        get() = focusedTextFields.isNotEmpty()

    /** Any conversation composer currently holds focus in this window. */
    val anyComposerFocused: Boolean
        get() = focusedComposers.isNotEmpty()

    /** The set of regions that currently contain a focused descendant. */
    val activeFocusedRegions: Set<RaftFocusRegion>
        get() = focusedRegions.values.toSet()

    /** True while anything in this window (text field, composer, region, or untagged control) holds focus. */
    val anyElementFocused: Boolean
        get() =
            focusedTextFields.isNotEmpty() ||
                focusedComposers.isNotEmpty() ||
                focusedRegions.isNotEmpty() ||
                windowFocusOwners.isNotEmpty()

    internal fun acquireModal(token: Any) {
        if (token in modalStack) return
        if (modalStack.isEmpty()) {
            preModalRegionOwners = focusedRegions.keys.toSet()
            restoringPreModalRegionOwners.clear()
        }
        modalStack = modalStack + token
    }

    internal fun releaseModal(token: Any) {
        if (token !in modalStack) return
        val next = modalStack - token
        modalStack = next
        if (next.isEmpty() && preModalRegionOwners.isNotEmpty()) {
            restoringPreModalRegionOwners = preModalRegionOwners.toMutableSet()
            restoringPreModalInteractionEpoch = interactionEpoch
            preModalRegionOwners = emptySet()
        }
    }

    /** Marks a modal dialog (`RaftWindowDialog`) as open on [modalStack] while in composition. */
    @Composable
    internal fun HoldModal(token: Any, enabled: Boolean = true) {
        DisposableEffect(enabled, token, this) {
            if (enabled) acquireModal(token)
            onDispose { if (enabled) releaseModal(token) }
        }
    }

    internal fun acquireOpenSurface() {
        openPopovers += 1
    }

    internal fun releaseOpenSurface() {
        if (openPopovers > 0) openPopovers -= 1
    }

    internal fun setTextFieldFocused(owner: Any, focused: Boolean, region: RaftFocusRegion? = null) {
        val wasFocused = owner in focusedTextFields
        if (focused) {
            if (!wasFocused || focusedTextFields[owner] != region) {
                focusedTextFields = focusedTextFields + (owner to region)
            }
            if (!wasFocused) focusEpoch += 1L
        } else if (wasFocused) {
            focusedTextFields = focusedTextFields - owner
        }
    }

    internal fun setRegionFocused(owner: Any, region: RaftFocusRegion, focused: Boolean) {
        val wasFocused = owner in focusedRegions
        if (focused) {
            if (!wasFocused || focusedRegions[owner] != region) {
                focusedRegions = focusedRegions + (owner to region)
            }
            if (!wasFocused) {
                val isOsModalReturn =
                    restoringPreModalInteractionEpoch == interactionEpoch &&
                        restoringPreModalRegionOwners.remove(owner)
                if (!isOsModalReturn) {
                    focusEpoch += 1L
                }
            }
        } else if (wasFocused) {
            focusedRegions = focusedRegions - owner
        }
    }

    fun setComposerFocused(owner: Any, focused: Boolean, region: RaftFocusRegion? = null) {
        val wasFocused = owner in focusedComposers
        if (focused) {
            if (!wasFocused || focusedComposers[owner] != region) {
                focusedComposers = focusedComposers + (owner to region)
            }
            if (!wasFocused) focusEpoch += 1L
        } else if (wasFocused) {
            focusedComposers = focusedComposers - owner
        }
    }

    /**
     * Tracks whether the host window root currently contains any focused descendant (used as a
     * fallback presence check for untagged non-text controls). Does not bump [focusEpoch]: OS
     * window refocus when a dialog dismisses fires `hasFocus = true` at the root without a new
     * focus target being chosen by the user.
     */
    internal fun setWindowHasFocus(owner: Any, hasFocus: Boolean) {
        val wasFocused = owner in windowFocusOwners
        if (hasFocus) {
            if (!wasFocused) {
                windowFocusOwners = windowFocusOwners + owner
            }
        } else if (wasFocused) {
            windowFocusOwners = windowFocusOwners - owner
        }
    }

    fun recordUserInteraction() {
        interactionEpoch += 1L
        if (restoringPreModalRegionOwners.isNotEmpty()) {
            restoringPreModalRegionOwners.clear()
        }
    }

    /** Issues a focus ticket capturing the current focus and user-interaction epochs. */
    fun request(
        targetKey: Any,
        region: RaftFocusRegion?,
        source: RaftFocusRequestSource,
        isText: Boolean = true,
        modalToken: Any? = null,
    ): RaftFocusTicket {
        nextTicketId += 1L
        val id = nextTicketId
        latestTicketByTarget[targetKey] = id
        val capturedInteraction =
            (source as? RaftFocusRequestSource.User)?.interactionEpoch ?: interactionEpoch
        return RaftFocusTicket(
            id = id,
            targetKey = targetKey,
            region = region,
            source = source,
            isText = isText,
            capturedFocusEpoch = focusEpoch,
            capturedInteractionEpoch = capturedInteraction,
            modalToken = modalToken,
        )
    }

    /** Invalidates and removes any pending ticket tracked for [targetKey]. */
    fun cancelPending(targetKey: Any) {
        latestTicketByTarget.remove(targetKey)
    }

    /** True while [ticket] is still the newest ticket for its target. */
    fun isLatest(ticket: RaftFocusTicket): Boolean = latestTicketByTarget[ticket.targetKey] == ticket.id

    /** Number of targets that currently hold an unconsumed pending focus ticket. */
    val pendingTicketCount: Int
        get() = latestTicketByTarget.size

    /** True once [ticket] has been superseded or invalidated by user input or a focus change. */
    fun isStale(ticket: RaftFocusTicket): Boolean =
        !isLatest(ticket) ||
            interactionEpoch != ticket.capturedInteractionEpoch ||
            focusEpoch != ticket.capturedFocusEpoch

    private fun isTopModalTarget(region: RaftFocusRegion?, modalToken: Any?): Boolean =
        region == RaftFocusRegion.Modal && modalOpen && !blockedByModalStack(region, modalToken)

    private fun hasBlockingOtherTextField(targetRegion: RaftFocusRegion?, modalToken: Any?): Boolean {
        if (!otherTextFieldFocused) return false
        if (!isTopModalTarget(targetRegion, modalToken)) return true
        // When targeting the topmost open modal, host-window text fields beneath the dialog that
        // have not blurred yet must not block the dialog's own auto-focus target; text fields
        // already focused inside the modal (or untagged) still block.
        return focusedTextFields.values.any { it == null || it == RaftFocusRegion.Modal }
    }

    /**
     * True while another region (or an untagged non-text control in the window) holds focus that a
     * passive system request for [targetRegion] must not steal (rule 2, audit S6).
     *
     * A focused conversation composer in [RaftFocusRegion.Conversation] does not block its opening
     * [RaftFocusRegion.SideThread] from taking focus. When targeting the topmost open modal
     * ([RaftFocusRegion.Modal]), host-window focus beneath the dialog does not block it.
     */
    fun hasBlockingFocusInOtherRegion(
        targetRegion: RaftFocusRegion?,
        modalToken: Any? = null,
    ): Boolean {
        if (isTopModalTarget(targetRegion, modalToken)) return false
        if (focusedRegions.isNotEmpty()) {
            val composerRegions = focusedComposers.values.filterNotNull().toSet()
            val blockedByOtherRegion =
                focusedRegions.values.any { focusedRegion ->
                    if (focusedRegion == targetRegion) {
                        false
                    } else if (
                        targetRegion == RaftFocusRegion.SideThread &&
                        focusedRegion == RaftFocusRegion.Conversation &&
                        focusedRegion in composerRegions
                    ) {
                        false
                    } else {
                        true
                    }
                }
            if (blockedByOtherRegion) return true
        }
        // Untagged non-text control focused in the window (outside any explicit RaftFocusRegionHost).
        if (
            windowFocusOwners.isNotEmpty() &&
            focusedComposers.isEmpty() &&
            focusedTextFields.isEmpty() &&
            focusedRegions.isEmpty()
        ) {
            return true
        }
        return false
    }

    private fun blockedByModalStack(region: RaftFocusRegion?, modalToken: Any?): Boolean {
        val activeModal = modalStack.lastOrNull() ?: return false
        val matchesTopModal =
            if (modalToken != null) {
                modalToken == activeModal
            } else {
                region == RaftFocusRegion.Modal && modalStack.size == 1
            }
        return !matchesTopModal
    }

    /**
     * True while a system request for [targetRegion] is currently blocked by an open modal,
     * menu/popover, text field, or other region.
     */
    fun blocksSystemFocus(
        targetRegion: RaftFocusRegion? = null,
        modalToken: Any? = null,
        allowCrossRegionFromNonText: Boolean = false,
    ): Boolean =
        blockedByModalStack(targetRegion, modalToken) ||
            (openPopovers > 0 && targetRegion != RaftFocusRegion.Modal) ||
            hasBlockingOtherTextField(targetRegion, modalToken) ||
            (!allowCrossRegionFromNonText && hasBlockingFocusInOtherRegion(targetRegion, modalToken))

    /**
     * Evaluates [ticket] at execution time against rules 1, 2, 3, and 5.
     * Pass [consume] = `true` on terminal evaluation so completed tickets are dropped from tracking.
     */
    fun evaluate(
        ticket: RaftFocusTicket,
        touchPrimary: Boolean = false,
        checkEpochs: Boolean = true,
        consume: Boolean = false,
    ): RaftFocusDecision {
        val latest = isLatest(ticket)
        if (consume && latest) {
            latestTicketByTarget.remove(ticket.targetKey)
        }
        if (!latest) return RaftFocusDecision.DropSuperseded
        if (checkEpochs && interactionEpoch != ticket.capturedInteractionEpoch) {
            return RaftFocusDecision.DropStaleInteraction
        }
        if (checkEpochs && focusEpoch != ticket.capturedFocusEpoch) {
            return RaftFocusDecision.DropStaleFocus
        }
        // Rule 1 (modals): requests outside the topmost open modal are dropped for both User and System.
        if (blockedByModalStack(ticket.region, ticket.modalToken)) {
            return RaftFocusDecision.DropOpenSurface
        }
        if (ticket.source is RaftFocusRequestSource.System) {
            // Non-modal menus/popovers block System requests only (Jasper ruling / audit line 98).
            if (openPopovers > 0 && ticket.region != RaftFocusRegion.Modal) {
                return RaftFocusDecision.DropOpenSurface
            }
            if (hasBlockingOtherTextField(ticket.region, ticket.modalToken)) {
                return RaftFocusDecision.DropOtherTextField
            }
            if (
                !ticket.source.allowCrossRegionFromNonText &&
                hasBlockingFocusInOtherRegion(ticket.region, ticket.modalToken)
            ) {
                return RaftFocusDecision.DropOtherRegionFocus
            }
            if (touchPrimary && ticket.isText) return RaftFocusDecision.DropTouchPrimary
        }
        return RaftFocusDecision.Allow
    }

    /**
     * Evaluates a close-restore [ticket] against rules 3, 4, and 5: only restore when focus fell
     * to nowhere, no modal or popover is open, and (for text targets) the host is not touch-only.
     *
     * Set [allowNonTextPlatformDetachFallback] only when the closing pane's own focused composer
     * was still focused at the start of the close composition: on Android in non-touch mode
     * (hardware keyboard), detaching a focused Compose node causes the Android View root to
     * immediately dump focus onto the first focusable non-text control in the window without any
     * user action. Modals, popovers, other text fields, composers, and post-close interactions or
     * focus moves still block restoration.
     */
    fun evaluateRestoreIfLost(
        ticket: RaftFocusTicket,
        touchPrimary: Boolean = false,
        checkEpochs: Boolean = true,
        consume: Boolean = false,
        allowNonTextPlatformDetachFallback: Boolean = false,
    ): RaftFocusDecision {
        val latest = isLatest(ticket)
        if (consume && latest) {
            latestTicketByTarget.remove(ticket.targetKey)
        }
        if (!latest) return RaftFocusDecision.DropSuperseded
        if (checkEpochs && interactionEpoch != ticket.capturedInteractionEpoch) {
            return RaftFocusDecision.DropStaleInteraction
        }
        if (checkEpochs && focusEpoch != ticket.capturedFocusEpoch) {
            return RaftFocusDecision.DropStaleFocus
        }
        if (blockedByModalStack(ticket.region, ticket.modalToken)) {
            return RaftFocusDecision.DropOpenSurface
        }
        if (openPopovers > 0 && ticket.region != RaftFocusRegion.Modal) {
            return RaftFocusDecision.DropOpenSurface
        }
        if (otherTextFieldFocused || anyComposerFocused) {
            return RaftFocusDecision.DropFocusNotLost
        }
        if (!allowNonTextPlatformDetachFallback && anyElementFocused) {
            return RaftFocusDecision.DropFocusNotLost
        }
        if (touchPrimary && ticket.isText) return RaftFocusDecision.DropTouchPrimary
        return RaftFocusDecision.Allow
    }
}

/**
 * Returns this window's [RaftFocusCoordinator] from the single [LocalRaftAutofocusGuard] source of
 * truth, so guard and coordinator can never drift out of sync.
 */
@Composable
@ReadOnlyComposable
fun currentRaftFocusCoordinator(): RaftFocusCoordinator = LocalRaftAutofocusGuard.current.coordinator

/** The coarse [RaftFocusRegion] enclosing this subtree, if any. */
val LocalRaftFocusRegion = staticCompositionLocalOf<RaftFocusRegion?> { null }

/** The token of the enclosing [build.raft.designsystem.components.RaftWindowDialog], or null outside a modal. */
val LocalRaftFocusModalToken = staticCompositionLocalOf<Any?> { null }

/** Guards against double-installing [raftObserveFocusInteractions] inside nested shells of the same window. */
val LocalRaftFocusInteractionObserverInstalled = staticCompositionLocalOf { false }

/**
 * Reports whether any focusable descendant inside this subtree holds focus to the window's
 * [RaftFocusCoordinator] under [region]. Changing [region] updates the registration in place
 * without clearing active focus or advancing [RaftFocusCoordinator.focusEpoch].
 */
@Composable
fun Modifier.raftFocusRegion(
    region: RaftFocusRegion,
    coordinator: RaftFocusCoordinator = LocalRaftAutofocusGuard.current.coordinator,
): Modifier {
    val owner = remember { Any() }
    var currentlyFocused by remember { mutableStateOf(false) }
    SideEffect {
        if (currentlyFocused) coordinator.setRegionFocused(owner, region, focused = true)
    }
    DisposableEffect(coordinator, owner) {
        onDispose { coordinator.setRegionFocused(owner, region, focused = false) }
    }
    return onFocusChanged { state ->
        currentlyFocused = state.hasFocus
        coordinator.setRegionFocused(owner, region, state.hasFocus)
    }
}

/**
 * Wraps [content] in a [Box] that provides [LocalRaftFocusRegion] and reports focus inside the
 * region (both text and non-text keyboard focus) to the window's [RaftFocusCoordinator].
 */
@Composable
fun RaftFocusRegionHost(
    region: RaftFocusRegion,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val coordinator = LocalRaftAutofocusGuard.current.coordinator
    CompositionLocalProvider(LocalRaftFocusRegion provides region) {
        Box(modifier = Modifier.raftFocusRegion(region = region, coordinator = coordinator).then(modifier)) {
            content()
        }
    }
}

/**
 * Passively observes pointer-down, key-down, and optional window-level focus presence on a window,
 * dialog, or popup root so [RaftFocusCoordinator] can advance [RaftFocusCoordinator.interactionEpoch]
 * and detect untagged non-text focus without consuming any input event.
 */
@Composable
fun Modifier.raftObserveFocusInteractions(
    coordinator: RaftFocusCoordinator = LocalRaftAutofocusGuard.current.coordinator,
    observeWindowFocus: Boolean = true,
    forceInstall: Boolean = false,
): Modifier {
    if (!forceInstall && LocalRaftFocusInteractionObserverInstalled.current) return this
    val focusOwner = remember { Any() }
    DisposableEffect(coordinator, focusOwner, observeWindowFocus) {
        if (!observeWindowFocus) coordinator.setWindowHasFocus(focusOwner, false)
        onDispose { coordinator.setWindowHasFocus(focusOwner, false) }
    }
    val pointerAndKey =
        this
            .pointerInput(coordinator) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) {
                            coordinator.recordUserInteraction()
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    coordinator.recordUserInteraction()
                }
                false
            }
    return if (observeWindowFocus) {
        pointerAndKey.onFocusChanged { state ->
            coordinator.setWindowHasFocus(focusOwner, state.hasFocus)
        }
    } else {
        pointerAndKey
    }
}

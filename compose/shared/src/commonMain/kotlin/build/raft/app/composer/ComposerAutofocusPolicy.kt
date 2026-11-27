package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.visibleSideThread
import build.raft.app.thread.key
import build.raft.designsystem.focus.LocalRaftAutofocusGuard
import build.raft.designsystem.focus.LocalRaftFocusInteractionObserverInstalled
import build.raft.designsystem.focus.LocalRaftFocusModalToken
import build.raft.designsystem.focus.LocalRaftFocusRegion
import build.raft.designsystem.focus.RaftAutofocusGuard
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusDecision
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.focus.raftObserveFocusInteractions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Opening a conversation puts the keyboard focus in its composer, as on the web (botiverse/slock
 * MessageInput `autoFocus`, default mode "desktop": focus on mount and on every channel change
 * when `(hover: hover)` matches; ChatPanel and the thread panel both pass it, so the thread panel,
 * mounted after the channel, ends up with the focus).
 *
 * Here: the Compose desktop app always; a phone or tablet only while a hardware keyboard is
 * connected. A touch-only device never takes focus on open, so no soft keyboard comes up by itself.
 * The request is focus only; it never asks for the soft keyboard ([ComposerAutofocusOnOpenEffect]).
 *
 * An open that lands on one message (a message link, an Activity row, a search result, a
 * permalink: the route's focused message) leaves the composer alone on a phone or tablet
 * ([composerAutofocusSkipsMessageOpen]).
 */

/** Whether opening a conversation focuses its composer: a desktop host, or a connected hardware keyboard. */
internal fun composerAutofocusOnOpen(desktopHost: Boolean, hardwareKeyboardConnected: Boolean): Boolean =
    desktopHost || hardwareKeyboardConnected

/**
 * A hardware keyboard is connected now (Android: a keyboard the configuration reports as present
 * and not hidden; iOS: `GCKeyboard.coalescedKeyboard`; desktop: always). Recomposes when one is
 * connected or removed.
 */
@Composable
internal expect fun rememberRaftHardwareKeyboardConnected(): Boolean

/**
 * Whether an open that lands on one message ([opensOnMessage]: the route has a focused message)
 * leaves the composer unfocused: on every host but desktop. On a phone or tablet the focus can
 * still raise the soft keyboard (Android shows it beside a hardware keyboard when the user's
 * setting says so, and cloud phones and emulators report a keyboard), and its inset pushed the
 * message the user came for off the screen. Desktop has no soft keyboard and keeps the web's
 * focus on open.
 */
internal fun composerAutofocusSkipsMessageOpen(desktopHost: Boolean, opensOnMessage: Boolean): Boolean =
    opensOnMessage && !desktopHost

@Composable
internal fun rememberComposerAutofocusOnOpen(): Boolean =
    composerAutofocusOnOpen(
        desktopHost = raftPlatformHasFinePointer(),
        hardwareKeyboardConnected = rememberRaftHardwareKeyboardConnected(),
    )

/**
 * Whether the conversation page [page] leaves the focus to the reply thread beside it: it is the
 * wide detail's main conversation while a side thread is shown for it. The side thread's own page
 * (its close request is the side thread's) and pages outside the wide detail (null) never yield.
 * When the side thread closes this turns false and the conversation takes the focus back only if
 * the focus was in the thread and left with it ([ComposerFocusOnOpen.restoreGeneration];
 * raft-design "Moving focus" rule 4).
 */
internal fun SharedAdaptiveWorkspaceSession.composerAutofocusYieldsToSideThread(
    page: RaftAdaptiveDetailCloseRequest?,
): Boolean {
    if (page == null) return false
    val side = visibleSideThread() ?: return false
    return !(side.identity.key == page.identityKey && side.generation == page.generation)
}

/**
 * Frames an open request waits for [RaftAutofocusGuard] to clear: the click or Enter that opened
 * the conversation may still be closing its menu, or blurring the search field it came from (the
 * outside-tap blur lands two frames after release). Still blocked after that, the user is in
 * something else, and the composer leaves the focus where it is.
 */
internal const val ComposerAutofocusSettleFrames = 6

/**
 * Frames a restore waits for the closed side thread to leave the composition and drop its focus;
 * also how long the focus leaving the thread's composer waits before it counts as the user moving
 * on (the composer leaving with its closing thread blurs it too).
 */
internal const val ComposerFocusRestoreSettleFrames = 2

internal enum class ComposerAutofocusOutcome {
    Focused,
    Blocked,
    Disabled,

    /** The open lands on one message: the composer stays unfocused ([composerAutofocusSkipsMessageOpen]). */
    OpensOnMessage,

    /** A pending open request was overtaken by user input, a focus move, or a newer ticket (rule 3). */
    Cancelled,
}

/**
 * One conversation page's automatic composer focus (raft-design "Moving focus"), remembered by the
 * page, not the composer, so a composer rebuilt under the page (its instance id swapped when the
 * conversation finishes loading) does not count as a new open.
 *
 * - The open request runs once per conversation ([ComposerAutofocusOnOpenEffect]'s key, the
 *   conversation's identity): it waits for the page's first showing ([shown]), then for
 *   [RaftAutofocusGuard] to clear, and reads [enabled] only then. A later change of [enabled]
 *   (a hardware keyboard connected again, the window widened, a side thread closed) or of [shown]
 *   (a page over this one popped) never runs it again: only a person opening the conversation does.
 * - [restoreGeneration] counts the side threads beside this conversation that closed; each one
 *   puts the focus back in the composer only when the focus was in the side thread's composer at
 *   the close ([sideThreadHeldFocus]) and went with it. Focus the user moved elsewhere while the
 *   thread was open (Members search, a row, a button) stays there.
 *
 * Main thread only.
 */
@Stable
internal class ComposerFocusOnOpen(
    enabled: Boolean = false,
    shown: Boolean = true,
    yieldsToSideThread: Boolean = false,
    opensOnMessage: Boolean = false,
    userInitiated: Boolean = false,
    policy: Boolean = enabled,
) {
    /** The platform allows it (desktop / hardware keyboard) and no reply thread beside takes it. */
    var enabled: Boolean by mutableStateOf(enabled)

    /**
     * The platform allows automatic composer focus (desktop / hardware keyboard), kept distinct
     * from [yieldsToSideThread] so deferred system requests can read touch-only status cleanly.
     */
    var policy: Boolean by mutableStateOf(policy)
        private set

    /** The page is on screen. */
    var shown: Boolean by mutableStateOf(shown)

    /**
     * The open lands on one message and the host can raise a soft keyboard
     * ([composerAutofocusSkipsMessageOpen]): the open request leaves the composer alone. Read when
     * the request runs, like [enabled]; a side thread closing still restores the focus it took.
     */
    var opensOnMessage: Boolean by mutableStateOf(opensOnMessage)

    /**
     * Whether this conversation was opened by a direct user navigation action (clicking or pressing
     * Enter on a channel/DM row, opening a side thread, picking a search result) rather than a
     * passive shell default landing. User-initiated opens may move focus away from non-text
     * keyboard focus (e.g. the activated list row or message action button) while still yielding to
     * open modals/menus/popovers, focused text fields, touch-only hosts, and settle interruptions.
     */
    var userInitiated: Boolean by mutableStateOf(userInitiated)

    /** +1 each time the reply thread beside this conversation closes. */
    var restoreGeneration: Int by mutableStateOf(0)

    /** A reply thread beside this conversation takes the focus. */
    var yieldsToSideThread: Boolean by mutableStateOf(yieldsToSideThread)
        private set

    /**
     * While a side thread was shown: whether its composer held the focus last. Recorded while the
     * thread is open, so it says where the focus was when the thread closed.
     */
    internal var sideThreadHeldFocus: Boolean = false

    /**
     * True when the side thread's composer still held focus at the moment [yieldsToSideThread]
     * flipped from true to false (before composition commit detached the closing thread). Used to
     * distinguish Android's involuntary non-touch fallback focus onto the window's first focusable
     * from a user who moved focus elsewhere before closing the thread.
     */
    internal var sideThreadDetachedWithFocus: Boolean = false

    internal var openedConversation: Any? = null

    /** The page's current answers; a side thread that stops being shown beside it asks for a restore. */
    internal fun update(
        enabled: Boolean,
        shown: Boolean,
        yieldsToSideThread: Boolean,
        opensOnMessage: Boolean = false,
        userInitiated: Boolean = false,
        sideThreadDetachedWithFocus: Boolean = false,
        policy: Boolean = enabled,
    ) {
        if (this.enabled != enabled) this.enabled = enabled
        if (this.policy != policy) this.policy = policy
        if (this.opensOnMessage != opensOnMessage) this.opensOnMessage = opensOnMessage
        if (this.userInitiated != userInitiated) this.userInitiated = userInitiated
        if (this.shown != shown) this.shown = shown
        if (this.yieldsToSideThread && !yieldsToSideThread) {
            this.sideThreadDetachedWithFocus = sideThreadDetachedWithFocus
            restoreGeneration += 1
        }
        if (this.yieldsToSideThread != yieldsToSideThread) this.yieldsToSideThread = yieldsToSideThread
    }
}

/**
 * The conversation composers that hold the focus now in one window, by their page's
 * [ComposerFocusOnOpen] ([composerReportsFocus]). Kept apart from the window's
 * [build.raft.designsystem.focus.RaftAutofocusGuard], where a composer never reports. One per
 * window ([LocalComposerFocusHolders], [RaftWindowFocusScope]): a composer focused in another app
 * window is not this window's side thread holding the focus. Main thread only.
 */
@Stable
internal class ComposerFocusHolders(
    private val coordinator: RaftFocusCoordinator = RaftAutofocusGuard.Default.coordinator,
) {
    var holders: Set<ComposerFocusOnOpen> by mutableStateOf(emptySet())
        private set

    fun set(page: ComposerFocusOnOpen, focused: Boolean, region: RaftFocusRegion? = null) {
        val next = if (focused) holders + page else holders - page
        if (next != holders) holders = next
        coordinator.setComposerFocused(page, focused, region)
    }

    companion object {
        /** The holders of a composition no window provides them for (tests, a single-window host). */
        val Default: ComposerFocusHolders = ComposerFocusHolders(RaftAutofocusGuard.Default.coordinator)
    }
}

/** This window's [ComposerFocusHolders]; [ComposerFocusHolders.Default] where no window provides them. */
internal val LocalComposerFocusHolders = staticCompositionLocalOf { ComposerFocusHolders.Default }

/**
 * Tracks active in-app overlays (`Modal(keepsHostKeyboard = true)`, e.g. thread message long-press
 * menus) that intentionally keep the host window's IME alive while their dialog window takes window
 * focus. Main thread only.
 */
@Stable
internal class RaftHostKeyboardRetentionState {
    private val tokens = mutableSetOf<Any>()
    var retained: Boolean by mutableStateOf(false)
        private set

    fun retain(token: Any) {
        if (tokens.add(token)) retained = true
    }

    fun release(token: Any) {
        if (tokens.remove(token)) retained = tokens.isNotEmpty()
    }
}

/** This window's [RaftHostKeyboardRetentionState], or null outside a [RaftWindowFocusScope]. */
internal val LocalRaftHostKeyboardRetentionState =
    staticCompositionLocalOf<RaftHostKeyboardRetentionState?> { null }

/**
 * Opaque handle for the platform host window wrapping [RaftWindowFocusScope] (Android: root
 * `LocalView.current`, so child `Dialog`/`Popup` windows with their own `AndroidComposeView` pass
 * straight through [RaftWindowPlatformInputGate] without watching the host Activity's window focus).
 */
@Composable
internal expect fun rememberRaftPlatformHostWindowToken(): Any?

/** Whether this [PlatformTextInputSession] belongs to the host window identified by [hostToken]. */
internal expect fun PlatformTextInputSession.belongsToRaftPlatformHostWindow(hostToken: Any?): Boolean

/**
 * Mobile touch-host platform text input gate implementing the 3-axis IME state model
 * (`Window Focus` × `Editor Focus & Cursor` × `Explicit Keyboard-Show Intent`):
 *
 * Compose's `TextFieldDecoratorModifierNode` keeps its `inputSessionJob` alive across external
 * window-focus loss (`isWindowFocused: true -> false`), which leaves `AndroidComposeView`'s
 * `onCheckIsTextEditor() == true` and allows Android IMMS to transfer/flash a floating window's
 * IME onto Raft on window-focus regain before `RaftInputFocusDismissHost` blurs focus.
 *
 * This interceptor releases ONLY the inner platform `startInputMethod` session on an external
 * `isWindowFocused: true -> false` transition (never wiping editor focus, draft text, or cursor,
 * and leaving `Modal(keepsHostKeyboard = true)` overlays untouched), parks the outer session so
 * window-focus regain keeps `onCheckIsTextEditor() == false`, and wakes/re-invokes the re-entrant
 * [PlatformTextInputMethodRequest] as soon as `SoftwareKeyboardController.show()` fires on an
 * explicit tap or programmatic keyboard-show intent.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Stable
internal class RaftWindowPlatformInputGate(
    private val hostWindowToken: () -> Any?,
    private val isWindowFocused: () -> Boolean,
    private val retainsHostKeyboard: () -> Boolean,
    private val scheduleBlurCleanup: ((() -> Unit) -> Unit)? = null,
) : PlatformTextInputInterceptor {
    private var parkedWake: CompletableDeferred<(() -> Unit)?>? = null
    private var activeHostInterceptions: Int = 0
    private var parkedAfterExternalWindowLoss: Boolean = false
    private val keyboardShowListeners = mutableSetOf<() -> Unit>()

    val isParked: Boolean
        get() = parkedAfterExternalWindowLoss || parkedWake != null

    fun addKeyboardShowListener(listener: () -> Unit) {
        keyboardShowListeners += listener
    }

    fun removeKeyboardShowListener(listener: () -> Unit) {
        keyboardShowListeners -= listener
    }

    fun notifyKeyboardShow(showPlatformKeyboard: () -> Unit) {
        keyboardShowListeners.toList().forEach { it() }
        val wake = parkedWake
        if (wake != null) {
            parkedAfterExternalWindowLoss = false
            parkedWake = null
            wake.complete(showPlatformKeyboard)
        } else {
            parkedAfterExternalWindowLoss = false
            showPlatformKeyboard()
        }
    }

    /**
     * Ends a parked window-focus-loss tenure when a host editor explicitly blurs (for example,
     * tapping the message list after returning from a floating window). Never wakes an exiting
     * parked session, so the blurring editor cannot blip `onCheckIsTextEditor()` while tearing down.
     */
    fun onHostEditorBlurred() {
        parkedAfterExternalWindowLoss = false
    }

    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        if (!nextHandler.belongsToRaftPlatformHostWindow(hostWindowToken())) {
            nextHandler.startInputMethod(request)
        }
        activeHostInterceptions += 1
        try {
            while (true) {
                var onWoken: (() -> Unit)? = null
                if (parkedAfterExternalWindowLoss) {
                    onWoken = awaitWake()
                }
                var wasWindowFocused = isWindowFocused()
                coroutineScope {
                    val activeSession =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            nextHandler.startInputMethod(request)
                        }
                    onWoken?.invoke()
                    // Trigger strictly on an external true -> false window-focus edge while no
                    // keepsHostKeyboard modal is open: closing a keepsHostKeyboard modal releases its
                    // retention token while isWindowFocused is still false for a frame, so ignoring
                    // retention flips prevents false parking on modal dismiss.
                    snapshotFlow { isWindowFocused() }
                        .first { focused ->
                            if (focused) {
                                wasWindowFocused = true
                                false
                            } else {
                                wasWindowFocused && !retainsHostKeyboard()
                            }
                        }
                    parkedAfterExternalWindowLoss = true
                    activeSession.cancelAndJoin()
                }
            }
        } finally {
            activeHostInterceptions = (activeHostInterceptions - 1).coerceAtLeast(0)
            if (activeHostInterceptions == 0 && parkedAfterExternalWindowLoss) {
                scheduleBlurCleanup?.invoke {
                    if (activeHostInterceptions == 0) {
                        parkedAfterExternalWindowLoss = false
                    }
                }
            }
        }
    }

    private suspend fun awaitWake(): (() -> Unit)? {
        val wake = CompletableDeferred<(() -> Unit)?>()
        parkedWake = wake
        return try {
            wake.await()
        } finally {
            if (parkedWake === wake) {
                parkedWake = null
            }
        }
    }
}

/** This window's [RaftWindowPlatformInputGate], or null on desktop / outside [RaftWindowFocusScope]. */
@OptIn(ExperimentalComposeUiApi::class)
internal val LocalRaftWindowPlatformInputGate =
    staticCompositionLocalOf<RaftWindowPlatformInputGate?> { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RaftWindowPlatformInputGateScope(
    desktopHost: Boolean = raftPlatformHasFinePointer(),
    hostKeyboardRetention: RaftHostKeyboardRetentionState,
    content: @Composable () -> Unit,
) {
    if (desktopHost) {
        content()
        return
    }
    val windowInfo = LocalWindowInfo.current
    val hostWindowToken = rememberRaftPlatformHostWindowToken()
    val parentKeyboardController = LocalSoftwareKeyboardController.current
    val gateScope = rememberCoroutineScope()
    val currentWindowInfo by rememberUpdatedState(windowInfo)
    val currentHostWindowToken by rememberUpdatedState(hostWindowToken)
    val gate =
        remember(hostKeyboardRetention, gateScope) {
            RaftWindowPlatformInputGate(
                hostWindowToken = { currentHostWindowToken },
                isWindowFocused = { currentWindowInfo.isWindowFocused },
                retainsHostKeyboard = { hostKeyboardRetention.retained },
                scheduleBlurCleanup = { onCleanup ->
                    gateScope.launch {
                        withFrameNanos { }
                        onCleanup()
                    }
                },
            )
        }
    val wrappedKeyboardController =
        remember(gate, parentKeyboardController) {
            object : SoftwareKeyboardController {
                override fun show() {
                    gate.notifyKeyboardShow {
                        parentKeyboardController?.show()
                    }
                }

                override fun hide() {
                    parentKeyboardController?.hide()
                }
            }
        }
    CompositionLocalProvider(
        LocalSoftwareKeyboardController provides wrappedKeyboardController,
        LocalRaftWindowPlatformInputGate provides gate,
    ) {
        InterceptPlatformTextInput(interceptor = gate, content = content)
    }
}

/**
 * One app window's focus bookkeeping: its own focus coordinator, autofocus guard and composer
 * focus holders, so a dialog, a focused field or a focused composer in one window never decides
 * automatic focus in another (Android's app windows, one per server, and the desktop window). Put
 * it at the window's root, around every page the window shows; popups and dialogs inside inherit
 * it. Hosts that also observe window-level key events before the Compose tree (Android
 * `RaftCmpActivity.dispatchKeyEvent`, desktop `Window.onPreviewKeyEvent`) may pass a pre-created
 * [coordinator] so those events advance the same `interactionEpoch`.
 */
@Composable
fun RaftWindowFocusScope(
    modifier: Modifier = Modifier,
    coordinator: RaftFocusCoordinator? = null,
    content: @Composable () -> Unit,
) {
    val resolvedCoordinator = coordinator ?: remember { RaftFocusCoordinator() }
    val guard = remember(resolvedCoordinator) { RaftAutofocusGuard(resolvedCoordinator) }
    val holders = remember(resolvedCoordinator) { ComposerFocusHolders(resolvedCoordinator) }
    val hostKeyboardRetention = remember { RaftHostKeyboardRetentionState() }
    CompositionLocalProvider(
        LocalRaftAutofocusGuard provides guard,
        LocalComposerFocusHolders provides holders,
        LocalRaftHostKeyboardRetentionState provides hostKeyboardRetention,
    ) {
        RaftWindowPlatformInputGateScope(hostKeyboardRetention = hostKeyboardRetention) {
            Box(modifier = modifier.raftObserveFocusInteractions(coordinator = resolvedCoordinator, forceInstall = true)) {
                CompositionLocalProvider(
                    LocalRaftFocusInteractionObserverInstalled provides true,
                    content = content,
                )
            }
        }
    }
}

/** Reports the composer editor's focus to its window's [ComposerFocusHolders] for its page [focus]. */
@Composable
internal fun Modifier.composerReportsFocus(focus: ComposerFocusOnOpen?): Modifier {
    if (focus == null) return this
    val holders = LocalComposerFocusHolders.current
    val region = LocalRaftFocusRegion.current
    var currentlyFocused by remember(focus) { mutableStateOf(false) }
    SideEffect {
        if (currentlyFocused) holders.set(focus, true, region)
    }
    DisposableEffect(focus, holders) {
        onDispose { holders.set(focus, false, region) }
    }
    return onFocusChanged {
        currentlyFocused = it.isFocused
        holders.set(focus, it.isFocused, region)
    }
}

/**
 * The page's [ComposerFocusOnOpen]: [policy] (desktop or a hardware keyboard,
 * [rememberComposerAutofocusOnOpen]), [shown] (the page is on screen) and [yieldsToSideThread]
 * ([composerAutofocusYieldsToSideThread]), [opensOnMessage] ([composerAutofocusSkipsMessageOpen]),
 * and [userInitiated] (direct user navigation vs passive shell default landing). Updated after each
 * composition; the composer reads it when its requests run, so a change here never recomposes the
 * composer.
 */
@Composable
internal fun rememberComposerFocusOnOpen(
    policy: Boolean,
    shown: Boolean,
    yieldsToSideThread: Boolean,
    opensOnMessage: Boolean = false,
    userInitiated: Boolean = false,
): ComposerFocusOnOpen {
    val holders = LocalComposerFocusHolders.current
    val focus =
        remember {
            ComposerFocusOnOpen(
                enabled = policy && !yieldsToSideThread,
                shown = shown,
                yieldsToSideThread = yieldsToSideThread,
                opensOnMessage = opensOnMessage,
                userInitiated = userInitiated,
                policy = policy,
            )
        }
    // Sample without read observation during composition (before applyChanges detaches the closing
    // side thread) so we know whether the side thread's composer still owned focus at close time.
    val sideThreadDetachedWithFocus =
        if (focus.yieldsToSideThread && !yieldsToSideThread) {
            Snapshot.withoutReadObservation { holders.holders.any { it !== focus } }
        } else {
            false
        }
    SideEffect {
        focus.update(
            enabled = policy && !yieldsToSideThread,
            shown = shown,
            yieldsToSideThread = yieldsToSideThread,
            opensOnMessage = opensOnMessage,
            userInitiated = userInitiated,
            sideThreadDetachedWithFocus = sideThreadDetachedWithFocus,
            policy = policy,
        )
    }
    return focus
}

/**
 * The composer side of [focus]: focuses [focusRequester] once per [conversation] (the
 * conversation's identity: its surface kind and thread / channel, never the composer instance)
 * unless [blocked] stays true for [ComposerAutofocusSettleFrames] frames, and focuses it again
 * after a side thread closes only when the thread's composer had the focus and took it along.
 * The editor carries [composerReportsFocus] for the same [focus]. Focus only: on Android and iOS the soft
 * keyboard stays down while a hardware keyboard is connected, and desktop has none.
 */
@Composable
internal fun ComposerAutofocusOnOpenEffect(
    conversation: Any?,
    focus: ComposerFocusOnOpen?,
    focusRequester: FocusRequester,
    blocked: (() -> Boolean)? = null,
    onOutcome: (ComposerAutofocusOutcome) -> Unit = {},
) {
    if (focus == null || conversation == null) return
    val guard = LocalRaftAutofocusGuard.current
    val coordinator = guard.coordinator
    val region = LocalRaftFocusRegion.current
    val modalToken = LocalRaftFocusModalToken.current
    val holders = LocalComposerFocusHolders.current
    val currentRequester by rememberUpdatedState(focusRequester)
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    val currentBlocked by rememberUpdatedState(blocked)
    val currentRegion by rememberUpdatedState(region)
    val currentModalToken by rememberUpdatedState(modalToken)
    DisposableEffect(focus, coordinator) {
        onDispose { coordinator.cancelPending(focus) }
    }
    val isCurrentlyBlocked: (Boolean) -> Boolean = { allowCrossRegionFromNonText ->
        (currentBlocked?.invoke() == true) ||
            coordinator.blocksSystemFocus(
                targetRegion = currentRegion,
                modalToken = currentModalToken,
                allowCrossRegionFromNonText = allowCrossRegionFromNonText,
            )
    }
    LaunchedEffect(focus, conversation, coordinator) {
        if (focus.openedConversation == conversation) return@LaunchedEffect
        // Opened behind another page or before the page appeared: the open is its first showing.
        if (!focus.shown) snapshotFlow { focus.shown }.first { it }
        val allowCrossRegionFromNonText = focus.userInitiated
        val ticket =
            coordinator.request(
                targetKey = focus,
                region = currentRegion,
                source =
                    RaftFocusRequestSource.System(
                        reason = "conversation_open",
                        allowCrossRegionFromNonText = allowCrossRegionFromNonText,
                    ),
                isText = true,
                modalToken = currentModalToken,
            )
        var frames = 0
        while (isCurrentlyBlocked(allowCrossRegionFromNonText) && frames < ComposerAutofocusSettleFrames) {
            if (coordinator.isStale(ticket)) break
            withFrameNanos { }
            frames += 1
        }
        // Read when the request runs: what the page and coordinator say now, not when composed.
        val decision =
            if (currentBlocked?.invoke() == true) {
                coordinator.cancelPending(focus)
                RaftFocusDecision.DropOpenSurface
            } else {
                coordinator.evaluate(ticket, consume = true)
            }
        val outcome =
            when {
                !decision.isAllowed ->
                    if (
                        decision == RaftFocusDecision.DropStaleInteraction ||
                        decision == RaftFocusDecision.DropStaleFocus ||
                        decision == RaftFocusDecision.DropSuperseded
                    ) {
                        ComposerAutofocusOutcome.Cancelled
                    } else {
                        ComposerAutofocusOutcome.Blocked
                    }
                !focus.enabled || !focus.shown -> ComposerAutofocusOutcome.Disabled
                focus.opensOnMessage -> ComposerAutofocusOutcome.OpensOnMessage
                currentRequester.tryRequestFocus() -> ComposerAutofocusOutcome.Focused
                else -> ComposerAutofocusOutcome.Blocked
            }
        focus.openedConversation = conversation
        currentOnOutcome(outcome)
    }
    LaunchedEffect(focus, holders) {
        // Where the focus is while a side thread is open: in another composer (the thread's), or
        // moved elsewhere by the user. The thread's composer leaving with the closing thread blurs
        // it as well; the close (yieldsToSideThread false) arrives within the settle frames and
        // cancels that wait, so the record keeps "the thread had it".
        snapshotFlow { focus.yieldsToSideThread to holders.holders.any { it !== focus } }
            .collectLatest { (yielding, threadHasFocus) ->
                if (!yielding) return@collectLatest
                if (threadHasFocus) {
                    focus.sideThreadHeldFocus = true
                } else {
                    repeat(ComposerFocusRestoreSettleFrames) { withFrameNanos { } }
                    if (focus.yieldsToSideThread) focus.sideThreadHeldFocus = false
                }
            }
    }
    LaunchedEffect(focus, holders, coordinator) {
        // Only side threads that close from now on: one that closed while this composer was not
        // mounted is history, not a request.
        var handled = focus.restoreGeneration
        snapshotFlow { focus.restoreGeneration }.collect { generation ->
            if (generation <= handled) return@collect
            handled = generation
            val restoreTicket =
                coordinator.request(
                    targetKey = focus,
                    region = currentRegion,
                    source = RaftFocusRequestSource.System("side_thread_close_restore"),
                    isText = true,
                    modalToken = currentModalToken,
                )
            // Let the closed side thread leave the composition and its focused element drop the focus.
            repeat(ComposerFocusRestoreSettleFrames) {
                if (coordinator.isStale(restoreTicket)) return@repeat
                withFrameNanos { }
            }
            val threadHadFocus = focus.sideThreadHeldFocus
            val detachedWithFocus = focus.sideThreadDetachedWithFocus
            focus.sideThreadHeldFocus = false
            focus.sideThreadDetachedWithFocus = false
            // Rule 4: back only if the focus went with the thread (nothing holds focus now and the
            // user did not act or move focus during settle). When the thread's composer detached
            // while focused on Android in non-touch mode, ignore the involuntary OS fallback onto
            // the window's first non-text focusable.
            val restoreDecision =
                coordinator.evaluateRestoreIfLost(
                    ticket = restoreTicket,
                    touchPrimary = !focus.policy,
                    consume = true,
                    allowNonTextPlatformDetachFallback = detachedWithFocus,
                )
            if (
                threadHadFocus &&
                focus.enabled &&
                focus.shown &&
                holders.holders.isEmpty() &&
                currentBlocked?.invoke() != true &&
                restoreDecision.isAllowed
            ) {
                currentRequester.tryRequestFocus()
            }
        }
    }
}

/** The editor is not attached (a read-only or not yet mounted composer): nothing to focus. */
private fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }

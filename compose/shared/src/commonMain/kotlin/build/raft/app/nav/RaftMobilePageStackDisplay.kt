package build.raft.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.ui.NavDisplay
import build.raft.app.shell.RAFT_OPEN_WITHOUT_ANIMATION
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.resources.RaftProvideResourceEnvironment
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * [stack]'s `NavDisplay` on a phone window (Android app shell; iOS later): [RaftMobileStackScene]
 * (push / pop motion, the predictive back preview of the page below), a ViewModel store per page,
 * and only the top page active.
 *
 * Pages not on top are paused, as an Activity under another one is stopped: their lifecycle is
 * capped at CREATED (the shared page host sends them `viewDidDisappear`, lifecycle-aware collectors
 * stop) and they are inactive ([RaftProvidePageActive]: their back handlers do not register, so a
 * covered shell's handlers never take Back from the page on top). Laid out at 0x0, they draw nothing.
 *
 * The root page (the app shell) is composed once, outside `NavDisplay`, and drawn under it where the
 * scenes would draw it ([RaftMobileStackRootPage]); the pages over it are `NavDisplay`'s entries.
 *
 * Text focus: when the top page changes (a push or a pop), the window's text focus is cleared before
 * the new top page composes, so a covered chat's composer gives up the keyboard (an Activity
 * covered by a new one loses window focus the same way) and a page that focuses its field on open
 * (Search) still gets it.
 *
 * Back order: the top page's own back handlers (registered after this display's), then this
 * display pops the top page, then, with only the root page left (no previous scene, `NavDisplay`'s
 * handler off), the root page's handlers and the host's own fallback.
 *
 * [transitions] (optional) hears each push, pop and predictive back gesture from its start to its
 * settled motion ([RaftStackTransitionTracker]); the Android host measures their frames.
 */
@Composable
fun RaftMobilePageStackDisplay(
    stack: RaftPageStack,
    transitions: RaftStackTransitionListener? = null,
    pageContent: @Composable (RaftStackPage) -> Unit,
) {
    // The root page never leaves the stack; guard NavDisplay's non-empty requirement anyway.
    if (stack.pages.isEmpty()) return
    val motion = remember { RaftMobileStackMotion().apply { settledTop = stack.pages.last().contentKey } }
    val contentKeys = stack.pages.map { it.contentKey }
    val tracker = remember { RaftStackTransitionTracker() }
    tracker.listener = transitions
    // After the change is applied, before the scenes' motion reports (their effects run later).
    SideEffect { tracker.onStack(contentKeys) }

    val topOpensWithoutMotion =
        (stack.pages.last().key.pageData[RAFT_OPEN_WITHOUT_ANIMATION] as? JsonPrimitive)?.booleanOrNull == true
    motion.update(contentKeys, topOpensWithoutMotion = topOpensWithoutMotion)
    // A settle that never comes (a scene left mid motion) must not leave the top page inactive.
    LaunchedEffect(contentKeys.last()) {
        delay(RaftMobileStackSettleFallbackMillis)
        motion.settledTop = contentKeys.last()
    }

    val sceneStrategy =
        remember(motion, tracker) { RaftMobileStackSceneStrategy<RaftStackPage>(motion, tracker.takeIf { transitions != null }) }
    val focusManager = LocalFocusManager.current
    // Before NavDisplay: its effects (the new top page's focus requests) run after this one.
    DisposableEffect(stack.pages.last().contentKey) {
        focusManager.clearFocus(force = true)
        onDispose {}
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    val entryDecorators = remember(saveableStateHolder) { listOf(raftMobileStackSaveableDecorator<RaftStackPage>(saveableStateHolder)) }
    // Share one locale observer across the whole phone stack so every pushed page skips
    // per-string collectAsState coroutines; the Android host stack wraps this in
    // RaftReactionAtlasProvider so bitmap decoding stays out of headless navigation tests.
    RaftProvideResourceEnvironment {
    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = stack.pages,
            onBack = { if (stack.pages.size > 1) stack.back() },
            entryDecorators = entryDecorators,
            sceneStrategies = listOf(sceneStrategy),
            transitionSpec = { RaftMobileStackSceneTransform },
            popTransitionSpec = { RaftMobileStackSceneTransform },
            predictivePopTransitionSpec = { RaftMobileStackSceneTransform },
            entryProvider = { page ->
                if (page === stack.pages.firstOrNull()) {
                    // Drawn by RaftMobileStackRootPage below; the scenes keep an empty slot for it.
                    NavEntry(page, contentKey = page.contentKey, metadata = raftMobileStackEntryMetadata(page) + RaftMobileStackRootSlot) {}
                } else {
                    NavEntry(page, contentKey = page.contentKey, metadata = raftMobileStackEntryMetadata(page)) {
                        RaftStackPageViewModels {
                            val top = stack.pages.lastOrNull() === page
                            val settled = motion.settledTop == page.contentKey
                            // Popped or replaced: still composed while it slides out, never shown again.
                            val leaving = stack.pages.none { it === page }
                            // On top: resumed at once (its content renders through the slide), active (back
                            // handlers, viewDidAppear: read state, realtime interest, loads) once settled.
                            // Until then it is in motion (LocalRaftStackPageInMotion): heavy first builds wait.
                            CompositionLocalProvider(
                                LocalRaftStackPageInMotion provides (top && !settled),
                                LocalRaftStackPageLeaving provides leaving,
                            ) {
                                RaftStackPageActivity(resumed = top, active = top && settled, toastAvoidActive = top) { pageContent(page) }
                            }
                        }
                    }
                }
            },
        )
        // After NavDisplay: its back handler registers first, and this reads the target scene's
        // RaftMobileStackMotion.rootShown of this same composition.
        RaftMobileStackRootPage(stack, motion, Modifier.zIndex(-1f), pageContent)
    }
    }
}

/**
 * The root page (the app shell), composed once, here, and never by NavDisplay (Hands 1dd76297,
 * 0ac26a92: "Key compose_slock_app_shell#0 was used multiple times", on a wide window as a page was
 * pushed over the shell). Navigation 3 renders each entry as movable content and moves it into the
 * scene of every push and pop; the root page is under every page, so the shell, with all it holds
 * (on the wide layout the conversation and its lists too), moved on every push and pop, and on a
 * wide window it was composed a second time while still composed. Here it stays where it is.
 *
 * Drawn under NavDisplay ([modifier] puts it below) where the scenes would have drawn it
 * ([RaftMobileStackMotion.rootShown]: on top, or covered by a push still sliding in; the scene's
 * slot for it takes the taps then), and measured at full size without placement while covered so
 * kept-alive tabs never collapse to 0x0 and rebuild on gesture back. Lifecycle and activity match
 * a stack page's, and no higher than STARTED until its scene has settled, as NavDisplay's scene
 * lifecycle capped it.
 */
@Composable
private fun RaftMobileStackRootPage(
    stack: RaftPageStack,
    motion: RaftMobileStackMotion,
    modifier: Modifier,
    pageContent: @Composable (RaftStackPage) -> Unit,
) {
    val root = stack.pages.firstOrNull() ?: return
    // A stable key (not the page instance) so the root page's saved UI state comes back after a recreation.
    // Keep the root shell measured at full window constraints and skip placement while covered
    // (reading motion.rootShown in placement only) so kept-alive tabs never collapse to 0x0 and
    // rebuild their lists / toolbars from scratch when gesture back reveals the shell.
    val placedModifier =
        remember(motion) {
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (motion.rootShown) placeable.place(0, 0)
                }
            }
        }
    key(root.contentKey) {
        Box(modifier.fillMaxSize().then(placedModifier)) {
            RaftStackPageViewModels {
                val top = stack.pages.lastOrNull() === root
                val settled = motion.settledTop == root.contentKey
                val maxLifecycle =
                    when {
                        top && settled -> Lifecycle.State.RESUMED
                        top -> Lifecycle.State.STARTED
                        else -> Lifecycle.State.CREATED
                    }
                CompositionLocalProvider(
                    LocalRaftStackPageInMotion provides (top && !settled),
                    LocalRaftStackPageLeaving provides false,
                ) {
                    RaftStackPageActivity(maxLifecycle = maxLifecycle, active = top && settled, toastAvoidActive = top) { pageContent(root) }
                }
            }
        }
    }
}

/** Entry metadata of the root page's slot in the scenes ([RaftMobileStackRootPage] draws the page). */
internal data object RaftMobileStackRootSlotKey : NavMetadataKey<Boolean>

private val RaftMobileStackRootSlot: Map<String, Any> = metadata { put(RaftMobileStackRootSlotKey, true) }

/**
 * NavDisplay's saved UI state per entry (its default decorator), without a state of its own for the
 * root page's empty slot: the root page keeps its state where it is composed.
 */
internal fun <T : Any> raftMobileStackSaveableDecorator(holder: SaveableStateHolder): NavEntryDecorator<T> =
    NavEntryDecorator(
        onPop = { contentKey -> holder.removeState(contentKey) },
        decorate = { entry ->
            if (entry.metadata[RaftMobileStackRootSlotKey] == true) {
                entry.Content()
            } else {
                holder.SaveableStateProvider(entry.contentKey) { entry.Content() }
            }
        },
    )

private const val RaftMobileStackSettleFallbackMillis = 1_000L

/**
 * True while the enclosing stack page is the top page and its motion (the push slide, a pop landing
 * on it) has not settled yet; false once settled, under the top page, and on every host without a
 * mobile page stack (iOS, Compose desktop, the Android stack flag off), so reading it changes nothing
 * there. A page whose content is ready shows it during the slide (#proj-mobile:#171: never "Loading"
 * when there is something to show) and defers only work beyond the screen until this turns false; the
 * chat's message list opens its scroll-ahead window after the slide.
 */
val LocalRaftStackPageInMotion = compositionLocalOf { false }

/**
 * True once the enclosing stack page has left the stack (popped, or replaced by another page) while
 * it is still composed for its exit motion; a covered page is not leaving. False on every host
 * without a mobile page stack. Work that must not outlive the page, such as a programmatic scroll of
 * its message list (Hands 712d8386), stops here instead of waiting for the page's disposal.
 */
val LocalRaftStackPageLeaving = compositionLocalOf { false }

/**
 * A stack page's lifecycle cap and page activity: on top it is resumed at once (its content renders
 * through the slide) and [active] once its motion settled; under the top page stopped and inactive.
 */
@Composable
internal fun RaftStackPageActivity(
    resumed: Boolean,
    active: Boolean = resumed,
    toastAvoidActive: Boolean = active,
    content: @Composable () -> Unit,
) = RaftStackPageActivity(
    if (resumed) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    active,
    toastAvoidActive,
    content,
)

/** [RaftStackPageActivity] with its lifecycle cap given. */
@Composable
internal fun RaftStackPageActivity(
    maxLifecycle: Lifecycle.State,
    active: Boolean,
    toastAvoidActive: Boolean = active,
    content: @Composable () -> Unit,
) {
    val owner = rememberLifecycleOwner(maxLifecycle = maxLifecycle)
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        RaftProvidePageActive(active = active, toastAvoidActive = toastAvoidActive, content = content)
    }
}

/** A page's entry metadata: its pane role plus the mobile scene's keys ([raftMobileStackMetadata]). */
internal fun raftMobileStackEntryMetadata(page: RaftStackPage): Map<String, Any> =
    raftPaneRoleMetadata(page.args.pageName) +
        raftMobileStackMetadata(
            coveredKeepsSize = page.args.pageName in RaftStackCoveredKeepsSizePages,
            opensWithoutMotion = (page.key.pageData[RAFT_OPEN_WITHOUT_ANIMATION] as? JsonPrimitive)?.booleanOrNull == true,
        )

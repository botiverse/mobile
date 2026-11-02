package build.raft.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import build.raft.app.RAFT_OPEN_WITHOUT_ANIMATION
import build.raft.app.compat.ui.RaftProvidePageActive
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
 * Text focus: when the top page changes (a push or a pop), the window's text focus is cleared before
 * the new top page composes, so a covered chat's composer gives up the keyboard (an Activity
 * covered by a new one loses window focus the same way) and a page that focuses its field on open
 * (Search) still gets it.
 *
 * Back order: the top page's own back handlers (registered after this display's), then this
 * display pops the top page, then, with only the root page left (no previous scene, `NavDisplay`'s
 * handler off), the root page's handlers and the host's own fallback.
 */
@Composable
fun RaftMobilePageStackDisplay(
    stack: RaftPageStack,
    pageContent: @Composable (RaftStackPage) -> Unit,
) {
    // The root page never leaves the stack; guard NavDisplay's non-empty requirement anyway.
    if (stack.pages.isEmpty()) return
    val motion = remember { RaftMobileStackMotion() }
    motion.update(stack.pages.map { it.contentKey })
    val sceneStrategy = remember(motion) { RaftMobileStackSceneStrategy<RaftStackPage>(motion) }
    val focusManager = LocalFocusManager.current
    // Before NavDisplay: its effects (the new top page's focus requests) run after this one.
    DisposableEffect(stack.pages.last().contentKey) {
        focusManager.clearFocus(force = true)
        onDispose {}
    }
    NavDisplay(
        backStack = stack.pages,
        onBack = { if (stack.pages.size > 1) stack.back() },
        sceneStrategies = listOf(sceneStrategy),
        transitionSpec = { RaftMobileStackSceneTransform },
        popTransitionSpec = { RaftMobileStackSceneTransform },
        predictivePopTransitionSpec = { RaftMobileStackSceneTransform },
        entryProvider = { page ->
            NavEntry(page, contentKey = page.contentKey, metadata = raftMobileStackEntryMetadata(page)) {
                RaftStackPageViewModels {
                    RaftStackPageActivity(active = stack.pages.lastOrNull() === page) { pageContent(page) }
                }
            }
        },
    )
}

/** A stack page's lifecycle cap and page activity: resumed and active on top, stopped and inactive under it. */
@Composable
internal fun RaftStackPageActivity(active: Boolean, content: @Composable () -> Unit) {
    val owner =
        rememberLifecycleOwner(maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        RaftProvidePageActive(active, content)
    }
}

/** A page's entry metadata: its pane role plus the mobile scene's keys ([raftMobileStackMetadata]). */
internal fun raftMobileStackEntryMetadata(page: RaftStackPage): Map<String, Any> =
    raftPaneRoleMetadata(page.args.pageName) +
        raftMobileStackMetadata(
            coveredKeepsSize = page.args.pageName in RaftStackCoveredKeepsSizePages,
            opensWithoutMotion = (page.key.pageData[RAFT_OPEN_WITHOUT_ANIMATION] as? JsonPrimitive)?.booleanOrNull == true,
        )

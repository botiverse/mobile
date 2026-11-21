package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackDisplay
import build.raft.designsystem.components.LocalRaftToastAvoidanceActive
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastAvoidBottom
import build.raft.designsystem.components.RaftToastBottomInsetProvider
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.raftToastAvoid
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * artin 2026-11-07 (recording): a toast moved with each page under it, above the composer, above the
 * tab bar, down to the window's edge on a page still loading. While it shows it only moves up (Kirby):
 * never down, and never left covering something taller that appears.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftToastHoldsPositionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.bottomOf(text: String): Dp = onNodeWithText(text).getBoundsInRoot().bottom

    /**
     * With the clock held, a state write from outside composition (or from layout: an avoidance
     * measured in that frame) needs its apply notification and a further frame to recompose.
     */
    private fun ComposeUiTest.settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(250)
            waitForIdle()
        }
    }

    private fun ComposeUiTest.host(
        state: RaftToastHostState,
        keyboard: () -> Dp = { 0.dp },
        scale: RaftScale = RaftScale.Compact,
        barHeight: () -> Dp,
    ) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = scale) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        val height = barHeight()
                        if (height > 0.dp) {
                            val density = LocalDensity.current
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
                                    .raftToastAvoid(keyboardPx = { with(density) { keyboard().toPx() } })
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun aToastDoesNotDropWhenThePageChangesUnderIt() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var bar by mutableStateOf(120.dp)
        mainClock.autoAdvance = false
        host(state) { bar }
        state.show("First")
        settle()
        val appeared = bottomOf("First")

        bar = 0.dp
        settle()
        assertEquals(appeared, bottomOf("First"), "the page changed; the toast stays")

        state.dismissAll()
        settle()
        state.show("Second")
        settle()
        assertTrue(bottomOf("Second") > appeared, "a new toast takes the page it appears on")
    }

    @Test
    fun aToastMovesUpForSomethingTallerAndStaysThere() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var bar by mutableStateOf(0.dp)
        mainClock.autoAdvance = false
        host(state) { bar }
        state.show("First")
        settle()
        val appeared = bottomOf("First")

        bar = 120.dp
        settle()
        val above = bottomOf("First")
        assertTrue(above < appeared, "the composer that appeared is not covered")

        bar = 0.dp
        settle()
        assertEquals(above, bottomOf("First"), "and it does not drop again")
    }

    /**
     * raft-design #1217: a wide window (a tablet) keeps the toast above the composer as a phone does,
     * not at the bottom of the window where it covered the composer while typing.
     */
    @Test
    fun aWideWindowsToastSitsAboveTheComposerToo() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        host(state, scale = RaftScale.Wide) { 120.dp }
        state.show("Wide")
        settle()
        val composerTop = onRoot().getBoundsInRoot().bottom - 120.dp
        // The text sits inside the toast's padding: its bottom is at least 16dp above the composer.
        assertTrue(bottomOf("Wide") <= composerTop - 16.dp, "above the composer, not over it at the window's bottom")
    }

    /** A toast shown while typing: above the composer on the keyboard, then back down with it. */
    @Test
    fun aToastComesBackDownWhenTheKeyboardCloses() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var keyboard by mutableStateOf(200.dp)
        mainClock.autoAdvance = false
        host(state, keyboard = { keyboard }) { 120.dp + keyboard }
        state.show("Typing")
        settle()
        val onKeyboard = bottomOf("Typing")

        keyboard = 0.dp
        settle()
        val closed = bottomOf("Typing")
        assertEquals(onKeyboard + 200.dp, closed, "down by the keyboard's height, still above the composer")
    }

    @Test
    fun aToastOnFirstFrameAlreadyClearsTheComposerWithoutDroppingToWindowEdge() = runRobolectricComposeUiTest {
        val state = RaftToastHostState().apply { show("Immediate") }
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(120.dp)
                                .raftToastAvoid()
                        )
                        RaftToastHost(state)
                    }
                }
            }
        }
        // Single frame (no multi-frame settle): onPlaced + placement-phase layout already clears the bar.
        val composerTop = onRoot().getBoundsInRoot().bottom - 120.dp
        assertTrue(bottomOf("Immediate") <= composerTop - 16.dp, "frame 1 already sits above the composer")
    }

    @Test
    fun topStackPageInMotionStillReportsToastAvoidanceAndDialogSubtreeDoesNot() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        // Simulates Nav3 top page mid-slide: active=false (back handlers wait), toastAvoidActive=true.
                        RaftProvidePageActive(active = false, toastAvoidActive = true) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .raftToastAvoid()
                            )
                        }
                        // Simulates separate dialog window whose local bounds must not override host avoidance.
                        CompositionLocalProvider(LocalRaftToastAvoidanceActive provides false) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .raftToastAvoid()
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Navigating")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastBottom = bottomOf("Navigating")
        assertTrue(toastBottom <= rootBottom - 116.dp, "clears the 100dp bar of the sliding-in top page")
        assertTrue(toastBottom > rootBottom - 260.dp, "ignores the 260dp bar inside an isolated dialog window")
    }

    @Test
    fun bottomInsetProviderFollowsHostImeInsteadOfStaleWindowInsets() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        var hostBottomInsetDp by mutableStateOf(0.dp)
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        RaftToastBottomInsetProvider { hostBottomInsetDp.toPx() }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Inset")
        settle()
        val resting = bottomOf("Inset")

        hostBottomInsetDp = 240.dp
        settle()
        assertEquals(resting - 240.dp, bottomOf("Inset"), "lifts by host-reported IME inset")

        hostBottomInsetDp = 0.dp
        settle()
        assertEquals(resting, bottomOf("Inset"), "returns to resting height once host IME closes")
    }

    @Test
    fun zeroSizedAndHorizontallyClippedAvoidanceNodesDoNotShootToastToTop() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        // Zero-sized avoided node (e.g., collapsed footer or hidden 0x0 subtree): ignored.
                        Box(Modifier.size(0.dp).raftToastAvoid())
                        // Horizontally translated completely offscreen inside a clipped parent (Frame 1 of push slide):
                        // positionInRoot().y stays at bottom - 100dp instead of boundsInRoot()'s clipped Rect.Zero (top = 0).
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = 2_000f }
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .raftToastAvoid()
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("Sliding")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastTop = onNodeWithText("Sliding").getBoundsInRoot().top
        val toastBottom = bottomOf("Sliding")
        assertTrue(toastTop > 100.dp, "toast must stay near bottom instead of shooting to y=0 ($toastTop)")
        assertTrue(toastBottom <= rootBottom - 116.dp, "toast still clears the 100dp bar on the offscreen sliding page")
    }

    @Test
    fun realRaftWindowDialogIsolatesItsAvoidancesFromHostToast() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize().testTag("toast-dialog-host-root")) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(100.dp)
                                .raftToastAvoid()
                        )
                        RaftWindowDialog(onDismissRequest = {}) {
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .testTag("toast-dialog-avoid-bar")
                                        .raftToastAvoid()
                                )
                            }
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("DialogHost")
        settle()
        assertTrue(
            onNodeWithTag("toast-dialog-avoid-bar").getBoundsInRoot().height >= 250.dp,
            "dialog avoidance bar actually mounted and laid out",
        )
        val rootBottom = onNodeWithTag("toast-dialog-host-root").getBoundsInRoot().bottom
        val toastBottom = bottomOf("DialogHost")
        assertTrue(toastBottom <= rootBottom - 116.dp, "clears the host window's 100dp bar")
        assertTrue(toastBottom > rootBottom - 260.dp, "ignores the 260dp bar inside RaftWindowDialog")
    }

    @Test
    fun realRaftStackSceneSuppressesCoveredPagesAndRespectsParentInactive() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        val mainStack = RaftPageStack()
        val nestedInactiveStack = RaftPageStack()
        mainStack.open(THREAD_PAGE, JsonObject(emptyMap()), from = mainStack.pages.single())
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        RaftPageStackDisplay(mainStack) { page ->
                            val isRoot = page.args.pageName == RAFT_APP_SHELL_PAGE
                            val barHeight = if (isRoot) 240.dp else 80.dp
                            // RaftToastAvoidBottom does not depend on layout size, so this verifies
                            // LocalRaftToastAvoidanceActive=false on the covered root even though
                            // RaftStackScene collapses covered pages to 0x0.
                            if (isRoot) RaftToastAvoidBottom(240.dp)
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(barHeight)
                                        .raftToastAvoid()
                                )
                            }
                        }
                        // Nested stack inside an inactive parent must remain inactive even for its top entry.
                        CompositionLocalProvider(LocalRaftToastAvoidanceActive provides false) {
                            RaftPageStackDisplay(nestedInactiveStack) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(300.dp)
                                            .raftToastAvoid()
                                    )
                                }
                            }
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("StackScene")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastBottom = bottomOf("StackScene")
        assertTrue(toastBottom <= rootBottom - 96.dp, "clears top stack entry's 80dp bar")
        assertTrue(toastBottom > rootBottom - 240.dp, "ignores covered root entry's 240dp bar and inactive nested stack's 300dp bar")
    }

    @Test
    fun realRaftMobilePageStackDisplaySuppressesCoveredRootAndTracksInMotionTop() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        val stack = RaftPageStack()
        var topInMotion: Boolean? = null
        var topActive: Boolean? = null
        mainClock.autoAdvance = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        RaftMobilePageStackDisplay(stack) { page ->
                            val isRoot = page.args.pageName == RAFT_APP_SHELL_PAGE
                            if (!isRoot) {
                                topInMotion = LocalRaftStackPageInMotion.current
                                topActive = LocalRaftPageActive.current
                            }
                            val barHeight = if (isRoot) 240.dp else 90.dp
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(barHeight)
                                        .raftToastAvoid()
                                )
                            }
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        settle()
        // Push a new page over the root; advance 1 frame so NavDisplay mounts THREAD_PAGE (removing
        // the covered root's 240dp avoidance) while the 300ms push slide has just started, then show
        // the toast and advance 256ms so the toast's 220ms entry translationY finishes while the
        // 300ms push slide is still in motion (top=true, settled=false).
        runOnIdle { stack.open(THREAD_PAGE, JsonObject(emptyMap()), from = stack.pages.single()) }
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        state.show("MobileStack")
        waitForIdle()
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(256)
        waitForIdle()
        assertEquals(true, topInMotion, "push slide is still in motion")
        assertEquals(false, topActive, "top page is not active yet while sliding in")
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val toastBottom = bottomOf("MobileStack")
        assertTrue(toastBottom <= rootBottom - 106.dp, "clears incoming top page's 90dp bar mid-slide")
        assertTrue(toastBottom > rootBottom - 240.dp, "ignores covered root's 240dp bar")
    }

    @Test
    fun realRaftTabBarHostReportsBottomBarAvoidanceToHostToast() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        val items =
            listOf(
                RaftBottomTabItemPayload(key = "home", label = "Home", selected = true),
                RaftBottomTabItemPayload(key = "tasks", label = "Tasks"),
            )
        mainClock.autoAdvance = false
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftToastHostState provides state,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                    Box(Modifier.fillMaxSize()) {
                        RaftAppBottomTabBarHost(
                            items = items,
                            onSelect = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                            bottomSafeArea = 0.dp,
                        )
                        RaftToastHost(state)
                    }
                }
            }
        }
        state.show("AboveTabBar")
        settle()
        val rootBottom = onRoot().getBoundsInRoot().bottom
        val barHeight = raftSharedBottomTabBarHostHeight(0.dp)
        assertTrue(
            bottomOf("AboveTabBar") <= rootBottom - barHeight - 16.dp,
            "toast clears real RaftAppBottomTabBarHost height ($barHeight)",
        )
    }

    @Test
    fun toastStaysHorizontallyCenteredUnderRtlWithoutDoubleMirroring() = runRobolectricComposeUiTest {
        val state = RaftToastHostState().apply { show("Centered") }
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalRaftToastHostState provides state,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                    Box(Modifier.width(291.dp).height(300.dp).testTag("rtl-toast-container")) {
                        RaftToastHost(state)
                    }
                }
            }
        }
        settle()
        val container = onNodeWithTag("rtl-toast-container").getBoundsInRoot()
        val toast = onNodeWithText("Centered").getBoundsInRoot()
        val containerCenter = (container.left.value + container.right.value) / 2f
        val toastCenter = (toast.left.value + toast.right.value) / 2f
        assertTrue(
            abs(containerCenter - toastCenter) <= 1.5f,
            "toast remains horizontally centered under RTL ($toastCenter vs $containerCenter)",
        )
    }

    /**
     * Regression for real-device #242 keyboard open/close check: when the composer sits deep inside
     * the page tree (much deeper than the root overlay's RaftToastHost Column), Compose's
     * DepthSortedSet runs RaftToastHost's layout pass before the composer's layout + onPlaced on
     * an IME frame. Reading `TopEdge.liveKeyboardPx` inside `RaftToastHost`'s layout pass must keep
     * the toast glued 16dp above the composer on that very first `measureAndLayoutForTest()` pass —
     * verified directly via `ViewRootForTest.semanticsOwner` without `waitForIdle()` so Frame 2
     * cannot hide a Frame 1 overlap or mid-air float.
     */
    @Test
    fun deeplyNestedComposerKeyboardOpenAndCloseHasZeroFrameLag() {
        val activity = activities.launch()
        val state = RaftToastHostState().apply { show("DeepSync") }
        var keyboard by mutableStateOf(0.dp)
        val composerHeight = 90.dp
        activity.setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = RaftScale.Compact) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        RaftToastBottomInsetProvider { keyboard.toPx() }
                        // Nest the composer 12 levels deep and drive its keyboard lift in the
                        // layout phase (mirroring ThreadComposerDock's raftThreadComposerLiveKeyboardPadding)
                        // so Compose's DepthSortedSet runs RaftToastHost's layout pass before the
                        // composer's layout + onPlaced on an IME frame.
                        NestedContainer(depth = 12) {
                            val density = LocalDensity.current
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        val kbPx = keyboard.roundToPx()
                                        val placeable = measurable.measure(constraints)
                                        layout(placeable.width, (placeable.height + kbPx).coerceAtMost(constraints.maxHeight)) {
                                            placeable.place(0, 0)
                                        }
                                    }
                                    .testTag("deep-composer")
                                    .raftToastAvoid(keyboardPx = { with(density) { keyboard.toPx() } })
                                    .height(composerHeight),
                            )
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        val composeRoot = findComposeRoot(activity.window.decorView) ?: error("no compose root")
        val density = activity.resources.displayMetrics.density
        val gapPx = 16f * density
        val openJumpPx = 220f * density

        fun settleHost() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                composeRoot.measureAndLayoutForTest()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

        fun composerTopPx(): Float =
            composeRoot.semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = false)
                .first { it.config.getOrNull(SemanticsProperties.TestTag) == "deep-composer" }
                .boundsInRoot
                .top

        fun toastBottomPx(): Float =
            composeRoot.semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = true)
                .first { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "DeepSync" } == true
                }
                .boundsInRoot
                .bottom

        settleHost()
        val restingToastBottom = toastBottomPx()
        val restingComposerTop = composerTopPx()
        assertTrue(
            restingToastBottom <= restingComposerTop - gapPx + 1f,
            "resting toast ($restingToastBottom) sits at least 16dp ($gapPx px) above composer ($restingComposerTop)",
        )

        // Keyboard opens in one frame: execute ONLY ONE snapshot apply + measureAndLayoutForTest()
        // pass (no second frame, no waitForIdle). Depth 6 RaftToastHost runs BEFORE Depth 12 composer.
        keyboard = 220.dp
        Snapshot.sendApplyNotifications()
        composeRoot.measureAndLayoutForTest()
        val openComposerTop = composerTopPx()
        val openToastBottom = toastBottomPx()
        assertTrue(
            openToastBottom <= openComposerTop - gapPx + 1f,
            "on Frame 1 of keyboard open, toast ($openToastBottom) must already clear composer ($openComposerTop) by 16dp ($gapPx px) without overlapping it",
        )
        assertTrue(
            abs((restingToastBottom - openToastBottom) - openJumpPx) <= 2f * density,
            "on Frame 1 of keyboard open, toast rose by the full 220dp (${restingToastBottom - openToastBottom} vs $openJumpPx)",
        )

        // Settle open state, then close keyboard in one frame: execute ONLY ONE layout pass!
        settleHost()
        keyboard = 0.dp
        Snapshot.sendApplyNotifications()
        composeRoot.measureAndLayoutForTest()
        val closeComposerTop = composerTopPx()
        val closeToastBottom = toastBottomPx()
        assertTrue(
            abs(closeToastBottom - restingToastBottom) <= 2f * density,
            "on Frame 1 of keyboard close, toast ($closeToastBottom) must drop back to resting ($restingToastBottom) above composer ($closeComposerTop) without floating mid-air",
        )
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Composable
    private fun NestedContainer(
        depth: Int,
        content: @Composable BoxScope.() -> Unit,
    ) {
        if (depth <= 0) {
            Box(Modifier.fillMaxSize(), content = content)
        } else {
            Box(Modifier.fillMaxSize()) { NestedContainer(depth - 1, content) }
        }
    }
}

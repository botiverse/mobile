package build.raft.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

/**
 * How the horizontal safe area reaches content: the page shell pads nothing (backgrounds run edge
 * to edge) and hands the inset down; `raftSafeAreaHorizontalPadding` pads it once per layout path,
 * so nested child pages, rows in a padded container and a modal never pad it twice; a side tab
 * bar's band joins the inset; physical edges stay physical in RTL. Bounds are in px at xxhdpi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h300dp-xxhdpi")
class RaftSafeAreaPropagationTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val bounds = mutableMapOf<String, Pair<Int, Int>>()
    private var rootWidth = 0
    private var density = 0f

    private fun px(dp: Int): Int = (dp * density).roundToInt()

    @Test
    fun embeddedChildPageInsideAPaddedContainerDoesNotPadAgain() {
        show(rightDp = 59) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(60.dp).raftSafeAreaHorizontalPadding()) {
                    // An embedded child page: its own page shell, the same window.
                    RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea()) {
                        Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("child.padded"))
                    }
                }
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea()) {
                        Box(Modifier.fillMaxWidth().track("child.background"))
                        Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("child.full"))
                    }
                }
            }
        }
        assertEquals(0 to rootWidth - px(59), bounds.getValue("child.padded"))
        // A full-width child page: its background reaches the edge, its content pads once.
        assertEquals(0 to rootWidth, bounds.getValue("child.background"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("child.full"))
    }

    @Test
    fun membersAdaptiveWorkspaceSplitConsumesInnerEdgesOnly() {
        show(leftDp = 36, rightDp = 42) {
            MembersAdaptiveWorkspace(
                split = true,
                selected = true,
                listPane = {
                    Box(Modifier.fillMaxSize().track("list.bg")) {
                        Box(Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().track("list.content"))
                    }
                },
                detailPane = {
                    Box(Modifier.fillMaxSize().track("detail.bg")) {
                        Box(Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().track("detail.content"))
                    }
                },
            )
        }
        assertEquals(0 to px(360), bounds.getValue("list.bg"))
        // List pane pads only its exposed left edge; inner right edge has zero padding.
        assertEquals(px(36) to px(360), bounds.getValue("list.content"))
        assertEquals(px(361) to rootWidth, bounds.getValue("detail.bg"))
        // Detail pane pads only its exposed right edge; inner left edge has zero padding.
        assertEquals(px(361) to rootWidth - px(42), bounds.getValue("detail.content"))
    }

    @Test
    fun bottomTabBarKeepsFullBleedBackgroundAndPadsSlotsInsideSafeArea() {
        show(leftDp = 36, rightDp = 42) {
            RaftBottomTabBar(
                items = listOf(RaftBottomTabItemPayload(key = AppShellServerTabKey, label = "Server")),
                onSelect = {},
                modifier = Modifier.track("bar.bg"),
                serverTab = { slotModifier -> Box(slotModifier.track("bar.slot")) },
            )
        }
        assertEquals(0 to rootWidth, bounds.getValue("bar.bg"))
        val sidePad = (RaftMobileNavDefaults.SidePadding.value * density).roundToInt()
        assertEquals(px(36) + sidePad to rootWidth - px(42) - sidePad, bounds.getValue("bar.slot"))
    }

    @Test
    fun modalWindowRootBleedsBackdropFullWidthAndDoesNotDoublePadNestedContent() {
        show(leftDp = 36, rightDp = 42) {
            RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea(), windowRoot = true) {
                val bleed = LocalRaftHorizontalSafeAreaBleed.current
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.raftBleedHorizontalSafeArea(bleed).fillMaxSize().track("modal.backdrop"))
                    Box(Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().track("modal.content"))
                }
            }
        }
        assertEquals(0 to rootWidth, bounds.getValue("modal.backdrop"))
        assertEquals(px(36) to rootWidth - px(42), bounds.getValue("modal.content"))
    }

    private fun show(
        leftDp: Int = 0,
        rightDp: Int = 0,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        tabBar: RaftNativeTabBarBridge? = null,
        pageShell: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        val activity = activities.launch()
        val windowId = "propagation-${seq++}"
        activity.setContent {
            density = LocalDensity.current.density
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject("""{"slockWindowId":"$windowId"}""")) }
                }
            remember {
                RaftHostWindowInsets.publish(
                    windowId,
                    RaftHostWindowInsetValues(0f, 0f, statusBarHidden = true, leftPx = leftDp * density, rightPx = rightDp * density),
                )
            }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalLayoutDirection provides layoutDirection,
                LocalRaftNativeTabBarBridge provides tabBar,
            ) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { rootWidth = it.size.width }) {
                    if (pageShell) RaftPageHorizontalSafeAreaShell(content) else content()
                }
            }
        }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun Modifier.track(name: String): Modifier =
        onGloballyPositioned { coordinates ->
            val left = coordinates.positionInRoot().x.roundToInt()
            bounds[name] = left to left + coordinates.size.width
        }

    private companion object {
        var seq = 0
    }
}

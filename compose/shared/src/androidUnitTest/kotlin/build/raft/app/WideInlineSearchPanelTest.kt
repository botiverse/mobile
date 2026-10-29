package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 2026-10-29 (Compose 版): the wide layout's inline Search (top bar search field, the Duo
 * rail's Search) opens with its field focused and draws no Back button beside it; Esc / system
 * Back close it through the shell's root Back handler (on desktop, Compose maps Esc to Back).
 * The list column is 360dp wide here, as in the screenshot. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h300dp-xxhdpi")
class WideInlineSearchPanelTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wideInlineSearchLight() =
        RaftRobolectricScreenshot.capture("search.wide-inline", RaftAppAppearance.Light, reportDir = REPORT_DIR, activities = activities) {
            Host { InlinePanel() }
        }

    @Test
    fun openingTheInlineSearchFocusesItsFieldWithoutABackButton() {
        var focused = false
        val activity = activities.launch()
        // Robolectric's activity window never gains focus on its own (ThreadComposerImeCompositionTest).
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            Host {
                Box(Modifier.fillMaxSize().onFocusChanged { focused = it.hasFocus }) { InlinePanel() }
            }
        }
        settle()
        // The open focus waits a short real-time delay (kotlinx `delay` off the main looper), so
        // the fake looper clock alone does not reach it: give it wall time too.
        repeat(50) {
            if (focused) return@repeat
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        assertTrue(focused, "the inline Search field takes focus as it opens")
        assertEquals(0, activity.backButtons(), "no Back button beside the field in the wide layout")
    }

    @Test
    fun thePushedSearchPageKeepsItsBackButton() {
        val activity = activities.launch()
        activity.setContent { Host { InlinePanel(chrome = homeSearchPageChrome(tabRoot = false)) } }
        settle()
        assertEquals(1, activity.backButtons(), "a pushed (channel) search keeps Back")
    }

    @Test
    fun backClosesTheInlineSearch() {
        var open by mutableStateOf(true)
        var closes = 0
        val activity = activities.launch()
        activity.setContent {
            Host {
                // The shell's root handler: Back (and Esc on desktop) leaves the inline Search
                // (popMainRoute clears railInlineSecondaryDestination).
                RaftAppShellRootBackHandler {
                    closes++
                    open = false
                }
                if (open) InlinePanel(onClose = { open = false })
            }
        }
        settle()
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(1, closes)
        assertFalse(open, "Back closes the inline Search")
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) { content() }
        }
    }

    @Composable
    private fun InlinePanel(
        chrome: HomeSearchPageChrome = homeSearchPageChrome(tabRoot = false, railInline = true),
        onClose: () -> Unit = {},
    ) {
        SharedHomeSearchPanel(
            state = SharedHomeSearchPanelState(serverId = "server-1"),
            homeState = SharedHomeState(),
            onIntent = {},
            onClose = onClose.takeIf { chrome.showsBack },
            showCloseButton = chrome.showsBackButton,
            initialFocusInput = chrome.focusesInputOnOpen,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 0.dp,
        )
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun ComponentActivity.backButtons(): Int {
        val root = assertNotNull(findRoot(window.decorView), "no compose root")
        fun count(node: SemanticsNode): Int =
            (if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Back") == true) 1 else 0) +
                node.children.sumOf { count(it) }
        return count(root.semanticsOwner.unmergedRootSemanticsNode)
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val REPORT_DIR = "build/reports/home-screenshots"
    }
}

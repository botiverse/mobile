package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.label
import build.raft.app.resources.StringDesc
import build.raft.app.thread.label
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android crash 52b93946 (1.26.0): "LayoutNode … not found in RectList" on the Members page. The
 * retraced stack: a window-level remeasure force-measures a Members list row in a kept-alive tab's
 * child page (measured, not placed); the sidebar row's name line (RaftSidebarItemLayout, a
 * subtitle and a `titleTrailing` both set) read the first baseline of the name-and-badges group
 * while it measured, which laid out the group, its slot and the role badge ahead of the row's
 * placement; Compose 1.12's RectManager threw placing the badge (androidx fd550bed79, fixed in
 * Compose 1.13). Same class as #4919 (the thread message header).
 *
 * The first test pins the cause: nothing in the name's slot is laid out while the row measures.
 * The second that the subtitle still sits where it did, on the group's first baseline (the highest
 * of the name's and the badge text's, as Row merges them). The others run the crash's shape:
 * production Members rows, and a long list of such rows, in a container that is measured but not
 * placed (as `appShellTabPlacedOnlyWhen` / `raftPlacedOnlyWhenActive` keep a page), resized and
 * scrolled while hidden, then shown again.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftSidebarItemBaselineReuseUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    /**
     * 52b93946's shape with the production Members list (MembersListRow: role badges and
     * descriptions): shown, then hidden but still measured as a kept-alive tab's page is, the
     * window resized several times while hidden, then shown again in the same pass as a resize.
     */
    @Test
    fun membersRowsSurviveResizeWhileTheirTabIsHidden() =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            var landscape by mutableStateOf(false)
            var placed by mutableStateOf(true)
            setContent {
                PageLocals {
                    Box(Modifier.size(if (landscape) 760.dp else 390.dp, if (landscape) 390.dp else 760.dp)) {
                        Box(Modifier.fillMaxSize().placedOnlyWhen(placed)) { MembersListVisualContent() }
                    }
                }
            }
            waitForIdle()
            assertSubtitleOnGroupLine(name = "artin", subtitle = "Product lead", badge = "You · Owner")

            runOnIdle { placed = false }
            waitForIdle()
            // An odd number of turns, so the last one, with the tab shown again, ends in portrait
            // (every row on screen).
            repeat(7) {
                runOnIdle { landscape = !landscape }
                waitForIdle()
            }
            runOnIdle {
                placed = true
                landscape = !landscape
            }
            waitForIdle()

            assertSubtitleOnGroupLine(name = "artin", subtitle = "Product lead", badge = "You · Owner")
        }

    /** What a page gets from its host: its activity and the app theme. */
    @Composable
    private fun PageLocals(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
        }
    }

    /** Measured and kept, but not placed while not [placed] (AppShellTabFadeThrough, RaftChildPage). */
    private fun Modifier.placedOnlyWhen(placed: Boolean): Modifier =
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (placed) placeable.place(0, 0)
            }
        }

    private fun SemanticsNode.label(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private fun ComposeUiTest.placedTexts(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.layoutInfo.isAttached && it.layoutInfo.isPlaced }

    private fun SemanticsNode.baselineInRoot(): Float {
        val baseline = getAlignmentLinePosition(FirstBaseline)
        assertTrue(baseline != AlignmentLine.Unspecified, "\"${label()}\" has a first baseline")
        return positionInRoot.y + baseline
    }

    /**
     * The row's subtitle has its first baseline on the name group's, as Row merges a group's first
     * baseline: the highest of the name's and (with a text badge) the badge text's, in px in root.
     */
    private fun ComposeUiTest.assertSubtitleOnGroupLine(name: String, subtitle: String, badge: String?) {
        val texts = placedTexts()

        fun one(label: String): SemanticsNode {
            val found = texts.filter { it.label() == label }
            assertEquals(1, found.size, "one placed \"$label\": ${found.map { it.positionInRoot }}")
            return found.single()
        }
        val nameLine = one(name).baselineInRoot()
        val groupLine = if (badge != null) minOf(nameLine, one(badge).baselineInRoot()) else nameLine
        assertEquals(groupLine, one(subtitle).baselineInRoot(), 0.5f, "\"$subtitle\" sits on \"$name\"'s line (name $nameLine)")
    }
}

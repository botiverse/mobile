package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
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
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun nameSlotIsLaidOutOnlyAfterTheRowMeasures() =
        runRobolectricComposeUiTest {
            var measuring = false
            var placedWhileMeasuring = false
            val probe =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (measuring) placedWhileMeasuring = true
                        placeable.place(0, 0)
                    }
                }
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Layout(
                        content = { MemberLikeRow("artin", "Product lead", "You · Owner", slotProbe = probe) },
                    ) { measurables, constraints ->
                        measuring = true
                        val placeable = measurables.single().measure(constraints)
                        measuring = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
            waitForIdle()

            assertTrue(placedTexts().any { it.label() == "You · Owner" }, "the badge was placed")
            assertFalse(placedWhileMeasuring, "the name's slot (its badges) was laid out while the row was measuring")
        }

    @Test
    fun subtitleSitsOnTheNameGroupsFirstBaseline() =
        runRobolectricComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    androidx.compose.foundation.layout.Column {
                        MemberLikeRow("artin", "Product lead", "You · Owner")
                        // A slot without text (an icon): the name's baseline alone.
                        RaftSidebarItem(
                            title = "release",
                            subtitle = "idle",
                            modifier = Modifier.width(360.dp),
                            onClick = {},
                            titleTrailing = { Box(Modifier.size(14.dp)) },
                        )
                    }
                }
            }
            waitForIdle()

            assertSubtitleOnGroupLine(name = "artin", subtitle = "Product lead", badge = "You · Owner")
            assertSubtitleOnGroupLine(name = "release", subtitle = "idle", badge = null)
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

    /**
     * Rows reused while hidden: a long list of member-like rows (a Box round each, as
     * MembersListActionRow has) scrolled and resized while measured but not placed, so slots go to
     * the reuse pool and come back measured with other members before anything places them.
     */
    @Test
    fun reusedRowsSurviveResizeAndScrollWhileHidden() =
        runRobolectricComposeUiTest {
            val listState = LazyListState()
            var landscape by mutableStateOf(false)
            var placed by mutableStateOf(true)
            val members = List(80) { index -> Triple("Member $index", "Description $index", if (index % 2 == 0) "Owner $index" else "Guest $index") }
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.size(if (landscape) 640.dp else 360.dp, if (landscape) 360.dp else 640.dp)) {
                        Box(Modifier.fillMaxSize().placedOnlyWhen(placed)) {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(members, key = { it.first }) { (name, subtitle, badge) ->
                                    Box { MemberLikeRow(name, subtitle, badge, width = null) }
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val step = with(density) { 160.dp.toPx() }
            runOnIdle { placed = false }
            waitForIdle()
            repeat(8) {
                runOnIdle {
                    landscape = !landscape
                    listState.dispatchRawDelta(step)
                }
                waitForIdle()
            }
            runOnIdle {
                placed = true
                landscape = !landscape
                listState.dispatchRawDelta(step)
            }
            waitForIdle()

            assertTrue(listState.firstVisibleItemIndex > 0, "the list scrolled while hidden: ${listState.firstVisibleItemIndex}")
            val shown = runOnIdle { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            assertTrue(shown.size >= 2, "the list shows rows: $shown")
            shown.forEach { key ->
                val (name, subtitle, badge) = members.first { it.first == key }
                assertSubtitleOnGroupLine(name, subtitle, badge)
            }
        }

    /** MembersListRow's shape: a name, a subtitle, and the slot after the name a Row of badges. */
    @Composable
    private fun MemberLikeRow(name: String, subtitle: String, badge: String, slotProbe: Modifier = Modifier, width: Int? = 360) {
        RaftSidebarItem(
            title = name,
            subtitle = subtitle,
            modifier = if (width != null) Modifier.width(width.dp) else Modifier,
            onClick = {},
            titleTrailing = {
                Row(slotProbe, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftBadge(text = badge, appearance = RaftBadgeAppearance.Outline)
                }
            },
        )
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

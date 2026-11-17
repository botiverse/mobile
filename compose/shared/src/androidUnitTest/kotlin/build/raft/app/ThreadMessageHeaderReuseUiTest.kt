package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.roundToInt
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
 * iOS crashes b692fce7 (1.25.1) and 50046806 (1.25.0): "LayoutNode … not found in RectList" in a
 * message list just after a rotation. The message header read the baseline of its sender cluster
 * (a row) and of the name's tooltip (a box) while it measured, which made Compose 1.12 lay out
 * their children before a reused row was placed (androidx fd550bed79, fixed in Compose 1.13).
 *
 * The RectList exception itself is iOS/skiko RectManager bookkeeping that this JVM run does not
 * reproduce reliably, so the first test pins the cause (nothing in the header is laid out while
 * the header is measured), the second that the time still sits on the name's baseline, and the
 * third runs real message rows through rotation-like resizes and item reuse.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageHeaderReuseUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Lines {
        var name = Float.NaN
        var meta = Float.NaN
        var time = Float.NaN
    }

    private fun LayoutCoordinates.baselineInRoot(): Float = positionInRoot().y + this[FirstBaseline]

    @Test
    fun headerLaysOutTheNameOnlyAfterMeasuring() =
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
            val lines = Lines()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Layout(
                        content = { Header(lines, nameProbe = probe) },
                    ) { measurables, constraints ->
                        measuring = true
                        val placeable = measurables.single().measure(constraints)
                        measuring = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Alice").assertExists()
            assertFalse(placedWhileMeasuring, "the sender's name was laid out while the header was measuring")
        }

    @Test
    fun timeAndMetaSitOnTheNamesBaseline() =
        runRobolectricComposeUiTest {
            val lines = Lines()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) { Header(lines) }
            }
            waitForIdle()

            assertTrue(!lines.name.isNaN() && !lines.time.isNaN() && !lines.meta.isNaN(), "all three texts were placed: name=${lines.name} meta=${lines.meta} time=${lines.time}")
            assertEquals(lines.name.roundToInt(), lines.time.roundToInt(), "the time's baseline (px in root) is the name's")
            assertEquals(lines.name.roundToInt(), lines.meta.roundToInt(), "the model label's baseline (px in root) is the name's")
        }

    /**
     * The shape of the crash: a message list that changes size (a rotation) while it scrolls, so
     * rows are re-laid out at the new width and reused slots take other messages in the same pass.
     */
    @Test
    fun messageRowsSurviveRotationWhileReused() =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val listState = LazyListState()
            var landscape by mutableStateOf(false)
            val messages =
                List(40) { index ->
                    RaftThreadMessageItem(
                        id = "rotate-$index",
                        authorId = "user-$index",
                        authorName = "Author $index",
                        // A handle: the name is wrapped in its "Mention @name" tooltip.
                        authorHandle = "author$index",
                        authorKind = if (index % 2 == 0) "human" else "agent",
                        authorDescription = if (index % 3 == 0) "Keeps the release train on time" else null,
                        avatarLabel = "A",
                        timestampLabel = "2026-10-21T16:40:00Z",
                        body = "Message $index: the build is green on every lane.",
                    )
                }
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                        Box(Modifier.size(if (landscape) 640.dp else 360.dp, if (landscape) 360.dp else 640.dp)) {
                            LazyColumn(state = listState) {
                                items(messages, key = { it.id }) { message ->
                                    ThreadMessageCard(
                                        message = message,
                                        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                                        timestampLabel = "4:40 PM",
                                        onShowActions = {},
                                        onOpenMember = {},
                                        onOpenThread = {},
                                        onOpenTask = {},
                                        onOpenTaskStatusMenu = {},
                                        onToggleReaction = {},
                                        onOpenAttachment = {},
                                        onOpenMarkdownTag = { _, _ -> },
                                        onOpenMarkdownUrl = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val step = with(density) { 180.dp.toPx() }
            repeat(8) {
                // Rotate and scroll in one pass: every row is measured at the new width while rows
                // that left the viewport hand their slots to the ones coming in.
                runOnIdle {
                    landscape = !landscape
                    listState.dispatchRawDelta(step)
                }
                waitForIdle()
            }

            assertTrue(listState.firstVisibleItemIndex > 0, "the list scrolled past its first message: ${listState.firstVisibleItemIndex}")
            val shown = messages[listState.firstVisibleItemIndex].authorName
            onNodeWithText(shown).assertExists()
        }

    /** The production header with the parts that matter here: a tooltip round the name, a meta text and a time. */
    @androidx.compose.runtime.Composable
    private fun Header(lines: Lines, nameProbe: Modifier = Modifier) {
        ThreadMessageHeaderRow(
            alignEnd = false,
            modifier = Modifier.width(360.dp),
            time = { timeModifier ->
                RaftText("4:40 PM", timeModifier.onGloballyPositioned { lines.time = it.baselineInRoot() }, style = RaftTheme.typography.monoMeta)
            },
            meta = {
                // A badge taller than the line: centred, it must not move the baselines.
                Box(Modifier.size(width = 24.dp, height = 28.dp))
                RaftText(
                    "gpt-5",
                    Modifier.alignByBaseline().onGloballyPositioned { lines.meta = it.baselineInRoot() },
                    style = RaftTheme.typography.caption,
                )
            },
        ) { inCluster, nameText ->
            RaftTooltip(text = "Mention @alice", modifier = inCluster, openOnLongPress = false) {
                RaftText(
                    "Alice",
                    // The probe goes outside the carried baseline: reading a text's own baseline
                    // places its inner modifiers (shallowly), never another layout node.
                    nameProbe.then(nameText).onGloballyPositioned { lines.name = it.baselineInRoot() },
                    style = RaftTheme.typography.subhead,
                )
            }
        }
    }
}

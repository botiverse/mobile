package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackDisplay
import build.raft.app.nav.RaftStackPage
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * iOS crashes b692fce7 (1.25.1) and 50046806 (1.25.0): "LayoutNode … not found in RectList" in a
 * message list. A window size record precedes the exception log by 89 / 167 ms (a rotation is not
 * confirmed); the SIGABRT itself has second precision only, so its order against them is unknown.
 * Suspected cause, not reproduced on the JVM: the message header read the baseline of its sender cluster
 * (a row) and of the name's tooltip (a box) while it measured, which made Compose 1.12 lay out
 * their children before a reused row was placed (androidx fd550bed79, fixed in Compose 1.13).
 *
 * A third report (06961e2a) came, it is suspected (not proven), from a thread whose rows were
 * reused while an attachment preview page covered it, or as it came back.
 *
 * The RectList exception itself is iOS/skiko RectManager bookkeeping that this JVM run does not
 * reproduce reliably, so the first test pins the cause (nothing in the header is laid out while
 * the header is measured), the second that the time still sits on the name's baseline, and the
 * others run real message rows through rotation-like resizes and item reuse: in a bare list, and
 * in a thread page covered by a pushed file preview page on the two page stacks of :shared
 * ([StackDisplay]). Every run ends with each shown row's header on its name's baseline.
 *
 * What this proves is JVM/Android behaviour only. On iOS each page is its own
 * ComposeUIViewController pushed on a UINavigationController (RaftCmpIos.makePage), so a covered
 * thread keeps its composition in a view that is off the window; that host is not composed here.
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
            val messages = threadMessages("rotate", 40)
            setContent {
                ThreadPageLocals {
                    Box(Modifier.size(if (landscape) 640.dp else 360.dp, if (landscape) 360.dp else 640.dp)) {
                        MessageRows(messages, listState)
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
            assertHeadersOnTheirNamesBaseline(listState, messages)
        }

    /** The page stacks a thread page can sit in under a pushed page, as :shared draws them. */
    enum class StackDisplay {
        /**
         * Android phone ([RaftMobilePageStackDisplay], mobile_nav3_stack_v1): the covered thread
         * keeps the window size, measured but not placed, so its rows are composed, measured and
         * reused while the preview covers it without ever being placed.
         */
        Mobile,

        /**
         * Desktop ([RaftPageStackDisplay]) and the pages not in RaftStackCoveredKeepsSizePages:
         * the covered thread is laid out at 0x0, its rows go to the reuse pool and come back out of
         * it when the preview closes.
         */
        Window,
    }

    @Test
    fun messageRowsSurviveReuseUnderAPushedPreviewOnThePhoneStack() = messageRowsSurviveReuseUnderAPushedPreview(StackDisplay.Mobile)

    @Test
    fun messageRowsSurviveReuseUnderAPushedPreviewOnTheWindowStack() = messageRowsSurviveReuseUnderAPushedPreview(StackDisplay.Window)

    /**
     * 06961e2a's suspected shape: a thread opens a file preview over itself (Pager
     * openRaftFilePreviewPage, a push on the page stack). While it is covered, messages arrive,
     * the list follows them and the window turns; the preview then closes in the same pass as
     * another turn and scroll, so the rows come back reused at a new width.
     */
    private fun messageRowsSurviveReuseUnderAPushedPreview(display: StackDisplay) =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val stack = RaftPageStack()
            val listState = LazyListState()
            var landscape by mutableStateOf(false)
            val messages = mutableStateListOf(*threadMessages("covered", 30).toTypedArray())
            setContent {
                ThreadPageLocals {
                    Box(Modifier.size(if (landscape) 640.dp else 360.dp, if (landscape) 360.dp else 640.dp)) {
                        val pageContent: @Composable (RaftStackPage) -> Unit = { page ->
                            when (page.args.pageName) {
                                THREAD_PAGE -> MessageRows(messages, listState)
                                else -> Box(Modifier.fillMaxSize())
                            }
                        }
                        when (display) {
                            StackDisplay.Mobile -> RaftMobilePageStackDisplay(stack, pageContent = pageContent)
                            StackDisplay.Window -> RaftPageStackDisplay(stack, pageContent)
                        }
                    }
                }
            }
            runOnIdle { stack.open(THREAD_PAGE, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            val step = with(density) { 180.dp.toPx() }
            runOnIdle { listState.dispatchRawDelta(step) }
            waitForIdle()
            assertHeadersOnTheirNamesBaseline(listState, messages)

            runOnIdle { stack.open(RAFT_FILE_PREVIEW_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            val coveredAt = listState.firstVisibleItemIndex
            repeat(6) { round ->
                runOnIdle {
                    landscape = !landscape
                    messages += threadMessage("covered-new", 100 + round)
                    listState.dispatchRawDelta(step)
                }
                waitForIdle()
            }
            assertEquals(RAFT_FILE_PREVIEW_PAGE, stack.pages.last().args.pageName, "the preview still covers the thread")
            when (display) {
                StackDisplay.Mobile -> {
                    assertTrue(listState.layoutInfo.viewportSize.height > 0, "the covered thread keeps the window size")
                    assertTrue(
                        listState.firstVisibleItemIndex > coveredAt,
                        "rows were reused while covered: first visible ${listState.firstVisibleItemIndex}, covered at $coveredAt",
                    )
                }
                StackDisplay.Window ->
                    assertEquals(0, listState.layoutInfo.viewportSize.height, "the covered thread is laid out at 0x0")
            }

            runOnIdle {
                stack.back()
                landscape = !landscape
                listState.dispatchRawDelta(step)
            }
            waitForIdle()

            assertEquals(THREAD_PAGE, stack.pages.last().args.pageName)
            assertTrue(listState.layoutInfo.visibleItemsInfo.isNotEmpty(), "the thread's rows came back")
            assertHeadersOnTheirNamesBaseline(listState, messages)
        }

    private fun threadMessage(prefix: String, index: Int) =
        RaftThreadMessageItem(
            id = "$prefix-$index",
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

    private fun threadMessages(prefix: String, count: Int) = List(count) { index -> threadMessage(prefix, index) }

    /** What a thread page gets from its host: the page's activity and the app theme. */
    @Composable
    private fun ThreadPageLocals(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
        }
    }

    /** Production message rows ([ThreadMessageCard]) in a keyed LazyColumn. */
    @Composable
    private fun MessageRows(messages: List<RaftThreadMessageItem>, listState: LazyListState) {
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

    /**
     * For every row the list shows: its sender name is placed, and each text to the right of the
     * name on the name's line (the time, the description) has its first baseline on the name's,
     * in px in root (1px for rounding). At least one such text per row: the time.
     */
    private fun ComposeUiTest.assertHeadersOnTheirNamesBaseline(listState: LazyListState, messages: List<RaftThreadMessageItem>) {
        val shownIds = runOnIdle { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
        val shown = shownIds.map { id -> messages.first { it.id == id } }
        val texts =
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.layoutInfo.isAttached && it.layoutInfo.isPlaced }

        fun SemanticsNode.label(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

        fun SemanticsNode.baselineInRoot(): Float {
            val baseline = getAlignmentLinePosition(FirstBaseline)
            assertTrue(baseline != androidx.compose.ui.layout.AlignmentLine.Unspecified, "\"${label()}\" has a first baseline")
            return positionInRoot.y + baseline
        }
        assertTrue(shown.size >= 2, "the list shows rows: $shownIds")
        shown.forEach { message ->
            val names = texts.filter { it.label() == message.authorName }
            assertEquals(1, names.size, "one placed name for ${message.id}: ${names.map { it.positionInRoot }}")
            val name = names.single()
            val nameBaseline = name.baselineInRoot()
            val nameRight = name.positionInRoot.x + name.size.width
            val nameMiddle = name.positionInRoot.y + name.size.height / 2f
            val line =
                texts.filter {
                    it !== name && it.positionInRoot.x >= nameRight - 1f &&
                        it.positionInRoot.y < nameMiddle && it.positionInRoot.y + it.size.height > nameMiddle
                }
            assertTrue(line.isNotEmpty(), "${message.id}: a time beside the name")
            line.forEach { other ->
                assertEquals(nameBaseline, other.baselineInRoot(), 1f, "${message.id}: \"${other.label()}\" sits on the name's baseline")
            }
        }
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

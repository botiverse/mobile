package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.TaskAssignmentNoticeParty
import build.raft.app.data.TaskAssignmentPartyType
import build.raft.app.data.stringDescLocaleType
import build.raft.app.markdown.raftInlineBoxRanges
import build.raft.app.markdown.raftTextWithoutInlineGaps
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The task hand-off message row and card on a real composition, through the timeline's system-line
 * branch ([ThreadSystemMessageRow]) as the message list calls it (web `MessageItem.tsx` +
 * `TaskHandoffCard.tsx`, botiverse/slock #15631 `4635757f2`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ThreadTaskHandoffCardUiTest {
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

    private fun ComposeUiTest.timeline(
        vararg lines: RaftThreadMessageItem,
        show: List<RaftThreadMessageItem> = lines.toList(),
        reasonMarkdown: ThreadTaskHandoffReasonMarkdown? = null,
        viewerUserId: String? = null,
        taskTitles: Map<Int, String> = emptyMap(),
        names: ThreadActionCardNameLookup = ThreadActionCardNameLookup.None,
        onOpenMember: ((MemberDetailIdentity) -> Unit)? = null,
    ) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = viewerUserId),
                LocalThreadTaskTitlesByNumber provides taskTitles,
                LocalThreadActionCardNames provides names,
            ) {
                RaftDesignSystemTheme {
                    ThreadTaskHandoffFixtures.WithTime {
                        Column(Modifier.fillMaxSize()) {
                            show.forEach {
                                ThreadSystemMessageRow(
                                    message = it,
                                    timestampValue = it.timestampLabel,
                                    timeline = lines.toList(),
                                    handoffReasonMarkdown = reasonMarkdown,
                                    onOpenMember = onOpenMember,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.titleLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    // slock #15339: in the conversation list the reason draws its links (and refs, @mentions) like a
    // message body, and a tap opens them; elsewhere (no handlers) it stays plain text.
    private val linkedReason =
        ThreadTaskHandoffFixtures.line(
            "h2", "2026-11-10T08:00:00.000Z", ThreadTaskHandoffFixtures.otter, ThreadTaskHandoffFixtures.xx,
            "Read [the brief]($LINK) before Friday",
        )

    private fun ComposeUiTest.reasonLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText("before Friday", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun aHandOffIsItsActorsMessageWithDisplayNameTaskTitleAndViewerHighlight() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val openedMembers = mutableListOf<MemberDetailIdentity>()
            val handoff = f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, "Your call")
            timeline(
                handoff,
                viewerUserId = f.xx.id,
                taskTitles = mapOf(51 to "Ship it"),
                onOpenMember = { openedMembers += it },
            )
            onNodeWithTag(TaskHandoffMessageTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(TaskHandoffActorAvatarTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(TaskHandoffActorNameTag, useUnmergedTree = true).assertTextEquals("Otter")
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Assigned to xxchan")
            onNodeWithTag(TaskHandoffCardTaskTag, useUnmergedTree = true).assertTextEquals("#51 Ship it")
            onNodeWithTag(TaskHandoffCardReasonTag, useUnmergedTree = true).assertTextEquals("Your call")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter assigned #51 to xxchan")

            // When the task went to the viewer, only their name carries the yellow "you" sticker box.
            val titleText = titleLayout().layoutInput.text
            assertEquals("Assigned to xxchan", titleText.raftTextWithoutInlineGaps())
            val boxes = titleText.raftInlineBoxRanges()
            assertEquals(1, boxes.size, "viewer assignee wears one self-mention sticker")
            assertEquals("xxchan", titleText.text.substring(boxes.single().start, boxes.single().end))

            // Tapping the actor's avatar opens the actor's profile.
            onNodeWithTag(ThreadMessageAvatarTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(listOf(f.otter.id), openedMembers.map { it.memberId.value })
            assertEquals(listOf(MemberKind.Agent), openedMembers.map { it.kind })
        }

    @Test
    fun someoneElsesTaskMovedReadsAsReassignmentAndWithoutTaskTitleShowsOnlyNumber() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val reassigned =
                f.line(
                    "h1",
                    "2026-11-10T08:00:00.000Z",
                    f.otter,
                    f.xx,
                    "Bob is out",
                    previousAssignee = f.bob,
                )
            timeline(reassigned, viewerUserId = f.xx.id)
            onNodeWithTag(TaskHandoffActorNameTag, useUnmergedTree = true).assertTextEquals("Otter")
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Reassigned from Bob to xxchan")
            onNodeWithTag(TaskHandoffCardTaskTag, useUnmergedTree = true).assertTextEquals("#51")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter reassigned #51 from Bob to xxchan")

            val titleText = titleLayout().layoutInput.text
            val boxes = titleText.raftInlineBoxRanges()
            assertEquals(1, boxes.size, "only the new assignee gets the yellow sticker")
            assertEquals("xxchan", titleText.text.substring(boxes.single().start, boxes.single().end))
        }

    @Test
    fun aHandOffWithoutAReasonIsTheCardsTitleAndTaskAlone() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val withoutReason = f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, reason = null)
            timeline(withoutReason, viewerUserId = f.bob.id)
            onNodeWithTag(TaskHandoffMessageTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(TaskHandoffActorNameTag, useUnmergedTree = true).assertTextEquals("Otter")
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Assigned to xxchan")
            onNodeWithTag(TaskHandoffCardTaskTag, useUnmergedTree = true).assertTextEquals("#51")
            onNodeWithTag(TaskHandoffCardReasonTag, useUnmergedTree = true).assertDoesNotExist()
            assertTrue(titleLayout().layoutInput.text.raftInlineBoxRanges().isEmpty(), "non-viewer gets no yellow sticker")
        }

    @Test
    fun aHandOffBackReadsAsAssignedBack() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val first = f.line("h1", "2026-11-10T08:00:00.000Z", f.xx, f.otter, "Can you draft it?")
            val back = f.line("h2", "2026-11-10T09:00:00.000Z", f.otter, f.xx, "Draft is up")
            timeline(first, back, show = listOf(back), viewerUserId = f.xx.id)
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Assigned back to xxchan")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter assigned #51 back to xxchan")
            val boxes = titleLayout().layoutInput.text.raftInlineBoxRanges()
            assertEquals(1, boxes.size)
            assertEquals("xxchan", titleLayout().layoutInput.text.text.substring(boxes.single().start, boxes.single().end))
        }

    @Test
    fun reassignmentTakesPrecedenceOverAssignedBack() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val first = f.line("h1", "2026-11-10T08:00:00.000Z", f.xx, f.otter, "Can you draft it?")
            // Even though xxchan previously gave it to Otter, Bob held it last so Otter moving it
            // from Bob to xxchan is a reassignment, not "Assigned back to".
            val toBob = f.line("h3", "2026-11-10T09:15:00.000Z", f.otter, f.bob, "Bob reviews")
            val reassignedFromBob = f.line("h4", "2026-11-10T09:30:00.000Z", f.otter, f.xx, "Bob is out", previousAssignee = f.bob)
            timeline(first, toBob, reassignedFromBob, show = listOf(reassignedFromBob), viewerUserId = f.xx.id)
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Reassigned from Bob to xxchan")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter reassigned #51 from Bob to xxchan")
        }

    @Test
    fun agentAssigneeNeverGetsViewerYellowSticker() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val toAgent = f.line("h5", "2026-11-10T10:00:00.000Z", f.xx, f.otter, "Take over")
            timeline(toAgent, viewerUserId = f.otter.id)
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Assigned to Otter")
            assertTrue(titleLayout().layoutInput.text.raftInlineBoxRanges().isEmpty(), "agent assignee never gets yellow sticker")
        }

    @Test
    fun localizedHandOffCardRendersChineseWithViewerStickerInRightSlot() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val reassigned =
                f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, "Bob 不在", previousAssignee = f.bob)

            StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
            timeline(reassigned, viewerUserId = f.xx.id, taskTitles = mapOf(51 to "发布"))
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("从 Bob 改派给 xxchan")
            onNodeWithTag(TaskHandoffCardTaskTag, useUnmergedTree = true).assertTextEquals("#51 发布")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter 把 #51 从 Bob 改派给了 xxchan")
            val zhBoxes = titleLayout().layoutInput.text.raftInlineBoxRanges()
            assertEquals(1, zhBoxes.size)
            assertEquals("xxchan", titleLayout().layoutInput.text.text.substring(zhBoxes.single().start, zhBoxes.single().end))
        }

    @Test
    fun localizedHandOffCardRendersJapaneseWithViewerStickerAtStart() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            StringDesc.localeType = AppLocale.Japanese.stringDescLocaleType
            val assigned = f.line("h2", "2026-11-10T08:00:00.000Z", f.otter, f.xx, null)
            timeline(assigned, viewerUserId = f.xx.id)
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("xxchan に割り当て")
            val jaBoxes = titleLayout().layoutInput.text.raftInlineBoxRanges()
            assertEquals(1, jaBoxes.size)
            assertEquals("xxchan", titleLayout().layoutInput.text.text.substring(jaBoxes.single().start, jaBoxes.single().end))
        }

    @Test
    fun olderLineWithoutPreviousAssigneeInfersHolderFromTimeline() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val first = f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, "First")
            val olderReassigned = f.line("h2", "2026-11-10T08:30:00.000Z", f.otter, f.bob, "Bob instead")
            timeline(first, olderReassigned, show = listOf(olderReassigned))
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Reassigned from xxchan to Bob")
        }

    @Test
    fun explicitNullPreviousAssigneeDoesNotInferHolderFromEarlierLine() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val first = f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, "First")
            val explicitNullAfter =
                f.line(
                    "h3",
                    "2026-11-10T09:00:00.000Z",
                    f.otter,
                    f.bob,
                    "Fresh assignment",
                    previousAssignee = null,
                    previousAssigneeRecorded = true,
                )
            timeline(first, explicitNullAfter, show = listOf(explicitNullAfter))
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true).assertTextEquals("Assigned to Bob")
        }

    @Test
    fun claimsAndUnassignsStayOneLineSystemEvents() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val claim = f.line("c1", "2026-11-10T08:00:00.000Z", f.otter, f.otter)
            val unassign = f.line("u1", "2026-11-10T08:10:00.000Z", f.otter, null)
            timeline(claim, unassign)
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(TaskHandoffMessageTag, useUnmergedTree = true).assertDoesNotExist()
            onNode(hasText("Otter took #51", substring = true), useUnmergedTree = true).assertExists()
            onNode(hasText("Otter unassigned #51", substring = true), useUnmergedTree = true).assertExists()
        }

    @Test
    fun displayNameResolutionPrefersRosterThenNoticeDisplayNameThenPlainHandleWithoutAt() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            val handleOnlyAssignee =
                TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "user-handle-only", "alice_h", null)
            val handoff =
                f.line(
                    "h1",
                    "2026-11-10T08:00:00.000Z",
                    f.otter,
                    handleOnlyAssignee,
                    "Check this",
                    previousAssignee = f.bob,
                )
            val roster =
                ThreadActionCardNameLookup { kind, id ->
                    when {
                        kind == ThreadActionCardEntityKind.Agent && id == f.otter.id -> "Otter Prime"
                        kind == ThreadActionCardEntityKind.Human && id == f.bob.id -> "Robert"
                        else -> null
                    }
                }
            timeline(handoff, names = roster)
            onNodeWithTag(TaskHandoffActorNameTag, useUnmergedTree = true).assertTextEquals("Otter Prime")
            onNodeWithTag(TaskHandoffCardTitleTag, useUnmergedTree = true)
                .assertTextEquals("Reassigned from Robert to alice_h")
            onNodeWithTag(TaskHandoffCardTag, useUnmergedTree = true)
                .assertContentDescriptionEquals("Otter Prime reassigned #51 from Robert to alice_h")
        }

    @Test
    fun handOffsWithOrWithoutReasonNeverFoldIntoSystemGroupsAndTaskTitlesResolveByContext() {
        val f = ThreadTaskHandoffFixtures
        val status1 =
            RaftThreadMessageItem(
                id = "s1",
                authorId = "system",
                authorName = "system",
                timestampLabel = "2026-11-10T07:59:00.000Z",
                messageType = "system",
                body = "🔄 Otter moved #51 \"Ship it\" to In Progress",
            )
        val status2 = status1.copy(id = "s2", timestampLabel = "2026-11-10T08:01:00.000Z")
        val withReason = f.line("h1", "2026-11-10T08:00:00.000Z", f.otter, f.xx, "Your call")
        val withoutReason = f.line("h2", "2026-11-10T08:00:30.000Z", f.otter, f.xx, reason = null)
        assertEquals(listOf(null, null, null), threadSystemMessageFolds(listOf(status1, withReason, status2)))
        assertEquals(listOf(null, null, null), threadSystemMessageFolds(listOf(status1, withoutReason, status2)))
        assertNull(withoutReason.threadSystemMessageCategory())

        // Task titles lookup: current channel tasks win over server tasks; other channels are ignored; currentTask wins last.
        val otherChannelTask =
            TaskSummary(id = TaskId("t-other"), title = "Wrong channel", status = TaskStatus.Todo, updatedAtLabel = "", channelId = ChannelId("ch-other"), taskNumber = 51)
        val serverTask =
            TaskSummary(id = TaskId("t-server"), title = "Server title", status = TaskStatus.Todo, updatedAtLabel = "", channelId = ChannelId("ch-1"), taskNumber = 51)
        val channelTask =
            TaskSummary(id = TaskId("t-channel"), title = "Channel title", status = TaskStatus.Todo, updatedAtLabel = "", channelId = ChannelId("ch-1"), taskNumber = 51)
        val currentTask =
            TaskSummary(id = TaskId("t-current"), title = "Current title", status = TaskStatus.Todo, updatedAtLabel = "", channelId = ChannelId("ch-1"), taskNumber = 51)
        assertEquals(
            mapOf(51 to "Server title"),
            threadTaskTitlesByNumber(emptyList(), listOf(otherChannelTask, serverTask), currentChannelId = "ch-1"),
        )
        assertEquals(
            mapOf(51 to "Channel title"),
            threadTaskTitlesByNumber(listOf(channelTask), listOf(serverTask), currentChannelId = "ch-1"),
        )
        assertEquals(
            mapOf(51 to "Current title"),
            threadTaskTitlesByNumber(listOf(channelTask), listOf(serverTask), currentChannelId = "ch-1", currentTask = currentTask),
        )
    }

    @Test
    fun inTheConversationListTheReasonsLinkOpensOnATap() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(org.robolectric.RuntimeEnvironment.getApplication())
            timeline(
                linkedReason,
                reasonMarkdown =
                    ThreadTaskHandoffReasonMarkdown(
                        highlightedMentions = emptySet(),
                        isTagValid = { true },
                        resolveTagLabel = null,
                        onOpenTag = { _, _ -> },
                        onOpenUrl = { opened += it },
                    ),
            )
            val text = reasonLayout().layoutInput.text
            assertEquals(
                listOf(LINK),
                text.getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url },
                "the reason's link is a link",
            )
            val at = reasonLayout().getBoundingBox(text.text.indexOf("the brief") + 1).center
            onNode(hasText("before Friday", substring = true), useUnmergedTree = true).performTouchInput { click(at) }
            waitForIdle()
            assertEquals(listOf(LINK), opened)
        }

    private companion object {
        const val LINK = "https://example.com/brief"
    }
}

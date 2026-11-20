package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ChannelAnnouncementChangeKind
import build.raft.app.data.ChannelAnnouncementNotice
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.RaftChannelAnnouncementActor
import build.raft.app.data.RaftChannelAnnouncementActorType
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A channel's announcement as the web's behaviour tests check it (botiverse/slock #14039 `channelAnnouncement.behavior.test.tsx`). */
internal val channelAnnouncementFixture =
    RaftChannelAnnouncement(
        artifactId = "pt_0b3b0f4e-2f0c-4a53-9d8e-2a5b7c1d9e01",
        title = "Release rules for 1.9",
        version = 4,
        updatedAt = "2026-11-10T09:30:00.000Z",
        updatedBy = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.Agent, "agent-bugen", "Bugen"),
        note = "Added the freeze dates for 1.9",
        seen = false,
        excerpt = "Code freeze Thursday 18:00. Release branch cut Friday morning.",
    )

/**
 * Channel announcements on a real composition (AGENTS.md "Compose UI tests"): the strip unseen and
 * seen, its one tap and its ⋯ menu, the details' Announcement row for who may change it and who
 * may not, and the change line in the reader's language.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ChannelAnnouncementUiTest {
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

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 11, 10),
            // 45 minutes after channelAnnouncementFixture.updatedAt (2026-11-10T09:30:00Z).
            nowEpochMillis = 1_794_305_700_000L,
        )

    private fun ComposeUiTest.show(
        timeContext: RaftTimeContext = fixedTimeContext,
        content: @Composable () -> Unit,
    ) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides remember(timeContext) { RaftTimeContextProvider(timeContext) },
            ) {
                RaftDesignSystemTheme { Column(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Test
    fun anUnseenVersionSaysWhoUpdatedItWithItsNoteAndTheRowOpensIt() =
        runRobolectricComposeUiTest {
            var opens = 0
            show {
                ChannelAnnouncementStrip(
                    announcement = channelAnnouncementFixture,
                    authorName = "Bugen",
                    onOpen = { opens++ },
                    onCopyLink = {},
                    onOpenSettings = null,
                )
            }
            onNodeWithText("Release rules for 1.9").assertExists()
            onNodeWithText("Bugen: Added the freeze dates for 1.9").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.StripDot, useUnmergedTree = true).assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.StripLine, useUnmergedTree = true).performClick()
            runOnIdle { assertEquals(1, opens) }
        }

    @Test
    fun aSeenVersionShowsTheFirstLineAndNoDot() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementStrip(
                    announcement = channelAnnouncementFixture.copy(seen = true),
                    authorName = "Bugen",
                    onOpen = {},
                    onCopyLink = {},
                    onOpenSettings = null,
                )
                ChannelAnnouncementStrip(
                    announcement = channelAnnouncementFixture.copy(note = null, title = "Ops"),
                    authorName = "Bugen",
                    onOpen = {},
                    onCopyLink = {},
                    onOpenSettings = null,
                )
            }
            onNodeWithText("Code freeze Thursday 18:00. Release branch cut Friday morning.").assertExists()
            onNodeWithText("Bugen updated it").assertExists()
        }

    @Test
    fun theMenuCopiesTheLinkAndOffersSettingsOnlyToWhoMayChangeIt() =
        runRobolectricComposeUiTest {
            var copies = 0
            var settings = 0
            show {
                ChannelAnnouncementStrip(
                    announcement = channelAnnouncementFixture,
                    authorName = "Bugen",
                    onOpen = {},
                    onCopyLink = { copies++ },
                    onOpenSettings = { settings++ },
                )
            }
            onNodeWithTag(ChannelAnnouncementTestTags.StripOptions).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.StripSettings).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.StripOptions).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.StripCopyLink).performClick()
            runOnIdle {
                assertEquals(1, settings)
                assertEquals(1, copies)
            }
        }

    @Test
    fun noSettingsEntryForWhoMayNotChangeIt() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementStrip(
                    announcement = channelAnnouncementFixture,
                    authorName = "Bugen",
                    onOpen = {},
                    onCopyLink = {},
                    onOpenSettings = null,
                )
            }
            onNodeWithTag(ChannelAnnouncementTestTags.StripOptions).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.StripSettings).assertDoesNotExist()
            // Close the menu: a popup left open outlives the test's activity.
            onNodeWithTag(ChannelAnnouncementTestTags.StripCopyLink).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.StripCopyLink).assertDoesNotExist()
        }

    /** slock #15659: for an editor, Replace pin and Unpin live in a ⋯ menu before the chevron. */
    @Test
    fun anEditorFindsReplaceAndRemoveInTheRowsMenu() =
        runRobolectricComposeUiTest {
            var replaces = 0
            var removes = 0
            var opens = 0
            show {
                ChannelAnnouncementDetailsRow(
                    slice = ChannelAnnouncementSlice(read = true, announcement = channelAnnouncementFixture),
                    canEdit = true,
                    onOpen = { opens++ },
                    onSet = {},
                    onReplace = { replaces++ },
                    onRemove = { removes++ },
                )
            }
            onNodeWithText("Pinned content").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsReplace).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsRemove).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsItem).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsActions).performClick()
            onNodeWithText("Replace pin").assertExists()
            onNodeWithText("Unpin").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsReplace).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsReplace).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsActions).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsRemove).performClick()
            runOnIdle {
                assertEquals(1, opens, "⋯ is a button of its own: pressing it doesn't open the pinned content")
                assertEquals(1, replaces)
                assertEquals(1, removes)
            }
        }

    /** ⋯ sits inside the row's height, immediately before the trailing chevron. */
    @Test
    fun theMenuButtonSitsBeforeTheChevron() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementDetailsRow(
                    slice = ChannelAnnouncementSlice(read = true, announcement = channelAnnouncementFixture),
                    canEdit = true,
                    onOpen = {},
                    onSet = {},
                    onReplace = {},
                    onRemove = {},
                )
            }
            val item = onNodeWithTag(ChannelAnnouncementTestTags.DetailsItem).getBoundsInRoot()
            val actions = onNodeWithTag(ChannelAnnouncementTestTags.DetailsActions).getBoundsInRoot()
            val meta = onNodeWithTag(ChannelAnnouncementTestTags.DetailsMeta, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(actions.top >= item.top && actions.bottom <= item.bottom, "⋯ inside the row's height: $actions in $item")
            assertTrue(meta.right <= actions.left, "subtitle clears ⋯ without running under it: meta=$meta actions=$actions")
            val endGap = item.right - actions.right
            assertTrue(endGap >= 36.dp && endGap <= 50.dp, "⋯ sits before the chevron: endGap=$endGap")
        }

    @Test
    fun aReaderSeesTheAnnouncementAloneAndNoRowWhenThereIsNone() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementDetailsRow(
                    slice = ChannelAnnouncementSlice(read = true, announcement = channelAnnouncementFixture),
                    canEdit = false,
                    onOpen = {},
                    onSet = {},
                    onReplace = {},
                    onRemove = {},
                    artifactKind = ArtifactKind.Html,
                )
            }
            onNodeWithText("Pinned content").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsItem).assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsActions).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsReplace).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsRemove).assertDoesNotExist()
        }

    @Test
    fun noneShowsSetToAnEditorAndNothingToAReader() =
        runRobolectricComposeUiTest {
            var sets = 0
            show {
                ChannelAnnouncementDetailsRow(
                    slice = ChannelAnnouncementSlice(read = true, announcement = null),
                    canEdit = true,
                    onOpen = {},
                    onSet = { sets++ },
                    onReplace = {},
                    onRemove = {},
                )
                ChannelAnnouncementDetailsRow(
                    slice = ChannelAnnouncementSlice(read = true, announcement = null),
                    canEdit = false,
                    onOpen = {},
                    onSet = {},
                    onReplace = {},
                    onRemove = {},
                )
            }
            onNodeWithText("Pinned content").assertExists()
            onNodeWithText("Nothing pinned").assertExists()
            onNodeWithText("Pin an Artifact").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsSet).performClick()
            runOnIdle { assertEquals(1, sets) }
        }

    /** slock #15693: a long updater name truncates while the short relative time stays unclipped. */
    @Test
    @Config(qualifiers = "w360dp-h900dp")
    fun longUpdaterNameTruncatesWhileRelativeTimeStaysWhole() =
        runRobolectricComposeUiTest {
            val longNameFixture =
                channelAnnouncementFixture.copy(
                    updatedBy =
                        RaftChannelAnnouncementActor(
                            RaftChannelAnnouncementActorType.Agent,
                            "agent-long",
                            "qa-wa-bot-very-long-agent-name-that-must-truncate-before-time",
                        ),
                )
            show {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    ChannelAnnouncementDetailsRow(
                        slice = ChannelAnnouncementSlice(read = true, announcement = longNameFixture),
                        canEdit = true,
                        onOpen = {},
                        onSet = {},
                        onReplace = {},
                        onRemove = {},
                    )
                    RaftText(
                        text = "45m ago",
                        modifier = Modifier.testTag("ref-time-label"),
                        style = RaftTheme.typography.monoMeta,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            val meta = onNodeWithTag(ChannelAnnouncementTestTags.DetailsMeta, useUnmergedTree = true).getBoundsInRoot()
            val name = onNodeWithTag(ChannelAnnouncementTestTags.DetailsMetaName, useUnmergedTree = true).getBoundsInRoot()
            val time = onNodeWithTag(ChannelAnnouncementTestTags.DetailsMetaTime, useUnmergedTree = true).getBoundsInRoot()
            val refTime = onNodeWithTag("ref-time-label", useUnmergedTree = true).getBoundsInRoot()
            assertTrue((name.right - name.left) > 0.dp, "name remains visible while truncating: name=$name")
            assertTrue(name.right <= time.left, "name stays left of time without overlap: name=$name time=$time")
            assertTrue(time.right <= meta.right + 1.dp, "time fits inside meta row: time=$time meta=$meta")
            assertTrue(
                (time.right - time.left) >= (refTime.right - refTime.left) - 1.dp,
                "short relative time stays completely unclipped: time=$time ref=$refTime",
            )
        }

    @Test
    fun pickerDialogRendersArtifactChoicesWithKindsAndSelectsChoice() =
        runRobolectricComposeUiTest {
            val chosen = mutableListOf<ChannelAnnouncementChoice>()
            show {
                ChannelAnnouncementPickerDialog(
                    replacing = false,
                    channelName = "engineering",
                    view =
                        ChannelAnnouncementPickerView.Ready(
                            choices =
                                listOf(
                                    ChannelAnnouncementChoice("a1", "Release checklist", "v2", ArtifactKind.Markdown),
                                    ChannelAnnouncementChoice("a2", "Architecture diagram", "v1", ArtifactKind.Html),
                                ),
                            hasMore = false,
                            loadingMore = false,
                        ),
                    running = false,
                    error = null,
                    onRetry = {},
                    onShowMore = {},
                    onConfirm = { chosen += it },
                    onDismiss = {},
                )
            }
            onNodeWithText("Architecture diagram").assertExists().performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.PickerConfirm).performClick()
            runOnIdle {
                assertEquals(1, chosen.size)
                assertEquals(ArtifactKind.Html, chosen.single().kind)
            }
        }

    @Test
    fun metaTemplateSplitHandlesEveryLocaleWordOrder() {
        assertEquals(
            listOf(
                ChannelAnnouncementMetaPart.Literal("Updated by "),
                ChannelAnnouncementMetaPart.Name,
                ChannelAnnouncementMetaPart.Literal(" · "),
                ChannelAnnouncementMetaPart.Time,
            ),
            splitChannelAnnouncementMetaTemplate("Updated by \u0001 · \u0002"),
        )
        assertEquals(
            listOf(
                ChannelAnnouncementMetaPart.Name,
                ChannelAnnouncementMetaPart.Literal(" 更新 · "),
                ChannelAnnouncementMetaPart.Time,
            ),
            splitChannelAnnouncementMetaTemplate("\u0001 更新 · \u0002"),
        )
    }

    @Test
    fun theChangeLineIsSaidInTheReadersLanguage() =
        runRobolectricComposeUiTest {
            val actor = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.User, "u-martin", "Martin")
            show {
                RaftText(channelAnnouncementNoticeLine(ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Set, actor, "pt_a", "Release rules for 1.9")))
                RaftText(channelAnnouncementNoticeLine(ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Removed, actor.copy(name = null), "pt_a", null)))
            }
            onNodeWithText("Martin pinned Release rules for 1.9").assertExists()
            onNodeWithText("Someone unpinned the channel’s pinned content").assertExists()
        }
}

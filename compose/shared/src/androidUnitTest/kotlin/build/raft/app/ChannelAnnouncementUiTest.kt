package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import build.raft.app.data.ChannelAnnouncementUnavailableReason
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.RaftChannelAnnouncementActor
import build.raft.app.data.RaftChannelAnnouncementActorType
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.designsystem.text.RaftText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** slock #16305: joint channel with another server's pinning off explains why nothing can be pinned and offers no Pin button. */
    @Test
    fun aManagerInAJointChannelWithPinningOffSeesWhyAndNoPinButton() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementDetailsRow(
                    slice =
                        ChannelAnnouncementSlice(
                            read = true,
                            announcement = null,
                            unavailable = ChannelAnnouncementUnavailableReason.JointServerOff,
                        ),
                    canEdit = true,
                    onOpen = {},
                    onSet = {},
                    onReplace = {},
                    onRemove = {},
                )
            }
            onNodeWithText("Pinned content").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsUnavailable).assertExists()
            onNodeWithText("Nothing pinned").assertExists()
            onNodeWithText("Another server sharing this channel has turned off pinning, so you can't pin here.").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsSet).assertDoesNotExist()
        }

    @Test
    fun aMemberWhoCannotPinSeesNoAnnouncementSectionWhenJointPinningIsOff() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementDetailsRow(
                    slice =
                        ChannelAnnouncementSlice(
                            read = true,
                            announcement = null,
                            unavailable = ChannelAnnouncementUnavailableReason.JointServerOff,
                        ),
                    canEdit = false,
                    onOpen = {},
                    onSet = {},
                    onReplace = {},
                    onRemove = {},
                )
            }
            onNodeWithText("Pinned content").assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsUnavailable).assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsSet).assertDoesNotExist()
        }
}

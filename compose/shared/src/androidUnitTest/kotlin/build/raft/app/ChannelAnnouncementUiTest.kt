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
import build.raft.app.data.ChannelAnnouncementChangeKind
import build.raft.app.data.ChannelAnnouncementNotice
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.RaftChannelAnnouncementActor
import build.raft.app.data.RaftChannelAnnouncementActorType
import build.raft.app.data.stringDescLocaleType
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

    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
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
            onNodeWithTag(ChannelAnnouncementTestTags.StripCopyLink).assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.StripSettings).assertDoesNotExist()
        }

    @Test
    fun theDetailsRowOffersRemoveAndReplaceToAnEditor() =
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
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsItem).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsReplace).performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsRemove).performClick()
            runOnIdle {
                assertEquals(1, opens)
                assertEquals(1, replaces)
                assertEquals(1, removes)
            }
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
                )
            }
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsItem).assertExists()
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
            onNodeWithText("No announcement").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsSet).performClick()
            runOnIdle { assertEquals(1, sets) }
        }

    @Test
    fun theChangeLineIsSaidInTheReadersLanguage() =
        runRobolectricComposeUiTest {
            val actor = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.User, "u-martin", "Martin")
            show {
                RaftText(channelAnnouncementNoticeLine(ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Set, actor, "pt_a", "Release rules for 1.9")))
                RaftText(channelAnnouncementNoticeLine(ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Removed, actor.copy(name = null), "pt_a", null)))
            }
            onNodeWithText("Martin set the announcement: Release rules for 1.9").assertExists()
            onNodeWithText("Someone removed the channel announcement").assertExists()
        }
}

package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
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
import build.raft.app.data.toMessageMetadata
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadDirectMessageSettingsDrawer
import build.raft.app.thread.ThreadSystemMessageRow
import build.raft.designsystem.text.RaftText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** botiverse/slock 05ad55edb (#16563): DM picker uses DM lead, empty, failed, label and dmGroup without # or channel copy. */
    @Test
    fun dmPickerDialogUsesDmCopyForEmptyReadyAndFailedStates() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementPickerDialog(
                    replacing = false,
                    channelName = "Tenny",
                    view = ChannelAnnouncementPickerView.Ready(choices = emptyList(), hasMore = false, loadingMore = false),
                    running = false,
                    error = null,
                    onRetry = {},
                    onShowMore = {},
                    onConfirm = {},
                    onDismiss = {},
                    isDm = true,
                )
            }
            onNodeWithText("Shown at the top of your DM with Tenny. When it’s updated, it shows as new until opened.").assertExists()
            onNodeWithText("This DM has no Artifacts yet. Push one here, then pin it.").assertExists()
            onNodeWithContentDescription("Pinned content in DM with Tenny").assertExists()
            onNodeWithText("#Tenny", substring = true).assertDoesNotExist()
        }

    @Test
    fun dmPickerReadyShowsDmGroupHeadingAndSelectsChoiceAndFailedUsesDmCopy() =
        runRobolectricComposeUiTest {
            val chosen = mutableListOf<ChannelAnnouncementChoice>()
            show {
                ChannelAnnouncementPickerDialog(
                    replacing = true,
                    channelName = "Tenny",
                    view =
                        ChannelAnnouncementPickerView.Ready(
                            choices = listOf(ChannelAnnouncementChoice("pt_next", "Next notes", "v2 · 5m", ArtifactKind.Markdown)),
                            hasMore = false,
                            loadingMore = false,
                        ),
                    running = false,
                    error = null,
                    onRetry = {},
                    onShowMore = {},
                    onConfirm = { chosen += it },
                    onDismiss = {},
                    isDm = true,
                )
            }
            onNodeWithText("Artifacts in this DM").assertExists()
            onNodeWithText("Next notes").assertExists().performClick()
            onNodeWithTag(ChannelAnnouncementTestTags.PickerConfirm).performClick()
            runOnIdle { assertEquals(listOf("pt_next"), chosen.map { it.artifactId }) }
        }

    @Test
    fun dmPickerFailedUsesDmFailedCopy() =
        runRobolectricComposeUiTest {
            show {
                ChannelAnnouncementPickerDialog(
                    replacing = false,
                    channelName = "Tenny",
                    view = ChannelAnnouncementPickerView.Failed,
                    running = false,
                    error = null,
                    onRetry = {},
                    onShowMore = {},
                    onConfirm = {},
                    onDismiss = {},
                    isDm = true,
                )
            }
            onNodeWithText("Couldn’t load this DM’s Artifacts.").assertExists()
        }

    @Test
    fun dmReplaceAndRemoveConfirmDialogsUseDmBodyWithoutHashOrChannelCopy() =
        runRobolectricComposeUiTest {
            val rendered = mutableListOf<String>()
            show {
                val replaceDm = channelAnnouncementReplaceBody("Next notes", "Current notes", "Tenny", isDm = true)
                val removeDm = channelAnnouncementRemoveBody("Current notes", "Tenny", isDm = true)
                val replaceChannel = channelAnnouncementReplaceBody("Next notes", "Current notes", "launch", isDm = false)
                val removeChannel = channelAnnouncementRemoveBody("Current notes", "launch", isDm = false)
                rendered.clear()
                rendered += listOf(replaceDm, removeDm, replaceChannel, removeChannel)
                ChannelAnnouncementConfirmDialog(
                    title = stringResource(Res.string.channel_announcement_replace_title),
                    body = replaceDm,
                    confirmLabel = stringResource(Res.string.channel_announcement_details_replace),
                    confirmTag = ChannelAnnouncementTestTags.ReplaceConfirm,
                    running = false,
                    error = null,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
            onNodeWithText("“Next notes” is pinned in this DM instead. “Current notes” stays in the DM as an Artifact.").assertExists()
            runOnIdle {
                assertEquals(
                    "“Next notes” is pinned in this DM instead. “Current notes” stays in the DM as an Artifact.",
                    rendered[0],
                )
                assertEquals(
                    "This DM will have nothing pinned. “Current notes” stays in the DM as an Artifact, and can be pinned again.",
                    rendered[1],
                )
                assertTrue(rendered[2].contains("#launch"))
                assertTrue(rendered[3].contains("#launch"))
                assertFalse(rendered[0].contains("#Tenny"))
                assertFalse(rendered[1].contains("#Tenny"))
            }
        }

    @Test
    fun untitledDmAndChannelNoticesRenderDistinctCopyIncludingSystemMessageRow() =
        runRobolectricComposeUiTest {
            val actor = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.User, "u-ada", "Ada")
            val grace = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.User, "u-grace", "Grace")
            val setUntitled = ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Set, actor, "pt_1", null)
            val replacedUntitled = ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Replaced, actor, "pt_2", null)
            val removedUntitled = ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Removed, actor, "pt_2", null)
            val systemMessage =
                RaftThreadMessageItem(
                    id = "msg-sys-1",
                    authorId = "system",
                    authorName = "System",
                    authorKind = "system",
                    body = "@grace set the announcement.",
                    timestampLabel = "2026-11-10T09:30:00.000Z",
                    messageType = "system",
                    metadata = ChannelAnnouncementNotice(ChannelAnnouncementChangeKind.Set, grace, "pt_3", null).toMessageMetadata(),
                )
            show {
                RaftText(channelAnnouncementNoticeLine(setUntitled, isDm = true))
                RaftText(channelAnnouncementNoticeLine(replacedUntitled, isDm = true))
                RaftText(channelAnnouncementNoticeLine(removedUntitled, isDm = true))
                RaftText(channelAnnouncementNoticeLine(setUntitled, isDm = false))
                ThreadSystemMessageRow(
                    message = systemMessage,
                    timestampValue = "09:30",
                    isDm = true,
                )
            }
            onNodeWithText("Ada pinned content in this DM").assertExists()
            onNodeWithText("Ada replaced this DM’s pinned content").assertExists()
            onNodeWithText("Ada unpinned this DM’s pinned content").assertExists()
            onNodeWithText("Ada pinned content in the channel").assertExists()
            onNodeWithText("Grace pinned content in this DM").assertExists()
        }

    /** Matches web `channelPinnedContent.behavior.test.tsx`: all 8 locales define every DM key with no # or channel wording. */
    @Test
    fun allEightLocalesHaveDmCopyWithoutHashOrChannelWording() =
        runRobolectricComposeUiTest {
            val allLocales =
                listOf(
                    AppLocale.English,
                    AppLocale.Spanish,
                    AppLocale.BrazilianPortuguese,
                    AppLocale.Russian,
                    AppLocale.Korean,
                    AppLocale.Japanese,
                    AppLocale.SimplifiedChinese,
                    AppLocale.TraditionalChinese,
                )
            val forbidden = Regex("""#|\bchannel\b|频道|頻道|チャンネル|채널|canal|канал""", RegexOption.IGNORE_CASE)
            val collected = linkedMapOf<AppLocale, List<String>>()
            var activeLocale by mutableStateOf(AppLocale.English)
            show {
                val locale = activeLocale
                collected[locale] =
                    listOf(
                        stringResource(Res.string.channel_announcement_picker_dm_lead, "Tenny"),
                        stringResource(Res.string.channel_announcement_picker_dm_label, "Tenny"),
                        stringResource(Res.string.channel_announcement_picker_dm_group),
                        stringResource(Res.string.channel_announcement_picker_dm_empty),
                        stringResource(Res.string.channel_announcement_picker_dm_failed),
                        stringResource(Res.string.channel_announcement_replace_dm_body, "Next", "Current"),
                        stringResource(Res.string.channel_announcement_remove_dm_body, "Current"),
                        stringResource(Res.string.channel_announcement_event_set_untitled_dm, "Ada"),
                        stringResource(Res.string.channel_announcement_event_replaced_untitled_dm, "Ada"),
                        stringResource(Res.string.channel_announcement_event_removed_untitled_dm, "Ada"),
                    )
            }
            allLocales.forEach { locale ->
                runOnUiThread {
                    StringDesc.localeType = locale.stringDescLocaleType
                    activeLocale = locale
                }
                waitForIdle()
            }
            runOnIdle {
                assertEquals(8, collected.size)
                collected.forEach { (locale, strings) ->
                    assertEquals(10, strings.size, "locale=$locale")
                    strings.forEach { text ->
                        assertTrue(text.isNotBlank(), "blank DM string for locale=$locale")
                        assertFalse(forbidden.containsMatchIn(text), "locale=$locale contains forbidden channel/hash wording: $text")
                    }
                }
            }
        }

    /** Web ChannelOverflowMenu: DM details shows Pinned content above Your preferences; Agent Lounge omits it. */
    @Test
    fun dmDetailsShowsPinnedContentForDmAndOmitsItForAgentLounge() =
        runRobolectricComposeUiTest {
            var isDm by mutableStateOf(true)
            show {
                ThreadDirectMessageSettingsDrawer(
                    name = if (isDm) "Tenny" else "Tenny’s Lounge",
                    activityMuteState = null,
                    section = null,
                    sectionEnabled = true,
                    longMessageCollapseSetting = null,
                    onDismiss = {},
                    onToggleActivityMute = null,
                    onMoveSection = {},
                    onCreateSection = { _, _ -> },
                    asPage = true,
                    directMessageTag = isDm,
                    announcement = {
                        ChannelAnnouncementDetailsRow(
                            slice = ChannelAnnouncementSlice(read = true, announcement = null),
                            canEdit = true,
                            onOpen = {},
                            onSet = {},
                            onReplace = {},
                            onRemove = {},
                        )
                    },
                )
            }
            onNodeWithText("Pinned content").assertExists()
            onNodeWithText("Nothing pinned").assertExists()
            onNodeWithTag(ChannelAnnouncementTestTags.DetailsSet).assertExists()

            // Agent Lounge (`directMessageTag = false`): never renders the DM pinned content slot.
            runOnUiThread { isDm = false }
            waitForIdle()
            onNodeWithText("Pinned content").assertDoesNotExist()
            onNodeWithTag(ChannelAnnouncementTestTags.Details).assertDoesNotExist()
        }
}

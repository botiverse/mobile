package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI verification for `#wg-mobile-sync:#279` (`botiverse/slock` #16493):
 * `PendingMentionActionBanner` resolves target display names from the server/channel roster (not
 * just the composer's `@` suggestions, which omit non-members in private/joint channels), falls
 * back cleanly to the raw handle when unknown or mismatched by kind, and formats multi-target
 * summaries and detail sheets consistently.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class PendingMentionActionBannerNamesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun singleRowResolvesServerRosterDisplayNameEvenWhenComposerSuggestionsOmitNonMember() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("user-kirby"),
                                displayName = "WA kirby",
                                handle = "qa5537502fa9cb84b1",
                                kind = MemberKind.Human,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            val onlyChannelMember =
                listOf(
                    RaftComposerMemberItem(
                        displayName = "Inside Member",
                        handle = "inside",
                        inMentionChannel = true,
                        kind = MemberKind.Human,
                        memberId = "user-inside",
                    ),
                )
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-private",
                            memberSuggestions = onlyChannelMember,
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-kirby",
                                        channelName = "general",
                                        targetType = "user",
                                        targetHandle = "qa5537502fa9cb84b1",
                                        reason = "not_member",
                                        availableActions = listOf("add", "notify"),
                                    ),
                                ),
                        ),
                )
            }

            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@WA kirby")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("@WA kirby isn’t in #general. Add them to #general or send a one-time notification.")
            onNode(hasText("qa5537502fa9cb84b1", substring = true), useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun singleRowResolvesDisplayNameForAddedNotifiedAndLoungeExcludedStates() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("user-kirby"),
                                displayName = "WA kirby",
                                handle = "qa5537502fa9cb84b1",
                                kind = MemberKind.Human,
                                serverId = ServerId("s1"),
                            ),
                            MemberProfile(
                                id = MemberId("agent-nova"),
                                displayName = "Nova the Agent",
                                handle = "Nova",
                                kind = MemberKind.Agent,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            // 1. Added state
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-added",
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-added",
                                        channelName = "general",
                                        targetType = "user",
                                        targetHandle = "qa5537502fa9cb84b1",
                                        availableActions = listOf("add", "notify"),
                                    ),
                                ),
                            mentionActionStates = mapOf("res-added" to "added"),
                        ),
                )
            }
            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@WA kirby")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("@WA kirby was added to #general")
        }

    @Test
    fun singleRowResolvesDisplayNameForNotifiedState() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("user-kirby"),
                                displayName = "WA kirby",
                                handle = "qa5537502fa9cb84b1",
                                kind = MemberKind.Human,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-notified",
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-notified",
                                        channelName = "general",
                                        targetType = "user",
                                        targetHandle = "qa5537502fa9cb84b1",
                                        availableActions = listOf("add", "notify"),
                                    ),
                                ),
                            mentionActionStates = mapOf("res-notified" to "notified"),
                        ),
                )
            }
            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@WA kirby")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("Notified @WA kirby once — still not in #general")
        }

    @Test
    fun singleRowResolvesDisplayNameForLoungeExcludedAgent() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("agent-nova"),
                                displayName = "Nova the Agent",
                                handle = "Nova",
                                kind = MemberKind.Agent,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            // Lounge excluded agent shows resolved display name
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-lounge",
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-lounge",
                                        channelName = "lounge",
                                        targetType = "agent",
                                        targetHandle = "Nova",
                                        reason = PendingMentionReasonLoungeAgentExcluded,
                                        availableActions = emptyList(),
                                    ),
                                ),
                        ),
                )
            }
            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@Nova the Agent")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals(
                    "@Nova the Agent won't be notified: a message posted here goes only to this Lounge's agent. Mention them in a thread to notify them."
                )
        }

    @Test
    fun absentTargetFallsBackCleanlyToHandleAsWritten() =
        runRobolectricComposeUiTest {
            setContent {
                ComposerHost(
                    localTargetMap = PendingMentionTargetMap.Empty,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-stranger",
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-stranger",
                                        channelName = "general",
                                        targetType = "user",
                                        targetHandle = "stranger",
                                        availableActions = listOf("add", "notify"),
                                    ),
                                ),
                        ),
                )
            }
            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@stranger")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("@stranger isn’t in #general. Add them to #general or send a one-time notification.")
        }

    @Test
    fun unknownHandleAndKindMismatchFallBackToHandleAsWritten() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("agent-nova"),
                                displayName = "Nova the Agent",
                                handle = "Nova",
                                kind = MemberKind.Agent,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            // User mention whose handle collides with an agent's name: kind mismatch keeps raw handle.
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-wrong-kind",
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-wrong-kind",
                                        channelName = "general",
                                        targetType = "user",
                                        targetHandle = "Nova",
                                        availableActions = listOf("notify"),
                                    ),
                                ),
                        ),
                )
            }
            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@Nova")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("@Nova isn’t in #general. Send a one-time notification without adding them.")
            onNode(hasText("Nova the Agent", substring = true), useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun multiTargetBatchSummaryAndDetailsSheetFormatResolvedDisplayNamesAndFallbacks() =
        runRobolectricComposeUiTest {
            val roster =
                buildPendingMentionTargetMap(
                    serverMembers =
                        listOf(
                            MemberProfile(
                                id = MemberId("user-kirby"),
                                displayName = "WA kirby",
                                handle = "qa5537502fa9cb84b1",
                                kind = MemberKind.Human,
                                serverId = ServerId("s1"),
                            ),
                            MemberProfile(
                                id = MemberId("agent-nova"),
                                displayName = "Nova the Agent",
                                handle = "Nova",
                                kind = MemberKind.Agent,
                                serverId = ServerId("s1"),
                            ),
                        ),
                )
            val actions =
                listOf(
                    RaftPendingMentionActionItem(
                        resolutionId = "res-1",
                        channelName = "general",
                        targetType = "user",
                        targetHandle = "qa5537502fa9cb84b1",
                        availableActions = listOf("add", "notify"),
                    ),
                    RaftPendingMentionActionItem(
                        resolutionId = "res-2",
                        channelName = "general",
                        targetType = "agent",
                        targetHandle = "Nova",
                        reason = PendingMentionReasonLoungeAgentExcluded,
                        availableActions = emptyList(),
                    ),
                    RaftPendingMentionActionItem(
                        resolutionId = "res-3",
                        channelName = "general",
                        targetType = "user",
                        targetHandle = "stranger",
                        availableActions = listOf("add", "notify"),
                    ),
                )
            setContent {
                ComposerHost(
                    localTargetMap = roster,
                    state =
                        ThreadComposerUiState(
                            instanceId = "pending-batch",
                            pendingMentionActions = actions,
                        ),
                )
            }

            // Summary line shows resolved display names for the first two targets plus overflow count.
            onNodeWithTag(PendingMentionActionBatchNamesTestTag, useUnmergedTree = true)
                .assertTextEquals("@WA kirby · @Nova the Agent · +1")
            onNode(hasText("qa5537502fa9cb84b1", substring = true), useUnmergedTree = true).assertDoesNotExist()

            // Open details sheet and verify every compact row names its target with the same rule.
            onNodeWithText("View", useUnmergedTree = true).performClick()
            waitForIdle()

            val titles =
                onAllNodesWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .map { node ->
                        node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                            .joinToString("") { it.text }
                    }
            assertEquals(listOf("@WA kirby", "@Nova the Agent", "@stranger"), titles)
            onNode(
                hasTestTag(PendingMentionActionRowStatusTestTag) and
                    hasText(
                        "@Nova the Agent won't be notified: a message posted here goes only to this Lounge's agent. Mention them in a thread to notify them."
                    ),
                useUnmergedTree = true,
            ).assertExists()
        }

    @Test
    fun threadComposerRouteResolvesPendingMentionNamesFromPayloadServerCandidates() =
        runRobolectricComposeUiTest {
            val surface = ThreadComposerSurface(channelId = "c-private", mentionChannelLabel = "#secret")
            val draft =
                SharedThreadComposerDraftViewModel().apply {
                    dispatch(
                        SharedThreadComposerDraftIntent.Start(
                            composer = RaftThreadComposerPayload(instanceId = "composer-private", seedText = ""),
                            draftKey = surface.threadComposerDraftKey(),
                        ),
                    )
                    dispatch(
                        SharedThreadComposerDraftIntent.ApplyPendingMentionActions(
                            pendingMentionActions =
                                listOf(
                                    RaftPendingMentionActionItem(
                                        resolutionId = "res-route",
                                        channelName = "secret",
                                        targetType = "user",
                                        targetHandle = "qa5537502fa9cb84b1",
                                        availableActions = listOf("add", "notify"),
                                    ),
                                ),
                        ),
                    )
                }
            val payload =
                RaftThreadScreenPayload(
                    instanceId = "c-private",
                    status = "content",
                    memberCandidates =
                        listOf(
                            RaftThreadMemberItem(
                                id = "user-kirby",
                                displayName = "WA kirby",
                                handle = "qa5537502fa9cb84b1",
                                kind = "human",
                            ),
                        ),
                )
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    val portal = remember { ThreadComposerSuggestionPortalState() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                            ThreadComposerRoute(
                                baseComposerState = RaftThreadComposerPayload(instanceId = "composer-private"),
                                composerDraftViewModel = draft,
                                renderedPayload = payload,
                                timelineSlice = payload.toThreadTimelineSlice(),
                                resolvedAuxiliaryThreadId = "c-private",
                                surface = surface,
                                // Empty memberSuggestionsFlow simulates a private channel where non-members are excluded.
                                memberSuggestionsFlow = flowOf(SharedMembersMemberSuggestionSlice()),
                                channelSuggestionsFlow = flowOf(SharedMembersChannelSuggestionSlice()),
                                agentStatusResolver = EmptyRaftAgentStatusResolver,
                                onEvent = { _, _, _, _ -> },
                                onDropFiles = {},
                                onSendRequest = { ThreadComposerSendAdmission.Rejected },
                                onKeyboardHeightChange = {},
                            )
                        }
                    }
                    ThreadComposerSuggestionPortalHost(portal)
                }
            }

            onNodeWithTag(PendingMentionActionRowTargetTestTag, useUnmergedTree = true).assertTextEquals("@WA kirby")
            onNodeWithTag(PendingMentionActionRowStatusTestTag, useUnmergedTree = true)
                .assertTextEquals("@WA kirby isn’t in #secret. Add them to #secret or send a one-time notification.")
        }

    @androidx.compose.runtime.Composable
    private fun ComposerHost(
        localTargetMap: PendingMentionTargetMap,
        state: ThreadComposerUiState,
        surface: ThreadComposerSurface = ThreadComposerSurface(channelId = "c1", mentionChannelLabel = "#general"),
    ) {
        RaftTheme(appearance = RaftAppearance.Light) {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(
                    LocalThreadComposerSuggestionPortal provides portal,
                    LocalPendingMentionTargetMap provides localTargetMap,
                ) {
                    ThreadComposerContent(
                        surface = surface,
                        state = state,
                        onEvent = { _, _, _, _ -> },
                        onKeyboardHeightChange = {},
                        contentPaddingTop = 0.dp,
                        contentPaddingBottom = 0.dp,
                        attachmentPreviewEnabled = false,
                    )
                }
            }
            ThreadComposerSuggestionPortalHost(portal)
        }
    }
}

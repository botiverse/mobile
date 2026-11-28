package build.raft.app

import build.raft.app.data.MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderActionSelectedProperties
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftProductEvent
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.model.ServerId
import build.raft.app.shell.RaftNativeNavBarAction
import build.raft.app.shell.RaftNativeNavBarPlacement
import build.raft.app.shell.foldPhoneConversationNativeNavBarActions
import build.raft.app.shell.raftNativeNavBarSplit
import build.raft.app.shell.raftNativeNavBarSymbolFor
import build.raft.app.thread.PhoneConversationHeaderActionSlot
import build.raft.app.thread.PhoneConversationHeaderPrimaryConfig
import build.raft.app.thread.PhoneConversationHeaderPrimaryEntry
import build.raft.app.thread.THREAD_FOLLOWERS_MENU_ITEM_ID
import build.raft.app.thread.ThreadHeaderOverflowMenuLabels
import build.raft.app.thread.ThreadHeaderOwnActionSpec
import build.raft.app.thread.icon
import build.raft.app.thread.parsePhoneConversationHeaderPrimaryConfig
import build.raft.app.thread.phoneConversationHeaderPrimaryConfig
import build.raft.app.thread.splitPhoneConversationHeaderActions
import build.raft.app.thread.threadHeaderConversationOwnActions
import build.raft.app.thread.threadHeaderFollowersFolded
import build.raft.app.thread.threadHeaderOverflowMenuItems
import build.raft.app.thread.threadHeaderPhoneShownOwnActions
import build.raft.app.thread.threadHeaderShownOwnActions
import build.raft.app.thread.wrapThreadHeaderNativeNavBarActionsForAnalytics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #proj-mobile:#281: web Task v2 conversation head button parity (slock `useChannelHeadToggles` /
 * `headFit.ts`) across Android/Compose and iOS native navigation bars.
 */
class ThreadConversationHeadActionsTest {
    @Test
    fun conversationHeadButtonsFollowWebOrderWithoutMembersAndFoldFromLastWhenNarrow() {
        val sideCards =
            listOf(
                ThreadHeaderOwnActionSpec(
                    id = "thread-side-card-tasks",
                    label = "Tasks",
                    icon = RaftLucideIconName.ListTodo,
                    toggle = true,
                    onClick = {},
                ),
                ThreadHeaderOwnActionSpec(
                    id = "thread-side-card-files",
                    label = "Files",
                    icon = RaftLucideIconName.Paperclip,
                    toggle = true,
                    onClick = {},
                ),
            )
        val channelActions =
            threadHeaderConversationOwnActions(
                searchLabel = "Search",
                onOpenSearch = {},
                sideCardActions = sideCards,
                settingsLabel = "Channel Details",
                settingsOpen = false,
                onShowSettings = {},
                openInChannelLabel = null,
                onOpenInChannel = {},
            )
        assertEquals(
            listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            channelActions.map { it.id },
        )

        // Standard 393dp phone fits Search, Tasks, Files, Settings with room left for the title.
        val shownPhone =
            threadHeaderShownOwnActions(
                widthDp = 393f,
                leadOverheadDp = 105f,
                ownCount = channelActions.size,
                actionSizeDp = 44f,
            )
        assertEquals(4, shownPhone)

        // Narrow 320dp screen folds from the last (Files, Settings) into ⋯ so the title keeps >= 56dp.
        val shownNarrow =
            threadHeaderShownOwnActions(
                widthDp = 320f,
                leadOverheadDp = 105f,
                ownCount = channelActions.size,
                actionSizeDp = 44f,
            )
        assertEquals(2, shownNarrow)
        assertEquals(listOf("thread-search", "thread-side-card-tasks"), channelActions.take(shownNarrow).map { it.id })
        assertEquals(listOf("thread-side-card-files", "thread-settings"), channelActions.drop(shownNarrow).map { it.id })
    }

    @Test
    fun dmHeadOmitsLegacyLoungeButtonAndIosNativeBarFoldsFromLastWithoutMembers() {
        val sideCards =
            listOf(
                ThreadHeaderOwnActionSpec(
                    id = "thread-side-card-tasks",
                    label = "Tasks",
                    icon = RaftLucideIconName.ListTodo,
                    toggle = true,
                    onClick = {},
                ),
                ThreadHeaderOwnActionSpec(
                    id = "thread-side-card-files",
                    label = "Files",
                    icon = RaftLucideIconName.Paperclip,
                    toggle = true,
                    onClick = {},
                ),
            )
        val dmActions =
            threadHeaderConversationOwnActions(
                searchLabel = "Search",
                onOpenSearch = {},
                sideCardActions = sideCards,
                settingsLabel = "Details",
                settingsOpen = false,
                onShowSettings = {},
                openInChannelLabel = null,
                onOpenInChannel = {},
            )
        assertEquals(
            listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            dmActions.map { it.id },
        )

        // Without the legacy Lounge button, all 4 DM head buttons fit on a 360dp phone.
        val shownPhoneDm =
            threadHeaderShownOwnActions(
                widthDp = 360f,
                leadOverheadDp = 112f,
                ownCount = dmActions.size,
                actionSizeDp = 44f,
            )
        assertEquals(4, shownPhoneDm)
        // Narrow 320dp phone folds Files and Settings into ⋯ while keeping Search and Tasks.
        val shownNarrowDm =
            threadHeaderShownOwnActions(
                widthDp = 320f,
                leadOverheadDp = 112f,
                ownCount = dmActions.size,
                actionSizeDp = 44f,
            )
        assertEquals(2, shownNarrowDm)
        assertEquals(listOf("thread-search", "thread-side-card-tasks"), dmActions.take(shownNarrowDm).map { it.id })
        assertEquals(listOf("thread-side-card-files", "thread-settings"), dmActions.drop(shownNarrowDm).map { it.id })

        // iOS native nav bar phone conversation fold (#proj-chat:#45, botiverse/slock#16464):
        // keeps Search on the bar and folds Call (when present), Tasks, Files, and Details into ⋯
        // in that exact order, with SF Symbol fallbacks for each action.
        val nativeChannelActions =
            listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings").map { id ->
                RaftNativeNavBarAction(
                    id = id,
                    label = id,
                    systemSymbol = raftNativeNavBarSymbolFor(id),
                    onClick = {},
                )
            }
        val foldedPhoneChannel =
            raftNativeNavBarSplit(
                foldPhoneConversationNativeNavBarActions(nativeChannelActions, foldForPhone = true)
            )
        assertEquals(
            listOf("thread-search"),
            foldedPhoneChannel.filter { it.placement == RaftNativeNavBarPlacement.Trailing }.map { it.id },
        )
        assertEquals(
            listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            foldedPhoneChannel.filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id },
        )
        val nativeAgentDmWithCall =
            listOf("thread-search", "thread-call", "thread-side-card-tasks", "thread-side-card-files", "thread-settings").map { id ->
                RaftNativeNavBarAction(
                    id = id,
                    label = id,
                    systemSymbol = raftNativeNavBarSymbolFor(id),
                    onClick = {},
                )
            }
        val foldedPhoneAgentDm =
            raftNativeNavBarSplit(
                foldPhoneConversationNativeNavBarActions(nativeAgentDmWithCall, foldForPhone = true)
            )
        assertEquals(
            listOf("thread-search"),
            foldedPhoneAgentDm.filter { it.placement == RaftNativeNavBarPlacement.Trailing }.map { it.id },
        )
        assertEquals(
            listOf("thread-call", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            foldedPhoneAgentDm.filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id },
        )
        assertEquals("phone", raftNativeNavBarSymbolFor("thread-call"))
        assertEquals("checklist", raftNativeNavBarSymbolFor("thread-side-card-tasks"))
        assertEquals("paperclip", raftNativeNavBarSymbolFor("thread-side-card-files"))
        assertEquals(AgentLoungeSystemSymbol, raftNativeNavBarSymbolFor("lounge"))
    }

    @Test
    fun phoneHeadFoldAlwaysKeepsOnlySearchAndMoreAcrossConversationKindsAndKeepsCallFirstInOverflow() {
        // Channel / Human DM / Agent Lounge: 4 own actions -> Search visible, Tasks / Files / Details in ⋯.
        assertEquals(1, threadHeaderPhoneShownOwnActions(ownCount = 4))
        // Agent DM with Call: 5 own actions -> Search visible, Call / Tasks / Files / Details in ⋯.
        assertEquals(1, threadHeaderPhoneShownOwnActions(ownCount = 5))
        // 3 own actions (e.g. Search, Files, Details): Search visible, Files / Details in ⋯.
        assertEquals(1, threadHeaderPhoneShownOwnActions(ownCount = 3))
        // 2 own actions (Search, Details) without pre-existing menu items: both stay visible, no ⋯.
        assertEquals(2, threadHeaderPhoneShownOwnActions(ownCount = 2))
        // 2 own actions WITH pre-existing menu items: keep Search + ⋯ (2 buttons total).
        assertEquals(1, threadHeaderPhoneShownOwnActions(ownCount = 2, existingMenuItems = true))
        assertEquals(0, threadHeaderPhoneShownOwnActions(ownCount = 0))

        // Verify Call sits immediately after Search, before Tasks, Files, and Details.
        val sideCards =
            listOf(
                ThreadHeaderOwnActionSpec(id = "thread-side-card-tasks", label = "Open Tasks", icon = RaftLucideIconName.ListTodo, toggle = true, onClick = {}),
                ThreadHeaderOwnActionSpec(id = "thread-side-card-files", label = "Open files", icon = RaftLucideIconName.Paperclip, toggle = true, onClick = {}),
            )
        val agentDmActions =
            threadHeaderConversationOwnActions(
                searchLabel = "Search this conversation",
                onOpenSearch = {},
                callLabel = "Call",
                onStartCall = {},
                sideCardActions = sideCards,
                settingsLabel = "Details and settings",
                settingsOpen = false,
                onShowSettings = {},
                openInChannelLabel = null,
                onOpenInChannel = {},
            )
        val shown = threadHeaderPhoneShownOwnActions(ownCount = agentDmActions.size)
        assertEquals(listOf("thread-search"), agentDmActions.take(shown).map { it.id })
        assertEquals(
            listOf("thread-call", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"),
            agentDmActions.drop(shown).map { it.id },
        )
    }

    @Test
    fun phoneHeaderRemoteConfigParsesVariantsAndDefaultsCleanlyWhenOffBlankOrInvalid() {
        assertEquals(PhoneConversationHeaderPrimaryConfig.Default, ServerFeatureFlagSnapshot.Unresolved.phoneConversationHeaderPrimaryConfig())
        assertEquals(
            PhoneConversationHeaderPrimaryConfig.Default,
            ServerFeatureFlagSnapshot(
                serverId = ServerId("s1"),
                resolved = true,
                values = mapOf(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY to false),
                variants = mapOf(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY to "tasks,search"),
            ).phoneConversationHeaderPrimaryConfig(),
        )
        assertEquals(
            PhoneConversationHeaderPrimaryConfig.Default,
            ServerFeatureFlagSnapshot(
                serverId = ServerId("s1"),
                resolved = true,
                values = mapOf(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY to true),
                variants = emptyMap(),
            ).phoneConversationHeaderPrimaryConfig(),
        )
        assertEquals(PhoneConversationHeaderPrimaryConfig.Default, parsePhoneConversationHeaderPrimaryConfig(null))
        assertEquals(PhoneConversationHeaderPrimaryConfig.Default, parsePhoneConversationHeaderPrimaryConfig("   "))
        assertEquals(PhoneConversationHeaderPrimaryConfig.Default, parsePhoneConversationHeaderPrimaryConfig(",,,"))
        assertEquals(PhoneConversationHeaderPrimaryConfig.Default, parsePhoneConversationHeaderPrimaryConfig("bogus,unknown"))

        val snapshotTasksSearch =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("s1"),
                resolved = true,
                values = mapOf(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY to true),
                variants = mapOf(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY to " Tasks , unknown , SEARCH , tasks "),
            )
        assertEquals(
            PhoneConversationHeaderPrimaryConfig(
                listOf(
                    PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Tasks),
                    PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Search),
                )
            ),
            snapshotTasksSearch.phoneConversationHeaderPrimaryConfig(),
        )
        // "settings" is an alias for "details" and deduplicates against "details".
        assertEquals(
            PhoneConversationHeaderPrimaryConfig(
                listOf(
                    PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Details),
                    PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Search),
                )
            ),
            parsePhoneConversationHeaderPrimaryConfig("Settings, details, search"),
        )
        // Tokens after "none" are unreachable and truncated cleanly.
        assertEquals(
            PhoneConversationHeaderPrimaryConfig(
                listOf(
                    PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Tasks),
                    PhoneConversationHeaderPrimaryEntry.None,
                )
            ),
            parsePhoneConversationHeaderPrimaryConfig("tasks, NONE, search"),
        )
    }

    @Test
    fun phoneHeaderRemoteConfigSelectsFirstAvailablePrimaryAndPreservesAllRemainingActionsInOrder() {
        val channelIds = listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings")
        val dmWithoutTasksIds = listOf("thread-search", "thread-side-card-files", "thread-settings")
        val agentDmWithCallIds = listOf("thread-search", "thread-call", "thread-side-card-tasks", "thread-side-card-files", "thread-settings")

        // Default: Search exposed, rest in ⋯ in canonical head order.
        val defaultChannel = splitPhoneConversationHeaderActions(channelIds, idOf = { it })
        assertEquals(listOf("thread-search"), defaultChannel.visible)
        assertEquals(listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"), defaultChannel.folded)

        // "tasks,search": Channel exposes Tasks (Search folded into ⋯), DM without Tasks falls back to Search.
        val tasksSearch = parsePhoneConversationHeaderPrimaryConfig("tasks,search")
        val tasksChannel = splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = tasksSearch)
        assertEquals(listOf("thread-side-card-tasks"), tasksChannel.visible)
        assertEquals(listOf("thread-search", "thread-side-card-files", "thread-settings"), tasksChannel.folded)

        val tasksDmFallback = splitPhoneConversationHeaderActions(dmWithoutTasksIds, idOf = { it }, config = tasksSearch)
        assertEquals(listOf("thread-search"), tasksDmFallback.visible)
        assertEquals(listOf("thread-side-card-files", "thread-settings"), tasksDmFallback.folded)

        val tasksAgentDm = splitPhoneConversationHeaderActions(agentDmWithCallIds, idOf = { it }, config = tasksSearch)
        assertEquals(listOf("thread-side-card-tasks"), tasksAgentDm.visible)
        assertEquals(listOf("thread-search", "thread-call", "thread-side-card-files", "thread-settings"), tasksAgentDm.folded)

        // "files,search": exposes Files.
        val filesChannel =
            splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = parsePhoneConversationHeaderPrimaryConfig("files,search"))
        assertEquals(listOf("thread-side-card-files"), filesChannel.visible)
        assertEquals(listOf("thread-search", "thread-side-card-tasks", "thread-settings"), filesChannel.folded)

        // "details" and alias "settings": expose Details.
        for (variant in listOf("details", "settings")) {
            val detailsChannel =
                splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = parsePhoneConversationHeaderPrimaryConfig(variant))
            assertEquals(listOf("thread-settings"), detailsChannel.visible, variant)
            assertEquals(listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files"), detailsChannel.folded, variant)
        }

        // "call,search": Agent DM exposes Call; Channel without Call falls back to Search.
        val callSearch = parsePhoneConversationHeaderPrimaryConfig("call,search")
        val callAgentDm = splitPhoneConversationHeaderActions(agentDmWithCallIds, idOf = { it }, config = callSearch)
        assertEquals(listOf("thread-call"), callAgentDm.visible)
        assertEquals(listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings"), callAgentDm.folded)

        val callChannelFallback = splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = callSearch)
        assertEquals(listOf("thread-search"), callChannelFallback.visible)
        assertEquals(listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"), callChannelFallback.folded)

        // Single unmatched token ("call") falls back to Search when available, else first available action.
        val callOnly = parsePhoneConversationHeaderPrimaryConfig("call")
        val callOnlyChannel = splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = callOnly)
        assertEquals(listOf("thread-search"), callOnlyChannel.visible)
        assertEquals(listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings"), callOnlyChannel.folded)

        val noSearchOrCall = listOf("thread-side-card-tasks", "thread-side-card-files", "thread-settings")
        val firstAvailableFallback = splitPhoneConversationHeaderActions(noSearchOrCall, idOf = { it }, config = callOnly)
        assertEquals(listOf("thread-side-card-tasks"), firstAvailableFallback.visible)
        assertEquals(listOf("thread-side-card-files", "thread-settings"), firstAvailableFallback.folded)

        // "none": exposes nothing beside ⋯, folding ALL available actions (even when <= 2 actions).
        val noneConfig = parsePhoneConversationHeaderPrimaryConfig("none")
        val noneChannel = splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = noneConfig)
        assertEquals(emptyList(), noneChannel.visible)
        assertEquals(channelIds, noneChannel.folded)

        val twoActions = listOf("thread-search", "thread-settings")
        val noneTwoActions = splitPhoneConversationHeaderActions(twoActions, idOf = { it }, config = noneConfig)
        assertEquals(emptyList(), noneTwoActions.visible)
        assertEquals(twoActions, noneTwoActions.folded)

        val noneEmpty = splitPhoneConversationHeaderActions(emptyList<String>(), idOf = { it }, config = noneConfig)
        assertEquals(emptyList(), noneEmpty.visible)
        assertEquals(emptyList(), noneEmpty.folded)

        // "tasks,none": channel exposes Tasks; DM without Tasks hits "none" and folds everything into ⋯.
        val tasksNone = parsePhoneConversationHeaderPrimaryConfig("tasks,none")
        val tasksNoneChannel = splitPhoneConversationHeaderActions(channelIds, idOf = { it }, config = tasksNone)
        assertEquals(listOf("thread-side-card-tasks"), tasksNoneChannel.visible)
        assertEquals(listOf("thread-search", "thread-side-card-files", "thread-settings"), tasksNoneChannel.folded)

        val tasksNoneDm = splitPhoneConversationHeaderActions(dmWithoutTasksIds, idOf = { it }, config = tasksNone)
        assertEquals(emptyList(), tasksNoneDm.visible)
        assertEquals(dmWithoutTasksIds, tasksNoneDm.folded)
    }

    @Test
    fun nativeNavBarAnalyticsAttributesFoldedSearchToMoreMenuAndPromotedDetailsOrCallToHeader() {
        val recorded = mutableListOf<RaftProductEvent<*>>()
        val previous = RaftProductEvents.testEntryEventObserver
        RaftProductEvents.testEntryEventObserver = { recorded += it }
        try {
            fun headerEvents(): List<RaftConversationHeaderActionSelectedProperties> =
                recorded.filterIsInstance<RaftProductEvent.ConversationHeaderActionSelected>().map { it.properties }

            val rawChannelActions =
                listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings").map { id ->
                    RaftNativeNavBarAction(id = id, label = id, systemSymbol = raftNativeNavBarSymbolFor(id), onClick = {})
                }
            val foldedTasksPrimary =
                wrapThreadHeaderNativeNavBarActionsForAnalytics(
                    actions =
                        foldPhoneConversationNativeNavBarActions(
                            actions = rawChannelActions,
                            foldForPhone = true,
                            primaryConfig = parsePhoneConversationHeaderPrimaryConfig("tasks,search"),
                        ),
                    conversation = RaftConversationHeaderKind.Channel,
                    compact = true,
                    settingsOpen = false,
                    focusModeActive = false,
                    threadFollowed = null,
                    taskConversation = false,
                )
            // Promoted Tasks stays owned by channel_tab_selected (emits no conversation_header_action_selected).
            foldedTasksPrimary.first { it.id == "thread-side-card-tasks" }.onClick()
            assertTrue(headerEvents().isEmpty())
            // Folded Search emits Search on MoreMenu; folded Details emits OpenDetails on MoreMenu.
            foldedTasksPrimary.first { it.id == "thread-search" }.onClick()
            foldedTasksPrimary.first { it.id == "thread-settings" }.onClick()
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Search,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenDetails,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )

            recorded.clear()
            // Promoted Details emits OpenDetails / CloseDetails on Header; folded Search emits Search on MoreMenu.
            val foldedDetailsOpen =
                wrapThreadHeaderNativeNavBarActionsForAnalytics(
                    actions =
                        foldPhoneConversationNativeNavBarActions(
                            actions = rawChannelActions,
                            foldForPhone = true,
                            primaryConfig = parsePhoneConversationHeaderPrimaryConfig("details"),
                        ),
                    conversation = RaftConversationHeaderKind.Channel,
                    compact = true,
                    settingsOpen = true,
                    focusModeActive = false,
                    threadFollowed = null,
                    taskConversation = false,
                )
            foldedDetailsOpen.first { it.id == "thread-settings" }.onClick()
            foldedDetailsOpen.first { it.id == "thread-search" }.onClick()
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.CloseDetails,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Search,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )

            recorded.clear()
            // Promoted Call on Agent DM emits StartCall on Header; folded Search emits Search on MoreMenu.
            val rawAgentDmActions =
                listOf("thread-search", "thread-call", "thread-side-card-tasks", "thread-side-card-files", "thread-settings").map { id ->
                    RaftNativeNavBarAction(id = id, label = id, systemSymbol = raftNativeNavBarSymbolFor(id), onClick = {})
                }
            val foldedCallPrimary =
                wrapThreadHeaderNativeNavBarActionsForAnalytics(
                    actions =
                        foldPhoneConversationNativeNavBarActions(
                            actions = rawAgentDmActions,
                            foldForPhone = true,
                            primaryConfig = parsePhoneConversationHeaderPrimaryConfig("call,search"),
                        ),
                    conversation = RaftConversationHeaderKind.Dm,
                    compact = true,
                    settingsOpen = false,
                    focusModeActive = false,
                    threadFollowed = null,
                    taskConversation = false,
                )
            foldedCallPrimary.first { it.id == "thread-call" }.onClick()
            foldedCallPrimary.first { it.id == "thread-search" }.onClick()
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.StartCall,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Search,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        } finally {
            RaftProductEvents.testEntryEventObserver = previous
        }
    }

    @Test
    fun threadHeaderFollowersFoldIntoOverflowMenuWhenNarrowOrCrowdedByRowTabs() {
        // Unmeasured width or no followers never folds.
        assertFalse(
            threadHeaderFollowersFolded(
                hasFollowers = false,
                widthDp = 180f,
                leadOverheadDp = 38f,
                actionSizeDp = 28f,
                fixedTrailingCount = 2,
                existingMenuItems = true,
            )
        )
        assertFalse(
            threadHeaderFollowersFolded(
                hasFollowers = true,
                widthDp = 0f,
                leadOverheadDp = 38f,
                actionSizeDp = 28f,
                fixedTrailingCount = 2,
                existingMenuItems = true,
            )
        )
        // Standard 360dp side thread pane keeps followers inline beside ⋯, Expand, Close.
        assertFalse(
            threadHeaderFollowersFolded(
                hasFollowers = true,
                widthDp = 360f,
                leadOverheadDp = 38f,
                actionSizeDp = 28f,
                fixedTrailingCount = 2,
                existingMenuItems = true,
            )
        )
        // Narrow 220dp side thread pane folds followers into ⋯ so the title keeps >= 56dp.
        assertTrue(
            threadHeaderFollowersFolded(
                hasFollowers = true,
                widthDp = 220f,
                leadOverheadDp = 38f,
                actionSizeDp = 28f,
                fixedTrailingCount = 2,
                existingMenuItems = true,
            )
        )
        // Crowded split view with inline row tabs reserves 120dp for tabs, folding followers at 300dp.
        assertTrue(
            threadHeaderFollowersFolded(
                hasFollowers = true,
                widthDp = 300f,
                leadOverheadDp = 38f,
                actionSizeDp = 28f,
                fixedTrailingCount = 2,
                existingMenuItems = true,
                hasRowTabs = true,
            )
        )

        // When folded, "Following agents" joins the conversation group after Search / Timeline / View in Channel.
        val menuItems =
            threadHeaderOverflowMenuItems(
                labels =
                    ThreadHeaderOverflowMenuLabels(
                        followLabel = "Unfollow thread",
                        markUnreadLabel = "Mark as unread",
                        searchLabel = "Search in thread",
                        viewInChannelLabel = "View in channel",
                    ),
                foldedActions =
                    listOf(
                        ThreadHeaderOwnActionSpec(
                            id = THREAD_FOLLOWERS_MENU_ITEM_ID,
                            label = "Following agents",
                            icon = RaftLucideIconName.Users,
                            onClick = {},
                        )
                    ),
                followed = true,
                hasToggleFollow = true,
                hasMarkUnread = true,
                hasSearch = true,
                hasViewInChannel = true,
            )
        assertEquals(
            listOf(
                "thread-toggle-follow",
                "thread-mark-unread",
                "task-search",
                "task-view-in-channel",
                THREAD_FOLLOWERS_MENU_ITEM_ID,
            ),
            menuItems.map { it.id },
        )
        assertEquals(RaftLucideIconName.Users, menuItems.last().icon)
        assertFalse(menuItems.last().dividerBefore)
    }
}

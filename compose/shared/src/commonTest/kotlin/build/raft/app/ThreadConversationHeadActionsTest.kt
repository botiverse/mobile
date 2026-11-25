package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

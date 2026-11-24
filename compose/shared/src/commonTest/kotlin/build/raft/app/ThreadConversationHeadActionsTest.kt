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

        // iOS native nav bar keeps the first two (Search, Tasks for a channel) on the bar and folds
        // Files and Settings into ⋯, with SF Symbol fallbacks for each action.
        val nativeChannelActions =
            listOf("thread-search", "thread-side-card-tasks", "thread-side-card-files", "thread-settings").map { id ->
                RaftNativeNavBarAction(
                    id = id,
                    label = id,
                    systemSymbol = raftNativeNavBarSymbolFor(id),
                    onClick = {},
                )
            }
        val split = raftNativeNavBarSplit(nativeChannelActions)
        assertEquals(
            listOf("thread-search", "thread-side-card-tasks"),
            split.filter { it.placement == RaftNativeNavBarPlacement.Trailing }.map { it.id },
        )
        assertEquals(
            listOf("thread-side-card-files", "thread-settings"),
            split.filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id },
        )
        assertEquals("checklist", raftNativeNavBarSymbolFor("thread-side-card-tasks"))
        assertEquals("paperclip", raftNativeNavBarSymbolFor("thread-side-card-files"))
        assertEquals("person.2", raftNativeNavBarSymbolFor("lounge"))
    }
}

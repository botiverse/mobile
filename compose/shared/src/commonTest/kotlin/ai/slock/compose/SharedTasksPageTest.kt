package ai.slock.compose

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedTasksPageTest {
    @Test
    fun tasksHeaderSubtitleShowsTotalWhenUnfiltered() {
        assertEquals("42 channel tasks", tasksHeaderSubtitle(totalTasks = 42, visibleTasks = 42))
    }

    @Test
    fun tasksHeaderSubtitleShowsVisibleOfTotalWhenFiltered() {
        assertEquals("12 of 42 channel tasks", tasksHeaderSubtitle(totalTasks = 42, visibleTasks = 12))
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactFilterText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks match this filter",
                description = "Try a different combination or clear the current selection."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = true, isChannelMode = true)
        )
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactChannelModeText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "Create one with the New Task button."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = false, isChannelMode = true)
        )
    }

    @Test
    fun tasksEmptyStateCopyMatchesReactServerWideText() {
        assertEquals(
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "DM and agent task boards are intentionally excluded from this server-wide view."
            ),
            sharedTasksEmptyStateCopy(hasAnyFilter = false, isChannelMode = false)
        )
    }

    @Test
    fun tasksToolbarKeepsThreeFiltersTogetherWhenTheyFit() {
        assertEquals(
            listOf(
                listOf(
                    TasksToolbarControl.ChannelFilter,
                    TasksToolbarControl.CreatorFilter,
                    TasksToolbarControl.AssigneeFilter
                ),
                listOf(TasksToolbarControl.ViewSwitch)
            ),
            tasksToolbarRows(
                maxWidth = 728.dp,
                showChannelFilter = true,
                showClearAll = false,
                showCreateButton = false
            )
        )
    }

    @Test
    fun tasksToolbarWrapsViewSwitchWhenCreateButtonWouldOverlapIt() {
        assertEquals(
            listOf(
                listOf(
                    TasksToolbarControl.CreatorFilter,
                    TasksToolbarControl.AssigneeFilter,
                    TasksToolbarControl.NewTask
                ),
                listOf(TasksToolbarControl.ViewSwitch)
            ),
            tasksToolbarRows(
                maxWidth = 728.dp,
                showChannelFilter = false,
                showClearAll = false,
                showCreateButton = true
            )
        )
    }
}

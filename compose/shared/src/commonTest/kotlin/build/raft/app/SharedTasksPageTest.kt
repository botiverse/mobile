package build.raft.app

import build.raft.app.model.ChannelId
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
    fun serverWideEmptyTasksSkipsToolbarWithoutCreateAction() {
        assertEquals(false, sharedTasksEmptyToolbarVisible(createTasksChannelId = null))
        assertEquals(true, sharedTasksEmptyToolbarVisible(createTasksChannelId = ChannelId("channel-1")))
    }

    @Test
    fun tasksToolbarActionRowKeepsCreateAndViewSwitchTogetherWhenTheyFit() {
        assertEquals(
            listOf(listOf(TasksToolbarActionControl.NewTask, TasksToolbarActionControl.ViewSwitch)),
            tasksToolbarActionRows(
                maxWidth = 728.dp,
                showCreateButton = true
            )
        )
    }

    @Test
    fun tasksToolbarActionRowWrapsViewSwitchAsGroupOnNarrowWidth() {
        assertEquals(
            listOf(
                listOf(TasksToolbarActionControl.NewTask),
                listOf(TasksToolbarActionControl.ViewSwitch)
            ),
            tasksToolbarActionRows(
                maxWidth = 420.dp,
                showCreateButton = true
            )
        )
    }

    @Test
    fun tasksToolbarActionRowOnlyShowsViewSwitchWhenCreateIsUnavailable() {
        assertEquals(
            listOf(listOf(TasksToolbarActionControl.ViewSwitch)),
            tasksToolbarActionRows(
                maxWidth = 728.dp,
                showCreateButton = false
            )
        )
    }

    @Test
    fun tasksBeyondBoundsKeepsSceneEnterPremeasureSmall() {
        assertEquals(1, sharedTasksBeyondBoundsItemCount(0))
        assertEquals(4, sharedTasksBeyondBoundsItemCount(40))
        assertEquals(4, sharedTasksBeyondBoundsItemCount(1752))
    }

}

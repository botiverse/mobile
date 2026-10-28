package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task v2 hides the composer's "As Task" (slock staging 2618da4f
 * `packages/web/src/components/message/ChatPanel.tsx` `showTaskButton={supportsChannelTasks &&
 * allowTasks && !taskV2}`, `asTaskShortcutDisabled={taskV2}`; ThreadPanel the same): a message is
 * sent as a message and the task is created from it. With the flag off nothing changes.
 */
class ThreadComposerTaskV2Test {
    @Test
    fun asTaskShowsAndSendsOnlyWithTaskV2Off() {
        // (surface kind, channel supports tasks, task v2) -> (toggle shown, sends as task)
        data class Case(val kind: String, val supportsTasks: Boolean, val taskV2: Boolean, val shown: Boolean, val sends: Boolean)
        val cases =
            listOf(
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = true, taskV2 = false, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = true, taskV2 = true, shown = false, sends = false),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = false, taskV2 = false, shown = false, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = false, taskV2 = true, shown = false, sends = false),
                Case(RaftThreadSurfaceKind.ReplyThread, supportsTasks = false, taskV2 = false, shown = false, sends = true),
                Case(RaftThreadSurfaceKind.ReplyThread, supportsTasks = false, taskV2 = true, shown = false, sends = false),
            )
        cases.forEach { case ->
            val base =
                RaftThreadComposerPayload(surfaceKind = case.kind, streamChannelId = "stream-1", showAsTaskToggle = case.supportsTasks)
                    .toThreadComposerSurface(channelId = "channel-1")
            val surface = base.withTaskV2(case.taskV2)
            assertEquals(case.shown to case.sends, surface.showTaskButton to surface.sendsAsTask, case.toString())
            // The draft is the same draft either way: the flag never moves it to another key.
            assertEquals(base.threadComposerDraftKey(), surface.threadComposerDraftKey(), case.toString())
            if (!case.taskV2) assertEquals(base, surface, "flag off leaves the surface as it was: $case")
        }
    }
}

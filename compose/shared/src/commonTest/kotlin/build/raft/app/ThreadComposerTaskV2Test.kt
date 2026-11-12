package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task v2 keeps "As task" in a channel's and a DM's composer (slock #13996, ChatPanel
 * `showTaskButton={supportsChannelTasks && allowTasks}`) and drops it elsewhere (a reply thread, a
 * task's conversation). With the flag off nothing changes.
 */
class ThreadComposerTaskV2Test {
    @Test
    fun asTaskShowsAndSendsOnlyWithTaskV2Off() {
        // (surface kind, channel supports tasks, task v2) -> (toggle shown, sends as task)
        data class Case(val kind: String, val supportsTasks: Boolean, val taskV2: Boolean, val shown: Boolean, val sends: Boolean)
        val cases =
            listOf(
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = true, taskV2 = false, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = true, taskV2 = true, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.DirectMessage, supportsTasks = true, taskV2 = true, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = false, taskV2 = false, shown = false, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = false, taskV2 = true, shown = false, sends = true),
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
            if (!case.taskV2 || case.kind != RaftThreadSurfaceKind.ReplyThread) assertEquals(base, surface, "only a reply thread changes: $case")
        }
    }
}

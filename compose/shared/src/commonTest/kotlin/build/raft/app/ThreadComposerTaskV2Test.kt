package build.raft.app

import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.threadComposerDraftKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task v2 keeps "As task" in a channel's and a DM's composer (slock #13996, ChatPanel
 * `showTaskButton={supportsChannelTasks && allowTasks}`) and drops it elsewhere (a reply thread, a
 * task's conversation).
 */
class ThreadComposerTaskV2Test {
    @Test
    fun asTaskShowsAndSendsOnlyInAChannelOrADm() {
        // (surface kind, channel supports tasks) -> (toggle shown, sends as task)
        data class Case(val kind: String, val supportsTasks: Boolean, val shown: Boolean, val sends: Boolean)
        val cases =
            listOf(
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = true, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.DirectMessage, supportsTasks = true, shown = true, sends = true),
                Case(RaftThreadSurfaceKind.Channel, supportsTasks = false, shown = false, sends = true),
                Case(RaftThreadSurfaceKind.ReplyThread, supportsTasks = false, shown = false, sends = false),
            )
        cases.forEach { case ->
            val base =
                RaftThreadComposerPayload(surfaceKind = case.kind, streamChannelId = "stream-1", showAsTaskToggle = case.supportsTasks)
                    .toThreadComposerSurface(channelId = "channel-1")
            val surface = base.withTaskV2()
            assertEquals(case.shown to case.sends, surface.showTaskButton to surface.sendsAsTask, case.toString())
            // The draft is the same draft either way: task v2 never moves it to another key.
            assertEquals(base.threadComposerDraftKey(), surface.threadComposerDraftKey(), case.toString())
            if (case.kind != RaftThreadSurfaceKind.ReplyThread) assertEquals(base, surface, "only a reply thread changes: $case")
        }
    }
}

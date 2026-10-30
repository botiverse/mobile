package build.raft.app

import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelScope
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftServerNotificationLevels
import build.raft.app.resources.Res
import build.raft.app.resources.notification_level_dm_note
import build.raft.app.resources.notification_level_muted_dm_note
import build.raft.app.resources.notification_level_muted_note
import build.raft.app.resources.notification_level_thread_note
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the channel / thread level picker shows (slock docs/contracts/notifications.md §1.1). */
class RaftNotificationLevelPickerTest {
    @Test
    fun aThreadInheritsItsChannelsExplicitLevelThenTheServers() {
        val thread = RaftNotificationLevelTarget("s1", RaftNotificationLevelScope.Thread, id = "t1", parentChannelId = "c1")
        val levels =
            RaftServerNotificationLevels(
                serverId = "s1",
                server = RaftNotificationLevel.All,
                channels = mapOf("c1" to RaftNotificationLevelSetting.Mentions)
            )
        assertEquals(RaftNotificationLevelSetting.Default, thread.setting(levels))
        assertEquals(RaftNotificationLevel.Mentions, thread.inherited(levels))
        assertEquals(RaftNotificationLevel.Mentions, thread.effective(levels))
        val explicitThread = levels.copy(threads = mapOf("t1" to RaftNotificationLevelSetting.All))
        assertEquals(RaftNotificationLevel.All, thread.effective(explicitThread), "the most specific explicit level wins")
        assertEquals(
            RaftNotificationLevel.Mentions,
            thread.copy(muted = true).effective(explicitThread),
            "a muted channel caps it at mentions"
        )
    }

    @Test
    fun theNotesFollowMuteDmAndThread() {
        val channel = RaftNotificationLevelTarget("s1", RaftNotificationLevelScope.Channel, id = "c1")
        assertEquals(emptyList(), raftNotificationLevelNotes(channel))
        assertEquals(listOf(Res.string.notification_level_muted_note), raftNotificationLevelNotes(channel.copy(muted = true)))
        assertEquals(listOf(Res.string.notification_level_dm_note), raftNotificationLevelNotes(channel.copy(isDirectMessage = true)))
        // Inside a muted DM only an actual @mention notifies.
        assertEquals(
            listOf(Res.string.notification_level_muted_dm_note),
            raftNotificationLevelNotes(channel.copy(isDirectMessage = true, muted = true))
        )
        assertEquals(
            listOf(Res.string.notification_level_thread_note),
            raftNotificationLevelNotes(channel.copy(scope = RaftNotificationLevelScope.Thread))
        )
    }
}

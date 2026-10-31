package build.raft.app

import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelScope
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftServerNotificationLevels
import build.raft.app.resources.Res
import build.raft.app.resources.notification_level_dm_note
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
        // slock #12231: a mute silences the conversation entirely.
        assertEquals(RaftNotificationLevel.None, thread.copy(muted = true).effective(explicitThread))
        val channel = RaftNotificationLevelTarget("s1", RaftNotificationLevelScope.Channel, id = "c1", muted = true)
        assertEquals(RaftNotificationLevel.None, channel.effective(levels))
    }

    @Test
    fun theNotesFollowMuteDmAndThread() {
        val channel = RaftNotificationLevelTarget("s1", RaftNotificationLevelScope.Channel, id = "c1")
        assertEquals(emptyList(), raftNotificationLevelNotes(channel))
        assertEquals(listOf(Res.string.notification_level_muted_note), raftNotificationLevelNotes(channel.copy(muted = true)))
        assertEquals(listOf(Res.string.notification_level_dm_note), raftNotificationLevelNotes(channel.copy(isDirectMessage = true)))
        // A muted DM notifies nothing either, the same note as a channel (slock #12231).
        assertEquals(
            listOf(Res.string.notification_level_muted_note),
            raftNotificationLevelNotes(channel.copy(isDirectMessage = true, muted = true))
        )
        assertEquals(
            listOf(Res.string.notification_level_thread_note),
            raftNotificationLevelNotes(channel.copy(scope = RaftNotificationLevelScope.Thread))
        )
    }

    @Test
    fun threadsHaveNoLevelAndChannelsOfferDefaultAllMentions() {
        // stdrc + Wug (#proj-raft-ui:f603e418): threads only follow / unfollow; channels and DMs
        // keep Default / All / Mentions, as Slack.
        assertEquals(false, raftNotificationLevelEntryShown(RaftNotificationLevelScope.Thread))
        assertEquals(true, raftNotificationLevelEntryShown(RaftNotificationLevelScope.Channel))
        assertEquals(true, raftNotificationLevelEntryShown(RaftNotificationLevelScope.Server))
        // A DM is only muted or not (web slock #12214).
        assertEquals(false, raftNotificationLevelEntryShown(RaftNotificationLevelScope.Channel, isDirectMessage = true))
        // A channel lists concrete levels only; no "Default" (stdrc, #proj-raft-ui:f603e418).
        assertEquals(
            listOf(RaftNotificationLevelSetting.All, RaftNotificationLevelSetting.Mentions, RaftNotificationLevelSetting.None),
            raftNotificationLevelChoices(RaftNotificationLevelScope.Channel),
        )
    }

    @Test
    fun aChannelShowsItsEffectiveLevelAndPickingTheServersLevelFollowsTheServerAgain() {
        // Nothing stored: the inherited level shows as selected.
        assertEquals(
            RaftNotificationLevelSetting.Mentions,
            raftChannelLevelShown(RaftNotificationLevelSetting.Default, RaftNotificationLevel.Mentions),
        )
        assertEquals(
            RaftNotificationLevelSetting.None,
            raftChannelLevelShown(RaftNotificationLevelSetting.None, RaftNotificationLevel.All),
        )
        // Picking the server's level writes default (follow the server); another level is stored.
        assertEquals(
            RaftNotificationLevelSetting.Default,
            raftChannelLevelWrite(RaftNotificationLevelSetting.All, RaftNotificationLevel.All),
        )
        assertEquals(
            RaftNotificationLevelSetting.Mentions,
            raftChannelLevelWrite(RaftNotificationLevelSetting.Mentions, RaftNotificationLevel.All),
        )
    }

    private val serverAll = RaftServerNotificationLevels(serverId = "s1", server = RaftNotificationLevel.All)
    private val channel = RaftNotificationLevelTarget("s1", RaftNotificationLevelScope.Channel, id = "c1", muteSupported = true)
    private val all = RaftNotificationChoice.Level(RaftNotificationLevelSetting.All)
    private val mentions = RaftNotificationChoice.Level(RaftNotificationLevelSetting.Mentions)
    private val nothing = RaftNotificationChoice.Level(RaftNotificationLevelSetting.None)

    @Test
    fun aChannelOffersAllMentionsAndMuteLoudToQuiet() {
        // slock #12231 NotificationLevelMenu: All / Only what involves me / Mute.
        assertEquals(
            listOf(all, mentions, RaftNotificationChoice.Mute),
            raftNotificationChoices(RaftNotificationLevelScope.Channel, true, RaftNotificationLevelSetting.All, muted = false),
        )
        // A channel set to Nothing before Mute took its place keeps showing it, before Mute.
        assertEquals(
            listOf(all, mentions, nothing, RaftNotificationChoice.Mute),
            raftNotificationChoices(RaftNotificationLevelScope.Channel, true, RaftNotificationLevelSetting.None, muted = false),
        )
        assertEquals(
            listOf(all, mentions, RaftNotificationChoice.Mute),
            raftNotificationChoices(RaftNotificationLevelScope.Channel, true, RaftNotificationLevelSetting.None, muted = true),
        )
        // No mute control: the plain levels.
        assertEquals(
            listOf(all, mentions, nothing),
            raftNotificationChoices(RaftNotificationLevelScope.Channel, false, RaftNotificationLevelSetting.All, muted = false),
        )
        assertEquals(false, channel.copy(scope = RaftNotificationLevelScope.Server).hasMuteChoice)
    }

    @Test
    fun aMutedChannelShowsMuteElseItsLevel() {
        assertEquals(all, raftNotificationChoiceShown(channel, serverAll))
        assertEquals(RaftNotificationChoice.Mute, raftNotificationChoiceShown(channel.copy(muted = true), serverAll))
        val mentionsStored = serverAll.copy(channels = mapOf("c1" to RaftNotificationLevelSetting.Mentions))
        assertEquals(mentions, raftNotificationChoiceShown(channel, mentionsStored))
        assertEquals(RaftNotificationChoice.Mute, raftNotificationChoiceShown(channel.copy(muted = true), mentionsStored))
        // A muted channel whose mute cannot be changed here still shows Mute (Unmute stays offered).
        assertEquals(RaftNotificationChoice.Mute, raftNotificationChoiceShown(channel.copy(muted = true, muteSupported = false), serverAll))
    }

    @Test
    fun muteOnlyMutesAndALevelOnAMutedChannelUnmutesFirst() {
        // Mute sets the mute; the level stays as it was.
        assertEquals(RaftNotificationChoicePlan(setMuted = true), raftNotificationChoicePlan(RaftNotificationChoice.Mute, channel, serverAll))
        // Unmuted: a level just writes (the server's own level follows the server again).
        assertEquals(
            RaftNotificationChoicePlan(writeLevel = RaftNotificationLevelSetting.Mentions),
            raftNotificationChoicePlan(mentions, channel, serverAll),
        )
        val mentionsStored = serverAll.copy(channels = mapOf("c1" to RaftNotificationLevelSetting.Mentions))
        assertEquals(
            RaftNotificationChoicePlan(writeLevel = RaftNotificationLevelSetting.Default),
            raftNotificationChoicePlan(all, channel, mentionsStored),
        )
        // Muted: picking the level it had only unmutes; another level unmutes and writes.
        val muted = channel.copy(muted = true)
        assertEquals(RaftNotificationChoicePlan(setMuted = false), raftNotificationChoicePlan(all, muted, serverAll))
        assertEquals(
            RaftNotificationChoicePlan(setMuted = false, writeLevel = RaftNotificationLevelSetting.Mentions),
            raftNotificationChoicePlan(mentions, muted, serverAll),
        )
    }
}

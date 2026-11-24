package build.raft.app.data

import build.raft.app.homeSidebarAgentLoungeItems
import build.raft.app.homeSidebarVisibleAgentLounges
import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web parity tests for `#wg-mobile-sync:#276` (`botiverse/slock` `#16474`, `#16479`, `#16493`, `#16497`):
 * - Closed/open Agent Lounge live unread and `@` updates from `message:new` without double-counting
 *   when `thread:updated` also arrives.
 * - Coalesced summary + DM list reloads for unplaced/followed Lounge threads.
 * - Realtime follow/unfollow (`NotificationLevelChanged` scope `thread`) and local follow/unfollow/own-send
 *   listing sync via coalesced `GET /channels/dm?include=lounges` reads only where listing could change.
 */
class SharedHomeStoreAgentLoungeLiveUnreadAndListingTest {
    private val serverId = ServerId("server-1")
    private val otherServerId = ServerId("server-2")
    private val me =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active,
        )
    private val otherUser =
        MemberProfile(
            id = MemberId("user-2"),
            displayName = "Cindy",
            handle = "cindy@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active,
        )
    private val agentBugen =
        MemberProfile(
            id = MemberId("agent-bugen"),
            displayName = "Bugen",
            handle = "bugen",
            kind = MemberKind.Agent,
            presence = Presence.Active,
        )
    private val generalId = ChannelId("channel-general")
    private val unjoinedChannelId = ChannelId("channel-unjoined")
    private val loungeDmId = ChannelId("dm-bugen")
    private val loungeChannelId = ChannelId("lounge-bugen")
    private val standaloneLoungeId = ChannelId("lounge-nova")
    private val unplacedLoungeId = ChannelId("lounge-unplaced")
    private val loungeThreadId = ChannelId("thread-lounge-1")
    private val standaloneThreadId = ChannelId("thread-standalone-1")

    @AfterTest
    fun tearDown() {
        RaftAgentLounges.clear()
    }

    @Test
    fun closedNonMemberLoungeLiveUnreadIncrementsAndResurfacesFromMessageNewWithoutThreadUpdated() = runTest {
        val api = StubHomeApi()
        val store =
            createStore(
                api = api,
                initialHome =
                    baseHomeSnapshot(
                        sidebarOrder = SettingsServerSidebarOrderSnapshot(hiddenDmIds = listOf(loungeChannelId.value)),
                        followedThreadUnread =
                            mapOf(
                                loungeThreadId.value to HomeFollowedThreadUnread(
                                    parentChannelId = loungeChannelId,
                                    unreadCount = 1,
                                    hasMention = false,
                                )
                            ),
                        unreadLatestActivitySeqs = mapOf(loungeThreadId.value to 10L),
                    ),
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // Closed Lounge gets a thread reply via message:new mentioning the viewer (no thread:updated).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-11",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 11L,
                                author = otherUser,
                                mentionUserId = me.id.value,
                            )
                    ),
            )
        )

        val snap = store.snapshot()
        assertEquals(
            HomeFollowedThreadUnread(parentChannelId = loungeChannelId, unreadCount = 2, hasMention = true),
            snap.followedThreadUnread[loungeThreadId.value],
        )
        assertEquals(11L, snap.unreadLatestActivitySeqs[loungeThreadId.value])
        assertEquals(0, api.summaryCalls)

        // Sidebar projects live followed-thread unread onto the non-member Lounge and resurfaces it from hiddenDmIds.
        val lounges =
            homeSidebarAgentLoungeItems(
                enabled = true,
                directMessages = snap.directMessages,
                known = RaftAgentLounges.lounges.value[serverId.value].orEmpty(),
                markedUnreadIds = snap.markedUnreadIds,
                title = { name -> "${name.orEmpty()}'s Lounge" },
                standaloneLounges = snap.standaloneLounges,
                followedThreadUnread = snap.followedThreadUnread,
                useLiveFollowedThreadUnread = true,
            )
        val visible =
            homeSidebarVisibleAgentLounges(
                lounges = lounges,
                shownUnderPinned = emptySet(),
                hiddenDmIds = snap.sidebarOrder?.hiddenDmIds.orEmpty().toSet(),
            )
        val row = visible.single { it.id == loungeChannelId.value }
        assertEquals(2, row.unreadCount)
        assertTrue(row.mentioned)
    }

    @Test
    fun ownReplyAndOpenOnSightReplyDoNotCountOrReloadButHiddenWindowCounts() = runTest {
        var hostSeen = true
        var sideThread: AdaptiveOpenSideThread? = null
        val api = StubHomeApi()
        val store =
            createStore(
                api = api,
                selectedConversationSeen = { hostSeen },
                openSideThread = { sideThread },
                initialHome =
                    baseHomeSnapshot(
                        followedThreadUnread =
                            mapOf(
                                loungeThreadId.value to HomeFollowedThreadUnread(
                                    parentChannelId = loungeChannelId,
                                    unreadCount = 1,
                                    hasMention = false,
                                )
                            ),
                        unreadLatestActivitySeqs = mapOf(loungeThreadId.value to 10L),
                    ),
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // 1. Own reply in Lounge thread never increments unread or triggers summary reload.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-own",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 11L,
                                author = me,
                            )
                    ),
            )
        )
        assertEquals(1, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)

        // 2. Reply while thread is open as main selected conversation and seen -> ignored.
        store.accept(SharedHomeIntent.SelectConversation(loungeThreadId.value))
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-open-main",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 12L,
                                author = otherUser,
                            )
                    ),
            )
        )
        assertEquals(1, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)

        // 3. Reply while thread is open in side pane by parentMessageId and seen -> ignored (even if absent).
        store.accept(SharedHomeIntent.SelectConversation(loungeChannelId.value))
        sideThread = AdaptiveOpenSideThread(conversationIds = emptySet(), parentMessageId = "parent-msg-new")
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-open-side",
                                threadChannelId = ChannelId("thread-lounge-open-side"),
                                parentChannelId = loungeChannelId,
                                parentMessageId = "parent-msg-new",
                                seq = 13L,
                                author = otherUser,
                            )
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(0, api.summaryCalls)

        // 4. When host window is hidden/unseen, a reply into the open Lounge thread DOES count.
        sideThread = AdaptiveOpenSideThread(conversationIds = setOf(loungeThreadId.value), parentMessageId = "parent-1")
        hostSeen = false
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-hidden-win",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 14L,
                                author = otherUser,
                            )
                    ),
            )
        )
        assertEquals(2, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)
    }

    @Test
    fun openLoungeReceivingBothMessageNewAndThreadUpdatedCountsOnceInEitherOrderAndRespectsReadCheckpoints() = runTest {
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            serverId = serverId,
            memberId = me.id,
            scopeId = loungeThreadId,
            checkpoint = PerReceiverReadStateCheckpoint(version = 3L, maxReadSeq = 20L),
        )
        val api = StubHomeApi()
        val store =
            createStore(
                api = api,
                versionStore = versionStore,
                initialHome =
                    baseHomeSnapshot(
                        followedThreadUnread =
                            mapOf(
                                loungeThreadId.value to HomeFollowedThreadUnread(
                                    parentChannelId = loungeChannelId,
                                    unreadCount = 1,
                                    hasMention = false,
                                )
                            ),
                        unreadLatestActivitySeqs = mapOf(loungeThreadId.value to 20L),
                    ),
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // Already-read seq (<= accepted maxReadSeq 20) is ignored (#16497).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-old",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 20L,
                                author = otherUser,
                            )
                    ),
            )
        )
        assertEquals(1, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)

        // Order A: message:new then thread:updated -> counts ONCE (+1).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-21",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 21L,
                                author = otherUser,
                            )
                    ),
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event = threadUpdatedEvent(loungeThreadId, loungeChannelId, seq = 21L, mentionUserId = null),
            )
        )
        assertEquals(2, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)
        assertFalse(store.snapshot().followedThreadUnread[loungeThreadId.value]?.hasMention == true)

        // Order B: thread:updated (with mention) arrives FIRST, then message:new -> counts ONCE (+1) and lights @.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event = threadUpdatedEvent(loungeThreadId, loungeChannelId, seq = 22L, mentionUserId = me.id.value),
            )
        )
        assertEquals(2, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)
        assertTrue(store.snapshot().followedThreadUnread[loungeThreadId.value]?.hasMention == true)
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-22",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 22L,
                                author = otherUser,
                                mentionUserId = me.id.value,
                            )
                    ),
            )
        )
        assertEquals(3, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)
        assertTrue(store.snapshot().followedThreadUnread[loungeThreadId.value]?.hasMention == true)

        // Duplicate delivery of seq 22 does not count again.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-22",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 22L,
                                author = otherUser,
                            )
                    ),
            )
        )
        assertEquals(3, store.snapshot().followedThreadUnread[loungeThreadId.value]?.unreadCount)
        assertEquals(0, api.summaryCalls)
    }

    @Test
    fun unplacedLoungeReplyReloadsSummaryThenDmListAndPopulatesStandaloneLounge() = runTest {
        val unplacedThreadId = ChannelId("thread-unplaced-1")
        val api =
            StubHomeApi(
                unreadSummaryBody = {
                    """
                    {
                      "channels": {
                        "${unplacedThreadId.value}": {
                          "unreadCount": 2,
                          "hasMention": true,
                          "hasAnyMention": true,
                          "parentChannelId": "${unplacedLoungeId.value}",
                          "readState": {
                            "kind": "present",
                            "maxReadSeq": "0",
                            "readStateVersion": 1,
                            "markedUnread": false,
                            "latestActivity": { "messageId": "m-2", "seq": "2" }
                          }
                        }
                      },
                      "threadUnread": {
                        "${unplacedLoungeId.value}": { "count": 2, "hasMention": true }
                      }
                    }
                    """.trimIndent()
                },
                dmListBody = {
                    """
                    {
                      "dms": [
                        {
                          "id": "${loungeDmId.value}",
                          "name": "bugen",
                          "type": "dm",
                          "peerType": "agent",
                          "peerId": "${agentBugen.id.value}",
                          "peerName": "bugen",
                          "peerDisplayName": "Bugen",
                          "lounge": { "channelId": "${loungeChannelId.value}", "joined": false, "listed": true }
                        }
                      ],
                      "lounges": [
                        {
                          "agentId": "agent-unplaced",
                          "peerType": "agent",
                          "peerName": "atlas",
                          "peerDisplayName": "Atlas",
                          "lounge": { "channelId": "${unplacedLoungeId.value}", "joined": false, "listed": true, "unreadCount": 2, "mentionCount": 1 }
                        }
                      ]
                    }
                    """.trimIndent()
                },
            )
        val store = createStore(api = api, initialHome = baseHomeSnapshot(dmListNamesLounges = true))
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // First reply in an unplaced public channel (a Lounge we hold nothing of yet) requests summary reload.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-1",
                                threadChannelId = unplacedThreadId,
                                parentChannelId = unplacedLoungeId,
                                seq = 1L,
                                author = otherUser,
                                mentionUserId = me.id.value,
                            )
                    ),
            )
        )
        // Second reply arriving in the same coalesce window joins it (not dropped).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-2",
                                threadChannelId = unplacedThreadId,
                                parentChannelId = unplacedLoungeId,
                                seq = 2L,
                                author = otherUser,
                            )
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.summaryCalls)
        assertEquals(
            HomeFollowedThreadUnread(parentChannelId = unplacedLoungeId, unreadCount = 2, hasMention = true),
            store.snapshot().followedThreadUnread[unplacedThreadId.value],
        )

        // Summary returned unplacedLoungeId -> coalesced DM list read fires to list the standalone Lounge.
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.dmListCalls)
        assertTrue(store.snapshot().standaloneLounges.any { it.lounge.channelId == unplacedLoungeId && it.lounge.listed == true })
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, unplacedLoungeId.value)?.listed)
    }

    @Test
    fun ordinaryChannelAndGuestOrFlagOffTriggerZeroReloads() = runTest {
        val api = StubHomeApi()
        val store = createStore(api = api, initialHome = baseHomeSnapshot(dmListNamesLounges = true))
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // Joined and unjoined ordinary channels in snapshot.channels never trigger summary or DM list reads from message:new.
        for (channelId in listOf(generalId, unjoinedChannelId)) {
            store.accept(
                SharedHomeIntent.ApplyRealtimeEvent(
                    serverId = serverId.value,
                    event =
                        MessageNew(
                            message =
                                threadReplyMessage(
                                    messageId = "m-${channelId.value}",
                                    threadChannelId = ChannelId("thread-${channelId.value}"),
                                    parentChannelId = channelId,
                                    seq = 5L,
                                    author = otherUser,
                                )
                        ),
                )
            )
        }
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, api.summaryCalls)
        assertEquals(0, api.dmListCalls)

        // Guest member or Lounges flag OFF: unplaced public channel thread triggers 0 summary or DM list reloads.
        for ((role, flagOn) in listOf("guest" to true, "member" to false)) {
            val gatedApi = StubHomeApi()
            val gatedStore =
                createStore(
                    api = gatedApi,
                    loungeFeatureEnabled = flagOn,
                    initialHome = baseHomeSnapshot(serverRole = role, dmListNamesLounges = true),
                )
            gatedStore.accept(SharedHomeIntent.Start)
            gatedApi.resetCounts()

            gatedStore.accept(
                SharedHomeIntent.ApplyRealtimeEvent(
                    serverId = serverId.value,
                    event =
                        MessageNew(
                            message =
                                threadReplyMessage(
                                    messageId = "m-gated-$role-$flagOn",
                                    threadChannelId = ChannelId("thread-unplaced"),
                                    parentChannelId = unplacedLoungeId,
                                    seq = 5L,
                                    author = otherUser,
                                )
                        ),
                )
            )
            gatedStore.accept(
                SharedHomeIntent.ApplyRealtimeEvent(
                    serverId = serverId.value,
                    event =
                        NotificationLevelChanged(
                            serverId = serverId,
                            scope = "thread",
                            scopeId = "thread-unplaced",
                            level = "all",
                            prefsVersion = 1L,
                        ),
                )
            )
            advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS * 2)
            runCurrent()
            assertEquals(0, gatedApi.summaryCalls, "role=$role flagOn=$flagOn")
            assertEquals(0, gatedApi.dmListCalls, "role=$role flagOn=$flagOn")
        }
    }

    @Test
    fun followAndUnfollowFramesSyncLoungeListingUnlistStandaloneAndIgnoreOrdinaryOrOtherServerOrStaleFrames() = runTest {
        var dmListBody =
            """
            {
              "dms": [
                {
                  "id": "${loungeDmId.value}",
                  "name": "bugen",
                  "type": "dm",
                  "peerType": "agent",
                  "peerId": "${agentBugen.id.value}",
                  "peerName": "bugen",
                  "peerDisplayName": "Bugen",
                  "lounge": { "channelId": "${loungeChannelId.value}", "joined": false, "listed": false }
                }
              ],
              "lounges": []
            }
            """.trimIndent()
        val api = StubHomeApi(dmListBody = { dmListBody })
        val ordinaryThreadId = ChannelId("thread-ordinary-1")
        val store =
            createStore(
                api = api,
                initialHome =
                    baseHomeSnapshot(
                        dmListNamesLounges = true,
                        standaloneLounges =
                            listOf(
                                HomeStandaloneLoungeRow(
                                    agentId = "agent-nova",
                                    agentName = "Nova",
                                    lounge = AgentLoungeSummary(standaloneLoungeId, joined = false, listed = true),
                                )
                            ),
                        followedThreadUnread =
                            mapOf(
                                standaloneThreadId.value to HomeFollowedThreadUnread(standaloneLoungeId, unreadCount = 2, hasMention = true),
                                ordinaryThreadId.value to HomeFollowedThreadUnread(generalId, unreadCount = 1, hasMention = false),
                            ),
                    ),
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, standaloneLoungeId.value)?.listed)
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)

        // 1. Frame for another server: ignored (0 reads, unread untouched).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = otherServerId,
                        scope = "thread",
                        scopeId = standaloneThreadId.value,
                        level = "muted",
                        prefsVersion = 1L,
                    ),
            )
        )
        // 2. Frame for an ordinary channel thread: clears unread locally, costs 0 DM list reads.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = ordinaryThreadId.value,
                        level = "muted",
                        prefsVersion = 1L,
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(0, api.dmListCalls)
        assertNull(store.snapshot().followedThreadUnread[ordinaryThreadId.value])

        // 3. Burst of follow/unfollow frames on Lounge threads coalesces into ONE DM list read.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = standaloneThreadId.value,
                        level = "muted",
                        prefsVersion = 2L,
                    ),
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = loungeThreadId.value,
                        level = "muted",
                        prefsVersion = 2L,
                    ),
            )
        )
        // Stale prefsVersion (<= 2) ignored.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = standaloneThreadId.value,
                        level = "all",
                        prefsVersion = 2L,
                    ),
            )
        )
        // Unread cleared immediately on unfollow frame.
        assertNull(store.snapshot().followedThreadUnread[standaloneThreadId.value])

        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.dmListCalls)
        // Both standalone Lounge (absent from lounges: []) and DM-backed Lounge (listed: false) are unlisted!
        assertTrue(store.snapshot().standaloneLounges.isEmpty())
        assertEquals(false, RaftAgentLounges.lounge(serverId.value, standaloneLoungeId.value)?.listed)
        assertEquals(false, store.snapshot().directMessages.single().lounge?.listed)
        assertEquals(false, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)

        // 4. Hidden Home marks dirty on same-server thread follow frame, NOT on other-server frame.
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = otherServerId,
                        scope = "thread",
                        scopeId = standaloneThreadId.value,
                        level = "all",
                        prefsVersion = 3L,
                    ),
            )
        )
        assertFalse(store.state.value.homeRealtimeDirty)

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = standaloneThreadId.value,
                        level = "all",
                        prefsVersion = 3L,
                    ),
            )
        )
        assertTrue(store.state.value.homeRealtimeDirty)
    }

    @Test
    fun localFollowStampsListedImmediatelyProtectsAgainstOlderInFlightDmListAndLocalUnfollowRetriesOnce() = runTest {
        val dmGate = CompletableDeferred<HomeNetworkResponse>()
        var dmCallIndex = 0
        val api =
            StubHomeApi(
                dmListResponse = {
                    dmCallIndex += 1
                    when (dmCallIndex) {
                        1 -> HomeNetworkResponse(code = 200, bodyText = """{"dms":[],"lounges":[]}""")
                        2 -> dmGate.await() // Triggered by unfollow frame, races with local follow below!
                        else -> HomeNetworkResponse(code = 500, bodyText = """{"error":"boom"}""")
                    }
                }
            )
        val store =
            createStore(
                api = api,
                initialHome =
                    baseHomeSnapshot(
                        dmListNamesLounges = true,
                        loungeListed = false,
                        followedThreadUnread = emptyMap(),
                    ),
                resolveExternalThreadParentChannelId = { _, threadId ->
                    if (threadId == loungeThreadId.value) loungeChannelId.value else null
                },
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()
        assertEquals(false, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)

        // Trigger an in-flight DM list read (via a thread frame), pause it at dmGate.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = loungeThreadId.value,
                        level = "all",
                        prefsVersion = 1L,
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.dmListCalls)

        // While that DM list read is still in flight, user follows a thread locally in loungeChannelId!
        store.accept(
            SharedHomeIntent.NoteLoungeThreadFollowed(
                serverId = serverId.value,
                parentChannelId = loungeChannelId.value,
                threadChannelId = loungeThreadId.value,
            )
        )
        assertEquals(true, store.snapshot().directMessages.single().lounge?.listed)
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)

        // The older DM list request returns listed=false -> followedSince race guard keeps listed=true!
        dmGate.complete(
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "dms": [
                        {
                          "id": "${loungeDmId.value}",
                          "name": "bugen",
                          "type": "dm",
                          "peerType": "agent",
                          "peerId": "${agentBugen.id.value}",
                          "peerName": "bugen",
                          "peerDisplayName": "Bugen",
                          "lounge": { "channelId": "${loungeChannelId.value}", "joined": false, "listed": false }
                        }
                      ],
                      "lounges": []
                    }
                    """.trimIndent(),
            )
        )
        runCurrent()
        assertEquals(true, store.snapshot().directMessages.single().lounge?.listed)
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)

        // Local unfollow in an ordinary channel costs 0 DM list reads.
        api.resetCounts()
        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = "thread-ordinary",
                phase = HomeThreadUnfollowPhase.Unfollowed,
                parentChannelId = generalId.value,
            )
        )
        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = "thread-ordinary",
                phase = HomeThreadUnfollowPhase.Confirmed,
                parentChannelId = generalId.value,
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS * 2)
        runCurrent()
        assertEquals(0, api.dmListCalls)

        // Local unfollow in a Lounge triggers a DM list read; when it fails (500), it retries ONCE and then stops.
        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = loungeThreadId.value,
                phase = HomeThreadUnfollowPhase.Unfollowed,
                parentChannelId = loungeChannelId.value,
            )
        )
        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = loungeThreadId.value,
                phase = HomeThreadUnfollowPhase.Confirmed,
                parentChannelId = loungeChannelId.value,
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.dmListCalls, "initial read after unfollow")
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(2, api.dmListCalls, "single retry after failure")
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS * 5)
        runCurrent()
        assertEquals(2, api.dmListCalls, "no retry loop after second failure")
    }

    @Test
    fun dmListAnswerWithoutLoungesArrayUnlistsNothing() = runTest {
        // slock #16479: an object answer omitting `lounges` (or a bare array) unlists nothing.
        val api =
            StubHomeApi(
                dmListBody = {
                    """
                    {
                      "dms": [
                        {
                          "id": "${loungeDmId.value}",
                          "name": "bugen",
                          "type": "dm",
                          "peerType": "agent",
                          "peerId": "${agentBugen.id.value}",
                          "peerName": "bugen",
                          "peerDisplayName": "Bugen",
                          "lounge": { "channelId": "${loungeChannelId.value}", "joined": false, "listed": true }
                        }
                      ]
                    }
                    """.trimIndent()
                }
            )
        val store =
            createStore(
                api = api,
                initialHome =
                    baseHomeSnapshot(
                        dmListNamesLounges = true,
                        standaloneLounges =
                            listOf(
                                HomeStandaloneLoungeRow(
                                    agentId = "agent-nova",
                                    agentName = "Nova",
                                    lounge = AgentLoungeSummary(standaloneLoungeId, joined = false, listed = true),
                                )
                            ),
                    ),
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = standaloneThreadId.value,
                phase = HomeThreadUnfollowPhase.Unfollowed,
                parentChannelId = standaloneLoungeId.value,
            )
        )
        store.accept(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadChannelId = standaloneThreadId.value,
                phase = HomeThreadUnfollowPhase.Confirmed,
                parentChannelId = standaloneLoungeId.value,
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()

        assertEquals(1, api.dmListCalls)
        // Standalone Lounge stays listed because the DM list response had no `lounges` array.
        assertEquals(1, store.snapshot().standaloneLounges.size)
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, standaloneLoungeId.value)?.listed)
    }

    @Test
    fun ownMessageAndTaskConversationIndirectionKeepListingAccurateWithZeroSpuriousReads() = runTest {
        val taskConvInLounge = "task-conv-in-lounge"
        val taskConvInGeneral = "task-conv-in-general"
        val api =
            StubHomeApi(
                dmListBody = { """{"dms":[],"lounges":[]}""" }
            )
        val store =
            createStore(
                api = api,
                initialHome = baseHomeSnapshot(dmListNamesLounges = true, loungeListed = false),
                resolveExternalThreadParentChannelId = { _, threadId ->
                    when (threadId) {
                        taskConvInLounge -> loungeChannelId.value
                        taskConvInGeneral -> generalId.value
                        else -> null
                    }
                },
            )
        store.accept(SharedHomeIntent.Start)
        api.resetCounts()

        // 1. Own reply in an ordinary channel thread costs 0 DM list reads (thread id is NOT treated as unplaced Lounge).
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-own-ord",
                                threadChannelId = ChannelId("thread-ordinary-own"),
                                parentChannelId = generalId,
                                seq = 50L,
                                author = me,
                            )
                    ),
            )
        )
        // 2. Follow frame on a task conversation belonging to an ordinary channel -> 0 DM list reads.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = taskConvInGeneral,
                        level = "muted",
                        prefsVersion = 1L,
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(0, api.dmListCalls)

        // 3. Own message in a known Lounge marks it listed=true immediately with 0 DM list reads.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    MessageNew(
                        message =
                            threadReplyMessage(
                                messageId = "m-own-lounge",
                                threadChannelId = loungeThreadId,
                                parentChannelId = loungeChannelId,
                                seq = 51L,
                                author = me,
                            )
                    ),
            )
        )
        assertEquals(true, store.snapshot().directMessages.single().lounge?.listed)
        assertEquals(true, RaftAgentLounges.lounge(serverId.value, loungeChannelId.value)?.listed)
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(0, api.dmListCalls)

        // 4. Follow/unfollow frame on a task conversation in a Lounge (resolved externally) triggers 1 DM list read.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = serverId.value,
                event =
                    NotificationLevelChanged(
                        serverId = serverId,
                        scope = "thread",
                        scopeId = taskConvInLounge,
                        level = "muted",
                        prefsVersion = 1L,
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(1, api.dmListCalls)
    }

    private fun baseHomeSnapshot(
        serverRole: String = "member",
        loungeListed: Boolean = true,
        dmListNamesLounges: Boolean = true,
        sidebarOrder: SettingsServerSidebarOrderSnapshot? = null,
        standaloneLounges: List<HomeStandaloneLoungeRow> = emptyList(),
        followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
        unreadLatestActivitySeqs: Map<String, Long> = emptyMap(),
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock", unreadCount = 0, role = serverRole),
            currentMember = me,
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId(generalId.value),
                        channelId = generalId,
                        name = "general",
                        joined = true,
                        unreadCount = 0,
                        unreadCountKnown = true,
                    ),
                    HomeChannelRow(
                        threadId = ThreadId(unjoinedChannelId.value),
                        channelId = unjoinedChannelId,
                        name = "random",
                        joined = false,
                        unreadCount = 0,
                        unreadCountKnown = true,
                    ),
                ),
            directMessages =
                listOf(
                    HomeDirectMessageRow(
                        threadId = ThreadId(loungeDmId.value),
                        channelId = loungeDmId,
                        name = "Bugen",
                        directMessagePeerType = "agent",
                        presenceMemberId = agentBugen.id,
                        lounge = AgentLoungeSummary(channelId = loungeChannelId, joined = false, listed = loungeListed),
                    )
                ),
            standaloneLounges = standaloneLounges,
            unreadTotal = 0,
            followedThreadUnread = followedThreadUnread,
            unreadLatestActivitySeqs = unreadLatestActivitySeqs,
            sidebarOrder = sidebarOrder,
            dmListNamesLounges = dmListNamesLounges,
        )

    private fun TestScope.createStore(
        api: RaftHomeApi,
        initialHome: HomeSnapshot,
        loungeFeatureEnabled: Boolean = true,
        versionStore: PerReceiverStateVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore()),
        selectedConversationSeen: () -> Boolean = { true },
        openSideThread: () -> AdaptiveOpenSideThread? = { null },
        resolveExternalThreadParentChannelId: (ServerId, String) -> String? = { _, _ -> null },
    ): SharedHomeStore {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = me.id.value, email = "artin@slock.ai"),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = serverId.value,
                servers = listOf(StoredAuthServer(id = serverId.value, name = "Slock", slug = "slock", role = initialHome.server.role)),
            )
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = api,
                    parseHome = { payload, _ ->
                        DataResult.Content(initialHome.copy(server = initialHome.server.copy(id = payload.serverId)))
                    },
                    parseDispatcher = Dispatchers.Unconfined,
                ),
            scope = backgroundScope,
            perReceiverStateVersionStore = versionStore,
            selectedConversationSeen = selectedConversationSeen,
            openSideThread = openSideThread,
            isAgentLoungeEnabled = { loungeFeatureEnabled },
            resolveExternalThreadParentChannelId = resolveExternalThreadParentChannelId,
        )
    }

    private fun threadReplyMessage(
        messageId: String,
        threadChannelId: ChannelId,
        parentChannelId: ChannelId,
        seq: Long,
        author: MemberProfile,
        parentMessageId: String = "parent-1",
        mentionUserId: String? = null,
    ): Message =
        Message(
            id = MessageId(messageId),
            threadId = ThreadId(threadChannelId.value),
            channelId = threadChannelId,
            seq = seq,
            author = author,
            body = "reply $messageId",
            timestampLabel = "2026-11-24T06:00:00.000Z",
            mentions =
                listOfNotNull(
                    mentionUserId?.let { MessageMention(type = "user", id = it, name = "artin") }
                ),
            conversationContext =
                MessageConversationContext(
                    channelKind = ConversationKind.Thread,
                    parentChannelId = parentChannelId,
                    parentChannelKind = ConversationKind.Channel,
                    parentMessageId = MessageId(parentMessageId),
                ),
        )

    private fun threadUpdatedEvent(
        threadChannelId: ChannelId,
        parentChannelId: ChannelId,
        seq: Long,
        mentionUserId: String?,
    ): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId("parent-1"),
            threadChannelId = threadChannelId,
            parentChannelId = parentChannelId,
            replyCount = 4,
            lastReplyAt = "2026-11-24T06:00:00.000Z",
            participantIds = listOf(otherUser.id),
            latestReply =
                ReplyPreview(
                    messageId = "m-$seq",
                    seq = seq,
                    content = "reply",
                    senderId = otherUser.id.value,
                    senderType = "user",
                    senderName = "Cindy",
                    createdAt = "2026-11-24T06:00:00.000Z",
                ),
            latestReplyMentions =
                listOfNotNull(
                    mentionUserId?.let { MessageMention(type = "user", id = it, name = "artin") }
                ),
        )

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as SharedHomeContentState.Content).snapshot

    private class StubHomeApi(
        private val unreadSummaryBody: () -> String = { """{"channels":{},"threadUnread":{}}""" },
        private val dmListBody: () -> String = { """{"dms":[],"lounges":[]}""" },
        private val dmListResponse: (suspend () -> HomeNetworkResponse)? = null,
    ) : RaftHomeApi {
        var summaryCalls = 0
        var dmListCalls = 0

        fun resetCounts() {
            summaryCalls = 0
            dmListCalls = 0
        }

        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse {
            dmListCalls += 1
            return dmListResponse?.invoke() ?: HomeNetworkResponse(code = 200, bodyText = dmListBody())
        }

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse {
            summaryCalls += 1
            return HomeNetworkResponse(code = 200, bodyText = unreadSummaryBody())
        }

        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}

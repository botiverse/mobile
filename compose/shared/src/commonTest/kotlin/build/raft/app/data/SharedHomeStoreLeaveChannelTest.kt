package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home row menu "Leave Channel" (web botiverse/slock #11289 Sidebar -> channelStore.leaveChannel
 * -> `POST /channels/:id/leave`), dispatched after its confirmation.
 */
class SharedHomeStoreLeaveChannelTest {
    // A successful leave hands the channel to onChannelLeft, which the provider wires to
    // publishChannelAccessLost: the same Activity ChannelAccessLost hand-off as settings' Leave.
    @Test
    fun publicChannelStaysListedAsNotJoinedAndCallsLeaveOnce() = runTest {
        val response = CompletableDeferred<DataResult<ChannelLeaveResult>>()
        val calls = mutableListOf<ChannelId>()
        val left = mutableListOf<ChannelId>()
        val store =
            store(
                leaveChannel = { channelId ->
                    calls += channelId
                    response.await()
                },
                onChannelLeft = { left += it },
            )
        store.accept(SharedHomeIntent.Start)

        val action = launch { store.accept(SharedHomeIntent.LeaveChannel("channel-public")) }
        runCurrent()

        val row = store.snapshot().channels.single { it.channelId == ChannelId("channel-public") }
        assertFalse(row.joined)
        assertEquals(listOf(ChannelId("channel-public")), calls)
        // Activity hears of it only once the server has acknowledged the leave.
        assertTrue(left.isEmpty())

        response.complete(DataResult.Content(ChannelLeaveResult(ChannelId("channel-public"))))
        action.join()
        assertFalse(store.snapshot().channels.single { it.channelId == ChannelId("channel-public") }.joined)
        assertEquals(listOf(ChannelId("channel-public")), left)
    }

    @Test
    fun privateAndJointChannelsLeaveTheListAndTheSelection() = runTest {
        for (conversationId in listOf("channel-private", "channel-joint")) {
            val calls = mutableListOf<ChannelId>()
            val left = mutableListOf<ChannelId>()
            val store =
                store(
                    leaveChannel = { channelId ->
                        calls += channelId
                        DataResult.Content(ChannelLeaveResult(channelId))
                    },
                    onChannelLeft = { left += it },
                )
            store.accept(SharedHomeIntent.Start)
            store.accept(SharedHomeIntent.SelectConversation(conversationId))

            store.accept(SharedHomeIntent.LeaveChannel(conversationId))

            assertEquals(listOf(ChannelId(conversationId)), calls)
            assertEquals(listOf(ChannelId(conversationId)), left, conversationId)
            assertTrue(store.snapshot().channels.none { it.channelId == ChannelId(conversationId) }, conversationId)
            assertNull(store.state.value.selectedConversationId, conversationId)
        }
    }

    @Test
    fun failedLeaveRestoresTheRowAndReportsIt() = runTest {
        val failures = mutableListOf<String>()
        val left = mutableListOf<ChannelId>()
        val store =
            store(
                leaveChannel = { DataResult.Error(ScreenError(code = "http_403", retryable = false)) },
                onFailure = { failures += it },
                onChannelLeft = { left += it },
            )
        store.accept(SharedHomeIntent.Start)
        val before = store.snapshot()

        store.accept(SharedHomeIntent.LeaveChannel("channel-private"))

        assertEquals(before.channels, store.snapshot().channels)
        assertEquals(listOf("Unable to leave channel"), failures)
        assertTrue(left.isEmpty())
    }

    @Test
    fun unknownConversationSendsNoRequest() = runTest {
        val calls = mutableListOf<ChannelId>()
        val store =
            store(leaveChannel = { channelId ->
                calls += channelId
                DataResult.Content(ChannelLeaveResult(channelId))
            })
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.LeaveChannel("missing"))

        assertTrue(calls.isEmpty())
    }

    @Test
    fun aJoinPatchesTheRowWithoutSelectingIt() = runTest {
        val store = store(home = sampleHome(notJoined = "channel-public"))
        store.accept(SharedHomeIntent.Start)
        assertFalse(store.row("channel-public").joined)

        store.accept(SharedHomeIntent.ChannelJoined("server-1", ChannelId("channel-public")))

        assertTrue(store.row("channel-public").joined)
        assertNull(store.state.value.selectedConversationId)
    }

    @Test
    fun aJoinListsTheGivenRowWhenHomeHasNone() = runTest {
        val store = store()
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.ChannelJoined("server-1", ChannelId("channel-new")))
        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-new") })

        store.accept(
            SharedHomeIntent.ChannelJoined(
                "server-1",
                ChannelId("channel-new"),
                rowIfMissing = row("channel-new").copy(joined = false),
            ),
        )
        assertTrue(store.row("channel-new").joined)
    }

    @Test
    fun aJoinOnAnotherServerChangesNothing() = runTest {
        val store = store(home = sampleHome(notJoined = "channel-public"))
        store.accept(SharedHomeIntent.Start)
        val before = store.snapshot()

        store.accept(SharedHomeIntent.ChannelJoined("server-2", ChannelId("channel-public"), row("channel-other")))

        assertEquals(before.channels, store.snapshot().channels)
    }

    // The join's own channel:members-updated (and the Refresh after it) can read a replica that is behind.
    @Test
    fun aFetchThatStillSaysNotJoinedKeepsTheJoinedRow() = runTest {
        var fetches = 0
        val store = store(home = sampleHome(notJoined = "channel-public"), onFetch = { fetches += 1 })
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ChannelJoined("server-1", ChannelId("channel-public")))
        val fetchesBefore = fetches

        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()

        assertTrue(fetches > fetchesBefore)
        assertTrue(store.row("channel-public").joined)
    }

    @Test
    fun leavingAfterAJoinEndsTheGuardSoTheFetchIsBelieved() = runTest {
        val store = store(home = sampleHome(notJoined = "channel-public"))
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ChannelJoined("server-1", ChannelId("channel-public")))

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-public"), keepListed = true))
        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()

        assertFalse(store.row("channel-public").joined)
    }

    @Test
    fun membershipEndedKeepsAPublicRowAsNotJoinedWithoutARequest() = runTest {
        val calls = mutableListOf<ChannelId>()
        val store =
            store(leaveChannel = { channelId ->
                calls += channelId
                DataResult.Content(ChannelLeaveResult(channelId))
            })
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectConversation("channel-public"))

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-public"), keepListed = true))

        assertFalse(store.row("channel-public").joined)
        assertEquals("channel-public", store.state.value.selectedConversationId)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun membershipEndedRemovesTheRowAndItsSelection() = runTest {
        val store = store()
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectConversation("channel-private"))

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-private"), keepListed = false))

        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-private") })
        assertNull(store.state.value.selectedConversationId)
    }

    @Test
    fun membershipEndedLeavesAnotherSelectionAlone() = runTest {
        val store = store()
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectConversation("channel-public"))

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-private"), keepListed = false))

        assertEquals("channel-public", store.state.value.selectedConversationId)
    }

    // HomeCreatedChannelGuard re-lists a created channel a fetch doesn't have yet; once it is left or
    // deleted, that fetch is right and the row must stay gone rather than return for 60 s.
    @Test
    fun aJustCreatedChannelThatEndsStaysGoneAcrossAFetchThatDoesNotListIt() = runTest {
        var fetches = 0
        val store = store(onFetch = { fetches += 1 })
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ChannelCreated(row("channel-new", channelType = "private")))
        // The guard is live: a fetch without the channel keeps it.
        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()
        assertTrue(store.snapshot().channels.any { it.channelId == ChannelId("channel-new") })

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-new"), keepListed = false))
        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-new") })
        assertNull(store.state.value.selectedConversationId)
        val fetchesBefore = fetches

        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()

        assertTrue(fetches > fetchesBefore)
        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-new") })
    }

    @Test
    fun aJustCreatedChannelLeftFromTheRowMenuStaysGoneAcrossAFetch() = runTest {
        var fetches = 0
        val store = store(onFetch = { fetches += 1 })
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ChannelCreated(row("channel-new", channelType = "private")))

        store.accept(SharedHomeIntent.LeaveChannel("channel-new"))
        val fetchesBefore = fetches
        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()

        assertTrue(fetches > fetchesBefore)
        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-new") })
    }

    @Test
    fun endingOneJustCreatedChannelKeepsAnothersGuard() = runTest {
        val store = store()
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ChannelCreated(row("channel-new", channelType = "private")))
        store.accept(SharedHomeIntent.ChannelCreated(row("channel-other", channelType = "private")))

        store.accept(SharedHomeIntent.ChannelMembershipEnded(ChannelId("channel-new"), keepListed = false))
        store.accept(SharedHomeIntent.Refresh)
        store.awaitRealtimeSettled()

        assertTrue(store.snapshot().channels.none { it.channelId == ChannelId("channel-new") })
        assertTrue(store.snapshot().channels.any { it.channelId == ChannelId("channel-other") })
    }

    @Test
    fun aGuardAppliesToItsServerAndSettlesOnceAFetchAgrees() {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val guard = HomeJoinedChannelGuard(ServerId("server-1"), ChannelId("channel-public"), null, mark)
        val behind = sampleHome(notJoined = "channel-public")

        assertFalse(guard.settledBy(behind))
        assertTrue(behind.withJoinedChannelsFrom(listOf(guard)).channels.first { it.channelId == guard.channelId }.joined)
        assertTrue(guard.settledBy(sampleHome()))

        val otherServer = behind.copy(server = behind.server.copy(id = ServerId("server-2")))
        assertFalse(guard.settledBy(otherServer.copy(channels = sampleHome().channels)))
        assertEquals(otherServer, otherServer.withJoinedChannelsFrom(listOf(guard)))
    }

    private fun TestScope.store(
        leaveChannel: suspend (ChannelId) -> DataResult<ChannelLeaveResult> = { DataResult.Content(ChannelLeaveResult(it)) },
        onFailure: (String) -> Unit = {},
        onChannelLeft: (ChannelId) -> Unit = {},
        home: HomeSnapshot = sampleHome(),
        onFetch: () -> Unit = {},
    ): SharedHomeStore {
        val session = sampleSession()
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = EmptyHomeApi(),
                    parseHome = { payload, _ ->
                        onFetch()
                        DataResult.Content(home.copy(server = home.server.copy(id = payload.serverId)))
                    },
                ),
            scope = backgroundScope,
            leaveChannel = leaveChannel,
            pinUpdateFailureCallback = onFailure,
            onChannelLeft = onChannelLeft,
        )
    }

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as SharedHomeContentState.Content).snapshot

    private fun SharedHomeStore.row(id: String): HomeChannelRow =
        snapshot().channels.single { it.channelId == ChannelId(id) }

    private fun sampleSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
        )

    private fun row(id: String, channelType: String? = null, kind: ConversationKind = ConversationKind.Channel) =
        HomeChannelRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            channelType = channelType,
            kind = kind,
            name = id.removePrefix("channel-"),
            unreadCountKnown = true,
        )

    private fun sampleHome(notJoined: String? = null) =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human,
                    presence = Presence.Active,
                ),
            channels =
                listOf(
                    row("channel-public", channelType = "channel"),
                    row("channel-private", channelType = "private"),
                    row("channel-joint", kind = ConversationKind.Joint),
                ).map { if (it.channelId.value == notJoined) it.copy(joined = false) else it },
            directMessages = emptyList(),
            unreadTotal = 0,
        )

    private class EmptyHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)
        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}

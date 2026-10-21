package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hands feedback cda23a95 (Android 1.15.1): saving the Pinned sort sheet scrambled the order.
 * The sheet hands back row ids — a pinned agent/human's row is its DM conversation, so its id
 * is the DM channel id, while the typed pinned ref holds the member id. The store dropped
 * those rows and re-appended them in their old order. These cases pin the row-id -> ref
 * resolution against the web contract (`PATCH /servers/:id/sidebar-order` `pinned: [{kind, id}]`).
 */
class SharedHomeStorePinnedReorderTest {
    private val channelRef = SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-uiux")
    private val agentRef = SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-mahua")
    private val humanRef = SettingsServerSidebarPinnedItem(kind = "human", id = "user-artin")
    private val memberOnlyRef = SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-no-dm")

    @Test
    fun savingPinnedSheetOrderWithDirectMessageRowsSendsAndKeepsThatOrder() = runTest {
        val initial = SettingsServerSidebarOrderSnapshot(
            pinned = listOf(channelRef, agentRef, humanRef, memberOnlyRef),
            pinnedSortMode = "manual"
        )
        val harness = harness(initial)

        // Sheet order by row id: DM rows carry their DM channel id, the DM-less member its member id.
        harness.store.accept(
            SharedHomeIntent.ReorderSidebar(pinnedOrder = listOf("dm-artin", "agent-no-dm", "channel-uiux", "dm-mahua"))
        )

        val expected = listOf(humanRef, memberOnlyRef, channelRef, agentRef)
        assertEquals(1, harness.updateCalls.size, "one PATCH for one save")
        assertEquals(expected, harness.updateCalls.single().pinned)
        assertEquals(null, harness.updateCalls.single().pinnedOrder, "typed pinned must not be mixed with legacy fields")
        val after = harness.sidebarOrder()
        assertEquals(expected, after.pinned, "optimistic + saved local order follows the sheet")
        assertEquals(expected.map { it.id }, after.pinnedOrder)
        assertEquals("manual", after.pinnedSortMode)
        harness.scope.cancel()
    }

    @Test
    fun savingSheetInRecentModeFlipsToManualEvenWhenStoredOrderAlreadyMatches() = runTest {
        // The sheet opened on the Recent order; the user dragged it back into the stored
        // manual order. Saving must still switch the section to manual, or the sidebar keeps
        // showing the recency order and the save looks lost.
        val initial = SettingsServerSidebarOrderSnapshot(
            pinned = listOf(channelRef, agentRef),
            pinnedSortMode = "recent"
        )
        val harness = harness(initial)

        harness.store.accept(SharedHomeIntent.ReorderSidebar(pinnedOrder = listOf("channel-uiux", "dm-mahua")))

        assertEquals(1, harness.updateCalls.size)
        assertEquals("manual", harness.updateCalls.single().pinnedSortMode)
        assertEquals(listOf(channelRef, agentRef), harness.sidebarOrder().pinned)
        assertEquals("manual", harness.sidebarOrder().pinnedSortMode)
        harness.scope.cancel()
    }

    @Test
    fun legacyPinnedAgentResolvesFromItsDirectMessageRow() = runTest {
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-uiux"),
            pinnedAgentIds = listOf("agent-mahua"),
            pinnedOrder = listOf("channel-uiux", "agent-mahua"),
            pinnedSortMode = "manual"
        )
        val harness = harness(initial)

        harness.store.accept(SharedHomeIntent.ReorderSidebar(pinnedOrder = listOf("dm-mahua", "channel-uiux")))

        assertEquals(listOf(agentRef, channelRef), harness.updateCalls.single().pinned)
        assertEquals(listOf("agent-mahua", "channel-uiux"), harness.sidebarOrder().pinnedOrder)
        harness.scope.cancel()
    }

    private class Harness(
        val scope: CoroutineScope,
        val store: SharedHomeStore,
        val updateCalls: MutableList<SettingsServerSidebarOrderUpdateRequest>
    ) {
        fun sidebarOrder(): SettingsServerSidebarOrderSnapshot {
            val content = store.state.value.content as? SharedHomeContentState.Content
                ?: error("home not loaded, state=${store.state.value.content}")
            return content.snapshot.sidebarOrder ?: error("sidebar order missing after reorder")
        }
    }

    private suspend fun harness(initial: SettingsServerSidebarOrderSnapshot): Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = authSessionFixture()
        val home = home().copy(sidebarOrder = initial)
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource = SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                api = RootEmptyHomeApi,
                parseHome = { _, _ -> DataResult.Content(home) }
            ),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                // The server echoes the typed refs it stored.
                DataResult.Content(
                    initial.copy(
                        pinned = request.pinned ?: initial.pinned,
                        pinnedOrder = (request.pinned ?: initial.pinned).map { it.id },
                        pinnedSortMode = request.pinnedSortMode ?: initial.pinnedSortMode
                    )
                )
            }
        )
        store.accept(SharedHomeIntent.Start)
        return Harness(scope, store, updateCalls)
    }

    private fun home(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember = MemberProfile(
                id = MemberId("user-1"),
                displayName = "Me",
                handle = "user-1@example.com",
                kind = MemberKind.Human,
                presence = Presence.Active
            ),
            channels = listOf(
                HomeChannelRow(threadId = ThreadId("channel-uiux"), channelId = ChannelId("channel-uiux"), name = "proj-uiux")
            ),
            directMessages = listOf(
                dm(id = "dm-mahua", name = "Mahua", memberId = "agent-mahua", peerType = "agent"),
                dm(id = "dm-artin", name = "artin", memberId = "user-artin", peerType = "human")
            ),
            unreadTotal = 0
        )

    private fun dm(id: String, name: String, memberId: String, peerType: String): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = name,
            directMessagePeerType = peerType,
            presenceMemberId = MemberId(memberId)
        )
}

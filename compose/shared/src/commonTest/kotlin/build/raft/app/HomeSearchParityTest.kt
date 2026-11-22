package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Search parity with the web (botiverse/slock staging `packages/web/src/features/search`):
 * destinations up to 50 behind "Show all N", a person or agent opening the DM (else the profile),
 * the Free plan's history notice, where an incomplete search got to and a thread result's title.
 */
class HomeSearchParityTest {
    // --- 1. Destinations: up to 50, 5 shown, then Show all N (searchConstants.ts) ---

    @Test
    fun destinationsReachFiftyAndTheListShowsFiveUntilExpanded() {
        val results =
            homeSearchEntityResults(
                query = "eng",
                homeState = homeWithChannels(60),
                senderFilters = emptyList(),
                channelLabel = "Channel",
                dmLabel = "DM",
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members",
            )

        assertEquals(HomeSearchEntityResultLimit, results.size, "web MAX_ENTITY_RESULTS")
        assertEquals(results.take(5), results.homeSearchShownEntities(expanded = false))
        assertEquals(results, results.homeSearchShownEntities(expanded = true))
        assertTrue(homeSearchEntitiesCollapsible(results.size))
    }

    // --- 3./4. Incomplete search and the history notice in the wide panel ---

    @Test
    fun theWidePanelSaysNoMatchesYetForAnIncompleteSearchAndShowsTheHistoryNotice() {
        fun content(note: String?, days: Int?) =
            HomeSearchOverlayContent(
                typed = true,
                recent = emptyList(),
                exact = null,
                entityGroups = emptyList(),
                messages = emptyList(),
                messageCount = null,
                searched = true,
                loading = false,
                historyWindowDays = days,
                incompleteNote = note,
            )

        val incomplete = content(note = "Searched messages back to 09-30 08:00.", days = 30).entries()
        assertEquals(30, assertIs<HomeSearchOverlayEntry.HistoryNotice>(incomplete.first()).days)
        assertEquals(
            "Searched messages back to 09-30 08:00.",
            incomplete.filterIsInstance<HomeSearchOverlayEntry.NoMatchesYet>().single().note,
        )
        assertTrue(incomplete.none { it == HomeSearchOverlayEntry.NoResults })

        val complete = content(note = null, days = null).entries()
        assertTrue(complete.none { it is HomeSearchOverlayEntry.HistoryNotice || it is HomeSearchOverlayEntry.NoMatchesYet })
        assertTrue(complete.any { it == HomeSearchOverlayEntry.NoResults })
    }

    @Test
    fun onlyAFreeServerHasAHistoryWindow() {
        assertEquals(
            listOf("free" to 30, "FREE" to 30, "pro" to null, "founder" to null, "partner" to null, null to null),
            listOf("free", "FREE", "pro", "founder", "partner", null).map { it to homeSearchHistoryWindowDays(it) },
        )
        assertEquals(30, homeWithChannels(1, plan = "free").homeSearchHistoryWindowDays())
        assertNull(homeWithChannels(1, plan = "pro").homeSearchHistoryWindowDays())
        assertNull(SharedHomeState().homeSearchHistoryWindowDays())
    }

    // --- 5. A person or agent opens the DM, else the profile (useSearchResultActions) ---

    @Test
    fun aPersonOrAgentResultOpensTheDirectMessageWithThem() = runTest {
        listOf(MemberKind.Human, MemberKind.Agent).forEach { kind ->
            val asked = mutableListOf<MemberDetailIdentity>()
            val opened = mutableListOf<SearchEntityResult>()

            val (outcome, didOpen) =
                openHomeSearchEntityWithDirectMessage(
                    entity = memberEntity(kind),
                    openDirectMessage = { identity ->
                        asked += identity
                        DataResult.Content(OpenDirectMessageResult(dmRow("dm-7", kind), emptyList()))
                    },
                    open = { opened += it; true },
                )

            assertEquals(HomeSearchMemberOpenOutcome.DirectMessage to true, outcome to didOpen)
            assertEquals(MemberId("member-7"), asked.single().memberId)
            val destination = assertIs<HomeSearchEntityDestination.Conversation>(opened.single().destinationOrNull())
            assertEquals(ThreadId("dm-7"), destination.identity.threadId)
            assertEquals(ConversationKind.DirectMessage, destination.identity.kind)
            // The wide shell opens the DM in its detail pane, beside the list.
            assertEquals(ThreadId("dm-7"), opened.single().homeSearchInlineConversationOrNull()?.threadId)
            // It is still the member's row (pin, frequent ranking).
            assertEquals("member:member-7", opened.single().key)
        }
    }

    @Test
    fun aDirectMessageThatCannotBeOpenedFallsBackToTheProfile() = runTest {
        val failures: List<DataResult<OpenDirectMessageResult>> =
            listOf(
                DataResult.Error(ScreenError(code = "network_error")),
                DataResult.NoPermission(PermissionBlocker(code = "forbidden")),
                DataResult.Empty(EmptyReason.NoChannels),
            )
        failures.forEach { failure ->
            val opened = mutableListOf<SearchEntityResult>()
            val logs = mutableListOf<String>()

            val (outcome, didOpen) =
                openHomeSearchEntityWithDirectMessage(
                    entity = memberEntity(MemberKind.Agent),
                    openDirectMessage = { failure },
                    open = { opened += it; true },
                    log = { logs += it },
                )

            assertEquals(HomeSearchMemberOpenOutcome.ProfileFallback to true, outcome to didOpen, "$failure")
            val destination = assertIs<HomeSearchEntityDestination.Member>(opened.single().destinationOrNull())
            assertEquals(MemberId("member-7"), destination.identity.memberId)
            assertTrue(logs.single().startsWith("search_member_dm_open_fallback_profile"), logs.single())
        }
    }

    @Test
    fun aSecondTapWhileTheDirectMessageIsOpeningOpensNothing() = runTest {
        var opens = 0
        val (outcome, didOpen) =
            openHomeSearchEntityWithDirectMessage(
                entity = memberEntity(MemberKind.Human),
                openDirectMessage = { null },
                open = { opens++; true },
            )
        assertEquals(HomeSearchMemberOpenOutcome.AlreadyOpening to false, outcome to didOpen)
        assertEquals(0, opens)
    }

    // --- 6. A thread result's title (searchGrouping.ts truncateThreadTitle) ---

    @Test
    fun aThreadResultsTitleIsCutAsTheWebCutsIt() {
        // Past 80 characters, the first 77 and "...".
        assertEquals(
            "Captured the Android visual artifact for #product:f8e569cb and queued React p...",
            homeSearchThreadTitle("Captured the Android visual artifact for #product:f8e569cb and queued React parity."),
        )
        // 80 characters are shown whole.
        val eighty = "Ship it. ".repeat(9).take(80)
        assertEquals(eighty, homeSearchThreadTitle(eighty))
    }

    private fun memberEntity(kind: MemberKind): SearchEntityResult =
        SearchEntityResult(
            key = "member:member-7",
            type = if (kind == MemberKind.Agent) SearchEntityType.Agent else SearchEntityType.Human,
            title = "Ada",
            subtitle = "@ada",
            threadId = null,
            memberIdentity = MemberDetailIdentity(memberId = MemberId("member-7"), kind = kind, sourceServerId = ServerId("server-1")),
            conversationIdentity = null,
        )

    private fun dmRow(id: String, kind: MemberKind): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.DirectMessage,
            name = "Ada",
            presenceMemberId = MemberId("member-7"),
            directMessagePeerType = if (kind == MemberKind.Agent) "agent" else "human",
        )

    private fun homeWithChannels(count: Int, plan: String? = null): SharedHomeState =
        SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = ServerSummary(ServerId("server-1"), "Raft", plan = plan),
                            currentMember =
                                MemberProfile(id = MemberId("user-1"), displayName = "Me", handle = "me", kind = MemberKind.Human),
                            channels =
                                (1..count).map { index ->
                                    val id = "eng-${index.toString().padStart(2, '0')}"
                                    HomeChannelRow(threadId = ThreadId(id), channelId = ChannelId(id), name = id)
                                },
                            directMessages = emptyList(),
                            unreadTotal = 0,
                        ),
                    session = authSessionFixture(),
                )
        )
}

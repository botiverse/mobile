package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #15664 human self-description edit contract (`PATCH /api/auth/me` `{ description }`):
 * - explicit `null` clears the stored description;
 * - trims outer whitespace and normalizes `\r\n` to `\n`;
 * - parses canonical `description` and `warnings` (`DescriptionLinkWarning`) from the response;
 * - preserves `profile_description_invalid` `serverCode` on 400 refusals;
 * - patches the signed-in person's description (including clearing to `null`) across cached rosters,
 *   profile caches, and channel member slices.
 */
class HumanProfileDescriptionUpdateTest {
    @Test
    fun buildBodyNormalizesLineBreaksAndSendsExplicitNullWhenCleared() {
        val updated = Json.parseToJsonElement(buildSharedAccountDescriptionUpdateBody("  Line 1\r\nLine 2  ")).jsonObject
        assertEquals(setOf("description"), updated.keys)
        assertEquals("Line 1\nLine 2", updated.getValue("description").jsonPrimitive.content)

        val clearedFromNull = Json.parseToJsonElement(buildSharedAccountDescriptionUpdateBody(null)).jsonObject
        assertEquals(setOf("description"), clearedFromNull.keys)
        assertEquals(JsonNull, clearedFromNull.getValue("description"))

        val clearedFromBlank = Json.parseToJsonElement(buildSharedAccountDescriptionUpdateBody("  \r\n\t ")).jsonObject
        assertEquals(setOf("description"), clearedFromBlank.keys)
        assertEquals(JsonNull, clearedFromBlank.getValue("description"))
    }

    @Test
    fun parseResultDecodesCanonicalDescriptionAndLinkWarnings() {
        val response =
            """{"id":"user-1","email":"u@slock.test","name":"alice","description":"See https://app.raft.build/a/abc",""" +
                """"warnings":[{"code":"conversation","url":"https://app.raft.build/a/abc","server":{"id":"s1","name":"Acme"}}]}"""

        val parsed = assertIs<AccountDescriptionUpdateResult>(parseSharedAccountDescriptionUpdateResult(response))
        assertEquals("See https://app.raft.build/a/abc", parsed.description)
        assertEquals(1, parsed.warnings.size)
        assertEquals(DescriptionLinkWarningCode.Conversation, parsed.warnings.single().code)
        assertEquals("https://app.raft.build/a/abc", parsed.warnings.single().url)
        assertEquals("Acme", parsed.warnings.single().serverName)

        val cleared = """{"id":"user-1","email":"u@slock.test","name":"alice","description":null}"""
        val parsedCleared = assertIs<AccountDescriptionUpdateResult>(parseSharedAccountDescriptionUpdateResult(cleared))
        assertNull(parsedCleared.description)
        assertTrue(parsedCleared.warnings.isEmpty())

        assertNull(parseSharedAccountDescriptionUpdateResult("""{"notAUser":true}"""))
    }

    @Test
    fun repositoryPatchesDescriptionReturnsWarningsAndPreservesInvalidCode() = runTest {
        val network =
            RecordingDescriptionNetworkClient(
                responseJson =
                    """{"id":"user-1","email":"u@slock.test","name":"alice","description":"Mobile lead",""" +
                        """"warnings":[{"code":"server_partial","url":"https://app.raft.build/a/xyz","unreadable":["guests"]}]}"""
            )
        val repository = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), network)

        val saved = assertIs<DataResult.Content<AccountDescriptionUpdateResult>>(
            repository.updateAccountDescription("  Mobile lead  ")
        )
        assertEquals("Mobile lead", saved.data.description)
        assertEquals(1, saved.data.warnings.size)
        assertEquals(DescriptionLinkWarningCode.ServerPartial, saved.data.warnings.single().code)
        assertTrue(saved.data.warnings.single().unreadableByGuests)

        val call = network.calls.single()
        assertEquals("auth/me", call.path)
        assertEquals("PATCH", call.method)
        assertEquals(DataRequestPriority.Foreground, call.priority)
        assertEquals("""{"description":"Mobile lead"}""", call.body)

        // Clearing sends explicit {"description":null}.
        val clearNetwork =
            RecordingDescriptionNetworkClient(
                responseJson = """{"id":"user-1","email":"u@slock.test","name":"alice","description":null}"""
            )
        val clearRepo = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), clearNetwork)
        val cleared = assertIs<DataResult.Content<AccountDescriptionUpdateResult>>(clearRepo.updateAccountDescription("   "))
        assertNull(cleared.data.description)
        assertEquals("""{"description":null}""", clearNetwork.calls.single().body)

        // 400 profile_description_invalid surfaces serverCode and serverMessage.
        val invalidNetwork =
            RecordingDescriptionNetworkClient(
                responseJson = null,
                code = 400,
                errorJson = """{"code":"profile_description_invalid","error":"A new or changed description can be at most 300 characters: shorten it."}"""
            )
        val invalidRepo = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), invalidNetwork)
        val failure = assertIs<DataResult.Error>(invalidRepo.updateAccountDescription("x".repeat(301)))
        assertEquals(PROFILE_DESCRIPTION_INVALID_CODE, failure.error.serverCode)
        assertEquals(
            "A new or changed description can be at most 300 characters: shorten it.",
            failure.error.serverMessage
        )
    }

    @Test
    fun serverMembersStoreUpdatesAndClearsHumanDescriptionAcrossServers() = runTest {
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")
        val channelA = ChannelId("channel-a")
        val selfA =
            MemberProfile(
                id = MemberId("user-1"),
                displayName = "Alice",
                handle = "alice",
                kind = MemberKind.Human,
                description = "Old bio",
                serverId = serverA
            )
        val otherA =
            MemberProfile(
                id = MemberId("user-2"),
                displayName = "Bob",
                handle = "bob",
                kind = MemberKind.Human,
                description = "Bob bio",
                serverId = serverA
            )
        val selfB = selfA.copy(serverId = serverB)
        var serverAFetches = 0
        var serverADescription = "Old bio"
        val repo =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    when (serverId) {
                        serverA -> {
                            serverAFetches += 1
                            DataResult.Content(
                                MemberSnapshot(
                                    ServerSummary(serverA, "Acme"),
                                    listOf(selfA.copy(description = serverADescription), otherA)
                                )
                            )
                        }
                        serverB -> DataResult.Content(MemberSnapshot(ServerSummary(serverB, "Beta"), listOf(selfB)))
                        else -> DataResult.Empty(EmptyReason.NoMembers)
                    }

                override suspend fun getChannelMembers(
                    priority: DataRequestPriority,
                    channelId: ChannelId
                ): DataResult<ChannelMemberSnapshot> =
                    DataResult.Content(ChannelMemberSnapshot(channelId = channelId, members = listOf(selfA, otherA)))
            }
        val rows = linkedMapOf<String, String>()
        val storage =
            object : RaftServerMembersDurableStorage {
                override suspend fun read(id: String): String? = rows[id]
                override suspend fun write(id: String, value: String) { rows[id] = value }
                override suspend fun delete(id: String) { rows.remove(id) }
                override suspend fun ids(): List<String> = rows.keys.toList()
            }
        val durable = RaftServerMembersDurableStore(storage = storage, scope = backgroundScope)
        val ownerA = ProcessServerMembersOwner("https://api.example", "user-1", serverA.value)
        val ownerB = ProcessServerMembersOwner("https://api.example", "user-1", serverB.value)
        val processCore =
            ProcessServerMembersCore(
                fetchMembers = { priority, sid -> repo.getMembers(priority, sid) },
                scope = backgroundScope
            )
        val store =
            SharedServerMembersStore(
                memberRepository = repo,
                processCore = processCore,
                processOwner = { sid -> if (sid == serverA) ownerA else if (sid == serverB) ownerB else null },
                durableStore = durable
            )
        store.refreshMembers(serverA)
        store.refreshMembers(serverB)
        store.loadChannelMembers(serverA, channelA)
        store.seedProfiles(serverA, listOf(selfA))
        assertEquals(1, serverAFetches)

        assertTrue(store.updateHumanDescription(MemberId("user-1"), "New bio"))
        assertEquals("New bio", store.memberProfile(serverA, "user-1")?.description)
        assertEquals("New bio", store.memberProfile(serverB, "user-1")?.description)
        assertEquals("Bob bio", store.memberProfile(serverA, "user-2")?.description)
        assertEquals(
            "New bio",
            store.currentChannelMembers(serverA, channelA).first { it.id == MemberId("user-1") }.description
        )
        assertEquals(
            "New bio",
            store.state.value.memberSuggestionSlice(serverA).suggestions.first { it.memberId == "user-1" }.description
        )
        assertEquals(
            "New bio",
            durable.read(RaftServerMembersDurableKey("https://api.example", "user-1", serverA.value))
                ?.members
                ?.first { it.id == MemberId("user-1") }
                ?.description
        )

        // processCore was invalidated, so a subsequent non-forced refresh fetches fresh data rather
        // than serving the pre-edit cached snapshot.
        serverADescription = "New bio"
        store.refreshMembers(serverA)
        assertEquals(2, serverAFetches)
        assertEquals("New bio", store.memberProfile(serverA, "user-1")?.description)

        // Clearing to null must replace rather than keep the previous non-null description.
        assertTrue(store.updateHumanDescription(MemberId("user-1"), null))
        assertNull(store.memberProfile(serverA, "user-1")?.description)
        assertNull(store.memberProfile(serverB, "user-1")?.description)
        assertNull(store.currentChannelMembers(serverA, channelA).first { it.id == MemberId("user-1") }.description)
        assertNull(store.state.value.memberSuggestionSlice(serverA).suggestions.first { it.memberId == "user-1" }.description)
        assertNull(
            durable.read(RaftServerMembersDurableKey("https://api.example", "user-1", serverA.value))
                ?.members
                ?.first { it.id == MemberId("user-1") }
                ?.description
        )
        assertFalse(store.updateHumanDescription(MemberId("user-1"), null))
    }

    private class RecordingDescriptionNetworkClient(
        private val responseJson: String?,
        private val code: Int = 200,
        private val errorJson: String? = null,
    ) : RaftNetworkClient {
        data class Call(val priority: DataRequestPriority, val path: String, val method: String, val body: String?)

        val calls = mutableListOf<Call>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(priority, path.trim('/'), method, bodyText)
            return if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = responseJson)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = errorJson)
            }
        }
    }
}

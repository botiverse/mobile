package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.seedCachedAgentDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent detail page offline (artin: everything fetched is stored locally): a whitelisted record on disk
 * paints a cold / offline open read-only; secrets never reach disk; confirmed edits write through.
 */
class AgentDetailOfflineTest {
    private class Device(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { ENVIRONMENT }, scope = scope)

        /** A new process over the same disk: empty memory, same rows. */
        fun cache(): RaftAgentDetailCache = raftAgentDetailCache(offline.resource(AgentDetailResourceSpec))
    }

    @Test
    fun coldStartPaintsTheStoredRecordThenTheRefreshReplacesIt() = runTest {
        val device = Device(backgroundScope)
        val first = device.cache()
        assertTrue(first.write(owner.agentDetailCacheKey(AGENT), cached(detail("Atlas")), first.ticket()))

        // Cold start: memory is empty; the disk answers. Knife: without the durable read this is null.
        val coldCache = device.cache()
        assertNull(coldCache.peek(owner.agentDetailCacheKey(AGENT)))
        val replay = assertNotNull(coldCache.cachedAgentDetailFor(owner, AGENT))
        assertEquals("Atlas", replay.detail.summary?.name)
        assertEquals(listOf("general"), replay.detail.channels.map { it.name })

        // Painted as a cache replay: no profile edit authority until a fresh answer (the page's gate).
        val store = SharedAgentDetailStore()
        store.seedCachedAgentDetail(AGENT, owner, profileGeneration = 1L, cached = replay)
        assertTrue(store.state.value.detailFromCache)

        // The refresh answers: memory and disk take it.
        assertTrue(coldCache.write(owner.agentDetailCacheKey(AGENT), cached(detail("Atlas Prime")), coldCache.ticket()))
        assertEquals("Atlas Prime", device.cache().cachedAgentDetailFor(owner, AGENT)?.detail?.summary?.name)
        // Other accounts never read it.
        assertNull(device.cache().cachedAgentDetailFor(sharedAgentDetailOwner(ENVIRONMENT, "user-2", ServerId(SERVER))!!, AGENT))
    }

    @Test
    fun secretsNeverReachDisk() = runTest {
        val device = Device(backgroundScope)
        val cache = device.cache()
        val secretDetail =
            detail("Atlas").let { base ->
                base.copy(
                    summary =
                        base.summary!!.copy(
                            activity = "thinking",
                            activityDetail = "thinking: the token is SECRET-11",
                            activityDetailKind = "thinking",
                            toolStuck = build.raft.app.model.AgentToolStuck(toolName = "bash SECRET-12", quietSinceMs = 1L),
                            envVarNames = listOf("PUBLIC_FLAG"),
                            envVars = mapOf("OPENAI_API_KEY" to "sk-env-SECRET-1", "PUBLIC_FLAG" to "env-SECRET-2"),
                            runtimeProvider = RuntimeProviderConfig.Custom(apiUrl = "https://llm.example/SECRET-URL", apiKey = "sk-provider-SECRET-3"),
                        ),
                    directMessages = listOf(AgentDmSummary(id = "dm-1", peerId = "u1", peerName = "Ann", lastMessagePreview = "preview SECRET-4")),
                    activity =
                        listOf(
                            AgentActivityEntry(
                                id = "act-1",
                                type = "tool",
                                title = "bash",
                                body = "body SECRET-5",
                                toolInput = "export TOKEN=SECRET-6",
                                details = listOf("password" to "SECRET-7"),
                            )
                        ),
                    appLogins =
                        AgentAppLoginSnapshot(
                            agentId = AGENT,
                            items =
                                listOf(
                                    AgentAppLoginItem(
                                        id = "login-1",
                                        type = AgentAppLoginType.Active,
                                        clientId = "client-1",
                                        clientKey = "client-key-SECRET-8",
                                        clientName = "Calendar",
                                        clientReturnUrl = "https://app.example/callback?SECRET-9",
                                        clientAgentManifestUrl = "https://app.example/manifest?SECRET-10",
                                    )
                                ),
                        ),
                )
            }

        assertTrue(cache.write(owner.agentDetailCacheKey(AGENT), cached(secretDetail), cache.ticket()))

        val onDisk = device.rows.rows.values.joinToString("\n") { it.value }
        assertTrue(onDisk.isNotEmpty())
        assertFalse("SECRET" in onDisk, "a secret reached disk: $onDisk")
        // The safe projections are kept: env var names, the login's display fields.
        val stored = assertNotNull(device.cache().cachedAgentDetailFor(owner, AGENT)).detail
        assertEquals(setOf("PUBLIC_FLAG", "OPENAI_API_KEY"), stored.summary?.envVarNames?.toSet())
        assertEquals(emptyMap(), stored.summary?.envVars)
        assertNull(stored.summary?.runtimeProvider)
        assertEquals(listOf("Calendar"), stored.appLogins.items.map { it.clientName })
        assertNull(stored.appLogins.items.single().clientKey)
        assertEquals(emptyList(), stored.activity)
        assertNull(stored.directMessages.single().lastMessagePreview)
    }

    @Test
    fun confirmedEditWinsOverAnOlderInFlightAnswerAndDeleteEvicts() = runTest {
        val device = Device(backgroundScope)
        val cache = device.cache()
        val key = owner.agentDetailCacheKey(AGENT)
        assertTrue(cache.write(key, cached(detail("Atlas")), cache.ticket()))

        // A refresh starts, then a profile rename is confirmed, then the older answer arrives.
        val olderRequest = cache.ticket()
        cache.confirm(key, cached(detail("Renamed")))
        cache.write(key, cached(detail("Atlas")), olderRequest)

        assertEquals("Renamed", cache.peek(key)?.detail?.summary?.name)
        assertEquals("Renamed", device.cache().cachedAgentDetailFor(owner, AGENT)?.detail?.summary?.name)

        cache.evict(key)
        assertNull(device.cache().cachedAgentDetailFor(owner, AGENT))
    }

    @Test
    fun onDiskAConfirmedEditSupersedesAnOlderAnswerAndIsStoredWhenNothingWasStored() = runTest {
        val device = Device(backgroundScope)
        val durable = RaftOfflineKeyedSnapshotDurable(device.offline.resource(AgentDetailResourceSpec))
        val key = owner.agentDetailCacheKey(AGENT)
        fun record(name: String) = cached(detail(name)).toStoredAgentDetail()

        // Nothing stored yet (e.g. the first write was fenced): the confirmed edit is stored.
        durable.confirm(key, record("Renamed"))
        assertEquals("Renamed", durable.read(key)?.summary?.name)

        // An answer whose request started before the next confirmed edit cannot overwrite it on disk.
        val olderRequest = durable.epoch
        durable.confirm(key, record("Renamed again"))
        assertFalse(durable.write(key, record("Atlas"), olderRequest))
        assertEquals("Renamed again", durable.read(key)?.summary?.name)
    }

    @Test
    fun lostServerWipeTakesTheStoredPageAndFencesAnInFlightAnswer() = runTest {
        val device = Device(backgroundScope)
        val cache = device.cache()
        val key = owner.agentDetailCacheKey(AGENT)
        assertTrue(cache.write(key, cached(detail("Atlas")), cache.ticket()))
        val inFlight = cache.ticket()

        device.offline.wipeServer(SERVER, accountId = "user-1")
        cache.write(key, cached(detail("Late")), inFlight)

        assertNull(device.cache().cachedAgentDetailFor(owner, AGENT))
    }

    @Test
    fun realtimeAgentDeletedDropsTheStoredPageOfThatAgentOnly() = runTest {
        val device = Device(backgroundScope)
        val cache = device.cache()
        val other = MemberId("agent-2")
        cache.write(owner.agentDetailCacheKey(AGENT), cached(detail("Atlas")), cache.ticket())
        cache.write(owner.agentDetailCacheKey(other), cached(detail("Other").copy(agentId = other)), cache.ticket())
        val deleted = mutableListOf<Pair<String, String>>()
        val realtime =
            SharedRealtimeDomainEventOwner(
                onAgentDeleted = { serverId, agentId ->
                    deleted += serverId to agentId
                    val key = sharedAgentDetailOwner(ENVIRONMENT, "user-1", ServerId(serverId))!!.agentDetailCacheKey(MemberId(agentId))
                    cache.evict(key)
                }
            )

        realtime.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = SERVER,
            event =
                build.raft.app.model.RealtimeRefreshRequired(
                    scope = build.raft.app.model.RealtimeRefreshScope.Agents,
                    reason = "agent:deleted",
                    subjectId = AGENT.value,
                ),
        )
        // Other agent refreshes (agent:created / session) are not deletions.
        realtime.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = SERVER,
            event =
                build.raft.app.model.RealtimeRefreshRequired(
                    scope = build.raft.app.model.RealtimeRefreshScope.Agents,
                    reason = "agent:session",
                    subjectId = other.value,
                ),
        )

        assertEquals(listOf(SERVER to AGENT.value), deleted)
        assertNull(device.cache().cachedAgentDetailFor(owner, AGENT))
        assertEquals("Other", device.cache().cachedAgentDetailFor(owner, other)?.detail?.summary?.name)
    }

    private val owner = sharedAgentDetailOwner(ENVIRONMENT, "user-1", ServerId(SERVER))!!

    private fun detail(name: String): AgentDetailSnapshot =
        AgentDetailSnapshot(
            agentId = AGENT,
            summary = AgentDetailSummary(id = AGENT.value, name = name),
            directMessages = emptyList(),
            channels = listOf(AgentChannelSummary(id = "c1", name = "general")),
            reminders = emptyList(),
            activity = emptyList(),
        )

    private fun cached(detail: AgentDetailSnapshot): RaftCachedAgentDetail =
        RaftCachedAgentDetail(server = ServerSummary(id = ServerId(SERVER), name = "Server"), currentUserId = "user-1", detail = detail)

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER = "server-1"
        val AGENT = MemberId("agent-1")
    }
}

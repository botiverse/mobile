package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.members.MemberDetailRouteState
import build.raft.app.model.serverPermissionsForRole
import build.raft.app.members.seedCachedAgentDetail
import build.raft.app.members.viewerSeesPrivateAgentSurfaces
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
    fun coldStartKeepsTheViewersRoleSoTheReplayShowsTheSameTabsAsTheLivePage() = runTest {
        // #proj-mobile:#236 (artin): the record kept only the server's id and name, so after a cold start
        // an owner opening an agent someone else created saw the Profile tab only until the background
        // refresh landed. Knife: without `viewerRole` on disk the owner's replay below has no permissions.
        val device = Device(backgroundScope)
        val first = device.cache()
        val ownersPage = cached(detail("Atlas")).copy(server = server(role = "owner"))
        assertTrue(first.write(owner.agentDetailCacheKey(AGENT), ownersPage, first.ticket()))

        val replay = assertNotNull(device.cache().cachedAgentDetailFor(owner, AGENT))
        assertEquals("owner", replay.server.role)
        assertTrue(replay.server.permissions.canManageSettings)
        val ownerRoute = MemberDetailRouteState.Agent(server = replay.server, currentUserId = replay.currentUserId)
        assertTrue(ownerRoute.viewerSeesPrivateAgentSurfaces(replay.detail), "an owner sees the private tabs from the first frame")

        // A plain member who did not create the agent sees the profile only, on the replay as on a live answer.
        val membersPage = cached(detail("Atlas")).copy(server = server(role = "member"))
        assertTrue(first.write(owner.agentDetailCacheKey(AGENT), membersPage, first.ticket()))
        val memberReplay = assertNotNull(device.cache().cachedAgentDetailFor(owner, AGENT))
        assertFalse(memberReplay.server.permissions.canManageSettings)
        val memberRoute = MemberDetailRouteState.Agent(server = memberReplay.server, currentUserId = memberReplay.currentUserId)
        assertFalse(memberRoute.viewerSeesPrivateAgentSurfaces(memberReplay.detail))
        // ... and the agent's creator does, whatever their role.
        val created = memberReplay.detail.copy(summary = memberReplay.detail.summary?.copy(creatorId = "user-1"))
        assertTrue(memberRoute.viewerSeesPrivateAgentSurfaces(created))
    }

    @Test
    fun coldStartShowsTheSavedFastModeAsTheLivePageDoes() = runTest {
        // The profile's Mode row reads `runtimeMode`: without it on disk a fast agent read Default until the
        // refresh landed. `executionMode` (where the agent runs) is its own field and comes back as stored.
        val device = Device(backgroundScope)
        val first = device.cache()
        val fast =
            detail("Atlas").let { base ->
                base.copy(summary = base.summary?.copy(runtime = "codex", model = "gpt-6.2-sol", runtimeMode = "fast", executionMode = "byoc"))
            }
        assertTrue(first.write(owner.agentDetailCacheKey(AGENT), cached(fast), first.ticket()))

        val replay = assertNotNull(device.cache().cachedAgentDetailFor(owner, AGENT))
        assertEquals("fast", replay.detail.summary?.runtimeMode)
        assertEquals("byoc", replay.detail.summary?.executionMode)
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

    @Test
    fun cachedChannelsPreserveLoungeAgentIdAcrossRoundTrip() = runTest {
        val device = Device(backgroundScope)
        val cache = device.cache()
        val withLounge =
            detail("Atlas").copy(
                channels =
                    listOf(
                        AgentChannelSummary(id = "c1", name = "general", type = "channel"),
                        AgentChannelSummary(id = "l1", name = "atlas-lounge", type = "channel", loungeAgentId = AGENT.value),
                    ),
            )
        cache.write(owner.agentDetailCacheKey(AGENT), cached(withLounge), cache.ticket())

        val restored = device.cache().cachedAgentDetailFor(owner, AGENT)
        assertEquals(listOf(null, AGENT.value), restored?.detail?.channels?.map { it.loungeAgentId })
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

    /** The viewer's server as the live load builds it from the session (role, so permissions). */
    private fun server(role: String): ServerSummary =
        ServerSummary(id = ServerId(SERVER), name = "Server", role = role, permissions = serverPermissionsForRole(role))

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER = "server-1"
        val AGENT = MemberId("agent-1")
    }
}

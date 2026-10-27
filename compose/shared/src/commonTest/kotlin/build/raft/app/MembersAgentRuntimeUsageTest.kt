package build.raft.app

import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftStatusVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

class MembersAgentRuntimeUsageTest {
    private val owner = SharedComputersOwner("https://api.example", "user-1", "gen-1")

    @Test
    fun noReadSubjectWithoutAccessIdentityServerMachineOrSupportedRuntime() {
        val access = access(owner, "s1")
        assertNull(membersRuntimeAccountUsageKey(null, "claude", "m1"))
        assertNull(membersRuntimeAccountUsageKey(access(null, "s1"), "claude", "m1"))
        assertNull(membersRuntimeAccountUsageKey(access(owner, null), "claude", "m1"))
        assertNull(membersRuntimeAccountUsageKey(access(owner, " "), "claude", "m1"))
        assertNull(membersRuntimeAccountUsageKey(access, "claude", null))
        assertNull(membersRuntimeAccountUsageKey(access, "claude", "  "))
        assertNull(membersRuntimeAccountUsageKey(access, "builtin", "m1"))
        val key = membersRuntimeAccountUsageKey(access, "kimi-sdk", " m1 ")
        assertEquals(RuntimeAccountUsageProvider.Kimi, key?.provider)
        assertEquals("m1", key?.machineId)
        assertEquals("s1", key?.serverId)
    }

    @Test
    fun switchingIdentityServerOrMachineChangesTheReadSubject() {
        val base = membersRuntimeAccountUsageKey(access(owner, "s1"), "claude", "m1")
        // The chip's state and LaunchedEffect are keyed on this value, so any change resets the
        // shown content and cancels the old read before it can backfill.
        assertNotEquals(base, membersRuntimeAccountUsageKey(access(owner.copy(accountId = "user-2"), "s1"), "claude", "m1"))
        assertNotEquals(base, membersRuntimeAccountUsageKey(access(owner.copy(sessionGeneration = "gen-2"), "s1"), "claude", "m1"))
        assertNotEquals(base, membersRuntimeAccountUsageKey(access(owner, "s2"), "claude", "m1"))
        assertNotEquals(base, membersRuntimeAccountUsageKey(access(owner, "s1"), "claude", "m2"))
        assertNotEquals(base, membersRuntimeAccountUsageKey(access(owner, "s1"), "codex", "m1"))
        assertEquals(base, membersRuntimeAccountUsageKey(access(owner, "s1"), "claude", "m1"))
    }

    @Test
    fun runtimeUsageStatusDotMatchesWebHealthSemantics() {
        assertNull(membersRuntimeUsageStatusVariant(offered = false, attention = false))
        assertEquals(RaftStatusVariant.Success, membersRuntimeUsageStatusVariant(offered = true, attention = false))
        assertEquals(RaftStatusVariant.Warning, membersRuntimeUsageStatusVariant(offered = true, attention = true))
    }

    @Test
    fun entryIsOfferedOnlyAfterANon403Read() {
        assertFalse(null.offersRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Forbidden.offersRuntimeAccountUsage())
        assertTrue(RuntimeAccountUsageLoad.Failed("server_unavailable", true).offersRuntimeAccountUsage())
        assertTrue(RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing).offersRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Forbidden.runtimeAccountUsageNeedsAttention())
    }

    @Test
    fun diagnosticsLabelsCarryOnlyTheLoadShape() {
        assertEquals("none", (null as RuntimeAccountUsageLoad?).diagnosticsLabel())
        assertEquals("forbidden", RuntimeAccountUsageLoad.Forbidden.diagnosticsLabel())
        assertEquals("superseded", RuntimeAccountUsageLoad.Superseded.diagnosticsLabel())
        assertEquals("failed:server_unavailable", RuntimeAccountUsageLoad.Failed("server_unavailable", true).diagnosticsLabel())
        assertEquals("missing", RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing).diagnosticsLabel())
        assertEquals("stale", loaded(stale = true, account()).diagnosticsLabel())
        assertEquals("fresh", loaded(stale = false, account()).diagnosticsLabel())
    }

    @Test
    fun aReadForAnIdentityThatIsNoLongerSignedInShowsNothing() {
        assertNull(RuntimeAccountUsageLoad.Superseded.shownRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Superseded.offersRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Superseded.runtimeAccountUsageNeedsAttention())
        val loaded = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing)
        assertEquals(loaded, loaded.shownRuntimeAccountUsage())
        assertEquals(RuntimeAccountUsageLoad.Forbidden, RuntimeAccountUsageLoad.Forbidden.shownRuntimeAccountUsage())
    }

    @Test
    fun missingStaleUnknownAndUnavailableAllNeedAttentionOnlyHealthyFreshDoesNot() {
        assertFalse(loaded(stale = false, account()).runtimeAccountUsageNeedsAttention())
        assertTrue(RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing).runtimeAccountUsageNeedsAttention())
        assertTrue(RuntimeAccountUsageLoad.Failed("server_unavailable", true).runtimeAccountUsageNeedsAttention())
        assertTrue(loaded(stale = true, account()).runtimeAccountUsageNeedsAttention())
        assertTrue(loaded(stale = false, account(health = RuntimeAccountUsageHealth.Unknown)).runtimeAccountUsageNeedsAttention())
        assertTrue(
            loaded(stale = false, account(window = window(RuntimeAccountUsageWindowStatus.Unavailable, null)))
                .runtimeAccountUsageNeedsAttention()
        )
        assertTrue(
            loaded(stale = false, account(window = window(RuntimeAccountUsageWindowStatus.LimitReached, 1.0)))
                .runtimeAccountUsageNeedsAttention()
        )
    }

    @Test
    fun unavailableWindowsNeverRenderAsAZeroPercentBar() {
        assertNull(window(RuntimeAccountUsageWindowStatus.Unavailable, null).displayPercentOrNull())
        assertNull(window(RuntimeAccountUsageWindowStatus.Unavailable, 0.5).displayPercentOrNull())
        assertNull(window(RuntimeAccountUsageWindowStatus.Ok, null).displayPercentOrNull())
        assertEquals(0, window(RuntimeAccountUsageWindowStatus.Ok, 0.0).displayPercentOrNull())
        assertEquals(42, window(RuntimeAccountUsageWindowStatus.Ok, 0.42).displayPercentOrNull())
        assertEquals(100, window(RuntimeAccountUsageWindowStatus.LimitReached, 1.0).displayPercentOrNull())
    }

    @Test
    fun finishedReadThenAccountSwitchNeverLetsTheOldCardOpen() = runPortableCoroutineTest<Unit> {
        // Account A reads usage and the page stays composed; then the user switches to B.
        val accountB = owner.copy(accountId = "user-2", sessionGeneration = "gen-b")
        var signedIn: SharedComputersOwner? = owner
        val resolveGate = CompletableDeferred<Unit>()
        var gateResolves = false
        val tracker = MembersRuntimeAccountUsageOwnerTracker {
            if (gateResolves) resolveGate.await()
            signedIn
        }
        val usage = SharedRuntimeAccountUsageStore(
            client = RaftComputersApiClient(UsageOkApi()),
            currentIdentity = { signedIn },
            clock = { 0L }
        )
        tracker.refresh()
        val keyA = membersRuntimeAccountUsageKey(access(tracker.owner.value, "s1"), "claude", "m1")!!
        assertIs<RuntimeAccountUsageLoad.Loaded>(usage.read(keyA))

        signedIn = accountB
        gateResolves = true
        val switching = async(start = CoroutineStart.UNDISPATCHED) {
            tracker.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 2))
        }
        // Before B is even resolved the owner is gone, so the chip's key is null: the loaded A
        // state is dropped (state is keyed on the key) and the entry is not offered.
        assertNull(tracker.owner.value)
        assertNull(membersRuntimeAccountUsageKey(access(tracker.owner.value, "s1"), "claude", "m1"))
        resolveGate.complete(Unit)
        switching.await()

        val keyB = membersRuntimeAccountUsageKey(access(tracker.owner.value, "s1"), "claude", "m1")!!
        assertEquals(accountB, keyB.owner)
        assertNotEquals(keyA, keyB)
        // B never sees A's cached usage, and A's key is refused outright now.
        assertNull(usage.peek(keyB))
        assertEquals(RuntimeAccountUsageLoad.Superseded, usage.read(keyA))
    }

    @Test
    fun tokenRotationKeepsTheOwnerAndOtherAuthEventsReResolveIt() = runPortableCoroutineTest<Unit> {
        var signedIn: SharedComputersOwner? = owner
        val tracker = MembersRuntimeAccountUsageOwnerTracker { signedIn }
        tracker.refresh()
        assertFalse(AuthSessionEvent.TokensRotated(generation = 1).changesRuntimeAccountUsageOwner())
        signedIn = owner.copy(sessionGeneration = "gen-2")
        tracker.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 1))
        assertEquals(owner, tracker.owner.value)
        assertTrue(AuthSessionEvent.BindingMetadataChanged(generation = 2).changesRuntimeAccountUsageOwner())
        signedIn = null
        tracker.onAuthSessionEvent(AuthSessionEvent.BindingMetadataChanged(generation = 2))
        assertNull(tracker.owner.value)
    }

    @Test
    fun aRefreshOvertakenByAnInvalidateNeverPublishesTheOldOwner() = runPortableCoroutineTest<Unit> {
        var signedIn: SharedComputersOwner? = owner
        val firstResolve = CompletableDeferred<Unit>()
        var calls = 0
        val tracker = MembersRuntimeAccountUsageOwnerTracker {
            calls += 1
            val seen = signedIn
            if (calls == 1) firstResolve.await()
            seen
        }
        val stale = async(start = CoroutineStart.UNDISPATCHED) { tracker.refresh() }
        signedIn = owner.copy(accountId = "user-2")
        tracker.invalidate()
        assertEquals("user-2", tracker.owner.value?.accountId)
        firstResolve.complete(Unit)
        stale.await()
        assertEquals("user-2", tracker.owner.value?.accountId)
    }

    private class UsageOkApi : RaftComputersApi {
        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"state":"fresh","snapshot":{"provider":"claude","accounts":[{"accountKey":"a","health":"ok","windows":[]}]}}"""
            )
        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
            error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            error("unused")
    }

    private fun access(owner: SharedComputersOwner?, serverId: String?) =
        MembersRuntimeAccountUsageAccess(store = store, owner = owner, serverId = serverId)

    private fun loaded(stale: Boolean, account: RuntimeAccountUsageAccount) =
        RuntimeAccountUsageLoad.Loaded(
            RuntimeAccountUsageRead.Available(
                stale = stale,
                snapshot = RuntimeAccountUsageSnapshot(provider = "claude", collectedAt = null, accounts = listOf(account))
            )
        )

    private fun account(
        health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok,
        window: RuntimeAccountUsageWindow = window(RuntimeAccountUsageWindowStatus.Ok, 0.4)
    ) = RuntimeAccountUsageAccount("a", "Max", null, health, listOf(window))

    private fun window(status: RuntimeAccountUsageWindowStatus, ratio: Double?) =
        RuntimeAccountUsageWindow(id = "w", label = "5h", status = status, usedRatio = ratio, resetsAt = null)

    private companion object {
        // Never called in these pure tests; the access object only needs a store instance.
        val store = SharedRuntimeAccountUsageStore(
            currentIdentity = { null },
            client = RaftComputersApiClient(object : RaftComputersApi {
                override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
                override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
                override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
                override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
                override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
                override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
                override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
                    error("unused")
                override suspend fun deleteMachineWorkspace(
                    serverId: ServerId,
                    machineId: String,
                    directoryName: String
                ): HomeNetworkResponse = error("unused")
            })
        )
    }
}

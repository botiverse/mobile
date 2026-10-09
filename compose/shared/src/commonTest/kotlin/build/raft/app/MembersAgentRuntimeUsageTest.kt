package build.raft.app

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
import build.raft.app.data.SlockComputersApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun entryIsOfferedOnlyAfterANon403Read() {
        assertFalse(null.offersRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Forbidden.offersRuntimeAccountUsage())
        assertTrue(RuntimeAccountUsageLoad.Failed("server_unavailable", true).offersRuntimeAccountUsage())
        assertTrue(RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing).offersRuntimeAccountUsage())
        assertFalse(RuntimeAccountUsageLoad.Forbidden.runtimeAccountUsageNeedsAttention())
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
            SlockComputersApiClient(object : SlockComputersApi {
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

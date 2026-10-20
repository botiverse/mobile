package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class ReconcilerFakeAuthStore(private var session: StoredAuthSession?) : RaftAuthSessionStore {
    override suspend fun readSession(): StoredAuthSession? = session
    override suspend fun saveSession(session: StoredAuthSession) {
        this.session = session
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit
    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
    }

    override suspend fun clearSession(reason: String, source: String) {
        session = null
    }
}

private fun reconcilerSession(serverId: String?): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "user@example.com"),
        accessToken = "token",
        refreshToken = "refresh",
        selectedServerId = serverId
    )

class SharedForegroundReconcilerTest {
    @Test
    fun coalescesRepeatedForegroundSignalsWithinWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val timeSource = TestTimeSource()
        val refreshed = mutableListOf<String>()
        val reconciler =
            SharedForegroundReconciler(
                authStore = ReconcilerFakeAuthStore(reconcilerSession("server-1")),
                refreshMembers = { serverId -> refreshed += serverId.value },
                scope = scope,
                coalesceWindowMs = 10_000,
                timeSource = timeSource
            )

        reconciler.onForeground("app_shell_resume")
        reconciler.onForeground("thread_resume")
        timeSource += 9_999.milliseconds
        reconciler.onForeground("thread_show")

        assertEquals(listOf("server-1"), refreshed)

        timeSource += 2.milliseconds
        reconciler.onForeground("app_shell_resume")

        assertEquals(listOf("server-1", "server-1"), refreshed)
        scope.cancel()
    }

    @Test
    fun serverSwitchBypassesCoalescingWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = ReconcilerFakeAuthStore(reconcilerSession("server-1"))
        val refreshed = mutableListOf<String>()
        val reconciler =
            SharedForegroundReconciler(
                authStore = authStore,
                refreshMembers = { serverId -> refreshed += serverId.value },
                scope = scope,
                coalesceWindowMs = 10_000,
                timeSource = TestTimeSource()
            )

        reconciler.onForeground("app_shell_resume")
        authStore.updateSelectedServer("server-2", null)
        reconciler.onForeground("app_shell_resume")

        assertEquals(listOf("server-1", "server-2"), refreshed)
        scope.cancel()
    }

    @Test
    fun missingSessionOrServerIsNoOp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refreshed = mutableListOf<String>()
        val reconciler =
            SharedForegroundReconciler(
                authStore = ReconcilerFakeAuthStore(null),
                refreshMembers = { serverId -> refreshed += serverId.value },
                scope = scope
            )

        reconciler.onForeground("app_shell_resume")

        assertEquals(emptyList(), refreshed)
        scope.cancel()
    }

    @Test
    fun refreshFailureDoesNotBlockNextWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val timeSource = TestTimeSource()
        var calls = 0
        val reconciler =
            SharedForegroundReconciler(
                authStore = ReconcilerFakeAuthStore(reconcilerSession("server-1")),
                refreshMembers = { _: ServerId ->
                    calls += 1
                    error("network down")
                },
                scope = scope,
                coalesceWindowMs = 10_000,
                timeSource = timeSource
            )

        reconciler.onForeground("app_shell_resume")
        timeSource += 10_001.milliseconds
        reconciler.onForeground("app_shell_resume")

        assertEquals(2, calls)
        scope.cancel()
    }
}

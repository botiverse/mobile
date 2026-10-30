package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedCreateAgentFormStoreTest {

    @Test
    fun silentTokenRefreshKeepsTheFormSessionAndANewLoginStartsANewOne() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val generation = SharedAuthLoginGeneration()
        var session = formSession(user = "account-1", accessToken = "access-1")
        val store = SharedCreateAgentFormStore(
            RaftComputersApiClient(api),
            this,
            ownerProvider = { sharedCreateAgentFormOwner(session, "https://api.example.test/", generation.current()) }
        )
        val machineKey = SharedCreateAgentMachineFormKey("server-1", "machine-1")
        assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(store.loadRuntimeCatalog("server-1", "machine-1"))
        val owner = store.state.value.owner

        // The auth store rotates both tokens and announces it, as on a device after `auth_refresh_successor_commit`.
        session = session.copy(accessToken = "access-2", refreshToken = "refresh-2")
        generation.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 2))
        store.reconcileOwner()
        generation.onAuthSessionEvent(AuthSessionEvent.BindingMetadataChanged(generation = 3))
        store.reconcileOwner()

        assertEquals(owner, store.state.value.owner, "a refresh is the same session")
        assertTrue(machineKey in store.state.value.runtimeCatalogs, "the runtime list the form is showing survives")
        assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(store.loadRuntimeCatalog("server-1", "machine-1"))
        assertEquals(1, api.runtimeOptionsCallCount, "and is not fetched again")

        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 4))
        store.reconcileOwner()
        assertTrue(store.state.value.owner != owner, "logging in again is a new session")
        assertTrue(store.state.value.runtimeCatalogs.isEmpty())
    }

    @Test
    fun formOwnerIgnoresTokensAndFollowsAccountAndEnvironment() {
        val session = formSession(user = "account-1", accessToken = "access-1")
        val owner = sharedCreateAgentFormOwner(session, "https://api.example.test/", "1")
        assertEquals(SharedCreateAgentFormOwner("https://api.example.test", "account-1", "1"), owner)
        assertEquals(owner, sharedCreateAgentFormOwner(session.copy(accessToken = "access-2"), "https://api.example.test", "1"))
        assertTrue(owner != sharedCreateAgentFormOwner(formSession(user = "account-2", accessToken = "access-1"), "https://api.example.test", "1"))
        assertTrue(owner != sharedCreateAgentFormOwner(session, "https://other.example.test", "1"))
        assertEquals(null, sharedCreateAgentFormOwner(formSession(user = " ", accessToken = "access-1"), "https://api.example.test", "1"))
        assertEquals(null, sharedCreateAgentFormOwner(session, " ", "1"))

        val generation = SharedAuthLoginGeneration()
        val start = generation.current()
        generation.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 1))
        generation.onAuthSessionEvent(AuthSessionEvent.BindingMetadataChanged(generation = 2))
        assertEquals(start, generation.current())
        generation.onAuthSessionEvent(AuthSessionEvent.Cleared(generation = 3, reason = AuthSessionClearReason.Logout))
        val afterLogout = generation.current()
        assertTrue(afterLogout != start)
        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 4))
        assertTrue(generation.current() != afterLogout)
    }

    private fun formSession(user: String, accessToken: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = user, email = "user@raft.build"),
            accessToken = accessToken,
            refreshToken = "refresh-1",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
        )

    @Test
    fun aCreateIsRememberedOnlyWithAComputerAndARuntime() = runPortableCoroutineTest {
        val prefs = mutableMapOf<String, String>()
        val store = SharedCreateAgentFormStore(
            RaftComputersApiClient(SequencedFormComputersApi()),
            this,
            lastConfigs = CreateAgentLastConfigRepository(
                object : SharedPreferenceStore {
                    override fun getString(key: String): String? = prefs[key]
                    override fun putString(key: String, value: String?) {
                        if (value == null) prefs.remove(key) else prefs[key] = value
                    }
                }
            )
        )
        store.rememberCreatedAgent("server-1", machineId = null, runtime = "codex")
        assertEquals(null, store.rememberedCreateRuntime("server-1"))
        store.rememberCreatedAgent("server-1", machineId = "machine-1", runtime = "codex")
        assertEquals("codex", store.rememberedCreateRuntime("server-1"))
        assertEquals(null, store.rememberedCreateRuntime("server-2"))
    }

    private class SequencedFormComputersApi(
        var runtimeOptionsBody: String = canonicalRuntimeOptionsBody
    ) : RaftComputersApi {
        var runtimeOptionsCallCount = 0

        override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse {
            runtimeOptionsCallCount += 1
            return ok(runtimeOptionsBody)
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private companion object {
        val canonicalRuntimeOptionsBody =
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"builtin","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"runtimeFormV2":{"protocolVersion":2}}]}"""

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}

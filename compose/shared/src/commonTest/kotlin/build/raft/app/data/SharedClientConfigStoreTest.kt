package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `GET /api/public/client-config` `{ appHosts }` (botiverse/slock #11421,
 * `packages/server/src/routes/publicClientConfig.ts` at feat/workspace-artifacts 8a44f57be).
 */
class SharedClientConfigStoreTest {
    // The store publishes to the process-wide list message links read (RaftClientConfigAppHosts).
    @AfterTest
    fun resetProcessHosts() = RaftClientConfigAppHosts.publish(emptyList())

    private class MemoryPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeApi(var next: () -> HomeNetworkResponse) : RaftClientConfigApi {
        var calls = 0
        val environments = mutableListOf<String>()
        override suspend fun read(environment: String): HomeNetworkResponse {
            calls += 1
            environments += environment
            return next()
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(200, body)

    @Test
    fun parsesHostnamesOnlyDedupedInOrder() {
        assertEquals(
            listOf("app.raft.build", "staging.raft.build", "xn--bcher-kva.example"),
            parseRaftClientConfig(
                """{"appHosts":["app.raft.build","staging.raft.build","app.raft.build","App.Raft.build","host:443","https://x.example","a/b","","xn--bcher-kva.example"],"later":1}""",
            )?.appHosts,
        )
        assertNull(parseRaftClientConfig("""{"appHosts":[]}"""))
        assertNull(parseRaftClientConfig("""{"other":true}"""))
        assertNull(parseRaftClientConfig("not json"))
    }

    @Test
    fun beforeAnyFetchTheFallbackIsTheCanonicalHost() = runTest {
        val store = MutableStateFlow("https://api.raft.build/api").let { env -> SharedClientConfigStore(FakeApi { error("offline") }, MemoryPreferences(), { env.value }, env, backgroundScope) }
        assertEquals(listOf("app.raft.build"), store.appHosts.value)
        store.start()
        runCurrent()
        assertEquals(CLIENT_CONFIG_FALLBACK_APP_HOSTS, store.appHosts.value, "a failed first read keeps the fallback")
    }

    @Test
    fun anAnswerIsAppliedAndCachedPerEnvironmentAndAFailureKeepsIt() = runTest {
        val preferences = MemoryPreferences()
        val environment = MutableStateFlow("https://api.raft.build/api")
        val api = FakeApi { ok("""{"appHosts":["app.raft.build","raft.build"]}""") }
        val store = SharedClientConfigStore(api, preferences, { environment.value }, environment, backgroundScope)
        store.start()
        runCurrent()
        assertEquals(listOf("app.raft.build", "raft.build"), store.appHosts.value)

        // Another environment: its own read, the production list not carried over.
        api.next = { ok("""{"appHosts":["app.raft.build","staging.raft.build"]}""") }
        environment.value = "https://staging-api.raft.build/api"
        runCurrent()
        assertEquals(listOf("app.raft.build", "staging.raft.build"), store.appHosts.value)

        // Back to production while offline: its cached list, not the fallback.
        api.next = { HomeNetworkResponse(503, null) }
        environment.value = "https://api.raft.build/api/"
        runCurrent()
        assertEquals(listOf("app.raft.build", "raft.build"), store.appHosts.value)
        assertEquals(3, api.calls)
        assertEquals(
            listOf("https://api.raft.build/api", "https://staging-api.raft.build/api", "https://api.raft.build/api"),
            api.environments,
            "each read is for the environment it is stored under",
        )

        // A new process starts from the disk cache before its read lands.
        val restarted = SharedClientConfigStore(FakeApi { error("offline") }, preferences, { environment.value }, environment, backgroundScope)
        assertEquals(listOf("app.raft.build", "raft.build"), restarted.appHosts.value)
        // What message links read: this environment's list, not the fallback.
        assertEquals(setOf("app.raft.build", "raft.build"), RaftClientConfigAppHosts.current)
    }

    /**
     * Device check 2026-10-29: after switching to staging (which signs out) the read went through
     * the authenticated request, answered 401 locally with no session, and nothing read again after
     * sign-in: Artifact links stayed plain until a restart. The read is public, on the asked environment.
     */
    @Test
    fun theReadIsPublicAndOnTheAskedEnvironmentWithoutASession() = runTest {
        val raw = mutableListOf<String>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse = HomeNetworkResponse(401, null)

                override suspend fun requestRawAbsoluteUrl(
                    priority: DataRequestPriority,
                    url: String,
                    method: String,
                    headers: Map<String, String>,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    raw += "$method $url ${headers.keys.sorted()}"
                    return ok("""{"appHosts":["app.raft.build","raft-app-staging.botiverse.dev"]}""")
                }
            }
        val environment = MutableStateFlow("https://api-aws-staging.botiverse.dev/api/")
        val store = SharedClientConfigStore(NetworkRaftClientConfigApi(client), MemoryPreferences(), { environment.value }, environment, backgroundScope)
        store.start()
        runCurrent()
        assertEquals(listOf("GET https://api-aws-staging.botiverse.dev/api/public/client-config []"), raw)
        assertEquals(listOf("app.raft.build", "raft-app-staging.botiverse.dev"), store.appHosts.value)
    }
}

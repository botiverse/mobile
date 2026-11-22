package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `GET /api/public/client-config` `{ appHosts }` (botiverse/slock #11421,
 * `packages/server/src/routes/publicClientConfig.ts` at feat/workspace-artifacts 8a44f57be).
 */
class SharedClientConfigStoreTest {
    // The store publishes to the process-wide list message links read (RaftClientConfigAppHosts).
    @AfterTest
    fun resetProcessState() {
        RaftClientConfigAppHosts.publish(emptyList())
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
    }

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

    /** RAFT_PRIVACY_MODE (slock docs/observability/privacy-mode.md): only `privacyMode: true` on a 2xx answer is on. */
    @Test
    fun privacyModeIsOnOnlyForATrueFieldOnASuccessfulAnswer() {
        val cases =
            listOf(
                HomeNetworkResponse(200, """{"appHosts":["app.raft.build"],"privacyMode":true}""") to RaftPrivacyMode.On,
                // No usable host is no app-host answer, but the privacy field still counts.
                HomeNetworkResponse(200, """{"privacyMode":true}""") to RaftPrivacyMode.On,
                HomeNetworkResponse(200, """{"appHosts":["app.raft.build"]}""") to RaftPrivacyMode.Off,
                HomeNetworkResponse(200, """{"appHosts":["app.raft.build"],"privacyMode":false}""") to RaftPrivacyMode.Off,
                HomeNetworkResponse(200, "not json") to RaftPrivacyMode.Off,
                HomeNetworkResponse(503, """{"privacyMode":true}""") to RaftPrivacyMode.Off,
                null to RaftPrivacyMode.Off,
            )
        for ((response, expected) in cases) {
            assertEquals(expected, raftClientConfigPrivacyMode(response), "response ${response?.code} ${response?.bodyText}")
        }
    }

    @Test
    fun privacyModeIsUnknownUntilEachEnvironmentAnswersAndAFailedReadIsOff() = runTest {
        val published = mutableListOf<RaftPrivacyMode>()
        val environment = MutableStateFlow("https://self-hosted.example/api")
        val api = FakeApi { ok("""{"appHosts":["app.raft.build"],"privacyMode":true}""") }
        val store = SharedClientConfigStore(api, MemoryPreferences(), { environment.value }, environment, backgroundScope, publishPrivacyMode = { _, answer -> published += answer.telemetryMode })
        store.start()
        runCurrent()
        assertEquals(listOf(RaftPrivacyMode.Unknown, RaftPrivacyMode.On), published)

        // Another environment starts over at unknown; a failed read is off.
        api.next = { error("offline") }
        environment.value = "https://api.raft.build/api"
        runCurrent()
        assertEquals(listOf(RaftPrivacyMode.Unknown, RaftPrivacyMode.On, RaftPrivacyMode.Unknown, RaftPrivacyMode.Off), published)
    }

    @Test
    fun aReadWithNoAnswerCountsAsOffAfterTheTimeoutAndALateOnStillWins() = runTest {
        val published = mutableListOf<RaftPrivacyMode>()
        val answer = CompletableDeferred<HomeNetworkResponse>()
        val api =
            object : RaftClientConfigApi {
                override suspend fun read(environment: String): HomeNetworkResponse = answer.await()
            }
        val environment = MutableStateFlow("https://self-hosted.example/api")
        val store = SharedClientConfigStore(api, MemoryPreferences(), { environment.value }, environment, backgroundScope, publishPrivacyMode = { _, answer -> published += answer.telemetryMode })
        store.start()
        advanceTimeBy(RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(listOf(RaftPrivacyMode.Unknown), published, "held while the read is pending")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(RaftPrivacyMode.Unknown, RaftPrivacyMode.Off), published)
        answer.complete(ok("""{"privacyMode":true}"""))
        runCurrent()
        assertEquals(RaftPrivacyMode.On, published.last())
    }

    /**
     * Owner ruling on #5082: a failed read lets trace telemetry go (off, #5064) but keeps the
     * deferrable uploads on the device; the next launch with a successful answer sends them.
     */
    @Test
    fun aFailedReadUploadsNothingAndTheNextLaunchWithASuccessfulAnswerUploads() = runTest {
        val uploaded = mutableListOf<String>()
        suspend fun appLaunch(response: () -> HomeNetworkResponse) {
            val environment = MutableStateFlow("https://self-hosted.example/api")
            val store = SharedClientConfigStore(FakeApi(response), MemoryPreferences(), { environment.value }, environment, backgroundScope)
            store.start()
            runCurrent()
            val decision = RaftServicesPrivacyGate.awaitLaunchDecision(waitMillis = 60_000L)
            if (RaftServicesPrivacyGate.allowsAutomatic(decision)) uploaded += "crash"
        }

        appLaunch { error("offline") }
        assertEquals(RaftPrivacyMode.Off, RaftTelemetryPrivacy.state.value, "traces follow #5064: a failed read is off")
        assertEquals(RaftPrivacyMode.Unknown, RaftTelemetryPrivacy.acrossSignedInServers.value, "deferrable uploads: unknown")
        assertEquals(emptyList(), uploaded, "nothing uploaded after a failed read")

        appLaunch { ok("""{"appHosts":["app.raft.build"]}""") }
        assertEquals(RaftPrivacyMode.Off, RaftTelemetryPrivacy.acrossSignedInServers.value)
        assertEquals(listOf("crash"), uploaded, "the next launch with a successful answer uploads")
    }
}

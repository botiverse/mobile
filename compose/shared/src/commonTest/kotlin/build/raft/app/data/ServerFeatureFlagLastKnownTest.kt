package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #1133: offline cold start gates a last-known flag with the last online evaluation of the
 * same (environment, account, server); an online evaluation always wins; logout / account switch never
 * leaks a value to the next account. Each `SharedServerFeatureFlags` instance below is one process.
 *
 * No production key is stored last-known today, so these tests opt `thread_markdown_single_paragraph_light_v0`
 * in and make it client default-on (ON without a decision), so a stored value is only observable when it is
 * OFF: these tests store an explicit server OFF and check who sees it.
 */
private val lastKnownKeys: Set<String> = setOf(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY)

class ServerFeatureFlagLastKnownTest {

    private val server = ServerId("s1")
    private val accountA = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
    private val accountB = ServerFeatureFlagLastKnownBinding("https://api.example", "user-b")

    private class ScriptedApi(var next: () -> HomeNetworkResponse) : RaftFeatureFlagsApi {
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
            entered.complete(Unit)
            gate?.await()
            return next()
        }
    }

    private fun evaluated(enabled: Boolean) =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                """{"evaluations":[{"key":"thread_markdown_single_paragraph_light_v0","enabled":$enabled},""" +
                    """{"key":"server_labs_ui_v0","enabled":true}]}""",
        )

    private val offline = HomeNetworkResponse(code = 0, bodyText = null)

    private class Device {
        val disk = InMemoryRaftKeyValueStore()
        var binding: ServerFeatureFlagLastKnownBinding? = null
        val logs = mutableListOf<String>()

        fun process(api: RaftFeatureFlagsApi): SharedServerFeatureFlags =
            SharedServerFeatureFlags(
                api = api,
                diagnosticsLogger = { logs += it },
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }, keys = lastKnownKeys),
                clientDefaultOnKeys = lastKnownKeys,
            )
    }

    private fun Device.onlineSession(enabled: Boolean) = runBlocking {
        process(ScriptedApi { evaluated(enabled) }).prefetch(server)
    }

    @Test
    fun offlineColdStartUsesLastOnlineEvaluation() = runBlocking {
        val device = Device().apply { binding = accountA }
        device.onlineSession(enabled = false)

        val coldStart = device.process(ScriptedApi { offline })
        assertFalse(coldStart.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
        coldStart.prefetch(server) // evaluate fails offline: still the stored value
        assertFalse(coldStart.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
        assertTrue(device.logs.any { it.startsWith("feature_flags last_known_used server=s1 key=thread_markdown_single_paragraph_light_v0 enabled=false") })
        // Raw reads never see the stored value.
        assertFalse(coldStart.snapshotFor(server).resolved)
    }

    @Test
    fun withoutAnyStoredEvaluationOfflineColdStartReadsTheClientDefault() = runBlocking {
        val device = Device().apply { binding = accountA }
        val coldStart = device.process(ScriptedApi { offline })
        coldStart.prefetch(server)
        assertTrue(coldStart.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }

    @Test
    fun onlyAllowlistedKeysAreStored() = runBlocking {
        val device = Device().apply { binding = accountA }
        device.onlineSession(enabled = false)
        val coldStart = device.process(ScriptedApi { offline })
        // server_labs_ui_v0 was ON online, but it keeps the defaults-off-until-evaluated contract.
        assertFalse(coldStart.isEnabled(server, SERVER_LABS_UI_FEATURE_FLAG_KEY))
    }

    @Test
    fun onlineEvaluationOverridesTheStoredValueAsSoonAsItLands() = runBlocking {
        val device = Device().apply { binding = accountA }
        device.onlineSession(enabled = false)

        val onlineColdStart = device.process(ScriptedApi { evaluated(true) })
        assertFalse(onlineColdStart.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY)) // before evaluate lands
        onlineColdStart.prefetch(server)
        assertTrue(onlineColdStart.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))

        // The online decision is also what the next offline cold start reads.
        val nextOffline = device.process(ScriptedApi { offline })
        assertTrue(nextOffline.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }

    @Test
    fun anotherAccountNeverReadsTheStoredValue() = runBlocking {
        val device = Device().apply { binding = accountA }
        device.onlineSession(enabled = false)

        device.binding = accountB
        val coldStartAsB = device.process(ScriptedApi { offline })
        assertTrue(coldStartAsB.isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))

        // Same account in another environment is another binding too.
        device.binding = accountA.copy(environmentId = "https://staging.example")
        assertTrue(device.process(ScriptedApi { offline }).isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }

    @Test
    fun clearAllOnLogoutRemovesTheStoredValue() = runBlocking {
        val device = Device().apply { binding = accountA }
        device.onlineSession(enabled = false)
        ServerFeatureFlagLastKnownStore(store = device.disk, currentBinding = { device.binding }, keys = lastKnownKeys).clearAll()

        assertTrue(device.process(ScriptedApi { offline }).isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }

    @Test
    fun evaluationLandingAfterAccountSwitchIsNotStored() = runBlocking {
        val device = Device().apply { binding = accountA }
        val api = ScriptedApi { evaluated(false) }.apply { gate = CompletableDeferred() }
        val flags = device.process(api)

        val inFlight = async { flags.prefetch(server) }
        api.entered.await() // the request (and its binding) left before the switch
        device.binding = accountB // switched while A's request is in flight
        api.gate?.complete(Unit)
        inFlight.await()

        assertTrue(device.logs.any { it.startsWith("feature_flags last_known_skipped server=s1") })
        assertTrue(device.process(ScriptedApi { offline }).isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
        device.binding = accountA
        assertTrue(device.process(ScriptedApi { offline }).isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }

    @Test
    fun noBindingMeansNoReadAndNoWrite() = runBlocking {
        val device = Device()
        device.onlineSession(enabled = false)
        assertNull(device.disk.getString(SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY))
        device.binding = accountA
        assertTrue(device.process(ScriptedApi { offline }).isEnabled(server, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }
}

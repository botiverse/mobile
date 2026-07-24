package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedServerFeatureFlagsTest {

    private val server = ServerId("s1")

    private class FakeApi(private val response: HomeNetworkResponse) : SlockFeatureFlagsApi {
        var lastKeys: List<String>? = null
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
            lastKeys = keys
            return response
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    @Test
    fun prefetchResolvesEnabledFlagAndDefaultsUnknownOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v0","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api)
        val snapshot = flags.prefetch(server)
        assertTrue(snapshot.resolved)
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY)) // absent from evaluations -> off
        assertFalse(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, INLINE_THREAD_REPLIES_FEATURE_FLAG_KEY))
        assertTrue(INLINE_THREAD_REPLIES_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS, api.lastKeys) // batch: all registered keys
    }

    @Test
    fun readStateV1FlagIsIndependentAndDefaultsOffUntilItsOwnEvaluation() = runBlocking {
        val flags =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v0","enabled":true},{"key":"sync_core_read_state_v1","enabled":true}]}"""))
            )

        assertFalse(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        flags.prefetch(server)
        assertTrue(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun explicitDisabledFlagStaysOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v0","enabled":false}]}"""))
        val flags = SharedServerFeatureFlags(api)
        flags.prefetch(server)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun enabledThreadPageSizeFlagPreservesItsVariant() = runBlocking {
        val api =
            FakeApi(
                ok(
                    """{"evaluations":[{"key":"$MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY","enabled":true,"variant":"20"}]}"""
                )
            )
        val snapshot = SharedServerFeatureFlags(api).prefetch(server)

        assertTrue(snapshot.isEnabled(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY))
        assertEquals("20", snapshot.variant(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY))
    }

    @Test
    fun transportVariantIsForwardedToProcessObserver() = runBlocking {
        var observedVariant: String? = null
        val flags =
            SharedServerFeatureFlags(
                api =
                    FakeApi(
                        ok(
                            """{"evaluations":[{"key":"$MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY","enabled":true,"variant":"curl_h2"}]}"""
                        )
                    ),
                onEvaluation = { _, key, _, _, variant, _, _ ->
                    if (key == MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY) observedVariant = variant
                }
            )

        flags.prefetch(server, evaluationContext = "production-env")

        assertEquals("curl_h2", observedVariant)
    }

    @Test
    fun disabledThreadPageSizeFlagDoesNotExposeItsVariant() = runBlocking {
        val api =
            FakeApi(
                ok(
                    """{"evaluations":[{"key":"$MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY","enabled":false,"variant":"50"}]}"""
                )
            )
        val snapshot = SharedServerFeatureFlags(api).prefetch(server)

        assertFalse(snapshot.isEnabled(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY))
        assertEquals(null, snapshot.variant(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY))
    }

    @Test
    fun selectedServerFeatureFlagsRefreshAgainWithoutASelectionChange() = runBlocking {
        var refreshCount = 0
        val waits = mutableListOf<Long>()

        var canceled = false
        try {
            refreshSelectedServerFeatureFlags(
                intervalMillis = 123L,
                wait = { interval ->
                    waits += interval
                    if (waits.size == 2) throw CancellationException("test complete")
                },
                refresh = { refreshCount += 1 }
            )
        } catch (_: CancellationException) {
            canceled = true
        }

        assertTrue(canceled)
        assertEquals(2, refreshCount)
        assertEquals(listOf(123L, 123L), waits)
    }

    @Test
    fun recoveryFlagForwardsAuthoritativeReasonToPlatformBridge() = runBlocking {
        val evaluations = mutableListOf<String>()
        var observedGeneration = 0L
        val api =
            FakeApi(
                ok(
                    """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
                )
            )
        val flags =
            SharedServerFeatureFlags(api) { serverId, key, enabled, reason, _, context, generation ->
                observedGeneration = generation
                evaluations += "${serverId.value}|$key|$enabled|$reason|$context"
            }

        flags.prefetch(server, evaluationContext = "production-env")

        assertEquals(
            listOf(
                "s1|$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY|false|kill_switch|production-env"
            ),
            evaluations
        )
        assertTrue(observedGeneration > 0L)
    }

    @Test
    fun observerFailureDoesNotBlockTheResolvedFeatureSnapshot() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val flags =
            SharedServerFeatureFlags(
                api =
                    FakeApi(
                        ok(
                            """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":true,"reason":"default"}]}"""
                        )
                    ),
                diagnosticsLogger = diagnostics::add,
                onEvaluation = { _, _, _, _, _, _, _ -> error("observer failed") }
            )

        val snapshot = flags.prefetch(server)

        assertTrue(snapshot.resolved)
        assertTrue(flags.isEnabled(server, ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY))
        assertTrue(diagnostics.any { it.contains("evaluation_observer_failed") })
    }

    @Test
    fun olderSameServerResponseCannotOverwriteSnapshotButStillCarriesGlobalGeneration() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse =
                    when (++callCount) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            ok(
                                """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
                            )
                        }
                        else ->
                            ok(
                                """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":true,"reason":"default"}]}"""
                            )
                    }
            }
        val evaluations = mutableListOf<Triple<Boolean, String?, Long>>()
        val diagnostics = mutableListOf<String>()
        val flags =
            SharedServerFeatureFlags(
                api = api,
                diagnosticsLogger = diagnostics::add,
                onEvaluation = { _, _, enabled, reason, _, _, generation ->
                    evaluations += Triple(enabled, reason, generation)
                }
            )

        val first = async { flags.prefetch(server) }
        firstStarted.await()
        val second = async { flags.prefetch(server) }
        second.await()
        releaseFirst.complete(Unit)
        first.await()

        assertTrue(flags.isEnabled(server, ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY))
        assertEquals(listOf(true to "default", false to "kill_switch"), evaluations.map { it.first to it.second })
        assertTrue(evaluations[0].third > evaluations[1].third)
        assertTrue(diagnostics.any { it.contains("stale_ignored") })
    }

    @Test
    fun newerFailedRequestDoesNotDiscardAnOlderAuthoritativeResponse() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse =
                    when (++callCount) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            ok(
                                """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
                            )
                        }
                        else -> HomeNetworkResponse(code = 500, bodyText = null)
                    }
            }
        val evaluations = mutableListOf<Triple<Boolean, String?, Long>>()
        val flags =
            SharedServerFeatureFlags(api = api) { _, _, enabled, reason, _, _, generation ->
                evaluations += Triple(enabled, reason, generation)
            }

        val first = async { flags.prefetch(server) }
        firstStarted.await()
        val second = async { flags.prefetch(server) }
        second.await()
        releaseFirst.complete(Unit)
        first.await()

        assertFalse(flags.isEnabled(server, ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY))
        assertEquals(listOf(false to "kill_switch"), evaluations.map { it.first to it.second })
        assertTrue(evaluations.single().third > 0L)
    }

    @Test
    fun cancelledPrefetchCannotPublishOrApplyANonCooperativeLateResponse() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return ok(
                        """{"evaluations":[{"key":"$ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
                    )
                }
            }
        val evaluations = mutableListOf<Triple<Boolean, String?, Long>>()
        val flags =
            SharedServerFeatureFlags(api = api) { _, _, enabled, reason, _, _, generation ->
                evaluations += Triple(enabled, reason, generation)
            }

        val prefetch = launch { flags.prefetch(server) }
        started.await()
        prefetch.cancel()
        release.complete(Unit)
        prefetch.join()

        assertFalse(flags.snapshotFor(server).resolved)
        assertTrue(evaluations.isEmpty())
    }

    @Test
    fun unresolvedSnapshotDefaultsAllOff() = runBlocking {
        val flags = SharedServerFeatureFlags(FakeApi(ok("{}")))
        // no prefetch yet
        assertFalse(flags.snapshotFor(server).resolved)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(null, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun failedFetchLeavesFlagsOffNeverThrows() = runBlocking {
        val flags = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        val snapshot = flags.prefetch(server)
        assertFalse(snapshot.resolved) // failed -> stays Unresolved, flags default off (feature stays dark)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun malformedBodyDoesNotThrow() = runBlocking {
        val flags = SharedServerFeatureFlags(FakeApi(ok("not-json")))
        assertFalse(flags.prefetch(server).resolved)
    }

    // Captures the raw request body the network API encodes, so we can assert the wire contract directly.
    private class BodyCapturingClient(private val response: HomeNetworkResponse) : SlockNetworkClient {
        var lastBody: String? = null
        var lastPath: String? = null
        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            lastPath = path
            lastBody = bodyText
            return response
        }
    }

    @Test
    fun networkApiSendsMobilePlatformInRequestBody() = runBlocking {
        // slock #186: the server evaluates a `platform` rule stage; the mobile client must report "mobile"
        // so a mobile-only rollout (e.g. sync_core_messages_v0) targets this client and not web.
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        SlockFeatureFlagsNetworkApi(client).evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = client.lastBody ?: error("no request body captured")
        assertEquals("feature-flags/evaluate", client.lastPath)
        assertTrue(body.contains("\"platform\":\"mobile\""), "request must send platform=mobile, got: $body")
        assertTrue(body.contains("\"serverId\":\"s1\""), "request must send serverId, got: $body")
    }
}

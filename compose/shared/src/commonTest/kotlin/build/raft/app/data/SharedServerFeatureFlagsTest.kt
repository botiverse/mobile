package build.raft.app.data

import build.raft.app.model.ServerId
import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
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
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api)
        val snapshot = flags.prefetch(server)
        assertTrue(snapshot.resolved)
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY)) // absent from evaluations -> off
        assertFalse(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS, api.lastKeys) // batch: all registered keys
    }

    @Test
    fun defaultOnKeyReadsOnWithoutAnExplicitServerDecision() = runBlocking {
        val doh = MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY
        // Unresolved (no evaluation yet, no stored value) and no server at all: ON.
        val idle = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        assertTrue(idle.isEnabled(server, doh))
        assertTrue(idle.isEnabled(null, doh))
        // Ordinary keys keep reading OFF without a decision.
        assertFalse(idle.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY))

        // Key absent from the response: ON.
        idle.prefetch(server)
        assertTrue(idle.isEnabled(server, doh))

        // No flag row on the server (`missing_flag`): ON.
        val missing =
            SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[{"key":"$doh","enabled":false,"reason":"missing_flag"}]}""")))
        missing.prefetch(server)
        assertTrue(missing.isEnabled(server, doh))

        // Evaluate failed or the body is malformed: still no decision, ON.
        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 503, bodyText = null)))
        failed.prefetch(server)
        assertTrue(failed.isEnabled(server, doh))
        val malformed = SharedServerFeatureFlags(FakeApi(ok("not json")))
        malformed.prefetch(server)
        assertTrue(malformed.isEnabled(server, doh))
    }

    @Test
    fun defaultOnKeyFollowsAnExplicitServerDecision() = runBlocking {
        val doh = MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY
        for (reason in listOf("kill_switch", "flag_disabled", "default", "platform_rule")) {
            val flags =
                SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[{"key":"$doh","enabled":false,"reason":"$reason"}]}""")))
            flags.prefetch(server)
            assertFalse(flags.isEnabled(server, doh), "explicit off ($reason) turns it off")
        }
        val on =
            SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[{"key":"$doh","enabled":true,"reason":"default"}]}""")))
        on.prefetch(server)
        assertTrue(on.isEnabled(server, doh))
    }

    @Test
    fun diagnosticOverrideHookReceivesServerValueAndDecidesBothReadShapes() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":false}]}"""))
        val seen = mutableListOf<Triple<ServerId?, String, Boolean>>()
        val flags =
            SharedServerFeatureFlags(
                api,
                diagnosticOverride = { serverId, key, serverEffective ->
                    seen += Triple(serverId, key, serverEffective)
                    if (key == SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY) true else serverEffective
                },
            )

        // Before any resolve: the raw snapshot is Unresolved (OFF) but the hook decides ON for its key only.
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertTrue(flags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, READ_RECEIPTS_FEATURE_FLAG_KEY))
        assertEquals(Triple<ServerId?, String, Boolean>(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false), seen[0])
        assertEquals(Triple<ServerId?, String, Boolean>(null, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY, false), seen[1])

        // After resolve the hook still sees the true server value (explicit OFF) and the raw snapshot is untouched.
        flags.prefetch(server)
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.snapshotFor(server).isEnabled(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertEquals(false, seen.last().third)
    }

    @Test
    fun diagnosticOverrideHookCanForceAServerEnabledFlagOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api, diagnosticOverride = { _, _, _ -> false })
        flags.prefetch(server)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        assertTrue(flags.snapshotFor(server).isEnabled(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun withoutDiagnosticOverrideHookReadsAreTheRawSnapshot() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api)
        assertFalse(flags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
        flags.prefetch(server)
        assertTrue(flags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun readStateV1FlagIsIndependentAndDefaultsOffUntilItsOwnEvaluation() = runBlocking {
        val flags =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":true},{"key":"sync_core_read_state_v1","enabled":true}]}"""))
            )

        assertFalse(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        flags.prefetch(server)
        assertTrue(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        assertTrue(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun explicitDisabledFlagStaysOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_messages_v1","enabled":false}]}"""))
        val flags = SharedServerFeatureFlags(api)
        flags.prefetch(server)
        assertFalse(flags.isEnabled(server, SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY))
    }

    @Test
    fun webviewOnboardingIsRegisteredAndRequiresExplicitResolvedEnablement() = runBlocking {
        val enabled = SharedServerFeatureFlags(
            FakeApi(ok("""{"evaluations":[{"key":"$WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY","enabled":true}]}"""))
        )
        assertFalse(enabled.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
        enabled.prefetch(server)
        assertTrue(enabled.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
        assertTrue(WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)

        val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        missing.prefetch(server)
        assertFalse(missing.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))

        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        assertFalse(failed.prefetch(server).resolved)
        assertFalse(failed.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
    }

    @Test
    fun concurrentColdStartPrefetchesShareOneEvaluation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse {
                    calls += 1
                    started.complete(Unit)
                    release.await()
                    return ok(
                        """{"evaluations":[{"key":"$WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY","enabled":false}]}"""
                    )
                }
            }
        val flags = SharedServerFeatureFlags(api)
        val first = async { flags.prefetch(server, evaluationContext = "env-a") }
        started.await()
        // UNDISPATCHED: the owner runs `evaluate` on the flags' own Dispatchers.Default scope, so once
        // `release` completes it can finish and drop the in-flight entry in parallel with this thread.
        // A dispatched `async` only *queues* the second caller; if the owner wins that race the second
        // caller finds no flight to join and issues its own evaluate (calls == 2, task #1129). Running
        // the second caller synchronously up to its first suspension registers it as a waiter before
        // the owner is released, which is the scenario this test is about.
        val second = async(start = CoroutineStart.UNDISPATCHED) { flags.prefetch(server, evaluationContext = "env-a") }
        release.complete(Unit)

        assertTrue(first.await().resolved)
        assertTrue(second.await().resolved)
        assertEquals(1, calls)
        assertFalse(flags.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedEvaluation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse {
                    calls += 1
                    started.complete(Unit)
                    release.await()
                    return ok("{}")
                }
            }
        val flags = SharedServerFeatureFlags(api)
        val owner = launch { flags.prefetch(server) }
        started.await()
        val survivor = async(start = CoroutineStart.UNDISPATCHED) { flags.prefetch(server) }
        owner.cancel()
        owner.join()
        release.complete(Unit)

        assertTrue(survivor.await().resolved)
        assertEquals(1, calls)
    }

    @Test
    fun cancellingAllWaitersCannotPublishALateSharedResponse() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstReturned = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val observed = mutableListOf<Boolean>()
        val api =
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse {
                    when (++calls) {
                        1 -> {
                            firstStarted.complete(Unit)
                            withContext(NonCancellable) { releaseFirst.await() }
                            firstReturned.complete(Unit)
                            return ok(
                                """{"evaluations":[{"key":"$WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY","enabled":false}]}"""
                            )
                        }
                        else -> {
                            secondStarted.complete(Unit)
                            return ok(
                                """{"evaluations":[{"key":"$WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY","enabled":true}]}"""
                            )
                        }
                    }
                }
            }
        val flags =
            SharedServerFeatureFlags(api) { _, key, enabled, _, _, _, _ ->
                if (key == WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY) {
                    observed += enabled
                }
            }
        val first = launch { flags.prefetch(server) }
        firstStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { flags.prefetch(server) }
        first.cancel()
        second.cancel()
        first.join()
        second.join()

        releaseFirst.complete(Unit)
        val fresh = async { flags.prefetch(server) }
        secondStarted.await()
        assertTrue(fresh.await().isEnabled(WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
        firstReturned.await()

        assertEquals(2, calls)
        assertEquals(listOf(true), observed)
        assertTrue(flags.isEnabled(server, WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY))
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

        val first = async { flags.prefetch(server, forceRefresh = true) }
        firstStarted.await()
        val second = async { flags.prefetch(server, forceRefresh = true) }
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

        val first = async { flags.prefetch(server, forceRefresh = true) }
        firstStarted.await()
        val second = async { flags.prefetch(server, forceRefresh = true) }
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

        val prefetch = launch { flags.prefetch(server, forceRefresh = true) }
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
        // so a mobile-only rollout (e.g. sync_core_messages_v1) targets this client and not web.
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        SlockFeatureFlagsNetworkApi(client).evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = client.lastBody ?: error("no request body captured")
        assertEquals("feature-flags/evaluate", client.lastPath)
        assertTrue(body.contains("\"platform\":\"mobile\""), "request must send platform=mobile, got: $body")
        assertTrue(body.contains("\"serverId\":\"s1\""), "request must send serverId, got: $body")
    }

    @Test
    fun networkApiSendsClientContextForOsAndBuildTargeting() = runBlocking {
        // task #1144: server rules can require an OS and a minimum build; the client reports both.
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        SlockFeatureFlagsNetworkApi(client) {
            FeatureFlagClientContext(os = "android", appVersion = "1.12.0", buildNumber = "1120131", buildType = "alpha")
        }.evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = Json.parseToJsonElement(client.lastBody ?: error("no request body captured")).jsonObject
        assertEquals("android", body["os"]?.jsonPrimitive?.content)
        assertEquals("1.12.0", body["appVersion"]?.jsonPrimitive?.content)
        assertEquals("1120131", body["buildNumber"]?.jsonPrimitive?.content)
        assertEquals("alpha", body["buildType"]?.jsonPrimitive?.content)
        assertEquals("mobile", body["platform"]?.jsonPrimitive?.content)
    }

    @Test
    fun missingAppIdentityIsSentAsNullNotAsAGuess() = runBlocking {
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        SlockFeatureFlagsNetworkApi(client) {
            FeatureFlagClientContext(os = "ios", appVersion = null, buildNumber = null, buildType = null)
        }.evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = Json.parseToJsonElement(client.lastBody ?: error("no request body captured")).jsonObject
        assertEquals("ios", body["os"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["buildNumber"])
        assertEquals(JsonNull, body["appVersion"])
    }

    @Test
    fun productionClientContextReadsTheUserAgentIdentity() {
        val previous = NetworkUserAgent.appIdentity
        try {
            NetworkUserAgent.appIdentity =
                NetworkAppIdentity(name = "Raft", version = " 1.12.0 ", buildNumber = "1120131", buildType = "")
            val context = currentFeatureFlagClientContext()
            assertTrue(context.os in setOf("android", "ios", "ohos"), context.os)
            assertEquals("1.12.0", context.appVersion)
            assertEquals("1120131", context.buildNumber)
            assertEquals(null, context.buildType) // blank is unknown, not ""

            NetworkUserAgent.appIdentity = null
            assertEquals(null, currentFeatureFlagClientContext().buildNumber)
        } finally {
            NetworkUserAgent.appIdentity = previous
        }
    }
}

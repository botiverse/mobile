package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.RaftAppIdentity
import build.raft.app.network.RaftAppIdentityRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedServerFeatureFlagsTest {

    private val server = ServerId("s1")

    private class FakeApi(private val response: HomeNetworkResponse) : RaftFeatureFlagsApi {
        var lastKeys: List<String>? = null
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
            lastKeys = keys
            return response
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    @Test
    fun prefetchResolvesEnabledFlagAndDefaultsUnknownOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_notification_prefs_v0","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api)
        val snapshot = flags.prefetch(server)
        assertTrue(snapshot.resolved)
        assertTrue(flags.isEnabled(server, SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY))
        assertFalse(flags.isEnabled(server, SERVER_LABS_UI_FEATURE_FLAG_KEY)) // absent from evaluations -> off
        assertFalse(flags.isEnabled(server, SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY))
        assertEquals(REGISTERED_SERVER_FEATURE_FLAG_KEYS, api.lastKeys) // batch: all registered keys
    }

    @Test
    fun activityInPlaceV1DefaultsOnAndHonorsBothRemoteOffSwitches() = runTest {
        assertEquals("mobile_activity_inplace_v1", MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertTrue(MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertTrue(MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        // Before evaluation (unresolved) and when server reports missing_flag: default ON.
        val missingFlags =
            SharedServerFeatureFlags(
                FakeApi(
                    ok(
                        """{"evaluations":[
                            {"key":"$MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"missing_flag"},
                            {"key":"$MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY","enabled":false,"reason":"default"}
                        ]}"""
                    )
                )
            )
        assertTrue(missingFlags.isActivityInPlaceEnabled(server), "unresolved defaults ON")
        missingFlags.prefetch(server)
        assertTrue(missingFlags.isActivityInPlaceEnabled(server), "missing_flag + legacy default-false stays ON")

        // Explicit server off on mobile_activity_inplace_v1 (flag_disabled, kill_switch, server_rule, default) turns it OFF.
        for (reason in listOf("flag_disabled", "kill_switch", "server_rule", "default")) {
            val disabled =
                SharedServerFeatureFlags(
                    FakeApi(
                        ok(
                            """{"evaluations":[
                                {"key":"$MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"$reason"},
                                {"key":"$MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY","enabled":false,"reason":"default"}
                            ]}"""
                        )
                    )
                )
            disabled.prefetch(server)
            assertFalse(disabled.isActivityInPlaceEnabled(server), "v1 off ($reason) disables path")
        }

        // Legacy mobile_inbox_inplace_v0 kill_switch does NOT disable 1.30: mobile_activity_inplace_v1 is the sole off-switch.
        val legacyKilled =
            SharedServerFeatureFlags(
                FakeApi(
                    ok(
                        """{"evaluations":[
                            {"key":"$MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"missing_flag"},
                            {"key":"$MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}
                        ]}"""
                    )
                )
            )
        legacyKilled.prefetch(server)
        assertTrue(legacyKilled.isActivityInPlaceEnabled(server), "legacy v0 kill_switch does not disable 1.30 path")

        // Local diagnostic override can force mobile_activity_inplace_v1 OFF.
        val overridden =
            SharedServerFeatureFlags(
                api = FakeApi(ok("""{"evaluations":[{"key":"$MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"missing_flag"}]}""")),
                diagnosticOverride = { _, key, eff -> if (key == MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY) false else eff },
            )
        overridden.prefetch(server)
        assertFalse(overridden.isActivityInPlaceEnabled(server), "diagnostic override force-off takes effect")

        // Explicit off on mobile_activity_inplace_v1 persists across offline cold start via lastKnown.
        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
        fun process(api: RaftFeatureFlagsApi) =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }),
            )
        process(
            FakeApi(
                ok("""{"evaluations":[{"key":"$MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"flag_disabled"}]}""")
            )
        ).prefetch(server)
        val coldStart = process(FakeApi(HomeNetworkResponse(code = 0, bodyText = null)))
        assertFalse(coldStart.isActivityInPlaceEnabled(server), "persisted off-switch holds across offline cold start")
    }

    @Test
    fun diagnosticOverrideHookCanForceAServerEnabledFlagOff() = runBlocking {
        val api = FakeApi(ok("""{"evaluations":[{"key":"sync_core_notification_prefs_v0","enabled":true}]}"""))
        val flags = SharedServerFeatureFlags(api, diagnosticOverride = { _, _, _ -> false })
        flags.prefetch(server)
        assertFalse(flags.isEnabled(server, SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY))
        assertTrue(flags.snapshotFor(server).isEnabled(SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY))
    }

    @Test
    fun ordinaryRegisteredFlagsRequireExplicitResolvedEnablement() = runTest {
        // Task v2, Drafts, and Agent usage are always on in the app: their flags are no longer asked for.
        assertFalse("task_v2_conversations_v0" in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse("drafts_section_v0" in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse("agent_usage_v0" in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        // slock staging 82ec87f2 packages/shared/src/featureFlags.ts SLACK_BRIDGE_FEATURE_FLAG_KEYS.master.
        assertEquals("slack_bridge_v0", SLACK_BRIDGE_FEATURE_FLAG_KEY)
        // slock staging ee41b3f3 packages/server/src/services/featureFlagService.ts PERSONAL_ASSISTANT_FEATURE_FLAG_KEY.
        assertEquals("personal_assistant", PERSONAL_ASSISTANT_FEATURE_FLAG_KEY)
        // New mobile switch for server setup.
        assertEquals("mobile_server_setup_v1", MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY)
        // botiverse/slock packages/shared/src/featureFlags.ts SERVER_LABS_UI_FEATURE_FLAG_KEY (default off).
        assertEquals("server_labs_ui_v0", SERVER_LABS_UI_FEATURE_FLAG_KEY)
        // #proj-workspace:#356: mobile Login-with-Raft in-app browser catch (default off).
        assertEquals("mobile_raft_sign_in_v0", MOBILE_RAFT_SIGN_IN_FEATURE_FLAG_KEY)
        // #wg-mobile-feedback:#276 screenshot -> quick feedback sheet allowlist (default off).
        assertEquals("mobile_screenshot_feedback_v0", MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY)
        assertFalse(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        // dm:@artin:#6 standalone cross-platform Hybrid WebView Container (default off, last-known persisted for cold start).
        assertEquals("mobile_web_container_v1", MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertTrue(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)
        assertFalse(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        // #proj-mobile:#379: Android silent FCM reachability probe + dual-engine fallback (default off).
        assertEquals("mobile_fcm_probe_v0", MOBILE_FCM_PROBE_FEATURE_FLAG_KEY)
        assertFalse(MOBILE_FCM_PROBE_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        // botiverse/slock #16715: per-server wide rail places gate (default off when unresolved/missing/false).
        assertEquals("rail_places_v0", RAIL_PLACES_V0_FEATURE_FLAG_KEY)
        assertFalse(RAIL_PLACES_V0_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(RAIL_PLACES_V0_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)
        // botiverse/slock #16964 / #proj-mobile:#329: phone & native tablet/foldable navigation places gate (default off).
        assertEquals("tab_bar_places_v0", TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY)
        assertFalse(TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)
        for (
            key in listOf(
                MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY,
                SLACK_BRIDGE_FEATURE_FLAG_KEY,
                PERSONAL_ASSISTANT_FEATURE_FLAG_KEY,
                SERVER_LABS_UI_FEATURE_FLAG_KEY,
                MOBILE_RAFT_SIGN_IN_FEATURE_FLAG_KEY,
                MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY,
                MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY,
                MOBILE_FCM_PROBE_FEATURE_FLAG_KEY,
                RAIL_PLACES_V0_FEATURE_FLAG_KEY,
                TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY,
            )
        ) {
            assertTrue(key in REGISTERED_SERVER_FEATURE_FLAG_KEYS, key)
            val enabled = SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$key","enabled":true,"reason":"server_rule"}]}"""))
            )
            assertFalse(enabled.isEnabled(server, key), key)
            enabled.prefetch(server)
            assertTrue(enabled.isEnabled(server, key), key)
            // Per server: another server stays off until it is evaluated itself.
            assertFalse(enabled.isEnabled(ServerId("s2"), key), key)

            val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
            missing.prefetch(server)
            assertFalse(missing.isEnabled(server, key), key)

            val defaultOff = SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$key","enabled":false,"reason":"default"}]}"""))
            )
            defaultOff.prefetch(server)
            assertFalse(defaultOff.isEnabled(server, key), key)

            val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
            assertFalse(failed.prefetch(server).resolved, key)
            assertFalse(failed.isEnabled(server, key), key)
        }
    }

    @Test
    fun activityFlagsKeepTheirLastValueForAColdStart() = runTest {
        // #triage:#244: the Activity list and badge must not run the off path on a cold start until the
        // evaluation lands (or offline, at all) when the last session had the flags on.
        assertEquals("inbox_row_position_v1", INBOX_ROW_POSITION_FEATURE_FLAG_KEY)
        val keys = listOf(MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY, INBOX_ROW_POSITION_FEATURE_FLAG_KEY)
        keys.forEach { key ->
            assertTrue(key in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS, key)
            assertTrue(key in REGISTERED_SERVER_FEATURE_FLAG_KEYS, key)
        }
        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
        fun process(api: RaftFeatureFlagsApi) =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }),
            )
        val evaluations = keys.joinToString(",") { """{"key":"$it","enabled":true}""" }
        process(FakeApi(ok("""{"evaluations":[$evaluations]}"""))).prefetch(server)

        val coldStart = process(FakeApi(HomeNetworkResponse(code = 0, bodyText = null)))
        keys.forEach { key -> assertTrue(coldStart.isEnabled(server, key), "before the evaluation: $key") }
        coldStart.prefetch(server)
        keys.forEach { key -> assertTrue(coldStart.isEnabled(server, key), "a failed evaluation keeps it: $key") }

        // A server-side kill is followed on the next evaluation, and remembered.
        process(FakeApi(ok("""{"evaluations":[${evaluations.replace("true", "false")}]}"""))).prefetch(server)
        val afterKill = process(FakeApi(HomeNetworkResponse(code = 0, bodyText = null)))
        keys.forEach { key -> assertFalse(afterKill.isEnabled(server, key), "after the kill: $key") }
    }

    @Test
    fun webContainerFlagPersistsInLastKnownStoreHonorsPerServerFalseOmittedKillAndSignOut() = runTest {
        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")
        fun newFlags(api: RaftFeatureFlagsApi) =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }),
            )

        assertFalse(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))

        // Enable on serverA, explicitly disable on serverB.
        newFlags(
            FakeApi(ok("""{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":true}]}"""))
        ).prefetch(serverA)
        newFlags(
            FakeApi(ok("""{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":false}]}"""))
        ).prefetch(serverB)

        // Pre-auth-store cold start (preferredServerId = null) sees true because serverA has it on;
        // preferredServerId = serverB respects explicit false without falling through to serverA!
        assertTrue(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))
        assertTrue(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "server-a"))
        assertFalse(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "server-b"))

        // Omitting the key from server evaluations overwrites stored value to false (never stuck true).
        newFlags(FakeApi(ok("""{"evaluations":[]}"""))).prefetch(serverA)
        assertFalse(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "server-a"))
        assertFalse(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))

        // Sign-out clears persisted last-known storage completely.
        newFlags(
            FakeApi(ok("""{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":true}]}"""))
        ).prefetch(serverA)
        assertTrue(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))
        ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }).clearAll()
        assertFalse(ServerFeatureFlagLastKnownStore.readColdStartFlag(disk, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))
    }

    @Test
    fun decisionStaysPendingUntilAnEvaluationSettlesOrALastKnownValueExists() = runTest {
        // A default-off flag; the cold-start part below keeps it on disk explicitly.
        val key = SERVER_LABS_UI_FEATURE_FLAG_KEY
        class ThrowingApi : RaftFeatureFlagsApi {
            override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                throw IllegalStateException("offline")
        }
        data class Case(val name: String, val api: RaftFeatureFlagsApi, val enabledAfter: Boolean)
        val cases =
            listOf(
                Case("accepted on", FakeApi(ok("""{"evaluations":[{"key":"$key","enabled":true}]}""")), enabledAfter = true),
                Case("accepted off", FakeApi(ok("""{"evaluations":[{"key":"$key","enabled":false}]}""")), enabledAfter = false),
                Case("http failure", FakeApi(HomeNetworkResponse(code = 500, bodyText = null)), enabledAfter = false),
                Case("malformed", FakeApi(ok("not json")), enabledAfter = false),
                Case("transport throws", ThrowingApi(), enabledAfter = false),
            )
        for (case in cases) {
            val flags = SharedServerFeatureFlags(case.api)
            assertTrue(flags.isDecisionPending(server, key), case.name)
            assertFalse(flags.isDecisionUndetermined(server, key), "pending is not undetermined: ${case.name}")
            runCatching { flags.prefetch(server) }
            assertFalse(flags.isDecisionPending(server, key), case.name)
            assertEquals(case.enabledAfter, flags.isEnabled(server, key), case.name)
            // A failed evaluation reads off but is not a known off (web `undetermined`).
            assertEquals(!case.name.startsWith("accepted"), flags.isDecisionUndetermined(server, key), case.name)
            // Per server: another server still waits for its own evaluation.
            assertTrue(flags.isDecisionPending(ServerId("s2"), key), case.name)
        }
        assertFalse(SharedServerFeatureFlags(FakeApi(ok("{}"))).isDecisionPending(ServerId(""), key))

        // Cold start: the value remembered from the last online session decides before any evaluation.
        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
        fun process(api: RaftFeatureFlagsApi) =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }, keys = setOf(key)),
            )
        process(FakeApi(ok("""{"evaluations":[{"key":"$key","enabled":true}]}"""))).prefetch(server)
        val coldStart = process(FakeApi(HomeNetworkResponse(code = 0, bodyText = null)))
        assertFalse(coldStart.isDecisionPending(server, key))
        assertTrue(coldStart.isEnabled(server, key))
        // A failed evaluation with a stored value: the stored value is known, not undetermined.
        coldStart.prefetch(server)
        assertFalse(coldStart.isDecisionUndetermined(server, key))
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
    fun phoneHeaderActionFlagDefaultsOffAndPreservesVariantOnlyWhenEnabled() = runBlocking {
        assertEquals("mobile_phone_header_action_v0", MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)

        val enabled =
            SharedServerFeatureFlags(
                FakeApi(
                    ok(
                        """{"evaluations":[{"key":"$MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY","enabled":true,"variant":" tasks,search "}]}"""
                    )
                )
            )
        assertFalse(enabled.isEnabled(server, MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
        assertEquals(null, enabled.snapshotFor(server).variant(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))

        val enabledSnapshot = enabled.prefetch(server)
        assertTrue(enabledSnapshot.isEnabled(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
        assertEquals("tasks,search", enabledSnapshot.variant(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
        assertFalse(enabled.isEnabled(ServerId("s2"), MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))

        val disabledSnapshot =
            SharedServerFeatureFlags(
                FakeApi(
                    ok(
                        """{"evaluations":[{"key":"$MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY","enabled":false,"variant":"tasks,search"}]}"""
                    )
                )
            ).prefetch(server)
        assertFalse(disabledSnapshot.isEnabled(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
        assertEquals(null, disabledSnapshot.variant(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))

        val blankSnapshot =
            SharedServerFeatureFlags(
                FakeApi(
                    ok(
                        """{"evaluations":[{"key":"$MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY","enabled":true,"variant":"   "}]}"""
                    )
                )
            ).prefetch(server)
        assertTrue(blankSnapshot.isEnabled(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
        assertEquals(null, blankSnapshot.variant(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
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
    fun olderSameServerResponseCannotOverwriteSnapshotButStillCarriesGlobalGeneration() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0
        val api =
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse =
                    when (++callCount) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            ok(
                                """{"evaluations":[{"key":"$SERVER_LABS_UI_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
                            )
                        }
                        else ->
                            ok(
                                """{"evaluations":[{"key":"$SERVER_LABS_UI_FEATURE_FLAG_KEY","enabled":true,"reason":"default"}]}"""
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

        assertTrue(flags.isEnabled(server, SERVER_LABS_UI_FEATURE_FLAG_KEY))
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
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(
                    serverId: ServerId,
                    keys: List<String>
                ): HomeNetworkResponse =
                    when (++callCount) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            ok(
                                """{"evaluations":[{"key":"$SERVER_LABS_UI_FEATURE_FLAG_KEY","enabled":false,"reason":"kill_switch"}]}"""
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

        assertFalse(flags.isEnabled(server, SERVER_LABS_UI_FEATURE_FLAG_KEY))
        assertEquals(listOf(false to "kill_switch"), evaluations.map { it.first to it.second })
        assertTrue(evaluations.single().third > 0L)
    }

    @Test
    fun failedFetchLeavesFlagsOffNeverThrows() = runBlocking {
        val flags = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        val snapshot = flags.prefetch(server)
        assertFalse(snapshot.resolved) // failed -> stays Unresolved, ordinary flags default off (feature stays dark)
        assertFalse(flags.isEnabled(server, SERVER_LABS_UI_FEATURE_FLAG_KEY))
    }

    @Test
    fun malformedBodyDoesNotThrow() = runBlocking {
        val flags = SharedServerFeatureFlags(FakeApi(ok("not-json")))
        assertFalse(flags.prefetch(server).resolved)
    }

    // Captures the raw request body the network API encodes, so we can assert the wire contract directly.
    private class BodyCapturingClient(private val response: HomeNetworkResponse) : RaftNetworkClient {
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
        // so a mobile-only rollout targets this client and not web.
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        RaftFeatureFlagsNetworkApi(client).evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = client.lastBody ?: error("no request body captured")
        assertEquals("feature-flags/evaluate", client.lastPath)
        assertTrue(body.contains("\"platform\":\"mobile\""), "request must send platform=mobile, got: $body")
        assertTrue(body.contains("\"serverId\":\"s1\""), "request must send serverId, got: $body")
    }

    @Test
    fun networkApiSendsClientContextForOsAndBuildTargeting() = runBlocking {
        // task #1144: server rules can require an OS and a minimum build; the client reports both.
        val client = BodyCapturingClient(ok("""{"evaluations":[]}"""))
        RaftFeatureFlagsNetworkApi(client) {
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
        RaftFeatureFlagsNetworkApi(client) {
            FeatureFlagClientContext(os = "ios", appVersion = null, buildNumber = null, buildType = null)
        }.evaluate(server, REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val body = Json.parseToJsonElement(client.lastBody ?: error("no request body captured")).jsonObject
        assertEquals("ios", body["os"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["buildNumber"])
        assertEquals(JsonNull, body["appVersion"])
    }

    @Test
    fun networkApiSendsSingleRequestWhenWithinServerBatchLimit() = runBlocking {
        val capturedKeys = mutableListOf<List<String>>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse {
                    val obj = Json.parseToJsonElement(bodyText ?: error("missing body")).jsonObject
                    capturedKeys += obj["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
                    return ok("""{"evaluations":[]}""")
                }
            }
        val exactLimitKeys = (1..FEATURE_FLAG_EVALUATE_MAX_BATCH_SIZE).map { "flag_$it" }
        val response = RaftFeatureFlagsNetworkApi(client).evaluate(server, exactLimitKeys)
        assertEquals(200, response.code)
        assertEquals(1, capturedKeys.size)
        assertEquals(exactLimitKeys, capturedKeys.single())
    }

    @Test
    fun networkApiChunksRequestsAtServerBatchLimitAndMergesEvaluations() = runBlocking {
        val lock = SynchronizedObject()
        val capturedBatches = mutableListOf<List<String>>()
        val capturedBodies = mutableListOf<String>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse {
                    val raw = bodyText ?: error("missing body")
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    val batchKeys = obj["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
                    synchronized(lock) {
                        capturedBodies += raw
                        capturedBatches += batchKeys
                    }
                    assertTrue(batchKeys.size in 1..FEATURE_FLAG_EVALUATE_MAX_BATCH_SIZE, "batch exceeded 50 keys: ${batchKeys.size}")
                    val evals = batchKeys.joinToString(",") { key ->
                        val enabled = key == "flag_1" || key == "flag_75"
                        val variant = if (key == "flag_75") ""","variant":"v75"""" else ""
                        """{"key":"$key","enabled":$enabled,"reason":"server_rule"$variant}"""
                    }
                    return ok("""{"evaluations":[$evals]}""")
                }
            }
        val manyKeys = (1..75).map { "flag_$it" } + listOf("flag_1", "flag_75")
        val api =
            RaftFeatureFlagsNetworkApi(client) {
                FeatureFlagClientContext(os = "android", appVersion = "1.31.0", buildNumber = "13100002", buildType = "alpha")
            }
        val flags = SharedServerFeatureFlags(api = api, keys = manyKeys)
        val snapshot = flags.prefetch(server)

        assertTrue(snapshot.resolved)
        assertEquals(2, capturedBatches.size)
        assertEquals(listOf(25, 50), capturedBatches.map { it.size }.sorted())
        assertEquals(75, capturedBatches.flatten().size)
        assertEquals((1..75).map { "flag_$it" }.toSet(), capturedBatches.flatten().toSet())
        for (raw in capturedBodies) {
            val obj = Json.parseToJsonElement(raw).jsonObject
            assertEquals("mobile", obj["platform"]?.jsonPrimitive?.content)
            assertEquals("s1", obj["serverId"]?.jsonPrimitive?.content)
            assertEquals("android", obj["os"]?.jsonPrimitive?.content)
            assertEquals("1.31.0", obj["appVersion"]?.jsonPrimitive?.content)
            assertEquals("13100002", obj["buildNumber"]?.jsonPrimitive?.content)
            assertEquals("alpha", obj["buildType"]?.jsonPrimitive?.content)
        }
        assertTrue(flags.isEnabled(server, "flag_1"))
        assertFalse(flags.isEnabled(server, "flag_50"))
        assertTrue(flags.isEnabled(server, "flag_75"))
        assertEquals("v75", snapshot.variant("flag_75"))
    }

    @Test
    fun networkApiFailsClosedWhenAnyChunkFailsHttp() = runBlocking {
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse =
                    if (bodyText?.contains("\"flag_51\"") == true) {
                        HomeNetworkResponse(code = 500, bodyText = null)
                    } else {
                        ok("""{"evaluations":[{"key":"flag_1","enabled":true}]}""")
                    }
            }
        val keys = (1..60).map { "flag_$it" }
        val flags = SharedServerFeatureFlags(api = RaftFeatureFlagsNetworkApi(client), keys = keys)
        val snapshot = flags.prefetch(server)
        assertFalse(snapshot.resolved)
        assertFalse(flags.isEnabled(server, "flag_1"))
    }

    @Test
    fun networkApiFailsClosedWhenAnyChunkIsMalformedJson() = runBlocking {
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse =
                    if (bodyText?.contains("\"flag_51\"") == true) {
                        ok("not-json")
                    } else {
                        ok("""{"evaluations":[{"key":"flag_1","enabled":true}]}""")
                    }
            }
        val keys = (1..60).map { "flag_$it" }
        val flags = SharedServerFeatureFlags(api = RaftFeatureFlagsNetworkApi(client), keys = keys)
        val snapshot = flags.prefetch(server)
        assertFalse(snapshot.resolved)
        assertFalse(flags.isEnabled(server, "flag_1"))
    }

    @Test
    fun productionClientContextReadsTheUserAgentIdentity() {
        try {
            RaftAppIdentityRegistry.install(
                RaftAppIdentity(name = "Raft", version = " 1.12.0 ", buildNumber = "1120131", buildType = "")
            )
            val context = currentFeatureFlagClientContext()
            assertTrue(context.os in setOf("android", "ios", "ohos"), context.os)
            assertEquals("1.12.0", context.appVersion)
            assertEquals("1120131", context.buildNumber)
            assertEquals(null, context.buildType) // blank is unknown, not ""

            RaftAppIdentityRegistry.install(null)
            // Android installs no platform fallback, so the identity stays unknown; iOS reads its bundle.
            if (currentFeatureFlagClientContext().os == "android") {
                assertEquals(null, currentFeatureFlagClientContext().buildNumber)
            }
        } finally {
            RaftAppIdentityRegistry.resetForTest()
        }
    }

    @Test
    fun earlyAccessFlagsDefaultOffAggregateAcrossServersAndPersistLastKnown() = runBlocking {
        assertEquals("mobile_early_access_visible_v0", MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY)
        assertEquals("mobile_early_access_default_on_v0", MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertTrue(MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertTrue(MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)
        assertTrue(MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val disk = InMemoryRaftKeyValueStore()
        val binding = ServerFeatureFlagLastKnownBinding("https://api.raft.build", "user-1")
        val api =
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    when (serverId.value) {
                        "srv-default-only" ->
                            ok(
                                """{"evaluations":[{"key":"$MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY","enabled":false},{"key":"$MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY","enabled":true}]}"""
                            )
                        "srv-visible-only" ->
                            ok(
                                """{"evaluations":[{"key":"$MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY","enabled":true},{"key":"$MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY","enabled":false}]}"""
                            )
                        "srv-both" ->
                            ok(
                                """{"evaluations":[{"key":"$MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY","enabled":true},{"key":"$MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY","enabled":true}]}"""
                            )
                        else -> ok("""{"evaluations":[]}""")
                    }
            }

        val flags =
            SharedServerFeatureFlags(
                api = api,
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }),
            )
        assertEquals(RaftEarlyAccessFlagPolicy(), flags.earlyAccessPolicy(emptyList()))
        assertEquals(RaftEarlyAccessFlagPolicy(), flags.earlyAccessPolicy(listOf("srv-none")))

        // defaultOn without visible stays false/false.
        flags.prefetch(ServerId("srv-default-only"))
        assertEquals(
            RaftEarlyAccessFlagPolicy(visible = false, defaultOn = false),
            flags.earlyAccessPolicy(listOf("srv-default-only"))
        )

        // visible-only yields visible=true, defaultOn=false.
        flags.prefetch(ServerId("srv-visible-only"))
        assertEquals(
            RaftEarlyAccessFlagPolicy(visible = true, defaultOn = false),
            flags.earlyAccessPolicy(listOf("srv-none", "srv-visible-only"))
        )

        // Joining a server with both enabled turns on visible + defaultOn across the account.
        flags.prefetch(ServerId("srv-both"))
        assertEquals(
            RaftEarlyAccessFlagPolicy(visible = true, defaultOn = true),
            flags.earlyAccessPolicy(listOf("srv-none", "srv-both"))
        )

        // Cold restart reads persisted last-known evaluations offline until refreshed.
        val coldStart =
            SharedServerFeatureFlags(
                api = FakeApi(HomeNetworkResponse(code = 0, bodyText = null)),
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }),
            )
        assertEquals(
            RaftEarlyAccessFlagPolicy(visible = true, defaultOn = true),
            coldStart.earlyAccessPolicy(listOf("srv-none", "srv-both"))
        )
    }

    @Test
    fun clientErrorAndWebViewTraceSampleFlagsAreRegisteredAndEvaluated() = runTest {
        val expectedKeys =
            listOf(
                "trace_sample.mobile.http_error",
                "trace_sample.mobile.ui_error",
                "trace_sample.mobile.webview",
                "trace_sample.mobile.web_container",
                "trace_sample.mobile",
                "trace_sample",
            )
        for (key in expectedKeys) {
            assertTrue(key in REGISTERED_SERVER_FEATURE_FLAG_KEYS, "missing registered key: $key")
        }
        val api =
            FakeApi(
                ok(
                    """{"evaluations":[
                        {"key":"trace_sample.mobile.http_error","enabled":true,"reason":"platform_rule","variant":"0.5"},
                        {"key":"trace_sample.mobile.ui_error","enabled":false,"reason":"kill_switch"},
                        {"key":"trace_sample.mobile.webview","enabled":true,"reason":"platform_rule","variant":"0.2"},
                        {"key":"trace_sample.mobile.web_container","enabled":true,"reason":"platform_rule","variant":"0.75"}
                    ]}"""
                )
            )
        val snapshot = SharedServerFeatureFlags(api).prefetch(server)
        assertEquals(0.5, resolveActiveServerTraceSampleRate(server.value, MOBILE_HTTP_ERROR_TRACE_NAME, 1.0, flags = snapshot))
        assertEquals(0.0, resolveActiveServerTraceSampleRate(server.value, MOBILE_UI_ERROR_TRACE_NAME, 1.0, flags = snapshot))
        assertEquals(0.2, resolveActiveServerTraceSampleRate(server.value, MOBILE_WEBVIEW_TRACE_NAME, 0.1, flags = snapshot))
        assertEquals(0.75, resolveActiveServerTraceSampleRate(server.value, MOBILE_WEB_CONTAINER_TRACE_NAME, 1.0, flags = snapshot))
    }

    @Test
    fun workerV1FlagDefaultsOffAndFollowsPerServerEvaluation() = runTest {
        assertEquals("worker_v1", WORKER_V1_FEATURE_FLAG_KEY)
        assertTrue(WORKER_V1_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(WORKER_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(WORKER_V1_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val enabled =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$WORKER_V1_FEATURE_FLAG_KEY","enabled":true,"reason":"server_rule"}]}"""))
            )
        assertFalse(enabled.isWorkerV1Enabled(null))
        assertFalse(enabled.isWorkerV1Enabled(server), "unresolved defaults OFF")
        enabled.prefetch(server)
        assertTrue(enabled.isWorkerV1Enabled(server))
        assertFalse(enabled.isWorkerV1Enabled(ServerId("s2")), "other server stays OFF until evaluated")

        val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        missing.prefetch(server)
        assertFalse(missing.isWorkerV1Enabled(server), "missing_flag stays OFF")

        val defaultOff =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$WORKER_V1_FEATURE_FLAG_KEY","enabled":false,"reason":"default"}]}"""))
            )
        defaultOff.prefetch(server)
        assertFalse(defaultOff.isWorkerV1Enabled(server))

        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        assertFalse(failed.prefetch(server).resolved)
        assertFalse(failed.isWorkerV1Enabled(server))

        val overridden =
            SharedServerFeatureFlags(
                api = FakeApi(ok("""{"evaluations":[{"key":"$WORKER_V1_FEATURE_FLAG_KEY","enabled":true}]}""")),
                diagnosticOverride = { _, key, eff -> if (key == WORKER_V1_FEATURE_FLAG_KEY) false else eff },
            )
        overridden.prefetch(server)
        assertFalse(overridden.isWorkerV1Enabled(server))
    }

    @Test
    fun dmMainTimelineLabFlagDefaultsOffAndFollowsPerServerEvaluation() = runTest {
        assertEquals("dm_main_timeline_lab_v0", DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY)
        assertTrue(DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val enabled =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY","enabled":true,"reason":"lab"}]}"""))
            )
        assertFalse(enabled.isDmMainTimelineLabEnabled(null))
        assertFalse(enabled.isDmMainTimelineLabEnabled(server), "unresolved defaults OFF")
        enabled.prefetch(server)
        assertTrue(enabled.isDmMainTimelineLabEnabled(server))
        assertFalse(enabled.isDmMainTimelineLabEnabled(ServerId("s2")), "other server stays OFF until evaluated")

        val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        missing.prefetch(server)
        assertFalse(missing.isDmMainTimelineLabEnabled(server), "missing_flag stays OFF")

        val defaultOff =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY","enabled":false,"reason":"default"}]}"""))
            )
        defaultOff.prefetch(server)
        assertFalse(defaultOff.isDmMainTimelineLabEnabled(server))

        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        assertFalse(failed.prefetch(server).resolved)
        assertFalse(failed.isDmMainTimelineLabEnabled(server))

        val overridden =
            SharedServerFeatureFlags(
                api = FakeApi(ok("""{"evaluations":[{"key":"$DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY","enabled":true}]}""")),
                diagnosticOverride = { _, key, eff -> if (key == DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY) false else eff },
            )
        overridden.prefetch(server)
        assertFalse(overridden.isDmMainTimelineLabEnabled(server))
    }

    @Test
    fun peopleActivityRowsFlagDefaultsOffAndFollowsPerServerEvaluation() = runTest {
        assertEquals("people_activity_rows_v0", PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY)
        assertTrue(PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val enabled =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY","enabled":true,"reason":"server_rule"}]}"""))
            )
        assertFalse(enabled.isPeopleActivityRowsEnabled(null))
        assertFalse(enabled.isPeopleActivityRowsEnabled(server), "unresolved defaults OFF")
        enabled.prefetch(server)
        assertTrue(enabled.isPeopleActivityRowsEnabled(server))
        assertFalse(enabled.isPeopleActivityRowsEnabled(ServerId("s2")))
    }

    @Test
    fun dataExportV0FlagDefaultsOffAndFollowsPerServerAndAccountEvaluation() = runTest {
        assertEquals("data_export_v0", DATA_EXPORT_FEATURE_FLAG_KEY)
        assertTrue(DATA_EXPORT_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(DATA_EXPORT_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(DATA_EXPORT_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val api =
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    when (serverId.value) {
                        "s1" ->
                            ok("""{"evaluations":[{"key":"$DATA_EXPORT_FEATURE_FLAG_KEY","enabled":false,"reason":"default"}]}""")
                        "s2" ->
                            ok("""{"evaluations":[{"key":"$DATA_EXPORT_FEATURE_FLAG_KEY","enabled":true,"reason":"user_rule"}]}""")
                        else -> ok("""{"evaluations":[]}""")
                    }
            }
        val flags = SharedServerFeatureFlags(api)

        assertFalse(flags.isDataExportEnabled(null))
        assertFalse(flags.isDataExportEnabled(ServerId("s1")), "unresolved defaults OFF")
        assertFalse(flags.isDataExportEnabledForAccount(emptyList()))
        assertFalse(flags.isDataExportEnabledForAccount(listOf("s1", "s2")), "unresolved account defaults OFF")

        flags.prefetch(ServerId("s1"))
        assertFalse(flags.isDataExportEnabled(ServerId("s1")))
        assertFalse(flags.isDataExportEnabledForAccount(listOf("s1", "s2")))

        flags.prefetch(ServerId("s2"))
        assertTrue(flags.isDataExportEnabled(ServerId("s2")))
        assertFalse(flags.isDataExportEnabled(ServerId("s1")))
        assertTrue(flags.isDataExportEnabledForAccount(listOf("s1", "s2")))
        assertTrue(flags.isDataExportEnabledForAccount(listOf("  s2  ", "")))

        val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        missing.prefetch(server)
        assertFalse(missing.isDataExportEnabled(server), "missing_flag stays OFF")

        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        assertFalse(failed.prefetch(server).resolved)
        assertFalse(failed.isDataExportEnabled(server))

        val overridden =
            SharedServerFeatureFlags(
                api = FakeApi(ok("""{"evaluations":[{"key":"$DATA_EXPORT_FEATURE_FLAG_KEY","enabled":true}]}""")),
                diagnosticOverride = { _, key, eff -> if (key == DATA_EXPORT_FEATURE_FLAG_KEY) false else eff },
            )
        overridden.prefetch(server)
        assertFalse(overridden.isDataExportEnabled(server))
    }

    @Test
    fun privateMentionAddFlagDefaultsOffAndFollowsPerServerEvaluation() = runTest {
        assertEquals("private_mention_add_v0", PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY)
        assertTrue(PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
        assertFalse(PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS)

        val enabled =
            SharedServerFeatureFlags(
                FakeApi(ok("""{"evaluations":[{"key":"$PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY","enabled":true,"reason":"server_rule"}]}"""))
            )
        assertFalse(enabled.isPrivateMentionAddEnabled(null))
        assertFalse(enabled.isPrivateMentionAddEnabled(server), "unresolved defaults OFF")
        enabled.prefetch(server)
        assertTrue(enabled.isPrivateMentionAddEnabled(server))
        assertFalse(enabled.isPrivateMentionAddEnabled(ServerId("s2")), "other server stays OFF until evaluated")

        val missing = SharedServerFeatureFlags(FakeApi(ok("""{"evaluations":[]}""")))
        missing.prefetch(server)
        assertFalse(missing.isPrivateMentionAddEnabled(server), "missing_flag stays OFF")

        val failed = SharedServerFeatureFlags(FakeApi(HomeNetworkResponse(code = 500, bodyText = null)))
        assertFalse(failed.prefetch(server).resolved)
        assertFalse(failed.isPrivateMentionAddEnabled(server))

        val overridden =
            SharedServerFeatureFlags(
                api = FakeApi(ok("""{"evaluations":[{"key":"$PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY","enabled":true}]}""")),
                diagnosticOverride = { _, key, eff -> if (key == PRIVATE_MENTION_ADD_FEATURE_FLAG_KEY) false else eff },
            )
        overridden.prefetch(server)
        assertFalse(overridden.isPrivateMentionAddEnabled(server))
    }
}

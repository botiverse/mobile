package build.raft.app.desktop

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import build.raft.app.data.toDiagnosticsHostSnapshot
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import build.raft.app.parseRaftJsonObjectOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Desktop `settingsCapability` diagnostic feature-flag overrides (`loadDiagnostics` +
 * `setDiagnosticFeatureFlagOverride`): strictly gated behind `raft.desktop.debug=true` or
 * explicit `-Draft.desktop.diagnosticOverrides=true` so public `.dmg` builds stay dark.
 */
@OptIn(RaftCoreInternalApi::class)
class RaftDesktopDiagnosticOverridesTest {
    private var nowMillis = 10_000L

    private fun newFixture(): Pair<DiagnosticFeatureFlagOverrideRegistry, ReadStateV1DiagnosticOverrideController> {
        val readState = ReadStateV1DiagnosticOverrideController { nowMillis }
        val registry = DiagnosticFeatureFlagOverrideRegistry({ nowMillis }, readState)
        return registry to readState
    }

    @Test
    fun `entitlement requires debug or explicit diagnosticOverrides true`() {
        assertFalse(RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { null })
        assertFalse(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(
                    RaftDesktopSettingsCapability.DESKTOP_DEBUG_PROPERTY to "false",
                    RaftDesktopSettingsCapability.DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY to "false",
                )[key]
            },
        )
        assertFalse(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(RaftDesktopSettingsCapability.DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY to "1")[key]
            },
        )
        assertFalse(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(RaftDesktopSettingsCapability.DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY to "yes")[key]
            },
        )
        assertTrue(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(RaftDesktopSettingsCapability.DESKTOP_DEBUG_PROPERTY to "true")[key]
            },
        )
        assertTrue(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(RaftDesktopSettingsCapability.DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY to "true")[key]
            },
        )
        assertTrue(
            RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled { key ->
                mapOf(RaftDesktopSettingsCapability.DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY to " TRUE ")[key]
            },
        )
    }

    @Test
    fun `release build stays unentitled and rejects override mutations`() {
        val (registry, readState) = newFixture()
        val diagnostics =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                lines = listOf("2026-11-25T10:30:00.000Z I [main] Desktop: started"),
                registry = registry,
                readStateController = readState,
                entitled = false,
            )
        val parsed = diagnostics.toDiagnosticsHostSnapshot()
        assertEquals(1, parsed.entries.size)
        val webContainer = parsed.featureFlagOverrides.firstOrNull { it.key == MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY }
        assertNotNull(webContainer)
        assertTrue(webContainer.supported)
        assertFalse(webContainer.entitled)
        assertEquals("Server", webContainer.clientOverride)
        assertFalse(parsed.readStateV1.entitled)

        val rejected =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "force_on"),
                registry = registry,
                entitled = false,
            )
        assertEquals("error", rejected["status"]?.jsonPrimitive?.content)
        assertEquals("diagnostic_feature_flag_override_not_entitled", rejected["code"]?.jsonPrimitive?.content)
        assertEquals(DiagnosticFeatureFlagOverrideMode.Server, registry.snapshot(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY).mode)
    }

    @Test
    fun `entitled build toggles mobile_web_container_v1 and round-trips through BridgeAuth parser`() {
        val (registry, readState) = newFixture()

        val appliedOn =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "force_on"),
                registry = registry,
                entitled = true,
            )
        assertEquals("ok", appliedOn["status"]?.jsonPrimitive?.content)
        assertEquals(DiagnosticFeatureFlagOverrideMode.ForceOn, registry.snapshot(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY).mode)

        val diagOn =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                lines = emptyList(),
                registry = registry,
                readStateController = readState,
                entitled = true,
            ).toDiagnosticsHostSnapshot()
        val flagOn = diagOn.featureFlagOverrides.first { it.key == MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY }
        assertTrue(flagOn.supported)
        assertTrue(flagOn.entitled)
        assertEquals("ForceOn", flagOn.clientOverride)
        assertEquals(true, flagOn.finalEffective)
        assertEquals("diagnostic_override_force_on", flagOn.precedence)
        assertNotNull(flagOn.expiresAtMillis)

        val appliedOff =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "force_off"),
                registry = registry,
                entitled = true,
            )
        assertEquals("ok", appliedOff["status"]?.jsonPrimitive?.content)
        val flagOff =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                lines = emptyList(),
                registry = registry,
                readStateController = readState,
                entitled = true,
            ).toDiagnosticsHostSnapshot().featureFlagOverrides.first { it.key == MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY }
        assertEquals("ForceOff", flagOff.clientOverride)
        assertEquals(false, flagOff.finalEffective)
        assertEquals("diagnostic_override_force_off", flagOff.precedence)

        val appliedServer =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "server"),
                registry = registry,
                entitled = true,
            )
        assertEquals("ok", appliedServer["status"]?.jsonPrimitive?.content)
        val flagServer =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                lines = emptyList(),
                registry = registry,
                readStateController = readState,
                entitled = true,
            ).toDiagnosticsHostSnapshot().featureFlagOverrides.first { it.key == MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY }
        assertEquals("Server", flagServer.clientOverride)
        assertNull(flagServer.finalEffective)
        assertEquals("server_mode_selected", flagServer.clearReason)
    }

    @Test
    fun `invalid mode and unsupported or malformed keys return explicit error codes`() {
        val (registry, _) = newFixture()

        val badMode =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "always"),
                registry = registry,
                entitled = true,
            )
        assertEquals("error", badMode["status"]?.jsonPrimitive?.content)
        assertEquals("diagnostic_feature_flag_override_mode_unknown", badMode["code"]?.jsonPrimitive?.content)

        val unsupportedKey =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand("unknown_flag_v1", "force_on"),
                registry = registry,
                entitled = true,
            )
        assertEquals("error", unsupportedKey["status"]?.jsonPrimitive?.content)
        assertEquals("diagnostic_feature_flag_consumer_unsupported", unsupportedKey["code"]?.jsonPrimitive?.content)

        val invalidKey =
            RaftDesktopSettingsCapability.setDiagnosticFeatureFlagOverridePayload(
                command = overrideCommand("BAD KEY", "force_on"),
                registry = registry,
                entitled = true,
            )
        assertEquals("error", invalidKey["status"]?.jsonPrimitive?.content)
        assertEquals("diagnostic_feature_flag_key_invalid", invalidKey["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `diagnostics snapshots redact raw environment, account, server, and channel identifiers`() {
        val (registry, readState) = newFixture()
        registry.configureEntitlement(true)
        val scope =
            DiagnosticFeatureFlagScope(
                environmentId = "https://secret-env.internal",
                accountId = "acct-secret-999",
                serverId = "srv-secret-888",
                channelId = "chan-secret-777",
            )
        registry.observe(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, scope.copy(channelId = null))
        registry.observe(SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY, scope)
        registry.decide(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, serverEffective = false, scope = scope.copy(channelId = null))

        val payload =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                lines = emptyList(),
                registry = registry,
                readStateController = readState,
                entitled = true,
            )
        val rawFlagsJson = payload["diagnosticFeatureFlagOverridesJson"]!!.jsonPrimitive.content
        val rawReadStateJson = payload["readStateV1DiagnosticJson"]!!.jsonPrimitive.content
        for (secret in listOf("secret-env", "acct-secret-999", "srv-secret-888", "chan-secret-777")) {
            assertFalse(rawFlagsJson.contains(secret), "leaked $secret in feature flags diagnostics")
            assertFalse(rawReadStateJson.contains(secret), "leaked $secret in read-state diagnostics")
        }
        val parsed = payload.toDiagnosticsHostSnapshot()
        val webContainer = parsed.featureFlagOverrides.first { it.key == MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY }
        assertTrue(webContainer.observedScope.startsWith("env=#"))
        assertEquals(false, webContainer.serverEffective)
        assertEquals(false, webContainer.finalEffective)
    }

    @Test
    fun `handle routes setDiagnosticFeatureFlagOverride instead of returning not implemented`() {
        var rawResponse: String? = null
        try {
            RaftDesktopSettingsCapability.handle(
                command = overrideCommand(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, "force_on"),
            ) { rawResponse = it }
            val parsed = assertNotNull(parseRaftJsonObjectOrNull(assertNotNull(rawResponse)))
            val status = parsed["status"]?.jsonPrimitive?.content
            assertTrue(status == "ok" || status == "error", "unexpected status=$status raw=$rawResponse")
            assertFalse(rawResponse == RaftDesktopBridgeTransport.NOT_IMPLEMENTED)
        } finally {
            ProcessDiagnosticFeatureFlagOverrides.registry.clearAll("test_cleanup")
            ProcessDiagnosticFeatureFlagOverrides.registry.configureEntitlement(
                RaftDesktopSettingsCapability.isDiagnosticFeatureFlagOverridesEntitled(),
            )
        }
    }

    private fun overrideCommand(key: String, mode: String): JsonObject =
        JsonObject(
            mapOf(
                "action" to JsonPrimitive("setDiagnosticFeatureFlagOverride"),
                "key" to JsonPrimitive(key),
                "mode" to JsonPrimitive(mode),
            ),
        )
}

package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeSettingsDiagnosticsParserTest {
    @Test
    fun parsesPrimitiveEntriesJsonFallback() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("entriesJson", entriesJson(tag = "SlockAuth/refresh").toString())

        val snapshot = response.toDiagnosticsHostSnapshot()

        assertEquals(1, snapshot.entries.size)
        assertEquals("2026-07-10T03:00:00Z", snapshot.entries.single().timestampLabel)
        assertEquals("SlockAuth/refresh", snapshot.entries.single().tag)
        assertEquals("refresh_success requestId=req-1", snapshot.entries.single().message)
    }

    @Test
    fun structuredEntriesTakePrecedenceForBackwardCompatibility() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("entries", entriesJson(tag = "structured"))
                .put("entriesJson", entriesJson(tag = "fallback").toString())

        val snapshot = response.toDiagnosticsHostSnapshot()

        assertEquals(listOf("structured"), snapshot.entries.map { it.tag })
    }

    @Test
    fun malformedEntriesJsonProducesEmptySnapshot() {
        val response = JSONObject().put("status", "ok").put("entriesJson", "not-json")

        assertEquals(emptyList(), response.toDiagnosticsHostSnapshot().entries)
    }

    @Test
    fun parsesMultipleDiagnosticFeatureFlagOverrides() {
        val flags =
            JSONArray().apply {
                put(
                    JSONObject()
                        .put("key", "sync_core_read_state_v1")
                        .put("supported", true)
                        .put("scopeKind", "ChannelExact")
                        .put("entitled", true)
                        .put("clientOverride", "ForceOn")
                        .put("observedScope", "env/account/server/channel")
                        .put("finalEffective", true)
                        .put("precedence", "diagnostic_override_force_on")
                        .put("evidence", "witnessGeneration=7")
                )
                put(
                    JSONObject()
                        .put("key", "mobile_activity_stable_order_v1")
                        .put("supported", true)
                        .put("scopeKind", "ServerExact")
                        .put("clientOverride", "Server")
                        .put("serverEffective", false)
                        .put("finalEffective", false)
                        .put("precedence", "server")
                )
            }
        val response = JSONObject().put("diagnosticFeatureFlagOverridesJson", flags.toString())

        val parsed = response.toDiagnosticsHostSnapshot().featureFlagOverrides

        assertEquals(listOf("sync_core_read_state_v1", "mobile_activity_stable_order_v1"), parsed.map { it.key })
        assertEquals("ChannelExact", parsed[0].scopeKind)
        assertEquals(true, parsed[0].finalEffective)
        assertEquals("witnessGeneration=7", parsed[0].evidence)
        assertEquals("ServerExact", parsed[1].scopeKind)
        assertEquals(false, parsed[1].serverEffective)
    }

    private fun entriesJson(tag: String): JSONArray =
        JSONArray().apply {
            put(
                JSONObject()
                    .put("timestampLabel", "2026-07-10T03:00:00Z")
                    .put("tag", tag)
                    .put("message", "refresh_success requestId=req-1")
            )
        }
}

package build.raft.app.data

import build.raft.app.toSlockJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeSettingsDiagnosticsParserTest {
    @Test
    fun parsesPrimitiveEntriesJsonFallback() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("entriesJson", entriesJson(tag = "SlockAuth/refresh").toSlockJsonString())
            }

        val snapshot = response.toDiagnosticsHostSnapshot()

        assertEquals(1, snapshot.entries.size)
        assertEquals("2026-07-10T03:00:00Z", snapshot.entries.single().timestampLabel)
        assertEquals("SlockAuth/refresh", snapshot.entries.single().tag)
        assertEquals("refresh_success requestId=req-1", snapshot.entries.single().message)
    }

    @Test
    fun structuredEntriesTakePrecedenceForBackwardCompatibility() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("entries", entriesJson(tag = "structured"))
                put("entriesJson", entriesJson(tag = "fallback").toSlockJsonString())
            }

        val snapshot = response.toDiagnosticsHostSnapshot()

        assertEquals(listOf("structured"), snapshot.entries.map { it.tag })
    }

    @Test
    fun malformedEntriesJsonProducesEmptySnapshot() {
        val response = buildJsonObject {
            put("status", "ok")
            put("entriesJson", "not-json")
        }

        assertEquals(emptyList(), response.toDiagnosticsHostSnapshot().entries)
    }

    @Test
    fun parsesMultipleDiagnosticFeatureFlagOverrides() {
        val flags =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "sync_core_read_state_v1")
                        put("supported", true)
                        put("scopeKind", "ChannelExact")
                        put("entitled", true)
                        put("clientOverride", "ForceOn")
                        put("observedScope", "env/account/server/channel")
                        put("finalEffective", true)
                        put("precedence", "diagnostic_override_force_on")
                        put("evidence", "witnessGeneration=7")
                    }
                )
                add(
                    buildJsonObject {
                        put("key", "mobile_activity_stable_order_v1")
                        put("supported", true)
                        put("scopeKind", "ServerExact")
                        put("clientOverride", "Server")
                        put("serverEffective", false)
                        put("finalEffective", false)
                        put("precedence", "server")
                    }
                )
            }
        val response = buildJsonObject { put("diagnosticFeatureFlagOverridesJson", flags.toSlockJsonString()) }

        val parsed = response.toDiagnosticsHostSnapshot().featureFlagOverrides

        assertEquals(listOf("sync_core_read_state_v1", "mobile_activity_stable_order_v1"), parsed.map { it.key })
        assertEquals("ChannelExact", parsed[0].scopeKind)
        assertEquals(true, parsed[0].finalEffective)
        assertEquals("witnessGeneration=7", parsed[0].evidence)
        assertEquals("ServerExact", parsed[1].scopeKind)
        assertEquals(false, parsed[1].serverEffective)
    }

    private fun entriesJson(tag: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("timestampLabel", "2026-07-10T03:00:00Z")
                    put("tag", tag)
                    put("message", "refresh_success requestId=req-1")
                }
            )
        }
}

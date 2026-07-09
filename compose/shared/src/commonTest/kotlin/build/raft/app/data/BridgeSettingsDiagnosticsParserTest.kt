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

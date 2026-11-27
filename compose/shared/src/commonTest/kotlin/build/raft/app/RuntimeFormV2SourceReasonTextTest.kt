package build.raft.app

import build.raft.app.data.runtimeform.v2.CODEX_V2_FORM_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V2_UNAVAILABLE_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.runtimeFormV2SourceStatus
import build.raft.app.resources.*
import build.raft.app.thread.key
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The runtime form's source-status line per reason, as the web's RUNTIME_FORM_V2_SOURCE_REASON_MESSAGE
 * (RuntimeFormV2SourceStatusLine.tsx). not_signed_in / runtime_outdated: botiverse/slock #15105.
 */
class RuntimeFormV2SourceReasonTextTest {
    @Test
    fun everyContractReasonHasItsOwnLineAndAnUnknownOneTheGenericLine() {
        mapOf(
            "probe_timeout" to Res.string.runtime_form_v2_source_status_probe_timeout,
            "probe_failed" to Res.string.runtime_form_v2_source_status_probe_failed,
            "missing_config" to Res.string.runtime_form_v2_source_status_missing_config,
            "no_models" to Res.string.runtime_form_v2_source_status_no_models,
            "unsupported" to Res.string.runtime_form_v2_source_status_unsupported,
            "machine_offline" to Res.string.runtime_form_v2_source_status_machine_offline,
            "not_signed_in" to Res.string.runtime_form_v2_source_status_not_signed_in,
            "runtime_outdated" to Res.string.runtime_form_v2_source_status_runtime_outdated,
        ).forEach { (reason, expected) -> assertEquals(expected, runtimeFormV2SourceReasonText(reason), reason) }
        assertEquals(Res.string.runtime_form_v2_source_status_unknown, runtimeFormV2SourceReasonText("a_reason_from_later"))
        assertEquals(Res.string.runtime_form_v2_source_status_unknown, runtimeFormV2SourceReasonText(null))
    }

    @Test
    fun theServersNewReasonsAreReadFromTheSourceAsSentAndStayRetryable() {
        val model = assertNotNull(parseRuntimeFormV2(CODEX_V2_FORM_JSON)).fields.single { it.key == "model" }
        listOf("not_signed_in", "runtime_outdated").forEach { reason ->
            // The server's unavailable source with the reason its probe mapping now sends
            // (runtimeFormV2SourceStatus.ts: runtime_not_authenticated / protocol_unsupported).
            val json = CODEX_V2_UNAVAILABLE_SOURCE_JSON.replace("\"machine_offline\"", "\"$reason\"")
            val source = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))
            val status = runtimeFormV2SourceStatus(model, mapOf("model" to source))
            assertEquals(RuntimeFormV2SourceStatus(unavailable = true, reason = reason, retryable = true), status)
            assertEquals(
                if (reason == "not_signed_in") Res.string.runtime_form_v2_source_status_not_signed_in
                else Res.string.runtime_form_v2_source_status_runtime_outdated,
                runtimeFormV2SourceReasonText(status?.reason),
            )
        }
    }
}

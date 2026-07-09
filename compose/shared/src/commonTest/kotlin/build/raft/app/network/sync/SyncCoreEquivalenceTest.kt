/*
 * Cross-platform equivalence 对拍 for the Raft Sync Core KMP port (task T4d, RFC 043 §9.3).
 * Consumes the SAME fixture JSON the TS generator emits (no hand-ported expectations): replays
 * the event sequence through the Kotlin core and asserts each ingest outcome deepEquals the
 * fixture's generator-authored oracle. First-round equivalence gate for artin.
 */
package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncCoreEquivalenceTest {

    /** Harness domain: contiguous, state = ordered list of applied event values. */
    private fun harnessDomain(name: String): SyncDomainConfig = SyncDomainConfig(
        name = name,
        density = SyncDensity.Contiguous,
        initialState = { emptyList<Long>() },
        fold = { state, event, _ ->
            @Suppress("UNCHECKED_CAST")
            (state as List<Long>) + (event as Long)
        },
        fromSnapshot = { snapshot ->
            @Suppress("UNCHECKED_CAST")
            (snapshot.state as? List<Long>) ?: emptyList<Long>()
        },
    )

    private fun epochOf(obj: JsonObject): String? {
        val e = obj["epoch"] ?: return null
        return if (e is JsonNull) null else e.jsonPrimitive.content
    }

    private fun eventValue(obj: JsonObject): Long = obj["event"]!!.jsonObject["value"]!!.jsonPrimitive.long

    private fun appliedState(stateObj: JsonObject): List<Long> =
        stateObj["applied"]!!.jsonArray.map { it.jsonPrimitive.long }

    private fun expectedOutcome(o: JsonObject): SyncIngestOutcome {
        val scope = o["scopeId"]!!.jsonPrimitive.content
        return when (val kind = o["kind"]!!.jsonPrimitive.content) {
            "applied" -> SyncIngestOutcome.Applied(scope, o["seq"]!!.jsonPrimitive.long)
            "duplicate_dropped" -> SyncIngestOutcome.DuplicateDropped(scope, o["seq"]!!.jsonPrimitive.long)
            "max_advanced" -> SyncIngestOutcome.MaxAdvanced(scope, o["seq"]!!.jsonPrimitive.long)
            "gap_repair_requested" -> SyncIngestOutcome.GapRepairRequested(
                scope, o["fromSeq"]!!.jsonPrimitive.long, o["toSeq"]!!.jsonPrimitive.long,
            )
            "epoch_rebaseline_requested" -> SyncIngestOutcome.EpochRebaselineRequested(scope)
            else -> error("unknown expectedOutcome kind: $kind")
        }
    }

    private fun replayStep(core: SyncCore, input: JsonObject): SyncIngestOutcome {
        val domain = input["domain"]!!.jsonPrimitive.content
        return when (val kind = input["kind"]!!.jsonPrimitive.content) {
            "frame" -> {
                val f = input["frame"]!!.jsonObject
                core.ingestFrame(
                    domain,
                    SyncFrame(
                        scopeId = f["scopeId"]!!.jsonPrimitive.content,
                        seq = f["seq"]!!.jsonPrimitive.long,
                        epoch = epochOf(f),
                        event = eventValue(f),
                    ),
                )
            }
            "snapshot" -> {
                val s = input["snapshot"]!!.jsonObject
                core.ingestSnapshot(
                    domain,
                    SyncSnapshot(
                        scopeId = s["scopeId"]!!.jsonPrimitive.content,
                        watermark = s["watermark"]!!.jsonPrimitive.long,
                        epoch = epochOf(s),
                        state = appliedState(s["state"]!!.jsonObject),
                    ),
                )
            }
            "difference" -> {
                val r = input["response"]!!.jsonObject
                core.ingestDifference(
                    domain,
                    SyncDifferenceResponse(
                        scopeId = r["scopeId"]!!.jsonPrimitive.content,
                        epoch = epochOf(r),
                        fromSeq = r["fromSeq"]!!.jsonPrimitive.long,
                        toSeq = r["toSeq"]!!.jsonPrimitive.long,
                        events = r["events"]!!.jsonArray.map {
                            val eo = it.jsonObject
                            SyncDifferenceEvent(eo["seq"]!!.jsonPrimitive.long, eo["event"]!!.jsonObject["value"]!!.jsonPrimitive.long)
                        },
                        partial = r["partial"]?.let { it != JsonNull && it.jsonPrimitive.content == "true" } ?: false,
                        snapshotRequired = r["snapshotRequired"]?.let { it != JsonNull && it.jsonPrimitive.content == "true" } ?: false,
                    ),
                )
            }
            else -> error("unknown input kind: $kind")
        }
    }

    @Test
    fun kotlinCoreMatchesTsGeneratedFixtureOutcomes() {
        val fixture = Json.parseToJsonElement(SYNC_CORE_MIXED_FIXTURE_JSON).jsonObject
        val domainName = fixture["domain"]!!.jsonPrimitive.content
        val core = createSyncCore(SyncCoreConfig(domains = listOf(harnessDomain(domainName))))

        val steps = fixture["steps"]!!.jsonArray
        assertTrue(steps.isNotEmpty(), "fixture must have steps")

        steps.forEachIndexed { i, stepEl ->
            val step = stepEl.jsonObject
            val actual = replayStep(core, step["input"]!!.jsonObject)
            val expected = expectedOutcome(step["expectedOutcome"]!!.jsonObject)
            assertEquals(expected, actual, "outcome mismatch at step $i (ordinal ${step["ordinal"]})")
        }
    }
}

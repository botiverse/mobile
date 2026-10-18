package build.raft.app.network.sync.activity

import build.raft.app.network.sync.SyncCore
import build.raft.app.network.sync.SyncCoreConfig
import build.raft.app.network.sync.SyncDifferenceEvent
import build.raft.app.network.sync.SyncDifferenceResponse
import build.raft.app.network.sync.SyncFrame
import build.raft.app.network.sync.SyncIngestOutcome
import build.raft.app.network.sync.SyncRequestDescription
import build.raft.app.network.sync.SyncSnapshot
import build.raft.app.network.sync.SyncSnapshotReason
import build.raft.app.network.sync.SyncViolationRecord
import build.raft.app.network.sync.activity.contract.v1.ActivityFixtureEnvelope
import build.raft.app.network.sync.activity.contract.v1.ActivityIngress
import build.raft.app.network.sync.activity.contract.v1.ActivityRow
import build.raft.app.network.sync.activity.contract.v1.ActivitySyncContractJson
import build.raft.app.network.sync.activity.contract.v1.DifferenceIngress
import build.raft.app.network.sync.activity.contract.v1.FrameIngress
import build.raft.app.network.sync.activity.contract.v1.IngestFixtureStep
import build.raft.app.network.sync.activity.contract.v1.NotModifiedIngress
import build.raft.app.network.sync.activity.contract.v1.ReadStateUpdatedIngress
import build.raft.app.network.sync.activity.contract.v1.SnapshotIngress
import build.raft.app.network.sync.createSyncCore
import build.raft.app.network.sync.toCanonicalDecimalString
import build.raft.app.network.sync.toSyncSeq
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivitySyncBehaviorVectorTest {
    @Test
    fun productionKmpRunnerMatchesEveryFrozenTypescriptDigest() {
        val bytes = checkNotNull(
            javaClass.getResourceAsStream("/sync/activity-sync.behavior.jsonl"),
        ).readBytes()
        assertEquals(BEHAVIOR_VECTORS_SHA256, sha256(bytes))

        val envelopes = bytes.decodeToString()
            .trim()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { ActivitySyncContractJson.strict.decodeFromString(ActivityFixtureEnvelope.serializer(), it) }
            .toList()
        val results = envelopes.map(::runCase)

        assertEquals(
            EXPECTED_CASE_DIGESTS,
            results.associate { result ->
                result.getValue("caseId").jsonString() to sha256(canonicalJson(result).encodeToByteArray())
            },
        )
        assertEquals(EXPECTED_AGGREGATE_SHA256, sha256(canonicalJson(JsonArray(results)).encodeToByteArray()))
    }

    private fun runCase(envelope: ActivityFixtureEnvelope): JsonObject {
        val core = createSyncCore(SyncCoreConfig(domains = listOf(createActivityDomain())))
        val scopeIds = linkedSetOf<String>()
        val steps = envelope.steps.map { step ->
            require(step is IngestFixtureStep) {
                "fixture step ${step::class.simpleName} is not executed by runnerProtocol 1"
            }
            val scopeId = encodeActivityScopeId(step.ingress.scope())
            scopeIds += scopeId
            JsonObject(
                mapOf(
                    "stepId" to JsonPrimitive(step.stepId),
                    "outcome" to applyStep(core, step.ingress, scopeId),
                ),
            )
        }

        val finalState = JsonObject(
            scopeIds.sorted().associateWith { scopeId ->
                (core.state(ACTIVITY_DOMAIN, scopeId) as? ActivityDomainState)
                    ?.let(::activityStateJson)
                    ?: JsonNull
            },
        )
        val violations = core.violations().records.map(::violationJson)
        return JsonObject(
            mapOf(
                "caseId" to JsonPrimitive(envelope.caseId),
                "steps" to JsonArray(steps),
                "pending" to JsonArray(core.pendingRequests().map(::requestJson)),
                "violations" to JsonArray(violations),
                "finalState" to finalState,
            ),
        )
    }

    private fun applyStep(core: SyncCore, ingress: ActivityIngress, scopeId: String): JsonElement = when (ingress) {
        is SnapshotIngress -> outcomeJson(
            core.ingestSnapshot(
                ACTIVITY_DOMAIN,
                SyncSnapshot(
                    scopeId = scopeId,
                    watermark = ingress.watermark.value.toSyncSeq(),
                    epoch = ingress.epoch.value,
                    state = ingress,
                ),
            ),
        )
        is DifferenceIngress -> outcomeJson(
            core.ingestDifference(
                ACTIVITY_DOMAIN,
                SyncDifferenceResponse(
                    scopeId = scopeId,
                    epoch = ingress.epoch.value,
                    fromSeq = ingress.fromSeq.value.toSyncSeq(),
                    toSeq = ingress.toSeq.value.toSyncSeq(),
                    events = listOf(
                        SyncDifferenceEvent(
                            seq = ingress.toSeq.value.toSyncSeq(),
                            event = ActivityDomainEvent.Frame(
                                rows = ingress.rows,
                                tombstones = ingress.tombstones,
                                activityVersion = ingress.activityVersion,
                            ),
                        ),
                    ),
                ),
            ),
        )
        is NotModifiedIngress -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("not_modified"),
                "scopeId" to JsonPrimitive(scopeId),
            ),
        )
        is FrameIngress -> outcomeJson(
            core.ingestFrame(
                ACTIVITY_DOMAIN,
                SyncFrame(scopeId, ingress.seq.value.toSyncSeq(), ingress.epoch.value, ingress),
            ),
        )
        is ReadStateUpdatedIngress -> outcomeJson(
            core.ingestFrame(
                ACTIVITY_DOMAIN,
                SyncFrame(scopeId, ingress.seq.value.toSyncSeq(), ingress.epoch.value, ingress),
            ),
        )
        else -> error("${ingress::class.simpleName} is not sequenced by runnerProtocol 1")
    }

    private fun outcomeJson(outcome: SyncIngestOutcome): JsonObject = when (outcome) {
        is SyncIngestOutcome.Applied -> resultWithSeq("applied", outcome.scopeId, outcome.seq.toCanonicalDecimalString())
        is SyncIngestOutcome.DuplicateDropped -> resultWithSeq(
            "duplicate_dropped",
            outcome.scopeId,
            outcome.seq.toCanonicalDecimalString(),
        )
        is SyncIngestOutcome.GapRepairRequested -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("gap_repair_requested"),
                "scopeId" to JsonPrimitive(outcome.scopeId),
                "fromSeq" to JsonPrimitive(outcome.fromSeq.toCanonicalDecimalString()),
                "toSeq" to JsonPrimitive(outcome.toSeq.toCanonicalDecimalString()),
            ),
        )
        is SyncIngestOutcome.MaxAdvanced -> resultWithSeq(
            "max_advanced",
            outcome.scopeId,
            outcome.seq.toCanonicalDecimalString(),
        )
        is SyncIngestOutcome.EpochRebaselineRequested -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("epoch_rebaseline_requested"),
                "scopeId" to JsonPrimitive(outcome.scopeId),
            ),
        )
        is SyncIngestOutcome.Violation -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("violation"),
                "scopeId" to JsonPrimitive(outcome.scopeId),
                "violation" to JsonPrimitive(outcome.violation.wire),
            ),
        )
    }

    private fun resultWithSeq(kind: String, scopeId: String, seq: String): JsonObject = JsonObject(
        mapOf(
            "kind" to JsonPrimitive(kind),
            "scopeId" to JsonPrimitive(scopeId),
            "seq" to JsonPrimitive(seq),
        ),
    )

    private fun requestJson(request: SyncRequestDescription): JsonObject = when (request) {
        is SyncRequestDescription.Difference -> JsonObject(
            buildMap {
                put("kind", JsonPrimitive("difference"))
                put("domain", JsonPrimitive(request.domain))
                put("scopeId", JsonPrimitive(request.scopeId))
                put("sinceSeq", JsonPrimitive(request.sinceSeq.toCanonicalDecimalString()))
                put("epoch", request.epoch?.let(::JsonPrimitive) ?: JsonNull)
                request.holdHash?.let { put("holdHash", JsonPrimitive(it)) }
                request.resnapshotOverGap?.let {
                    put("resnapshotOverGap", JsonPrimitive(it.toCanonicalDecimalString()))
                }
            },
        )
        is SyncRequestDescription.Snapshot -> JsonObject(
            buildMap {
                put("kind", JsonPrimitive("snapshot"))
                put("domain", JsonPrimitive(request.domain))
                put("scopeId", JsonPrimitive(request.scopeId))
                put("reason", JsonPrimitive(request.reason.wire()))
                request.holdHash?.let { put("holdHash", JsonPrimitive(it)) }
            },
        )
    }

    private fun SyncSnapshotReason.wire(): String = when (this) {
        SyncSnapshotReason.EpochMismatch -> "epoch_mismatch"
        SyncSnapshotReason.SnapshotRequired -> "snapshot_required"
        SyncSnapshotReason.Initial -> "initial"
        SyncSnapshotReason.SparseRepull -> "sparse_repull"
    }

    private fun violationJson(record: SyncViolationRecord): JsonObject = JsonObject(
        buildMap {
            put("kind", JsonPrimitive(record.kind.wire))
            put("domain", JsonPrimitive(record.domain))
            put("scopeId", JsonPrimitive(record.scopeId))
            record.seq?.let { put("seq", JsonPrimitive(it.toCanonicalDecimalString())) }
            record.epoch?.let { put("epoch", JsonPrimitive(it)) }
            record.detail?.let { detail ->
                put(
                    "detail",
                    JsonObject(detail.mapValues { (_, value) -> scalarJson(value) }),
                )
            }
        },
    )

    private fun activityStateJson(state: ActivityDomainState): JsonObject = JsonObject(
        mapOf(
            "rows" to JsonArray(
                state.rows.map { row ->
                    ActivitySyncContractJson.strict.encodeToJsonElement(ActivityRow.serializer(), row)
                },
            ),
            "tombstones" to JsonObject(
                state.tombstones.mapValues { (_, version) -> JsonPrimitive(version.value) },
            ),
            "activityVersion" to (state.activityVersion?.value?.let(::JsonPrimitive) ?: JsonNull),
            "nextCursor" to (state.nextCursor?.let(::JsonPrimitive) ?: JsonNull),
            "hasMore" to JsonPrimitive(state.hasMore),
            "complete" to JsonPrimitive(state.complete),
        ),
    )

    private fun ActivityIngress.scope() = when (this) {
        is SnapshotIngress -> scope
        is DifferenceIngress -> scope
        is NotModifiedIngress -> scope
        is FrameIngress -> scope
        is ReadStateUpdatedIngress -> scope
        else -> error("${this::class.simpleName} is not sequenced by runnerProtocol 1")
    }

    private fun canonicalJson(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> ActivitySyncContractJson.strict.encodeToString(JsonPrimitive.serializer(), value)
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalJson)
        is JsonObject -> value.entries
            .sortedBy(Map.Entry<String, JsonElement>::key)
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
                "${ActivitySyncContractJson.strict.encodeToString(key)}:${canonicalJson(item)}"
            }
    }

    private fun scalarJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("unsupported violation detail ${value::class.simpleName}")
    }

    private fun JsonElement.jsonString(): String = (this as JsonPrimitive).content

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BEHAVIOR_VECTORS_SHA256 = "ac94f85c23656184724e7b91e4957e278b4a8554878600842c3684b9c7855bd1"
        const val EXPECTED_AGGREGATE_SHA256 = "d43b3da565b81306b3ef5338ed50f432dfd84a66c7db050b8405af51078a236d"
        val EXPECTED_CASE_DIGESTS = linkedMapOf(
            "baseline-then-ordered-frames" to "db4e0d162c21ce4b776d44988394cdb6ff16d7c70007f298396eda7429784fcc",
            "gap-stop-gates-into-repair" to "34f7c599da22516c0d392f76f3629db9e75e8a49c2a7e7ef058cf2e38e7ad105",
            "difference-repairs-the-gap" to "82a058cc8b210973e3ff6050fb06e9f02391f3875271ad1c12d4f08b5c532c78",
            "not-modified-changes-nothing" to "64e88f5e18f665001d84f6d625de74e7191ce93a825e1c74e2ea305f4dc6d752",
            "out-of-order-row-version-does-not-regress" to "9f009a9952ab1c3343bee8a24e3bb78a7f13a1571cb60f65440999ecd9db7a42",
            "snapshot-tombstone-blocks-stale-resurrection" to "f80136728835244c218983a74f49b077e7437894ef02167c5129cc3af3f75fce",
            "uint64-adjacent-across-2p53" to "4b27d9b76d6a9a20590ab3447a144ccbf210d8432c7abefb93e22065e60fb322",
            "read-state-updated-is-version-monotonic" to "998cde3449a80d146758f6aee5e4744120fb362d9f23fa3de228da1f31825969",
        )
    }
}

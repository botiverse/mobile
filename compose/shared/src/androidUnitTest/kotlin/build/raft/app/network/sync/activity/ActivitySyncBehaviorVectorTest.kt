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
        const val BEHAVIOR_VECTORS_SHA256 = "654898b6d96648bc22ea19b916e3a133053b356ade44492cf394fe91e5129033"
        const val EXPECTED_AGGREGATE_SHA256 = "ae05ce0bc5c9c6a8dcc132759d7d5a926c51a2b37b8686ca10975f4d36bde67d"
        val EXPECTED_CASE_DIGESTS = linkedMapOf(
            "baseline-then-ordered-frames" to "311d73b7591030622a0534dad7b3a75c42e30d7e3372ad887403d9256494c11d",
            "gap-stop-gates-into-repair" to "5c128b1fa3def75bcbb8584b8e0c6d04afbd34d87ebadb96ec92c359865c7c49",
            "difference-repairs-the-gap" to "a5e5a6d632fa15254f1ce34fbf315c85cf1fddf7c57acedb5b9ab429b8d076c4",
            "not-modified-changes-nothing" to "2d513467ff4022fa9573cc05ed9d3feeb3c4367838f1df6b228f3ca662b2c745",
            "out-of-order-row-version-does-not-regress" to "d16d5646acfc2d0d65e0e8b59e3be4f937932f467ab6d8cb255bbb8c16ba836f",
            "snapshot-tombstone-blocks-stale-resurrection" to "c71cfcd677091ba8620b28ca455cb287abfb17856ddcb250d4288c1fdda41f2b",
            "uint64-adjacent-across-2p53" to "df3e85c1e529cbeae6d760c4f25385cd42ba8d548a50cdd4ad8b60a99406ae93",
            "read-state-updated-is-version-monotonic" to "386a07c017b76fa907dfffdf40e13169ef8dd18a56748543435795d881679603",
        )
    }
}

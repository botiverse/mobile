package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Commit B: independent pure RED contract. The fixture/oracle is test-owned; the subject remains
 * [missingCanonicalMessageV2GreenPort], so every behavioral requirement is deliberately RED until C.
 */
class CanonicalMessageV2ContractRedTest {
    private val subject = missingCanonicalMessageV2GreenPort
    private val json = Json { explicitNulls = true }

    @Test
    fun r1_fullV6RejectsV1AndSchemaV5Partial() {
        val v5 = CanonicalMessageV2RedOracle.mutatedEnvelope(javaClass) {
            it["schemaVersion"] = JsonPrimitive(CANONICAL_MESSAGE_V2_SCHEMA_VERSION)
            it["fact"] = buildJsonObject { put("reactions", JsonArray(emptyList())) }
        }
        assertRed("R1-v5", v5, CanonicalV2RedResult.Rejected("unsupported_canonical_message_schema"))
        assertRed(
            "R1-v1",
            Json.parseToJsonElement("{\"version\":1,\"id\":\"legacy\"}"),
            CanonicalV2RedResult.Rejected("unsupported_canonical_message_schema"),
        )
    }

    @Test
    fun r2_exactKeysRejectUnknownAndMissingShape() {
        val unknown = CanonicalMessageV2RedOracle.mutatedEnvelope(javaClass) { it["phantom"] = JsonPrimitive(true) }
        val missing = CanonicalMessageV2RedOracle.mutatedEnvelope(javaClass) { it.remove("context") }
        assertRed("R2-unknown", unknown, CanonicalV2RedResult.Rejected("canonical_message_exact_keys"))
        assertRed("R2-missing", missing, CanonicalV2RedResult.Rejected("canonical_message_exact_keys"))
    }

    @Test
    fun r3_manifestClosesEverySharedDtoPathBothDirections() =
        assertRed("R3", JsonPrimitive("manifest-v6-closure"), CanonicalV2RedResult.State("closed"))

    @Test
    fun r4_eachFieldHasExactlyOneOwner() =
        assertRed("R4", JsonPrimitive("ownership-ledger"), CanonicalV2RedResult.State("single-owner"))

    @Test
    fun r5_independentGoldenBytesMatchProductionCodec() {
        val maximal = fixture("canonical-message-v2.maximal.json")
        val minimal = fixture("canonical-message-v2.minimal.json")
        assertRed("R5-maximal", json.parseToJsonElement(maximal), CanonicalV2RedResult.Accepted(maximal))
        assertRed("R5-minimal", json.parseToJsonElement(minimal), CanonicalV2RedResult.Accepted(minimal))
    }

    @Test
    fun r6_sharedEnvelopeCannotExpressFullReactionRosters() =
        assertRed("R6", fixtureElement("canonical-message-v2.maximal.json"), CanonicalV2RedResult.State("no-rosters"))

    @Test
    fun r7_reactionAndPreviewBoundsAreEnforced() {
        val input = buildJsonObject {
            put("reactionCount", 31)
            put("previewCount", 4)
        }
        assertRed("R7", input, CanonicalV2RedResult.Rejected("canonical_reaction_bound_exceeded"))
    }

    @Test
    fun r8_sharedRoomCannotAuthorViewerOverlay() =
        assertRed(
            "R8",
            buildJsonObject { put("source", "shared-room"); put("viewerVersion", 7) },
            CanonicalV2RedResult.State("shared-only", overlayWriteCount = 0),
        )

    @Test
    fun r9_sharedEnvelopeHasNoViewerFieldsOrForwardedProvenance() =
        assertRed("R9", fixtureElement("canonical-message-v2.maximal.json"), CanonicalV2RedResult.State("privacy-clean"))

    @Test
    fun r10_rawCommentThreadAndConversationFieldsAreAbsent() =
        assertRed("R10", fixtureElement("canonical-message-v2.maximal.json"), CanonicalV2RedResult.State("typed-only"))

    @Test
    fun r11_replyMembershipAndChildReplyAnchorSurviveRestore() =
        assertRed(
            "R11",
            fixtureElement("canonical-message-v2.thread-context.json"),
            CanonicalV2RedResult.State("root=message-root,scope=thread-root"),
        )

    @Test
    fun r12_attachmentCommentsRoomNullAndCacheMissRemainUnknown() =
        assertRed("R12", buildJsonObject { put("roomValue", JsonNull); put("cacheHit", false) }, CanonicalV2RedResult.Unknown)

    @Test
    fun r13_illegalRootRelationCombinationCannotDecode() =
        assertRed(
            "R13",
            buildJsonObject { put("rootKind", "attachment"); put("relation", "reaction-actors") },
            CanonicalV2RedResult.Rejected("illegal_discussion_relation"),
        )

    @Test
    fun r14_crossServerMembershipFailsBeforePersistence() =
        assertRed(
            "R14",
            buildJsonObject { put("nodeServerId", "server-a"); put("rootServerId", "server-b") },
            CanonicalV2RedResult.Rejected("cross_server_reply_membership"),
        )

    @Test
    fun r15_parentRebaselineInvalidatesOnlyItsReadCache() =
        assertRed(
            "R15",
            buildJsonObject { put("invalidatedScope", "channel-a"); put("unrelatedScope", "channel-b") },
            CanonicalV2RedResult.State("channel-a=Unknown,channel-b=Loaded"),
        )

    @Test
    fun r16_childPageCannotRecomputeParentAggregate() =
        assertRed(
            "R16",
            buildJsonObject { put("serverReplyCount", 7); put("loadedChildren", 2) },
            CanonicalV2RedResult.State("replyCount=7"),
        )

    @Test
    fun r17_hydrateAckAndUserRoomUseOneVersionGate() =
        assertRed(
            "R17",
            buildJsonObject { put("sources", JsonArray(listOf("hydrate", "ack", "user-room").map(::JsonPrimitive))) },
            CanonicalV2RedResult.State("one-version-gate"),
        )

    @Test
    fun r18_greaterOverlayVersionReplaces() =
        assertRed(
            "R18",
            overlayVersions(stored = 4, incoming = 5, payload = "heart"),
            CanonicalV2RedResult.State("version=5,payload=heart", transactionCount = 1, overlayWriteCount = 1),
        )

    @Test
    fun r19_lowerOverlayVersionIsNamedNoOp() =
        assertRed(
            "R19",
            overlayVersions(stored = 5, incoming = 4, payload = "heart"),
            CanonicalV2RedResult.NoOp(VIEWER_OVERLAY_VERSION_REGRESSION_DIAGNOSTIC),
        )

    @Test
    fun r20_equalVersionDifferentPayloadFailStops() =
        assertRed(
            "R20",
            overlayVersions(stored = 5, incoming = 5, payload = "different"),
            CanonicalV2RedResult.Rejected(VIEWER_OVERLAY_VERSION_CONFLICT_DIAGNOSTIC),
        )

    @Test
    fun r21_missingOverlayIsUnknownNotEmpty() =
        assertRed("R21", buildJsonObject { put("stored", JsonNull) }, CanonicalV2RedResult.Unknown)

    @Test
    fun r22_overlayKeyIncludesPrincipalConsumerAndKind() =
        assertRed(
            "R22",
            buildJsonObject {
                put("principalA", "overlay-a")
                put("principalB", "overlay-b")
                put("consumerV1", "absent")
                put("consumerV2", "overlay-a")
                put("kind", "reactions")
            },
            CanonicalV2RedResult.State("isolated"),
        )

    @Test
    fun r23_overlayCompareAndWriteIsOneTransaction() =
        assertRed(
            "R23",
            overlayVersions(stored = 1, incoming = 2, payload = "+1"),
            CanonicalV2RedResult.State("committed", transactionCount = 1, overlayWriteCount = 1),
        )

    @Test
    fun r24_sqliteLongOverflowAndBoundaryFailClosed() =
        assertRed(
            "R24",
            buildJsonObject { put("stored", Long.MAX_VALUE); put("incoming", "9223372036854775808") },
            CanonicalV2RedResult.Rejected("viewer_overlay_version_out_of_range"),
        )

    @Test
    fun r25_roomReactionFrameProducesZeroOverlayWrites() =
        assertRed(
            "R25",
            buildJsonObject { put("source", "channel-room"); put("reactedEmojis", JsonArray(listOf(JsonPrimitive("+1")))) },
            CanonicalV2RedResult.State("shared-updated", overlayWriteCount = 0),
        )

    @Test
    fun r26_unknownActionKindIsNamedRejection() =
        assertRed(
            "R26",
            buildJsonObject { put("kind", "future-action") },
            CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_UNSUPPORTED_ACTION_METADATA_KIND),
        )

    @Test
    fun r27_forwardedProvenanceEntersNeitherSharedNorOverlayBytes() =
        assertRed(
            "R27",
            buildJsonObject {
                put("sourceMessageId", "private-pointer")
                put("provenanceState", "receiver-private")
                put("shared", fixtureElement("canonical-message-v2.maximal.json"))
                put("overlay", fixtureElement("canonical-message-v2.viewer-overlay.json"))
            },
            CanonicalV2RedResult.State("provenance-absent"),
        )

    @Test
    fun r28_v2ConsumerNeverReadsV1FactsOrCursor() =
        assertRed(
            "R28",
            buildJsonObject { put("consumer", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) },
            CanonicalV2RedResult.State("v2-only", v1ReadCount = 0),
        )

    @Test
    fun r29_firstIncrementalRequiresBaselineAndOneSingleFlightRebaseline() =
        assertRed(
            "R29",
            buildJsonObject { put("baseline", false); put("frames", 2) },
            CanonicalV2RedResult.State(
                CANONICAL_MESSAGE_V2_BASELINE_REQUIRED,
                transactionCount = 0,
                rebaselineRequestCount = 1,
            ),
        )

    @Test
    fun r30_snapshotCommitsFactsContextMembershipAndCursorOnce() =
        assertRed(
            "R30",
            fixtureElement("canonical-message-v2.thread-context.json"),
            CanonicalV2RedResult.State("snapshot-committed", transactionCount = 1),
        )

    @Test
    fun r31_restoreRejectsMalformedMixedAndNonV6Rows() =
        assertRed(
            "R31",
            buildJsonObject { put("rows", JsonArray(listOf(JsonPrimitive("malformed"), JsonPrimitive("v5"), JsonPrimitive("v6")))) },
            CanonicalV2RedResult.Rejected("canonical_v2_scope_poisoned"),
        )

    @Test
    fun r32_rebaselineCrashBeforeAndAfterRestoreOnlyCompleteStates() =
        assertRed(
            "R32",
            buildJsonObject { put("faults", JsonArray(listOf(JsonPrimitive("before-commit"), JsonPrimitive("after-commit")))) },
            CanonicalV2RedResult.State("before=old-complete,after=new-complete", transactionCount = 1),
        )

    @Test
    fun r36_eligibilityAndProductionBindingRemainUnchanged() {
        assertFalse(CANONICAL_FACT_SOLE_APPLY_ELIGIBLE)
        assertEquals("sync-core.messages.v1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
        assertNotEquals(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    }

    @Test
    fun r37_noT4KeyOrCanaryRouteWasAdded() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("build.raft.app.network.sync.CanonicalMessageV2ProductionBinding")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("build.raft.app.network.sync.CanonicalMessageV2CanaryRoute")
        }
    }

    @Test
    fun r38_allFiveProducerContextStatesNormalizeLosslessly() {
        listOf("channel", "private", "dm", "joint", "thread").forEach { kind ->
            assertRed("R38-$kind", CanonicalMessageV2RedOracle.producerContext(kind), CanonicalV2RedResult.State(kind))
        }
    }

    @Test
    fun r39_threadContextPreservesReplyScopeParentIdTypeAndRoot() =
        assertRed(
            "R39",
            fixtureElement("canonical-message-v2.thread-context.json"),
            CanonicalV2RedResult.State("thread-root,dm-a,dm,message-root"),
        )

    @Test
    fun r40_topLevelContextAndChildReplyScopeCoexist() =
        assertRed(
            "R40",
            fixtureElement("canonical-message-v2.top-level-context.json"),
            CanonicalV2RedResult.State("private-a+thread-parent"),
        )

    @Test
    fun r41_contextAndReplyMembershipMustExactMatch() {
        val source = fixtureElement("canonical-message-v2.thread-context.json").jsonObject.toMutableMap()
        val membership = source.getValue("replyMembership").jsonObject.toMutableMap()
        membership["parentScope"] = buildJsonObject { put("serverId", "server-a"); put("scopeId", "wrong-thread") }
        source["replyMembership"] = JsonObject(membership)
        assertRed(
            "R41",
            JsonObject(source),
            CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_CONTEXT_MEMBERSHIP_MISMATCH),
        )
    }

    private fun fixture(name: String): String = CanonicalMessageV2RedOracle.fixture(javaClass, name)

    private fun fixtureElement(name: String) = CanonicalMessageV2RedOracle.fixtureElement(javaClass, name)

    private fun overlayVersions(stored: Long, incoming: Long, payload: String) = buildJsonObject {
        put("storedVersion", stored)
        put("incomingVersion", incoming)
        put("payload", payload)
    }

    private fun assertRed(id: String, input: kotlinx.serialization.json.JsonElement, expected: CanonicalV2RedResult) {
        assertEquals(expected, subject.evaluate(CanonicalMessageV2RedOracle.invocation(id, input)), id)
    }
}

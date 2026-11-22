package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Frozen A/B contract driven through the GREEN observation port. The oracle remains test-owned and never
 * calls production serializers to construct expected bytes.
 */
class CanonicalMessageV2ContractRedTest {
    private val subject = missingCanonicalMessageV2GreenPort
    private val json = Json { explicitNulls = true }

    @Test
    fun r11_replyMembershipAndChildReplyAnchorSurviveRestore() =
        assertRed(
            "R11",
            fixtureElement("canonical-message-v2.thread-context.json"),
            CanonicalV2RedResult.State("root=message-root,scope=thread-root"),
        )

    @Test
    fun r14_everyEmbeddedServerRefMustMatchDurablePartitionBeforePersistence() {
        val mismatchPaths = listOf(
            "topLevelScope",
            "childReplyScope",
            "thread.replyScope",
            "thread.parentTopLevelScope",
            "thread.rootMessageRef",
            "replyMembership.root",
            "replyMembership.parentScope",
            "threadAndMembershipExactEqualButForeign",
        )
        assertRedTable(
            cases = mismatchPaths.associate { path -> "R14-$path" to embeddedServerMismatch(path) },
            expected = CanonicalV2RedResult.Rejected("cross_server_embedded_ref"),
        )
    }

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

    private fun fixtureElement(name: String) = CanonicalMessageV2RedOracle.fixtureElement(javaClass, name)

    private fun embeddedServerMismatch(path: String): JsonObject {
        val envelope = when (path) {
            "topLevelScope", "childReplyScope" -> fixtureElement("canonical-message-v2.maximal.json").jsonObject
            else -> fullThreadEnvelope()
        }.toMutableMap()

        fun foreign(ref: JsonObject): JsonObject = JsonObject(ref.toMutableMap().apply {
            this["serverId"] = JsonPrimitive("server-b")
        })

        when (path) {
            "topLevelScope" -> {
                val context = envelope.getValue("context").jsonObject.toMutableMap()
                context["scope"] = foreign(context.getValue("scope").jsonObject)
                envelope["context"] = JsonObject(context)
            }
            "childReplyScope" -> envelope["childReplyScope"] = foreign(envelope.getValue("childReplyScope").jsonObject)
            "thread.replyScope" -> {
                val context = envelope.getValue("context").jsonObject.toMutableMap()
                context["replyScope"] = foreign(context.getValue("replyScope").jsonObject)
                envelope["context"] = JsonObject(context)
            }
            "thread.parentTopLevelScope" -> {
                val context = envelope.getValue("context").jsonObject.toMutableMap()
                context["parentTopLevelScope"] = foreign(context.getValue("parentTopLevelScope").jsonObject)
                envelope["context"] = JsonObject(context)
            }
            "thread.rootMessageRef" -> {
                val context = envelope.getValue("context").jsonObject.toMutableMap()
                context["rootMessageRef"] = foreign(context.getValue("rootMessageRef").jsonObject)
                envelope["context"] = JsonObject(context)
            }
            "replyMembership.root" -> {
                val membership = envelope.getValue("replyMembership").jsonObject.toMutableMap()
                membership["root"] = foreign(membership.getValue("root").jsonObject)
                envelope["replyMembership"] = JsonObject(membership)
            }
            "replyMembership.parentScope" -> {
                val membership = envelope.getValue("replyMembership").jsonObject.toMutableMap()
                membership["parentScope"] = foreign(membership.getValue("parentScope").jsonObject)
                envelope["replyMembership"] = JsonObject(membership)
            }
            "threadAndMembershipExactEqualButForeign" -> {
                val context = envelope.getValue("context").jsonObject.toMutableMap()
                context["replyScope"] = foreign(context.getValue("replyScope").jsonObject)
                context["parentTopLevelScope"] = foreign(context.getValue("parentTopLevelScope").jsonObject)
                context["rootMessageRef"] = foreign(context.getValue("rootMessageRef").jsonObject)
                envelope["context"] = JsonObject(context)

                val membership = envelope.getValue("replyMembership").jsonObject.toMutableMap()
                membership["root"] = foreign(membership.getValue("root").jsonObject)
                membership["parentScope"] = foreign(membership.getValue("parentScope").jsonObject)
                envelope["replyMembership"] = JsonObject(membership)
            }
            else -> error("unknown mismatch path $path")
        }
        return buildJsonObject {
            put("partitionServerId", "server-a")
            put("envelope", JsonObject(envelope))
        }
    }

    private fun fullThreadEnvelope(): JsonObject {
        val envelope = fixtureElement("canonical-message-v2.minimal.json").jsonObject.toMutableMap()
        val thread = fixtureElement("canonical-message-v2.thread-context.json").jsonObject
        envelope["context"] = thread.getValue("context")
        envelope["replyMembership"] = thread.getValue("replyMembership")
        return JsonObject(envelope)
    }

    private fun assertRedTable(
        cases: Map<String, kotlinx.serialization.json.JsonElement>,
        expected: CanonicalV2RedResult,
    ) {
        val expectedByCase = cases.keys.associateWith { expected }
        val actualByCase = cases.mapValues { (id, input) ->
            subject.evaluate(CanonicalMessageV2RedOracle.invocation(id, input))
        }
        assertEquals(expectedByCase, actualByCase)
    }

    private fun assertRed(id: String, input: kotlinx.serialization.json.JsonElement, expected: CanonicalV2RedResult) {
        assertEquals(expected, subject.evaluate(CanonicalMessageV2RedOracle.invocation(id, input)), id)
    }
}

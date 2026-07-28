package build.raft.app.network.sync.activity.contract.v1

import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivitySyncContractCanaryTest {
    private val json = ActivitySyncContractJson.strict

    @Test
    fun directKotlinBindingEnforcesAllFrozenContractVectors() {
        val vectors = ACTIVITY_SYNC_CONTRACT_VECTORS_JSONL
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> json.decodeFromString(ContractVectorEnvelope.serializer(), line) }
            .toList()

        assertEquals(13, vectors.size)
        assertEquals(3, vectors.count { it.constraintClass == ConstraintClass.Positive })
        assertEquals(7, vectors.count { it.constraintClass == ConstraintClass.Structural })
        assertEquals(3, vectors.count { it.constraintClass == ConstraintClass.ValueDomain })

        vectors.forEach { vector ->
            val decoded = runCatching {
                json.decodeFromJsonElement(ActivityIngress.serializer(), vector.candidate)
            }
            when (vector.expectation.kotlinRuntime) {
                ContractVerdict.Accept -> assertTrue(
                    decoded.isSuccess,
                    "${vector.vectorId} must decode but failed with ${decoded.exceptionOrNull()}",
                )
                ContractVerdict.Reject -> assertTrue(
                    decoded.isFailure,
                    "${vector.vectorId} must be rejected by the generated Kotlin runtime codec",
                )
                ContractVerdict.Exempt -> error(
                    "Kotlin runtime cannot exempt a frozen Activity vector: ${vector.vectorId}",
                )
            }
        }

        val beyondSafeInteger = decodeCandidate(vectors.single { it.vectorId == "P2-beyond-js-safe-integer" })
        val snapshot = assertIs<SnapshotIngress>(beyondSafeInteger)
        assertEquals("9007199254740993", snapshot.watermark.value)
        assertEquals("9007199254740993", snapshot.activityVersion.value)
        val encoded = json.encodeToString(ActivityIngress.serializer(), snapshot)
        assertContains(encoded, "\"watermark\":\"9007199254740993\"")
        assertContains(encoded, "\"activityVersion\":\"9007199254740993\"")

        val requiredNull = assertIs<DifferenceIngress>(
            decodeCandidate(vectors.single { it.vectorId == "P3-required-null-present" }),
        )
        assertEquals(null, requiredNull.nextFromSeq)
    }

    @Test
    fun optionalNonNullableKeepsMissingDistinctFromExplicitNull() {
        val missing = json.decodeFromString(InboxQuery.serializer(), "{}")
        assertEquals(OptionalField.Missing, missing.cursor)

        assertFails {
            json.decodeFromString(InboxQuery.serializer(), "{\"cursor\":null}")
        }
    }

    @Test
    fun immutableFixtureSeedDecodesAllSevenIngressBranchesAndRoundTrips() {
        assertEquals(
            "f464700d04af3d47290f6b7df21dc74f6ef36b0093fac4397f64078fd9d64b67",
            ACTIVITY_SYNC_TYPESPEC_SHA256,
        )
        assertEquals(
            "c8f22f9eecb4714b1f110edfd37651815597bd042e22ede04af2342e99c0b980",
            ACTIVITY_SYNC_CONTRACT_VECTORS_SHA256,
        )
        assertEquals(
            "27f61399a8594e37ec6ac06201f30f48f84fadae4f4f96b131b66d408d0723f2",
            ACTIVITY_SYNC_BEHAVIOR_SEED_SHA256,
        )

        val envelope = json.decodeFromString(
            ActivityFixtureEnvelope.serializer(),
            ACTIVITY_SYNC_BEHAVIOR_SEED_JSONL.trim(),
        )
        val ingresses = envelope.steps
            .filterIsInstance<IngestFixtureStep>()
            .map(IngestFixtureStep::ingress)

        assertEquals(7, ingresses.size)
        assertEquals(
            listOf(
                "snapshot",
                "notModified",
                "difference",
                "frame",
                "commandReceipt",
                "commandRejected",
                "readStateUpdated",
            ),
            ingresses.map(::ingressKind),
        )

        ingresses.forEach { ingress ->
            val encoded = json.encodeToString(ActivityIngress.serializer(), ingress)
            val decoded = json.decodeFromString(ActivityIngress.serializer(), encoded)
            assertEquals(ingress, decoded)
        }
    }

    private fun decodeCandidate(vector: ContractVectorEnvelope): ActivityIngress =
        json.decodeFromJsonElement(ActivityIngress.serializer(), vector.candidate)

    private fun ingressKind(ingress: ActivityIngress): String = when (ingress) {
        is SnapshotIngress -> "snapshot"
        is NotModifiedIngress -> "notModified"
        is DifferenceIngress -> "difference"
        is FrameIngress -> "frame"
        is CommandReceiptIngress -> "commandReceipt"
        is CommandRejectedIngress -> "commandRejected"
        is ReadStateUpdatedIngress -> "readStateUpdated"
    }
}

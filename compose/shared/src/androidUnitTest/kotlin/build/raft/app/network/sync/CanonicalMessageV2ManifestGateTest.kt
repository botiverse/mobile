package build.raft.app.network.sync

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Commit-A structural gate for the complete schema-v6 ledger; no decoder/normalizer behavior lives here. */
class CanonicalMessageV2ManifestGateTest {
    private val manifestBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/$CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE")) {
            "$CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE missing"
        }.readBytes()
    }
    private val manifest by lazy { Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }

    @Test
    fun manifestDiscriminatorAndDurableGenerationMatchStableConstants() {
        assertEquals(CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION, manifest["schemaVersion"]!!.jsonPrimitive.content.toInt())
        // v7 ledger (RFC 077): the additive optional `$.fact.deletedAt` key, same schema 6 bytes otherwise.
        // v8 ledger: the additive optional action-card keys (sourceServerId, targetServerName,
        // confirmationVersion, presentation); a card without them keeps its schema 6 bytes.
        // v9 ledger (task #43): optional executedByUserId on the card and the per-kind optional result keys.
        assertEquals(9, manifest["manifestVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals(CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND, manifest["kind"]!!.jsonPrimitive.content)
        assertEquals(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, manifest["durableConsumerId"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(
                CANONICAL_MESSAGE_V2_UNSUPPORTED_ACTION_METADATA_KIND,
                CANONICAL_MESSAGE_V2_CONTEXT_MEMBERSHIP_MISMATCH,
                VIEWER_OVERLAY_VERSION_CONFLICT_DIAGNOSTIC,
                VIEWER_OVERLAY_VERSION_REGRESSION_DIAGNOSTIC,
                CANONICAL_MESSAGE_V2_BASELINE_REQUIRED,
            ),
            manifest["diagnostics"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            "3bfa4d72cc801e74f7be6e17e1d6dcd4cd76c81bb20008995ca3832e40e780fb",
            MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun exactShapeKeySetsCloseOverEveryCommitADataTransferObject() {
        val shapes = manifest["shapes"]!!.jsonArray.associate { shapeElement ->
            val shape = shapeElement.jsonObject
            shape["path"]!!.jsonPrimitive.content to
                shape["exactKeys"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
        fun assertShape(path: String, type: Class<*>, discriminator: Boolean = false) {
            val actual = declaredFieldNames(type) + if (discriminator) setOf("kind") else emptySet()
            assertEquals(shapes[path], actual, "$path must be exact-bidirectionally closed")
        }

        assertShape("\$", CanonicalMessageEnvelopeV2::class.java)
        assertShape("\$.fact", CanonicalMessageNodeV2::class.java)
        assertShape("\$.fact.mentions[]", CanonicalMentionV2::class.java)
        assertShape("\$.fact.attachments[]", CanonicalAttachmentV2::class.java)
        assertShape("\$.fact.reactions[]", ReactionFact::class.java)
        assertShape("\$.fact.reactions[].previewK[]", ReactionActorRef::class.java)
        assertShape("\$.context{top-level}", CanonicalConversationContextV2.TopLevel::class.java, true)
        assertShape("\$.context{thread}", CanonicalConversationContextV2.Thread::class.java, true)
        listOf(
            TopLevelScopeRefV2.Channel::class.java,
            TopLevelScopeRefV2.Private::class.java,
            TopLevelScopeRefV2.DirectMessage::class.java,
            TopLevelScopeRefV2.Joint::class.java,
        ).forEach { assertShape("topLevelScope{channel|private|dm|joint}", it, true) }
        assertShape("messageRef", MessageRefV2::class.java)
        assertShape("replyScopeRef", ReplyScopeRefV2::class.java)
        assertShape("\$.replyMembership", ReplyMembershipV2::class.java)
        assertShape("\$.fact.actionMetadata{action-card}", CanonicalActionMetadataV2.ActionCard::class.java, true)
        assertShape("actionCard.action", CanonicalActionCardActionV2::class.java)
        assertShape("actionCard.result", CanonicalActionCardResultV2::class.java)
        assertShape("\$.fact.actionMetadata{forwarded-bundle}", CanonicalActionMetadataV2.ForwardedBundle::class.java, true)
        assertShape("forwardedBundle.items[]", CanonicalForwardedBundleItemV2::class.java)
        assertShape("forwardedBundle.items[].author", CanonicalForwardedAuthorSnapshotV2::class.java)
        assertShape("forwardedBundle.items[].target", CanonicalForwardedTargetSnapshotV2::class.java)
        assertShape("forwardedBundle.items[].attachments[]", CanonicalForwardedAttachmentSnapshotV2::class.java)
        assertShape("viewerOverlay", VersionedViewerOverlayV1::class.java)
        assertShape("viewerOverlay.payload{reactions}", ViewerOverlayPayloadV1.Reactions::class.java, true)
    }

    @Test
    fun retractionIsTheOnlyOptionalFactKey() {
        val fact = manifest["shapes"]!!.jsonArray.map { it.jsonObject }.single { it["path"]!!.jsonPrimitive.content == "\$.fact" }
        assertEquals(listOf("deletedAt"), fact["optionalKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        val ledger = manifest["fields"]!!.jsonArray.map { it.jsonObject }.single { it["path"]!!.jsonPrimitive.content == "\$.fact.deletedAt" }
        assertEquals("one-way-present-overwrite", ledger["mergePolicy"]!!.jsonPrimitive.content)
    }

    @Test
    fun actionCardPresentationKeysAreOptionalAndOneWay() {
        val card = manifest["shapes"]!!.jsonArray.map { it.jsonObject }
            .single { it["path"]!!.jsonPrimitive.content == "\$.fact.actionMetadata{action-card}" }
        assertEquals(
            listOf("sourceServerId", "targetServerName", "confirmationVersion", "presentation", "executedByUserId"),
            card["optionalKeys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val ledger = manifest["fields"]!!.jsonArray.map { it.jsonObject }
            .associate { it["path"]!!.jsonPrimitive.content to it["mergePolicy"]!!.jsonPrimitive.content }
        assertEquals("one-way-present-overwrite", ledger["\$.fact.actionMetadata{action-card}.sourceServerId"])
        assertEquals("one-way-present-overwrite", ledger["\$.fact.actionMetadata{action-card}.targetServerName"])
        assertEquals("monotonic-version-gate", ledger["\$.fact.actionMetadata{action-card}.confirmationVersion"])
        assertEquals("one-way-present-overwrite", ledger["\$.fact.actionMetadata{action-card}.presentation"])
        // Task #43 (Tenny): room-common, the card already names who ran it; parent-replace like the name.
        assertEquals("parent-replace", ledger["\$.fact.actionMetadata{action-card}.executedByUserId"])
        val forbidden = manifest["forbiddenAuthoritativePaths"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertFalse("\$.fact.actionMetadata{action-card}.executedByUserId" in forbidden)
        assertTrue("\$.fact.actionMetadata{action-card}.conversionJobId" in forbidden)
    }

    @Test
    fun actionCardResultKeysAreLedgeredPerKind() {
        val shapes = manifest["shapes"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["path"]!!.jsonPrimitive.content }
        val result = shapes.getValue("actionCard.result")
        assertEquals(
            CANONICAL_ACTION_CARD_RESULT_OPTIONAL_KEYS,
            result["optionalKeys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            CANONICAL_ACTION_CARD_RESULT_BASE_KEYS + CANONICAL_ACTION_CARD_RESULT_OPTIONAL_KEYS,
            result["exactKeys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val byKind = shapes.filterKeys { it.startsWith("actionCard.result{") }.map { (path, shape) ->
            path.removePrefix("actionCard.result{").removeSuffix("}") to
                shape["exactKeys"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }.toMap()
        assertEquals(CANONICAL_ACTION_CARD_RESULT_KEYS_BY_KIND, byKind)
        val ledger = manifest["fields"]!!.jsonArray.map { it.jsonObject }.associateBy { it["path"]!!.jsonPrimitive.content }
        (CANONICAL_ACTION_CARD_RESULT_BASE_KEYS + CANONICAL_ACTION_CARD_RESULT_OPTIONAL_KEYS).forEach { key ->
            assertTrue("actionCard.result.$key" in ledger, "ledger entry for actionCard.result.$key")
        }
        CANONICAL_ACTION_CARD_RESULT_KEYS_BY_KIND.values.flatten().forEach { key ->
            assertTrue(key in CANONICAL_ACTION_CARD_RESULT_BASE_KEYS || key in CANONICAL_ACTION_CARD_RESULT_OPTIONAL_KEYS, key)
        }
    }

    @Test
    fun everyLedgerEntryCarriesTheCompleteOwnershipAndMergeClassification() {
        val requiredKeys = setOf(
            "path",
            "kotlinType",
            "nullable",
            "provenance",
            "mergePolicy",
            "producerFaces",
            "orderPolicy",
            "backing",
            "invalidationParentScope",
            "allowedCommands",
        )
        val fields = manifest["fields"]!!.jsonArray.map { it.jsonObject }
        assertTrue(fields.isNotEmpty())
        assertEquals(fields.size, fields.map { it["path"]!!.jsonPrimitive.content }.toSet().size, "ledger paths unique")
        fields.forEach { field -> assertEquals(requiredKeys, field.keys, "incomplete ledger entry: $field") }
    }

    @Test
    fun v1RawDebtAndUnversionedPrivateProvenanceAreExplicitlyForbidden() {
        val forbidden = manifest["forbiddenAuthoritativePaths"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("$.fact.threadId" in forbidden)
        assertTrue("$.fact.conversationContext" in forbidden)
        assertTrue("$.fact.commentRef" in forbidden)
        assertTrue("$.fact.reactions[].reactorIds" in forbidden)
        assertTrue("$.fact.reactions[].reactorNames" in forbidden)
        assertTrue("$.fact.actionMetadata.*.provenanceState" in forbidden)
        assertTrue("viewerOverlay.payload.attachmentCommentCount" in forbidden)
        assertTrue("viewerOverlay.payload.forwardedProvenance" in forbidden)
    }

    private fun declaredFieldNames(type: Class<*>): Set<String> =
        type.declaredFields.asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" || it == "serialVersionUID" }
            .toSet()
}

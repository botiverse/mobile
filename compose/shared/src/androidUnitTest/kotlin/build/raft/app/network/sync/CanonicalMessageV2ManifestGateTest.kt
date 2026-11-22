package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Commit-A structural gate for the complete schema-v6 ledger; no decoder/normalizer behavior lives here. */
class CanonicalMessageV2ManifestGateTest {
    private val manifestBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/$CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE")) {
            "$CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE missing"
        }.readBytes()
    }
    private val manifest by lazy { Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }

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

    private fun declaredFieldNames(type: Class<*>): Set<String> =
        type.declaredFields.asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" || it == "serialVersionUID" }
            .toSet()
}

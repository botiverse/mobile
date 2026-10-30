package build.raft.app.data

import build.raft.app.model.ExternalMessageAuthor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Wire shape of `externalAuthor` on a `senderType=external_projection` message.
 * Field names match the canonical server emit (`externalMessageAuthorFacts` →
 * `toAuthorProjection`); only the human-facing subset is parsed, the rest is
 * carried for provenance but never used for authority.
 */
@Serializable
data class WireExternalAuthor(
    val displayName: String? = null,
    val provider: String? = null,
    val actorKind: String? = null,
    val avatarUrl: String? = null,
    val avatarDigest: String? = null,
    val projectionId: String? = null,
    val externalActorId: String? = null,
    val externalConversationId: String? = null,
    val externalMessageId: String? = null,
    val workspaceId: String? = null,
    val installId: String? = null,
    val appRegistrationId: String? = null,
    val actorProjectionRevision: Long? = null,
)

/**
 * `externalAuthor` slot decoder: a non-object value (string, array, number) decodes as an empty
 * author instead of failing the whole message/page decode, so the message still renders with the
 * fallback author built by [toExternalMessageAuthor].
 */
internal object LenientWireExternalAuthorSerializer :
    JsonTransformingSerializer<WireExternalAuthor>(WireExternalAuthor.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        element as? JsonObject ?: JsonObject(emptyMap())
}

/**
 * English stand-in for an external author with no display name on the wire (server
 * `getSenderSnapshot` uses the same words). Rows carrying it set
 * [ExternalMessageAuthor.displayNameIsFallback]; surfaces show the localized
 * `message_author_external_fallback` instead (see `ExternalAuthorShownName.kt`).
 */
internal const val EXTERNAL_AUTHOR_FALLBACK_DISPLAY_NAME = "External user"

internal fun WireExternalAuthor.toExternalMessageAuthor(
    senderNameFallback: String?,
): ExternalMessageAuthor {
    val resolvedName =
        displayName?.takeIf { it.isNotBlank() }
            ?: senderNameFallback?.takeIf { it.isNotBlank() }
    return ExternalMessageAuthor(
        displayName = resolvedName ?: EXTERNAL_AUTHOR_FALLBACK_DISPLAY_NAME,
        // Absent provider stays blank: no provider subtitle, and the avatar hint uses the generic label.
        provider = provider?.trim().orEmpty(),
        actorKind = actorKind?.takeIf { it.isNotBlank() } ?: "unknown",
        avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
        avatarDigest = avatarDigest?.takeIf { it.isNotBlank() },
        projectionId = projectionId?.takeIf { it.isNotBlank() },
        externalActorId = externalActorId?.takeIf { it.isNotBlank() },
        externalConversationId = externalConversationId?.takeIf { it.isNotBlank() },
        externalMessageId = externalMessageId?.takeIf { it.isNotBlank() },
        workspaceId = workspaceId?.takeIf { it.isNotBlank() },
        installId = installId?.takeIf { it.isNotBlank() },
        appRegistrationId = appRegistrationId?.takeIf { it.isNotBlank() },
        actorProjectionRevision = actorProjectionRevision,
        displayNameIsFallback = resolvedName == null,
    )
}

/**
 * The author shown for an External sender. A missing (or malformed) `externalAuthor` no longer
 * drops the message: it renders with a fallback author built from the message's own sender
 * name, keeping whatever real fields the wire carried. Reports a `projection_value_defaulted`
 * diagnostic so the server-side gap stays visible.
 */
internal fun externalMessageAuthorOrFallback(
    wire: WireExternalAuthor?,
    senderNameFallback: String?,
    source: String,
    messageId: String,
    diagnostics: (String) -> Unit,
): ExternalMessageAuthor {
    if (wire == null) {
        diagnostics("projection_value_defaulted source=$source field=externalAuthor value=missing messageId=$messageId")
    }
    return (wire ?: WireExternalAuthor()).toExternalMessageAuthor(senderNameFallback)
}

/**
 * Sender id for an External message. The wire id is kept when present; a blank one becomes a
 * stable synthetic key (projection → external actor → message) so per-sender maps and grouping
 * stay deterministic. It is never a navigation target: External authors never resolve to a
 * member-detail identity (`MemberDetailIdentity`), and avatar taps show the external hint.
 */
internal fun externalSenderIdOrSyntheticKey(
    senderId: String?,
    author: ExternalMessageAuthor,
    messageId: String,
): String =
    senderId?.trim()?.takeIf { it.isNotBlank() }
        ?: author.projectionId?.let { "external:projection:$it" }
        ?: author.externalActorId?.let { "external:actor:$it" }
        ?: "external:message:$messageId"

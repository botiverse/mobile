package build.raft.app.data

import build.raft.app.model.ExternalMessageAuthor
import kotlinx.serialization.Serializable

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

internal fun WireExternalAuthor.toExternalMessageAuthor(
    senderNameFallback: String?,
): ExternalMessageAuthor =
    ExternalMessageAuthor(
        displayName = displayName?.takeIf { it.isNotBlank() }
            ?: senderNameFallback?.takeIf { it.isNotBlank() }
            ?: "External user",
        provider = provider?.takeIf { it.isNotBlank() } ?: "external",
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
    )

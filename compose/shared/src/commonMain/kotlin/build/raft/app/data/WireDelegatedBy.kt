package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.MessageDelegatedBy
import kotlinx.serialization.Serializable

/**
 * RFC 077 wire `delegatedBy` (server `DelegatedByLabel`): the assistant that sent a
 * message for its owner. botiverse/slock `packages/server/src/services/messageService.ts`
 * `DelegatedByLabel = { id, name, displayName, avatarUrl, deleted }`, put on every message
 * row (REST + `message:new` / `message:updated`, `messageRealtimeProducerRegistry.ts`) as
 * `delegatedBy: DelegatedByLabel | null`, on search hits (`searchService.ts`
 * `MessageSearchResult.delegatedBy`) and on Activity rows as `latestDelegatedBy`
 * (`withLatestDelegatedBy`). The agent API's snake_case `delegated_by: { name }` is a
 * different (agent-only) surface and is never read here.
 */
@Serializable
data class WireDelegatedBy(
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val deleted: Boolean = false,
)

/**
 * Web `delegatedMessage.ts` `readDelegatedBy`: no id → no label; the display name, else the
 * handle; no name left reads as deleted, so the words never go blank.
 */
internal fun WireDelegatedBy?.toMessageDelegatedBy(): MessageDelegatedBy? {
    val wire = this ?: return null
    val id = wire.id?.takeIf { it.isNotEmpty() } ?: return null
    val name = wire.displayName?.takeIf { it.isNotBlank() } ?: wire.name.orEmpty()
    return MessageDelegatedBy(
        id = id,
        name = name,
        avatarUrl = wire.avatarUrl,
        deleted = wire.deleted || name.isEmpty(),
    )
}

/** Web reads the label only on a person's message (`senderType === "user"`). */
internal fun WireDelegatedBy?.toMessageDelegatedBy(senderKind: MemberKind?): MessageDelegatedBy? =
    if (senderKind == MemberKind.Human) toMessageDelegatedBy() else null

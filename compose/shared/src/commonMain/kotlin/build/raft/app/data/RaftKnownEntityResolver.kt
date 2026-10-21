package build.raft.app.data

import build.raft.app.model.ServerId

/**
 * The identity a pushed page renders for. A known-entity candidate is usable only when it was
 * collected for this exact account and server (AGENTS.md: Route / PageData MVI Contract Rule).
 */
internal data class KnownEntityScope(
    val accountId: String,
    val serverId: ServerId,
) {
    fun normalizedOrNull(): KnownEntityScope? {
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val server = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
        return KnownEntityScope(accountId = account, serverId = ServerId(server))
    }
}

/** One in-memory store that may already hold a conversation row (Home sidebar, identity hub, ...). */
internal fun interface KnownConversationSource {
    fun conversation(scope: KnownEntityScope, id: String): SharedConversationDirectoryProjection?
}

/**
 * Single lookup for "what does the app already know about this entity" so a pushed page can paint
 * its first frame from memory while its canonical load runs in the background.
 *
 * Results are candidate snapshots: they are identity-checked here (account + server + id) and the
 * caller must still let canonical page state win over them. Sources are consulted in order; the
 * first identity-matching row wins. Members, tasks and computers plug in as further typed lookups
 * on this same resolver.
 */
internal class RaftKnownEntityResolver(
    private val conversationSources: List<KnownConversationSource>,
) {
    fun conversation(
        scope: KnownEntityScope,
        ids: List<String>,
    ): SharedConversationDirectoryProjection? {
        val normalizedScope = scope.normalizedOrNull() ?: return null
        return ids
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .firstNotNullOfOrNull { id ->
                conversationSources.firstNotNullOfOrNull { source ->
                    source.conversation(normalizedScope, id)
                        ?.takeIf { it.matchesKnownIdentity(normalizedScope, id) }
                }
            }
    }

    companion object {
        val Empty = RaftKnownEntityResolver(emptyList())
    }
}

private fun SharedConversationDirectoryProjection.matchesKnownIdentity(
    scope: KnownEntityScope,
    id: String,
): Boolean =
    serverId.trim() == scope.serverId.value &&
        (canonicalChannelId.trim() == id || aliases.any { it.trim() == id })

/**
 * Production wiring: the authority-checked identity hub first, then the live Home store (which
 * keeps every visited server's rows, and survives hub resets on auth-revision rotation). Stores
 * are looked up per call so a Home store created after the page mounted is still consulted.
 */
internal fun raftKnownEntityResolver(
    identityHub: () -> SharedConversationIdentityHub?,
    homeStore: () -> SharedHomeStore?,
): RaftKnownEntityResolver =
    RaftKnownEntityResolver(
        conversationSources =
            listOf(
                KnownConversationSource { scope, id ->
                    identityHub()?.resolveProjectionFor(scope.accountId, scope.serverId, id)
                },
                KnownConversationSource { scope, id ->
                    homeStore()?.knownConversationCandidate(scope.accountId, scope.serverId, id)
                },
            ),
    )

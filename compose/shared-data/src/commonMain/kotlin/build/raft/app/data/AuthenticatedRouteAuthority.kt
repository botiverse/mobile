package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * Durable identity-only owner for authenticated child routes. Tokens and
 * generations intentionally never cross pageData/process restoration.
 */
@RaftCoreInternalApi data class AuthenticatedRouteOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
)

/**
 * In-memory proof captured immediately before one route operation. The token
 * witness is transient and is used only by the auth middleware to reject a
 * request if the live session changed before transport execution.
 */
@RaftCoreInternalApi data class AuthenticatedRouteAuthorityReceipt(
    val owner: AuthenticatedRouteOwner,
    val authGeneration: Long,
    val selectionGeneration: Long,
    val canManageSettings: Boolean,
    val expectedAuthBinding: RaftExpectedAuthBinding,
)

@RaftCoreInternalApi interface AuthenticatedRouteAuthorityGateway {
    suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt?

    suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean

    fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient
}

@RaftCoreInternalApi const val AUTHENTICATED_ROUTE_AUTHORITY_CHANGED = "authenticated_route_authority_changed"

@RaftCoreInternalApi class AuthenticatedRouteOperationRunner(
    private val owner: AuthenticatedRouteOwner,
    private val gateway: AuthenticatedRouteAuthorityGateway,
) {
    suspend fun <T> run(
        block: suspend (
            receipt: AuthenticatedRouteAuthorityReceipt,
            networkClient: RaftNetworkClient,
        ) -> DataResult<T>,
    ): DataResult<T> {
        val receipt = gateway.capture(owner) ?: return authenticatedRouteAuthorityChanged()
        val result = block(receipt, gateway.networkClient(receipt))
        return if (gateway.isCurrent(receipt)) result else authenticatedRouteAuthorityChanged()
    }
}

@RaftCoreInternalApi fun <T> authenticatedRouteAuthorityChanged(): DataResult<T> =
    DataResult.Error(
        ScreenError(
            code = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
            retryable = false,
        )
    )

@RaftCoreInternalApi fun ThreadReplyCacheIdentitySnapshot.toAuthenticatedRouteAuthorityReceipt(
    expectedOwner: AuthenticatedRouteOwner,
): AuthenticatedRouteAuthorityReceipt? {
    val normalizedOwner = expectedOwner.normalizedOrNull() ?: return null
    if (
        environmentId.normalizedEnvironmentId() != normalizedOwner.environmentId ||
        principalId.trim() != normalizedOwner.accountId ||
        serverId.trim() != normalizedOwner.serverId
    ) {
        return null
    }
    if (
        session.user.id.trim() != normalizedOwner.accountId ||
        session.currentServerId?.trim() != normalizedOwner.serverId ||
        session.accessToken.isBlank()
    ) {
        return null
    }
    return AuthenticatedRouteAuthorityReceipt(
        owner = normalizedOwner,
        authGeneration = authGeneration,
        selectionGeneration = selectionGeneration,
        canManageSettings =
            session.servers
                .firstOrNull { it.id == normalizedOwner.serverId }
                ?.toServerSummary()
                ?.permissions
                ?.canManageSettings == true,
        expectedAuthBinding =
            RaftExpectedAuthBinding(
                accountId = normalizedOwner.accountId,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                serverId = normalizedOwner.serverId,
                environmentId = normalizedOwner.environmentId,
            ),
    )
}

@RaftCoreInternalApi fun ThreadReplyCacheIdentitySnapshot.matchesAuthenticatedRouteAuthority(
    receipt: AuthenticatedRouteAuthorityReceipt,
): Boolean = toAuthenticatedRouteAuthorityReceipt(receipt.owner) == receipt

@RaftCoreInternalApi fun AuthenticatedRouteOwner.normalizedOrNull(): AuthenticatedRouteOwner? {
    val environment = environmentId.normalizedEnvironmentId().takeIf { it.isNotBlank() } ?: return null
    val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
    val server = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    return AuthenticatedRouteOwner(environmentId = environment, accountId = account, serverId = server)
}

@RaftCoreInternalApi fun authenticatedRouteCandidateCacheScopeId(
    owner: AuthenticatedRouteOwner,
    authGeneration: Long,
    selectionGeneration: Long,
): String {
    val normalizedOwner = owner.normalizedOrNull() ?: return "invalid-authenticated-route-owner"
    return buildString {
        append("authenticated-route:")
        append(normalizedOwner.environmentId.length)
        append(':')
        append(normalizedOwner.environmentId)
        append(':')
        append(normalizedOwner.accountId.length)
        append(':')
        append(normalizedOwner.accountId)
        append(':')
        append(normalizedOwner.serverId.length)
        append(':')
        append(normalizedOwner.serverId)
        append(":auth-generation:")
        append(authGeneration)
        append(":selection-generation:")
        append(selectionGeneration)
    }
}

/**
 * Process-local candidate-cache namespace for one authenticated route owner.
 * Length prefixes keep arbitrary custom environment URLs/account ids from
 * aliasing each other without persisting any token or generation witness.
 */
@RaftCoreInternalApi fun AuthenticatedRouteAuthorityReceipt.candidateCacheScopeId(): String =
    authenticatedRouteCandidateCacheScopeId(
        owner = owner,
        authGeneration = authGeneration,
        selectionGeneration = selectionGeneration,
    )

@RaftCoreInternalApi fun String.normalizedEnvironmentId(): String = trim().trimEnd('/')

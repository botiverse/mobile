package build.raft.app.data

/**
 * Durable identity-only owner for authenticated child routes. Tokens and
 * generations intentionally never cross pageData/process restoration.
 */
internal data class AuthenticatedRouteOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
)

/**
 * In-memory proof captured immediately before one route operation. The token
 * witness is transient and is used only by the auth middleware to reject a
 * request if the live session changed before transport execution.
 */
internal data class AuthenticatedRouteAuthorityReceipt(
    val owner: AuthenticatedRouteOwner,
    val authGeneration: Long,
    val selectionGeneration: Long,
    val canManageSettings: Boolean,
    val expectedAuthBinding: RaftExpectedAuthBinding,
)

internal interface AuthenticatedRouteAuthorityGateway {
    suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt?

    suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean

    fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient
}

internal const val AUTHENTICATED_ROUTE_AUTHORITY_CHANGED = "authenticated_route_authority_changed"

internal class AuthenticatedRouteOperationRunner(
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

internal fun <T> authenticatedRouteAuthorityChanged(): DataResult<T> =
    DataResult.Error(
        ScreenError(
            code = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
            retryable = false,
        )
    )

internal fun ThreadReplyCacheIdentitySnapshot.toAuthenticatedRouteAuthorityReceipt(
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

internal fun ThreadReplyCacheIdentitySnapshot.matchesAuthenticatedRouteAuthority(
    receipt: AuthenticatedRouteAuthorityReceipt,
): Boolean = toAuthenticatedRouteAuthorityReceipt(receipt.owner) == receipt

internal fun AuthenticatedRouteOwner.normalizedOrNull(): AuthenticatedRouteOwner? {
    val environment = environmentId.normalizedEnvironmentId().takeIf { it.isNotBlank() } ?: return null
    val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
    val server = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    return AuthenticatedRouteOwner(environmentId = environment, accountId = account, serverId = server)
}

/**
 * Process-local candidate-cache namespace for one authenticated route owner.
 * Length prefixes keep arbitrary custom environment URLs/account ids from
 * aliasing each other without persisting any token or generation witness.
 */
internal fun AuthenticatedRouteAuthorityReceipt.candidateCacheScopeId(): String {
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

internal fun String.normalizedEnvironmentId(): String = trim().trimEnd('/')

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.network.RaftFailedRequestTraceIds
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val managedMcpConnectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Managed MCP App connection authorization & account choice API (RFC 087 §7.1, `#wg-mobile-sync:#228`,
 * parity with `botiverse/slock` `#15635`, `#16087`, `#16241`, `#16986`):
 *
 * - `GET /api/mcp/servers/:appId/connection-authorization?agentId=:agentId`
 * - `PUT /api/mcp/connections/:connectionId/agents/:agentId`
 * - `PUT /api/mcp/agents/:agentId/assignments/:appId`
 * - `POST /api/mcp/apps/:appId/connections/oauth/start`
 * - `GET /api/mcp/oauth/attempts/:attemptId`
 */
@RaftCoreInternalApi enum class McpConnectionAuthKind(val wire: String) {
    None("none"),
    Headers("headers"),
    OAuth("oauth");

    companion object {
        fun fromWire(raw: String?): McpConnectionAuthKind? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

@RaftCoreInternalApi enum class McpConnectionViewerRole(val wire: String) {
    ServerManager("server_manager"),
    AgentManager("agent_manager"),
    Observer("observer");

    companion object {
        fun fromWire(raw: String?): McpConnectionViewerRole? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

@RaftCoreInternalApi enum class McpAccountKind(val wire: String) {
    Personal("personal"),
    Shared("shared");

    companion object {
        fun fromWire(raw: String?): McpAccountKind? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

@RaftCoreInternalApi enum class McpConnectionStatus(val wire: String) {
    Ready("ready"),
    Pending("pending"),
    NeedsAuth("needs_auth"),
    Expired("expired"),
    Error("error");

    companion object {
        fun fromWire(raw: String?): McpConnectionStatus? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

@RaftCoreInternalApi enum class McpOAuthAttemptStatus(val wire: String) {
    Pending("pending"),
    Connected("connected"),
    Failed("failed"),
    Expired("expired");

    companion object {
        fun fromWire(raw: String?): McpOAuthAttemptStatus? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

@RaftCoreInternalApi enum class McpOAuthTokenEndpointAuthMethod(val wire: String) {
    ClientSecretPost("client_secret_post"),
    ClientSecretBasic("client_secret_basic"),
    None("none"),
}

@RaftCoreInternalApi data class McpConnectionAuthorizationApp(
    val name: String,
    val authKind: McpConnectionAuthKind,
)

@RaftCoreInternalApi data class McpAccountOption(
    val connectionId: String,
    val name: String?,
    val label: String?,
    val status: McpConnectionStatus,
    val agentIds: List<String> = emptyList(),
)

@RaftCoreInternalApi data class McpSharedAccountOption(
    val connectionId: String,
    val name: String?,
    val label: String?,
    val status: McpConnectionStatus,
    val agentIds: List<String> = emptyList(),
    val allAgents: Boolean = false,
    val signedInByUserId: String? = null,
    val signedInByDisplayName: String? = null,
) {
    fun toAccountOption(): McpAccountOption =
        McpAccountOption(
            connectionId = connectionId,
            name = name,
            label = label,
            status = status,
            agentIds = agentIds,
        )
}

@RaftCoreInternalApi data class McpPersonalAccountOptions(
    val connections: List<McpAccountOption> = emptyList(),
    val canConnectPersonal: Boolean = false,
)

@RaftCoreInternalApi data class McpSharedAccountOptions(
    val connections: List<McpSharedAccountOption> = emptyList(),
    val canConnectShared: Boolean = false,
)

@RaftCoreInternalApi data class McpConnectionAuthorizationOptions(
    val personal: McpPersonalAccountOptions,
    val shared: McpSharedAccountOptions? = null,
)

@RaftCoreInternalApi data class McpCurrentAccountSummary(
    val kind: McpAccountKind,
    val connectionId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val ownerUserId: String? = null,
    val ownerDisplayName: String? = null,
)

@RaftCoreInternalApi data class McpConnectionAuthorizationView(
    val viewerRole: McpConnectionViewerRole,
    val app: McpConnectionAuthorizationApp?,
    /** Present while server flag `mcp_personal_accounts` is ON; null when OFF on the server. */
    val options: McpConnectionAuthorizationOptions? = null,
    /** The account the Agent currently uses when any (null when none). */
    val account: McpCurrentAccountSummary? = null,
    val canAuthorizeNew: Boolean = false,
    val canSetServerDefault: Boolean = false,
    val newConnectionPinnable: Boolean = false,
)

/**
 * Optional registered OAuth client parameters for `POST /api/mcp/apps/:appId/connections/oauth/start`
 * (botiverse/slock `#16986`: accepted for both `personal` and `shared` accounts).
 */
@RaftCoreInternalApi data class McpOAuthClientInput(
    val clientId: String,
    val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
    val clientSecret: String? = null,
    val scope: String? = null,
    val requestOfflineAccess: Boolean? = null,
)

@RaftCoreInternalApi data class McpOAuthSignInIntent(
    val agentIds: List<String> = emptyList(),
    val actionCardMessageId: String? = null,
)

@RaftCoreInternalApi data class McpOAuthStartRequest(
    val kind: McpAccountKind,
    val name: String,
    val makeDefault: Boolean = false,
    val connectionId: String? = null,
    val oauthClient: McpOAuthClientInput? = null,
    val intent: McpOAuthSignInIntent? = null,
)

@RaftCoreInternalApi data class McpOAuthStartResult(
    val authorizationUrl: String,
    val attemptId: String,
    val connectionId: String,
)

@RaftCoreInternalApi data class McpOAuthAttemptResult(
    val attemptId: String,
    val status: McpOAuthAttemptStatus,
    val connectionId: String?,
)

/**
 * One radio choice in the Connect card account picker (mirror of web `mcpCardAccountChoices.ts`,
 * `#16087` + `#16241`).
 */
@RaftCoreInternalApi sealed interface McpCardAccountChoice {
    val key: String
    val kind: McpAccountKind

    data class Existing(
        override val key: String,
        override val kind: McpAccountKind,
        val option: McpAccountOption,
        val allAgents: Boolean = false,
    ) : McpCardAccountChoice

    data class New(
        override val key: String,
        override val kind: McpAccountKind,
    ) : McpCardAccountChoice
}

/**
 * Derives the ordered list of account choices for the Connect card from [view]:
 * 1. Viewer's own ready personal accounts (`status == Ready`, `#16241` — non-ready personal
 *    accounts are never listed on the card because granting them would fail or require reconnect).
 * 2. Team/shared accounts when visible to a server manager (including non-ready defaults shown
 *    disabled with their status text).
 * 3. New sign-in options for OAuth Apps (`new:personal` when `canConnectPersonal`, `new:shared`
 *    when `canConnectShared`). API-key (`headers`) and `none` Apps never offer new sign-in here.
 */
@RaftCoreInternalApi fun mcpCardAccountChoices(view: McpConnectionAuthorizationView): List<McpCardAccountChoice> {
    val options = view.options ?: return emptyList()
    val choices = mutableListOf<McpCardAccountChoice>()
    for (option in options.personal.connections) {
        if (option.status != McpConnectionStatus.Ready) continue
        choices +=
            McpCardAccountChoice.Existing(
                key = option.connectionId,
                kind = McpAccountKind.Personal,
                option = option,
                allAgents = false,
            )
    }
    val shared = options.shared
    if (shared != null) {
        for (option in shared.connections) {
            choices +=
                McpCardAccountChoice.Existing(
                    key = option.connectionId,
                    kind = McpAccountKind.Shared,
                    option = option.toAccountOption(),
                    allAgents = option.allAgents,
                )
        }
    }
    val oauth = (view.app?.authKind ?: McpConnectionAuthKind.OAuth) == McpConnectionAuthKind.OAuth
    if (oauth && options.personal.canConnectPersonal) {
        choices += McpCardAccountChoice.New(key = "new:personal", kind = McpAccountKind.Personal)
    }
    if (oauth && shared?.canConnectShared == true) {
        choices += McpCardAccountChoice.New(key = "new:shared", kind = McpAccountKind.Shared)
    }
    return choices
}

/** Whether [choice] can be picked and submitted (mirror of web `mcpCardChoiceUsable`). */
@RaftCoreInternalApi fun mcpCardChoiceUsable(choice: McpCardAccountChoice): Boolean =
    when (choice) {
        is McpCardAccountChoice.New -> true
        is McpCardAccountChoice.Existing -> choice.option.status == McpConnectionStatus.Ready
    }

/**
 * The choice preselected when the Connect card opens (mirror of web `initialMcpCardChoice`):
 * viewer's first ready personal account -> else the Agent's current ready account -> else the
 * ready "All agents" team account -> else the first usable choice.
 */
@RaftCoreInternalApi fun initialMcpCardChoice(
    choices: List<McpCardAccountChoice>,
    view: McpConnectionAuthorizationView,
    agentId: String,
): String? {
    val usable = choices.filter(::mcpCardChoiceUsable)
    if (usable.isEmpty()) return null
    usable.firstOrNull { it is McpCardAccountChoice.Existing && it.kind == McpAccountKind.Personal }?.let { return it.key }
    val currentId = view.account?.connectionId
    usable.firstOrNull { choice ->
        choice is McpCardAccountChoice.Existing &&
            ((currentId != null && choice.option.connectionId == currentId) || agentId in choice.option.agentIds)
    }?.let { return it.key }
    usable.firstOrNull { it is McpCardAccountChoice.Existing && it.kind == McpAccountKind.Shared && it.allAgents }?.let { return it.key }
    return usable.first().key
}

/**
 * Validates an MCP OAuth `authorizationUrl` returned by the server (mirror of web `isSafeOAuthUrl`):
 * only `https:` URLs (and loopback `http:` for local dev: `localhost`, `127.0.0.1`, `[::1]`)
 * with a non-empty host and no embedded credentials (`userinfo@`) or control characters are allowed.
 */
@RaftCoreInternalApi fun isSafeMcpAuthorizationUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any { it <= ' ' || it == '\u007F' }) return false
    val schemeSep = trimmed.indexOf("://")
    if (schemeSep <= 0) return false
    val scheme = trimmed.substring(0, schemeSep).lowercase()
    if (scheme != "https" && scheme != "http") return false
    val afterScheme = trimmed.substring(schemeSep + 3)
    if (afterScheme.isEmpty()) return false
    val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return false
    val host: String
    val portPart: String?
    if (authority.startsWith("[")) {
        val closeBracket = authority.indexOf(']')
        if (closeBracket <= 1) return false
        host = authority.substring(0, closeBracket + 1).lowercase()
        val rest = authority.substring(closeBracket + 1)
        portPart =
            when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return false
            }
    } else {
        val colon = authority.indexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon).lowercase()
            portPart = authority.substring(colon + 1)
        } else {
            host = authority.lowercase()
            portPart = null
        }
    }
    if (host.isEmpty()) return false
    if (portPart != null) {
        val port = portPart.toIntOrNull() ?: return false
        if (port !in 1..65535) return false
    }
    if (scheme == "http") {
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    }
    return true
}

/** Known MCP App refusal codes mapped to localized copy (mirror of web `mcpAppApi.ts`). */
@RaftCoreInternalApi enum class McpAppErrorCode {
    RateLimited,
    Unreachable,
    CredentialsRejected,
    OAuthBusy,
    OAuthRegistrationRejected,
    OAuthRegistrationUnsupported,
    OAuthDiscoveryFailed,
    OAuthSetupFailed,
    ConnectionRequestInvalid,
    HeaderNamesUnset,
    OAuthUnsafeUrl,
    Forbidden,
    Generic,
}

@RaftCoreInternalApi const val MCP_OAUTH_UNSAFE_URL_ERROR_CODE: String = "mcp_oauth_unsafe_url"
@RaftCoreInternalApi const val MCP_OAUTH_ERROR_SERVER_FIELD: String = "oauthError"

@RaftCoreInternalApi fun ScreenError.mcpAppErrorCode(): McpAppErrorCode {
    val normalized = (serverCode ?: code).trim().lowercase()
    return when (normalized) {
        "mcp_rate_limited", "http_429" -> McpAppErrorCode.RateLimited
        "mcp_server_unreachable" -> McpAppErrorCode.Unreachable
        "mcp_credentials_rejected" -> McpAppErrorCode.CredentialsRejected
        "mcp_oauth_in_progress" -> McpAppErrorCode.OAuthBusy
        "mcp_oauth_dcr_rejected" -> McpAppErrorCode.OAuthRegistrationRejected
        "mcp_oauth_dcr_unsupported" -> McpAppErrorCode.OAuthRegistrationUnsupported
        "mcp_oauth_discovery_failed" -> McpAppErrorCode.OAuthDiscoveryFailed
        "mcp_oauth_setup_failed" -> McpAppErrorCode.OAuthSetupFailed
        "mcp_connection_request_invalid" -> McpAppErrorCode.ConnectionRequestInvalid
        "mcp_header_names_unset" -> McpAppErrorCode.HeaderNamesUnset
        MCP_OAUTH_UNSAFE_URL_ERROR_CODE -> McpAppErrorCode.OAuthUnsafeUrl
        "http_403", "forbidden", "insufficient_role" -> McpAppErrorCode.Forbidden
        else -> McpAppErrorCode.Generic
    }
}

@RaftCoreInternalApi fun ScreenError.mcpOAuthProviderError(): String? =
    serverFields[MCP_OAUTH_ERROR_SERVER_FIELD]?.trim()?.takeIf { it.isNotEmpty() }

// ── Wire Request Bodies ──

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class McpFollowDefaultAssignmentBody(
    val enabled: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val connectionId: String? = null,
)

@Serializable
private data class McpEnsureEnabledAssignmentBody(
    val enabled: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class McpOAuthClientBody(
    val clientId: String,
    val tokenEndpointAuthMethod: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clientSecret: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val scope: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requestOfflineAccess: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class McpOAuthIntentBody(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val agentIds: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val actionCardMessageId: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class McpOAuthStartBody(
    val kind: String,
    val name: String,
    val makeDefault: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val connectionId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val oauthClient: McpOAuthClientBody? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val intent: McpOAuthIntentBody? = null,
)

/** Body of `PUT /api/mcp/agents/:agentId/assignments/:appId` to follow the team default account. */
@RaftCoreInternalApi fun buildMcpFollowDefaultBody(): String =
    managedMcpConnectJson.encodeToString(McpFollowDefaultAssignmentBody())

/** Body of `PUT /api/mcp/agents/:agentId/assignments/:appId` to enable the App for an Agent. */
@RaftCoreInternalApi fun buildMcpEnsureEnabledBody(): String =
    managedMcpConnectJson.encodeToString(McpEnsureEnabledAssignmentBody())

/** Body of `POST /api/mcp/apps/:appId/connections/oauth/start`. */
@RaftCoreInternalApi fun buildMcpOAuthStartBody(request: McpOAuthStartRequest): String =
    managedMcpConnectJson.encodeToString(
        McpOAuthStartBody(
            kind = request.kind.wire,
            name = request.name,
            makeDefault = request.makeDefault,
            connectionId = request.connectionId?.trim()?.takeIf { it.isNotEmpty() },
            oauthClient =
                request.oauthClient?.let { client ->
                    McpOAuthClientBody(
                        clientId = client.clientId,
                        tokenEndpointAuthMethod = client.tokenEndpointAuthMethod.wire,
                        clientSecret = client.clientSecret?.takeIf { it.isNotEmpty() },
                        scope = client.scope?.trim()?.takeIf { it.isNotEmpty() },
                        requestOfflineAccess = client.requestOfflineAccess,
                    )
                },
            intent =
                request.intent?.let { intent ->
                    val agents = intent.agentIds.map(String::trim).filter(String::isNotEmpty)
                    val cardId = intent.actionCardMessageId?.trim()?.takeIf { it.isNotEmpty() }
                    if (agents.isEmpty() && cardId == null) {
                        null
                    } else {
                        McpOAuthIntentBody(
                            agentIds = agents.takeIf { it.isNotEmpty() },
                            actionCardMessageId = cardId,
                        )
                    }
                },
        )
    )

// ── Transport & API Client ──

@RaftCoreInternalApi interface RaftManagedMcpConnectApi {
    suspend fun getConnectionAuthorization(
        appId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse

    suspend fun addConnectionAgent(
        connectionId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse

    suspend fun putAgentAssignment(
        agentId: String,
        appId: String,
        body: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse

    suspend fun startConnectionOAuth(
        appId: String,
        body: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse

    suspend fun pollOAuthAttempt(
        attemptId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse
}

@RaftCoreInternalApi class RaftManagedMcpConnectNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftManagedMcpConnectApi {
    override suspend fun getConnectionAuthorization(
        appId: String,
        agentId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "mcp/servers/${encodeMcpPathSegment(appId)}/connection-authorization",
            query = listOf("agentId" to agentId),
            serverIdOverride = serverIdOverride,
        )

    override suspend fun addConnectionAgent(
        connectionId: String,
        agentId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "mcp/connections/${encodeMcpPathSegment(connectionId)}/agents/${encodeMcpPathSegment(agentId)}",
            method = "PUT",
            bodyText = "{}",
            serverIdOverride = serverIdOverride,
        )

    override suspend fun putAgentAssignment(
        agentId: String,
        appId: String,
        body: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "mcp/agents/${encodeMcpPathSegment(agentId)}/assignments/${encodeMcpPathSegment(appId)}",
            method = "PUT",
            bodyText = body,
            serverIdOverride = serverIdOverride,
        )

    override suspend fun startConnectionOAuth(
        appId: String,
        body: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "mcp/apps/${encodeMcpPathSegment(appId)}/connections/oauth/start",
            method = "POST",
            bodyText = body,
            serverIdOverride = serverIdOverride,
        )

    override suspend fun pollOAuthAttempt(
        attemptId: String,
        serverIdOverride: ServerId?,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "mcp/oauth/attempts/${encodeMcpPathSegment(attemptId)}",
            serverIdOverride = serverIdOverride,
        )
}

@RaftCoreInternalApi class RaftManagedMcpConnectApiClient(
    private val transport: RaftManagedMcpConnectApi,
) {
    suspend fun getConnectionAuthorization(
        appId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): DataResult<McpConnectionAuthorizationView> {
        val response = transport.getConnectionAuthorization(appId, agentId, serverIdOverride)
        if (!response.isSuccessful) return response.toMcpFailure("mcp_connection_authorization_failed")
        val body =
            response.bodyText?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "mcp_connection_authorization_contract_mismatch", retryable = false, traceId = response.traceId))
        val wire =
            try {
                managedMcpConnectJson.decodeFromString(McpConnectionAuthorizationViewDto.serializer(), body)
            } catch (_: SerializationException) {
                return DataResult.Error(ScreenError(code = "mcp_connection_authorization_contract_mismatch", retryable = false, traceId = response.traceId))
            }
        val mapped =
            wire.toDomain()
                ?: return DataResult.Error(ScreenError(code = "mcp_connection_authorization_contract_mismatch", retryable = false, traceId = response.traceId))
        return DataResult.Content(mapped)
    }

    suspend fun addConnectionAgent(
        connectionId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): DataResult<Unit> {
        val response = transport.addConnectionAgent(connectionId, agentId, serverIdOverride)
        return if (response.isSuccessful) DataResult.Content(Unit) else response.toMcpFailure("mcp_add_connection_agent_failed")
    }

    suspend fun followDefaultAccount(
        appId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): DataResult<Unit> {
        val response = transport.putAgentAssignment(agentId, appId, buildMcpFollowDefaultBody(), serverIdOverride)
        return if (response.isSuccessful) DataResult.Content(Unit) else response.toMcpFailure("mcp_follow_default_failed")
    }

    suspend fun ensureAppEnabled(
        appId: String,
        agentId: String,
        serverIdOverride: ServerId? = null,
    ): DataResult<Unit> {
        val response = transport.putAgentAssignment(agentId, appId, buildMcpEnsureEnabledBody(), serverIdOverride)
        return if (response.isSuccessful) DataResult.Content(Unit) else response.toMcpFailure("mcp_ensure_app_enabled_failed")
    }

    suspend fun startConnectionOAuth(
        appId: String,
        request: McpOAuthStartRequest,
        serverIdOverride: ServerId? = null,
    ): DataResult<McpOAuthStartResult> {
        val response = transport.startConnectionOAuth(appId, buildMcpOAuthStartBody(request), serverIdOverride)
        if (!response.isSuccessful) return response.toMcpFailure("mcp_oauth_start_failed")
        val body =
            response.bodyText?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "mcp_oauth_start_contract_mismatch", retryable = false, traceId = response.traceId))
        val wire =
            try {
                managedMcpConnectJson.decodeFromString(McpOAuthStartResponseDto.serializer(), body)
            } catch (_: SerializationException) {
                return DataResult.Error(ScreenError(code = "mcp_oauth_start_contract_mismatch", retryable = false, traceId = response.traceId))
            }
        val url = wire.authorizationUrl?.trim()?.takeIf { it.isNotEmpty() }
        val attemptId = wire.attemptId?.trim()?.takeIf { it.isNotEmpty() }
        val connectionId = wire.connectionId?.trim()?.takeIf { it.isNotEmpty() }
        if (url == null || attemptId == null || connectionId == null) {
            return DataResult.Error(ScreenError(code = "mcp_oauth_start_contract_mismatch", retryable = false, traceId = response.traceId))
        }
        if (!isSafeMcpAuthorizationUrl(url)) {
            return DataResult.Error(
                ScreenError(
                    code = MCP_OAUTH_UNSAFE_URL_ERROR_CODE,
                    retryable = false,
                    serverCode = MCP_OAUTH_UNSAFE_URL_ERROR_CODE,
                    traceId = response.traceId,
                )
            )
        }
        return DataResult.Content(
            McpOAuthStartResult(
                authorizationUrl = url,
                attemptId = attemptId,
                connectionId = connectionId,
            )
        )
    }

    suspend fun pollOAuthAttempt(
        attemptId: String,
        serverIdOverride: ServerId? = null,
    ): DataResult<McpOAuthAttemptResult> {
        val response = transport.pollOAuthAttempt(attemptId, serverIdOverride)
        if (!response.isSuccessful) return response.toMcpFailure("mcp_oauth_attempt_failed")
        val body =
            response.bodyText?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "mcp_oauth_attempt_contract_mismatch", retryable = false, traceId = response.traceId))
        val wire =
            try {
                managedMcpConnectJson.decodeFromString(McpOAuthAttemptResponseDto.serializer(), body)
            } catch (_: SerializationException) {
                return DataResult.Error(ScreenError(code = "mcp_oauth_attempt_contract_mismatch", retryable = false, traceId = response.traceId))
            }
        val id = wire.attemptId?.trim()?.takeIf { it.isNotEmpty() } ?: attemptId
        val status =
            McpOAuthAttemptStatus.fromWire(wire.status)
                ?: return DataResult.Error(ScreenError(code = "mcp_oauth_attempt_contract_mismatch", retryable = false, traceId = response.traceId))
        return DataResult.Content(
            McpOAuthAttemptResult(
                attemptId = id,
                status = status,
                connectionId = wire.connectionId?.trim()?.takeIf { it.isNotEmpty() },
            )
        )
    }
}

// ── Wire Response DTOs & Error Mapping ──

@Serializable
private data class McpErrorEnvelopeDto(
    val error: String? = null,
    val code: String? = null,
    val errorCode: String? = null,
    val oauthError: String? = null,
)

@Serializable
private data class McpConnectionAuthorizationAppDto(
    val name: String? = null,
    val authKind: String? = null,
)

@Serializable
private data class McpUserRefDto(
    val id: String? = null,
    val displayName: String? = null,
)

@Serializable
private data class McpAccountOptionDto(
    val connectionId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val status: String? = null,
    val agentIds: List<String> = emptyList(),
)

@Serializable
private data class McpSharedAccountOptionDto(
    val connectionId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val status: String? = null,
    val agentIds: List<String> = emptyList(),
    val allAgents: Boolean = false,
    val signedInBy: McpUserRefDto? = null,
)

@Serializable
private data class McpPersonalAccountOptionsDto(
    val connections: List<McpAccountOptionDto> = emptyList(),
    val canConnectPersonal: Boolean = false,
)

@Serializable
private data class McpSharedAccountOptionsDto(
    val connections: List<McpSharedAccountOptionDto> = emptyList(),
    val canConnectShared: Boolean = false,
)

@Serializable
private data class McpConnectionAuthorizationOptionsDto(
    val personal: McpPersonalAccountOptionsDto? = null,
    val shared: McpSharedAccountOptionsDto? = null,
)

@Serializable
private data class McpCurrentAccountSummaryDto(
    val kind: String? = null,
    val connectionId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val ownerUserId: String? = null,
    val ownerDisplayName: String? = null,
)

@Serializable
private data class McpConnectionAuthorizationViewDto(
    val viewerRole: String? = null,
    val app: McpConnectionAuthorizationAppDto? = null,
    val options: McpConnectionAuthorizationOptionsDto? = null,
    val account: McpCurrentAccountSummaryDto? = null,
    val canAuthorizeNew: Boolean = false,
    val canSetServerDefault: Boolean = false,
    val newConnectionPinnable: Boolean = false,
)

@Serializable
private data class McpOAuthStartResponseDto(
    val authorizationUrl: String? = null,
    val attemptId: String? = null,
    val connectionId: String? = null,
)

@Serializable
private data class McpOAuthAttemptResponseDto(
    val attemptId: String? = null,
    val status: String? = null,
    val connectionId: String? = null,
)

private fun McpConnectionAuthorizationViewDto.toDomain(): McpConnectionAuthorizationView? {
    val role = McpConnectionViewerRole.fromWire(viewerRole) ?: return null
    val mappedApp =
        app?.let { dto ->
            val authKind = McpConnectionAuthKind.fromWire(dto.authKind) ?: return null
            McpConnectionAuthorizationApp(
                name = dto.name.orEmpty(),
                authKind = authKind,
            )
        }
    val mappedOptions =
        options?.let { opts ->
            val personalDto = opts.personal ?: return null
            val personalConnections =
                personalDto.connections.mapNotNull { conn ->
                    val id = conn.connectionId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val status = McpConnectionStatus.fromWire(conn.status) ?: return@mapNotNull null
                    McpAccountOption(
                        connectionId = id,
                        name = conn.name?.takeIf { it.isNotBlank() },
                        label = conn.label?.takeIf { it.isNotBlank() },
                        status = status,
                        agentIds = conn.agentIds.map(String::trim).filter(String::isNotEmpty),
                    )
                }
            val sharedOptions =
                opts.shared?.let { sharedDto ->
                    val sharedConnections =
                        sharedDto.connections.mapNotNull { conn ->
                            val id = conn.connectionId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                            val status = McpConnectionStatus.fromWire(conn.status) ?: return@mapNotNull null
                            McpSharedAccountOption(
                                connectionId = id,
                                name = conn.name?.takeIf { it.isNotBlank() },
                                label = conn.label?.takeIf { it.isNotBlank() },
                                status = status,
                                agentIds = conn.agentIds.map(String::trim).filter(String::isNotEmpty),
                                allAgents = conn.allAgents,
                                signedInByUserId = conn.signedInBy?.id?.trim()?.takeIf { it.isNotEmpty() },
                                signedInByDisplayName = conn.signedInBy?.displayName?.trim()?.takeIf { it.isNotEmpty() },
                            )
                        }
                    McpSharedAccountOptions(
                        connections = sharedConnections,
                        canConnectShared = sharedDto.canConnectShared,
                    )
                }
            McpConnectionAuthorizationOptions(
                personal =
                    McpPersonalAccountOptions(
                        connections = personalConnections,
                        canConnectPersonal = personalDto.canConnectPersonal,
                    ),
                shared = sharedOptions,
            )
        }
    val mappedAccount =
        account?.let { acc ->
            val kind = McpAccountKind.fromWire(acc.kind) ?: return@let null
            McpCurrentAccountSummary(
                kind = kind,
                connectionId = acc.connectionId?.trim()?.takeIf { it.isNotEmpty() },
                name = acc.name?.takeIf { it.isNotBlank() },
                label = acc.label?.takeIf { it.isNotBlank() },
                ownerUserId = acc.ownerUserId?.trim()?.takeIf { it.isNotEmpty() },
                ownerDisplayName = acc.ownerDisplayName?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    return McpConnectionAuthorizationView(
        viewerRole = role,
        app = mappedApp,
        options = mappedOptions,
        account = mappedAccount,
        canAuthorizeNew = canAuthorizeNew,
        canSetServerDefault = canSetServerDefault,
        newConnectionPinnable = newConnectionPinnable,
    )
}

private fun HomeNetworkResponse.toMcpFailure(fallbackCode: String): DataResult<Nothing> {
    val payload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText?.takeIf { it.isNotBlank() }
    val envelope =
        payload?.let { raw ->
            runCatching { managedMcpConnectJson.decodeFromString(McpErrorEnvelopeDto.serializer(), raw) }.getOrNull()
        }
    val serverMessage = envelope?.error?.takeIf { it.isNotBlank() }
    val resolvedTraceId = traceId ?: RaftFailedRequestTraceIds.recent()
    if (code == 401) {
        return DataResult.NoPermission(
            PermissionBlocker(
                code = "auth_required",
                requiredCapability = "auth_session",
                serverMessage = serverMessage,
                traceId = resolvedTraceId,
            )
        )
    }
    val refusalCode =
        looseRefusalCode(code = envelope?.code, errorCode = envelope?.errorCode, error = envelope?.error)
            ?: when (code) {
                403 -> "http_403"
                429 -> "http_429"
                else -> null
            }
    val serverFields =
        buildMap {
            envelope?.oauthError?.trim()?.takeIf { it.isNotEmpty() }?.let { put(MCP_OAUTH_ERROR_SERVER_FIELD, it) }
        }
    return DataResult.Error(
        ScreenError(
            code = if (code > 0) "http_$code" else fallbackCode,
            retryable = code == 0 || code >= 500 || code == 429,
            serverMessage = serverMessage,
            serverCode = refusalCode,
            serverFields = serverFields,
            traceId = resolvedTraceId,
        )
    )
}

private fun encodeMcpPathSegment(raw: String): String {
    val bytes = raw.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        val c = value.toChar()
        val unreserved =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'
        if (unreserved) {
            out.append(c)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[value ushr 4])
            out.append("0123456789ABCDEF"[value and 0x0F])
        }
    }
    return out.toString()
}

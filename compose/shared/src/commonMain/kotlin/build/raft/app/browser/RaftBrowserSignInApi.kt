package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.secondFactorStepUpErrorOrNull
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Native "Log in with Raft" API and installed-app entry verification (#proj-workspace:#356).
 *
 * Wire contracts verified against botiverse/slock commit 1e18003722ce4e38e599a11836305b7494be0a25:
 * - GET  oauth/clients/lookup?client_id=&server_id=&scope=
 *   packages/server/src/routes/oauth.ts:1146-1334
 *   packages/web/src/features/login-with-raft/loginClientSummary.ts:4-53
 * - POST oauth/authorize/human
 *   packages/server/src/routes/oauth.ts:1480-1688
 * - POST oauth/authorize/account
 *   packages/server/src/routes/oauth.ts:1707-1841
 *   (Decision #5: no `source` field; account mode never sends `serverId` or `server`.)
 * - GET  integrations/clients (X-Server-Id)
 *   packages/server/src/routes/integrations.ts:306-324
 *   packages/server/src/services/oauthService.ts:2424-2448
 * - GET  integrations/marketplace (X-Server-Id)
 *   packages/server/src/routes/integrations.ts:258-270
 *   packages/server/src/services/oauthService.ts:2450-2560
 */

private const val RAFT_OAUTH_CLIENT_LOOKUP_PATH = "oauth/clients/lookup"
private const val RAFT_OAUTH_AUTHORIZE_HUMAN_PATH = "oauth/authorize/human"
private const val RAFT_OAUTH_AUTHORIZE_ACCOUNT_PATH = "oauth/authorize/account"
private const val RAFT_INTEGRATIONS_APP_ORIGINS_PATH = "integrations/app-origins"
private const val RAFT_INTEGRATIONS_CLIENTS_PATH = "integrations/clients"
private const val RAFT_INTEGRATIONS_MARKETPLACE_PATH = "integrations/marketplace"

private val raftBrowserSignInJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

@Serializable
private data class RaftBrowserSignInLookupWireDto(
    val id: String? = null,
    val clientId: String? = null,
    val appType: String? = null,
    val name: String? = null,
    val description: String? = null,
    val homepageUrl: String? = null,
    val returnUrl: String? = null,
    val logoUrl: String? = null,
    val setupUrl: String? = null,
    val allowedScopes: List<String>? = null,
    val marketplace: Boolean? = null,
    val availability: String? = null,
    val installation: RaftBrowserSignInInstallationWireDto? = null,
    val bot: RaftBrowserSignInBotWireDto? = null,
    val scopeValidation: RaftBrowserSignInScopeValidationWireDto? = null,
    val loginMode: String? = null,
    val consent: RaftBrowserSignInConsentWireDto? = null,
)

@Serializable
private data class RaftBrowserSignInInstallationWireDto(
    val serverId: String? = null,
    val canInstall: Boolean = false,
)

@Serializable
private data class RaftBrowserSignInBotWireDto(
    val handle: String? = null,
)

@Serializable
private data class RaftBrowserSignInScopeValidationWireDto(
    val allowed: Boolean = true,
    val reason: String? = null,
    val disallowedScopes: List<String> = emptyList(),
)

@Serializable
private data class RaftBrowserSignInConsentWireDto(
    val granted: Boolean = false,
    val coversRequested: Boolean = false,
)

@Serializable
private data class RaftBrowserSignInHumanAuthorizeRequestBody(
    val clientId: String,
    val serverId: String,
    val returnUrl: String,
    val scopes: List<String>,
    val oidc: Boolean? = null,
    val nonce: String? = null,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    val server: String? = null,
    val confirmSkipped: Boolean? = null,
)

/** Account-mode authorize body: NEVER carries `serverId` or `server` (slock oauth.ts:1719). */
@Serializable
private data class RaftBrowserSignInAccountAuthorizeRequestBody(
    val clientId: String,
    val returnUrl: String,
    val scopes: List<String>,
    val oidc: Boolean? = null,
    val nonce: String? = null,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    val confirmSkipped: Boolean? = null,
)

@Serializable
private data class RaftBrowserSignInAuthorizeSuccessWireDto(
    val code: String? = null,
    val returnUrl: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable
private data class RaftBrowserSignInErrorWireDto(
    val code: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    val returnUrl: String? = null,
)

@Serializable
private data class RaftInstalledAppWireDto(
    val id: String? = null,
    val name: String? = null,
    val homepageUrl: String? = null,
    val logoUrl: String? = null,
    val installationId: String? = null,
    val installStatus: String? = null,
)

/** One Server's verified scope-validation summary from `GET oauth/clients/lookup`. */
internal data class RaftBrowserSignInScopeValidation(
    val allowed: Boolean,
    val reason: String?,
    val disallowedScopes: List<String>,
)

/** One Server's full lookup summary of the requested App (`GET oauth/clients/lookup`). */
internal data class RaftBrowserSignInClientDetails(
    val serverId: String,
    val clientId: String,
    val name: String,
    val description: String?,
    val homepageUrl: String?,
    val returnUrl: String?,
    val logoUrl: String?,
    val marketplace: Boolean,
    val availability: RaftSignInAvailability,
    val canInstall: Boolean,
    val botHandle: String?,
    val scopeValidation: RaftBrowserSignInScopeValidation?,
    val loginMode: RaftSignInLoginMode,
    val consent: RaftSignInConsent?,
) {
    fun toRuleLookup(): RaftSignInLookup =
        RaftSignInLookup(
            serverId = serverId,
            name = name,
            returnUrl = returnUrl,
            availability = availability,
            loginMode = loginMode,
            scopeAllowed = scopeValidation?.allowed != false,
            consent = consent,
        )
}

internal sealed interface RaftBrowserSignInLookupResult {
    data class Found(val client: RaftBrowserSignInClientDetails) : RaftBrowserSignInLookupResult

    data object NotAvailable : RaftBrowserSignInLookupResult

    data class Failed(val traceId: String? = null) : RaftBrowserSignInLookupResult
}

internal sealed interface RaftBrowserSignInApproveTarget {
    data class Server(val serverId: String) : RaftBrowserSignInApproveTarget

    data object Account : RaftBrowserSignInApproveTarget
}

internal data class RaftBrowserSignInApproveRequest(
    val clientId: String,
    val target: RaftBrowserSignInApproveTarget,
    val returnUrl: String,
    val scopes: List<String>,
    val oidcFlow: Boolean,
    val nonce: String? = null,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    /** Sent in server mode with OIDC flow only; never sent in account mode. */
    val serverHint: String? = null,
    val confirmSkipped: Boolean = false,
)

internal sealed interface RaftBrowserSignInApproveOutcome {
    data class Approved(val code: String, val returnUrl: String) : RaftBrowserSignInApproveOutcome

    data class AppNotAvailable(val traceId: String? = null) : RaftBrowserSignInApproveOutcome

    data object LoginModeChanged : RaftBrowserSignInApproveOutcome

    data class ConsentRequired(val traceId: String? = null) : RaftBrowserSignInApproveOutcome

    data class InstallationSuspended(
        val description: String?,
        val traceId: String? = null,
    ) : RaftBrowserSignInApproveOutcome

    data class ScopeNotApproved(
        val returnUrl: String?,
        val description: String?,
        val traceId: String? = null,
    ) : RaftBrowserSignInApproveOutcome

    data class RootIssuerRequired(val traceId: String? = null) : RaftBrowserSignInApproveOutcome

    data class Failed(
        val message: String?,
        val traceId: String? = null,
    ) : RaftBrowserSignInApproveOutcome
}

/** A verified installed App on the current Server matching a destination origin (#356 Decision #9). */
internal data class RaftInstalledAppOriginMatch(
    val origin: String,
    val appName: String?,
    val logoUrl: String? = null,
)

/**
 * Snapshot of installed App origins for one Server (`GET integrations/clients` + installed rows of
 * `GET integrations/marketplace`). [complete] is true only when BOTH endpoints returned 2xx, so
 * partial failures never poison negative cache entries.
 */
internal data class RaftInstalledAppOriginsFetchResult(
    val origins: Map<String, RaftInstalledAppOriginMatch>,
    val complete: Boolean,
)

internal interface RaftBrowserSignInApi {
    suspend fun lookupClient(
        clientId: String,
        serverId: String,
        scope: String,
    ): RaftBrowserSignInLookupResult

    /**
     * Issues a human or account authorization code (`POST oauth/authorize/human` or
     * `POST oauth/authorize/account`). A 403 `second_factor_step_up_required` returns
     * `DataResult.Error` with a step-up challenge so `SecondFactorStepUpCoordinator.run` can handle
     * interactive retries, while consent-skip callers treat any non-`Approved` result as fallback.
     */
    suspend fun approve(request: RaftBrowserSignInApproveRequest): DataResult<RaftBrowserSignInApproveOutcome>

    /**
     * Checks whether [targetOrigin] matches the `homepageUrl` origin of an App installed on
     * [serverId] (`GET integrations/clients` + installed entries of `GET integrations/marketplace`,
     * #356 Decision #9). Fails closed (returns null) when unmatched or on any lookup error.
     */
    suspend fun findInstalledAppByOrigin(
        serverId: String,
        targetOrigin: String,
    ): RaftInstalledAppOriginMatch?
}

internal class NetworkRaftBrowserSignInApi(
    private val networkClient: RaftNetworkClient,
    private val resolveLogoUrl: (String) -> String? = { it },
) : RaftBrowserSignInApi {
    override suspend fun lookupClient(
        clientId: String,
        serverId: String,
        scope: String,
    ): RaftBrowserSignInLookupResult {
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = RAFT_OAUTH_CLIENT_LOOKUP_PATH,
                    method = "GET",
                    query =
                        listOf(
                            "client_id" to clientId,
                            "server_id" to serverId,
                            "scope" to scope,
                        ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return RaftBrowserSignInLookupResult.Failed()
            }
        return parseRaftBrowserSignInLookupResponse(serverId = serverId, response = response)
    }

    override suspend fun approve(request: RaftBrowserSignInApproveRequest): DataResult<RaftBrowserSignInApproveOutcome> {
        val path =
            when (request.target) {
                is RaftBrowserSignInApproveTarget.Server -> RAFT_OAUTH_AUTHORIZE_HUMAN_PATH
                RaftBrowserSignInApproveTarget.Account -> RAFT_OAUTH_AUTHORIZE_ACCOUNT_PATH
            }
        val bodyText = buildRaftBrowserSignInApproveBody(request)
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = path,
                    method = "POST",
                    bodyText = bodyText,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return DataResult.Content(RaftBrowserSignInApproveOutcome.Failed(message = null, traceId = null))
            }
        return parseRaftBrowserSignInApproveResponse(response)
    }

    suspend fun fetchInstalledAppOrigins(
        serverId: String,
    ): RaftInstalledAppOriginsFetchResult? {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        val server = ServerId(cleanServerId)
        // Prefer server-authoritative GET integrations/app-origins (botiverse/slock #16668, #16687):
        // single lightweight read returning link-preview + verified homepage origins and served icons,
        // already filtering disabled/suspended Apps and guest account-mode Apps.
        val appOriginsResponse =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = RAFT_INTEGRATIONS_APP_ORIGINS_PATH,
                    method = "GET",
                    serverIdOverride = server,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return null
            }
        if (appOriginsResponse.isSuccessful) {
            val parsed =
                parseInstalledAppOriginsEndpointResponse(
                    body = appOriginsResponse.bodyText,
                    resolveLogoUrl = resolveLogoUrl,
                ) ?: return null
            return RaftInstalledAppOriginsFetchResult(
                origins = parsed,
                complete = true,
            )
        }
        // Only older servers before slock #16668 lack /integrations/app-origins (404/405); any other
        // error (5xx, 401/403) fails closed without fanning out to two fallback reads.
        if (appOriginsResponse.code != 404 && appOriginsResponse.code != 405) {
            return null
        }
        val (clientsResponse, marketplaceResponse) =
            coroutineScope {
                val clients =
                    async {
                        try {
                            networkClient.request(
                                priority = DataRequestPriority.Foreground,
                                path = RAFT_INTEGRATIONS_CLIENTS_PATH,
                                method = "GET",
                                serverIdOverride = server,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                val marketplace =
                    async {
                        try {
                            networkClient.request(
                                priority = DataRequestPriority.Foreground,
                                path = RAFT_INTEGRATIONS_MARKETPLACE_PATH,
                                method = "GET",
                                serverIdOverride = server,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                clients.await() to marketplace.await()
            }
        val clientsOk = clientsResponse?.isSuccessful == true
        val marketplaceOk = marketplaceResponse?.isSuccessful == true
        if (!clientsOk && !marketplaceOk) return null
        val origins =
            parseInstalledAppHomepageOrigins(
                clientsBody = clientsResponse?.takeIf { it.isSuccessful }?.bodyText,
                marketplaceBody = marketplaceResponse?.takeIf { it.isSuccessful }?.bodyText,
                resolveLogoUrl = resolveLogoUrl,
            )
        return RaftInstalledAppOriginsFetchResult(
            origins = origins,
            complete = clientsOk && marketplaceOk,
        )
    }

    override suspend fun findInstalledAppByOrigin(
        serverId: String,
        targetOrigin: String,
    ): RaftInstalledAppOriginMatch? {
        val normalizedOrigin = raftAppEntryOriginOf(targetOrigin)?.takeIf { it == targetOrigin } ?: return null
        return fetchInstalledAppOrigins(serverId)?.origins?.get(normalizedOrigin)
    }
}

internal fun buildRaftBrowserSignInApproveBody(request: RaftBrowserSignInApproveRequest): String {
    val oidc = if (request.oidcFlow) true else null
    val nonce = if (request.oidcFlow) request.nonce?.takeIf { it.isNotEmpty() } else null
    val codeChallenge = if (request.oidcFlow) request.codeChallenge?.takeIf { it.isNotEmpty() } else null
    val codeChallengeMethod = if (request.oidcFlow && codeChallenge != null) request.codeChallengeMethod?.takeIf { it.isNotEmpty() } else null
    val confirmSkipped = if (request.confirmSkipped) true else null
    return when (val target = request.target) {
        is RaftBrowserSignInApproveTarget.Server ->
            raftBrowserSignInJson.encodeToString(
                RaftBrowserSignInHumanAuthorizeRequestBody(
                    clientId = request.clientId,
                    serverId = target.serverId,
                    returnUrl = request.returnUrl,
                    scopes = request.scopes,
                    oidc = oidc,
                    nonce = nonce,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                    server = if (request.oidcFlow) request.serverHint?.takeIf { it.isNotEmpty() } else null,
                    confirmSkipped = confirmSkipped,
                ),
            )
        RaftBrowserSignInApproveTarget.Account ->
            raftBrowserSignInJson.encodeToString(
                RaftBrowserSignInAccountAuthorizeRequestBody(
                    clientId = request.clientId,
                    returnUrl = request.returnUrl,
                    scopes = request.scopes,
                    oidc = oidc,
                    nonce = nonce,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                    confirmSkipped = confirmSkipped,
                ),
            )
    }
}

internal fun parseRaftBrowserSignInLookupResponse(
    serverId: String,
    response: HomeNetworkResponse,
): RaftBrowserSignInLookupResult {
    if (response.isSuccessful) {
        val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return RaftBrowserSignInLookupResult.Failed(response.traceId)
        val wire =
            try {
                raftBrowserSignInJson.decodeFromString<RaftBrowserSignInLookupWireDto>(body)
            } catch (_: SerializationException) {
                return RaftBrowserSignInLookupResult.Failed(response.traceId)
            }
        val clientId = wire.clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return RaftBrowserSignInLookupResult.Failed(response.traceId)
        val name = wire.name?.trim().orEmpty()
        val availability = parseRaftSignInAvailability(wire.availability)
        val loginMode =
            when (wire.loginMode) {
                null -> RaftSignInLoginMode.Server
                "account" -> RaftSignInLoginMode.Account
                else -> {
                    reportUnknownTypeDropped(
                        source = "oauth_client_lookup",
                        field = "loginMode",
                        value = wire.loginMode,
                        fallback = "server",
                    )
                    RaftSignInLoginMode.Server
                }
            }
        val scopeValidation =
            wire.scopeValidation?.let { sv ->
                RaftBrowserSignInScopeValidation(
                    allowed = sv.allowed,
                    reason = sv.reason,
                    disallowedScopes = sv.disallowedScopes,
                )
            }
        val consent =
            wire.consent?.let { c ->
                RaftSignInConsent(granted = c.granted, coversRequested = c.coversRequested)
            }
        return RaftBrowserSignInLookupResult.Found(
            RaftBrowserSignInClientDetails(
                serverId = serverId,
                clientId = clientId,
                name = name,
                description = wire.description?.trim()?.takeIf { it.isNotEmpty() },
                homepageUrl = wire.homepageUrl?.trim()?.takeIf { it.isNotEmpty() },
                returnUrl = wire.returnUrl?.trim()?.takeIf { it.isNotEmpty() },
                logoUrl = wire.logoUrl?.trim()?.takeIf { it.isNotEmpty() },
                marketplace = wire.marketplace == true,
                availability = availability,
                canInstall = wire.installation?.canInstall == true,
                botHandle = wire.bot?.handle?.trim()?.takeIf { it.isNotEmpty() },
                scopeValidation = scopeValidation,
                loginMode = loginMode,
                consent = consent,
            ),
        )
    }
    val errorBody = parseSignInErrorBody(response)
    if (response.code == 404 || (response.code == 403 && errorBody?.code == "app_not_available")) {
        return RaftBrowserSignInLookupResult.NotAvailable
    }
    return RaftBrowserSignInLookupResult.Failed(response.traceId)
}

internal fun parseRaftBrowserSignInApproveResponse(
    response: HomeNetworkResponse,
): DataResult<RaftBrowserSignInApproveOutcome> {
    response.secondFactorStepUpErrorOrNull<RaftBrowserSignInApproveOutcome>()?.let { return it }
    if (response.isSuccessful) {
        val body = response.bodyText?.takeIf { it.isNotBlank() }
        val wire =
            body?.let {
                try {
                    raftBrowserSignInJson.decodeFromString<RaftBrowserSignInAuthorizeSuccessWireDto>(it)
                } catch (_: SerializationException) {
                    null
                }
            }
        val code = wire?.code?.takeIf { it.isNotEmpty() }
        val returnUrl = wire?.returnUrl?.takeIf { it.isNotEmpty() }
        return if (code != null && returnUrl != null) {
            DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = code, returnUrl = returnUrl))
        } else {
            DataResult.Content(RaftBrowserSignInApproveOutcome.Failed(message = null, traceId = response.traceId))
        }
    }
    val error = parseSignInErrorBody(response)
    val outcome =
        when {
            response.code == 404 || (response.code == 403 && error?.code == "app_not_available") ->
                RaftBrowserSignInApproveOutcome.AppNotAvailable(response.traceId)
            response.code == 409 && error?.code == "login_mode_changed" ->
                RaftBrowserSignInApproveOutcome.LoginModeChanged
            response.code == 409 && error?.code == "consent_required" ->
                RaftBrowserSignInApproveOutcome.ConsentRequired(response.traceId)
            response.code == 403 &&
                (error?.errorCode == "OAUTH_INSTALLATION_SUSPENDED" || error?.error == "installation_suspended") ->
                RaftBrowserSignInApproveOutcome.InstallationSuspended(
                    description = error.errorDescription?.trim()?.takeIf { it.isNotEmpty() },
                    traceId = response.traceId,
                )
            response.code == 400 && error?.error == "invalid_scope" && error.errorCode == "OAUTH_SCOPE_NOT_APPROVED" ->
                RaftBrowserSignInApproveOutcome.ScopeNotApproved(
                    returnUrl = error.returnUrl?.trim()?.takeIf { it.isNotEmpty() },
                    description = error.errorDescription?.trim()?.takeIf { it.isNotEmpty() },
                    traceId = response.traceId,
                )
            response.code == 400 && error?.code == "root_issuer_required" ->
                RaftBrowserSignInApproveOutcome.RootIssuerRequired(response.traceId)
            else ->
                RaftBrowserSignInApproveOutcome.Failed(
                    message =
                        error?.errorDescription?.trim()?.takeIf { it.isNotEmpty() }
                            ?: error?.error?.trim()?.takeIf { it.isNotEmpty() },
                    traceId = response.traceId,
                )
        }
    return DataResult.Content(outcome)
}

/**
 * Parses `GET integrations/app-origins` (botiverse/slock #16668, #16687):
 * `{ "origins": ["https://..."], "icons"?: { "https://...": "/api/integration-logos/..." } }`.
 *
 * Returns null if the body is missing, not a JSON object, or lacks an `origins` array so callers
 * fail closed without poisoning the cache. `icons` is optional (absent on pre-#16687 servers).
 */
internal fun parseInstalledAppOriginsEndpointResponse(
    body: String?,
    resolveLogoUrl: (String) -> String? = { it },
): Map<String, RaftInstalledAppOriginMatch>? {
    if (body.isNullOrBlank()) return null
    val root =
        try {
            raftBrowserSignInJson.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
    val originsArray = root["origins"] as? JsonArray ?: return null
    val iconsObject = root["icons"] as? JsonObject
    val result = LinkedHashMap<String, RaftInstalledAppOriginMatch>()
    for (element in originsArray) {
        val rawOrigin = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
        val normalizedOrigin = raftAppEntryOriginOf(rawOrigin)?.takeIf { it == rawOrigin } ?: continue
        val rawIcon =
            (iconsObject?.get(normalizedOrigin) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val resolvedIcon = rawIcon?.let(resolveLogoUrl)?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedOrigin !in result) {
            result[normalizedOrigin] =
                RaftInstalledAppOriginMatch(
                    origin = normalizedOrigin,
                    appName = null,
                    logoUrl = resolvedIcon,
                )
        }
    }
    return result
}

/**
 * Parses the `homepageUrl` origin map of Apps installed on the current Server
 * (`GET integrations/clients` + installed entries of `GET integrations/marketplace`, #356 Decision #9).
 *
 * - `integrations/clients` lists this Server's own Apps; active entries (`installStatus != "suspended"`)
 *   qualify and take precedence if the same origin also appears in `integrations/marketplace`.
 * - `integrations/marketplace` lists both public Marketplace Apps and installed Apps; ONLY rows with
 *   a non-blank `installationId` and `installStatus != "suspended"` qualify.
 */
internal fun parseInstalledAppHomepageOrigins(
    clientsBody: String?,
    marketplaceBody: String?,
    resolveLogoUrl: (String) -> String? = { it },
): Map<String, RaftInstalledAppOriginMatch> {
    val result = LinkedHashMap<String, RaftInstalledAppOriginMatch>()
    val ownClients = decodeInstalledAppList(clientsBody)
    for (app in ownClients) {
        if (app.installStatus == "suspended") continue
        val origin = app.homepageUrl?.let(::raftAppEntryOriginOf) ?: continue
        if (origin !in result) {
            result[origin] =
                RaftInstalledAppOriginMatch(
                    origin = origin,
                    appName = app.name?.trim()?.takeIf { it.isNotEmpty() },
                    logoUrl = app.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(resolveLogoUrl)?.trim()?.takeIf { it.isNotEmpty() },
                )
        }
    }
    val marketplaceClients = decodeInstalledAppList(marketplaceBody)
    for (app in marketplaceClients) {
        if (app.installationId.isNullOrBlank() || app.installStatus == "suspended") continue
        val origin = app.homepageUrl?.let(::raftAppEntryOriginOf) ?: continue
        if (origin !in result) {
            result[origin] =
                RaftInstalledAppOriginMatch(
                    origin = origin,
                    appName = app.name?.trim()?.takeIf { it.isNotEmpty() },
                    logoUrl = app.logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(resolveLogoUrl)?.trim()?.takeIf { it.isNotEmpty() },
                )
        }
    }
    return result
}

/**
 * Matches [targetOrigin] against the `homepageUrl` origin of Apps installed on the current Server
 * (`GET integrations/clients` + installed entries of `GET integrations/marketplace`, #356 Decision #9).
 */
internal fun matchInstalledAppHomepageOrigin(
    targetOrigin: String,
    clientsBody: String?,
    marketplaceBody: String?,
    resolveLogoUrl: (String) -> String? = { it },
): RaftInstalledAppOriginMatch? {
    val normalizedTarget = raftAppEntryOriginOf(targetOrigin)?.takeIf { it == targetOrigin } ?: return null
    return parseInstalledAppHomepageOrigins(
        clientsBody = clientsBody,
        marketplaceBody = marketplaceBody,
        resolveLogoUrl = resolveLogoUrl,
    )[normalizedTarget]
}

private fun decodeInstalledAppList(body: String?): List<RaftInstalledAppWireDto> {
    if (body.isNullOrBlank()) return emptyList()
    return try {
        raftBrowserSignInJson.decodeFromString<List<RaftInstalledAppWireDto>>(body)
    } catch (_: SerializationException) {
        emptyList()
    }
}

private fun parseRaftSignInAvailability(raw: String?): RaftSignInAvailability =
    when (raw) {
        null, "ready" -> RaftSignInAvailability.Ready
        "install_required" -> RaftSignInAvailability.InstallRequired
        "suspended" -> RaftSignInAvailability.Suspended
        else -> {
            reportUnknownTypeDropped(
                source = "oauth_client_lookup",
                field = "availability",
                value = raw,
                fallback = "unknown",
            )
            RaftSignInAvailability.Unknown
        }
    }

private fun parseSignInErrorBody(response: HomeNetworkResponse): RaftBrowserSignInErrorWireDto? {
    val raw = response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText?.takeIf { it.isNotBlank() } ?: return null
    return try {
        raftBrowserSignInJson.decodeFromString<RaftBrowserSignInErrorWireDto>(raw)
    } catch (_: SerializationException) {
        null
    }
}

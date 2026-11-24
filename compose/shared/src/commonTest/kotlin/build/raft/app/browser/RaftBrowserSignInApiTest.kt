package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.secondFactorStepUpChallengeOrNull
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-contract tests for [RaftBrowserSignInApi] verified against botiverse/slock commit
 * `1e18003722ce4e38e599a11836305b7494be0a25`:
 * - `packages/server/src/routes/oauth.ts:1146-1334,1480-1841`
 * - `packages/server/src/routes/secondFactor.ts:179-194`
 * - `packages/server/src/routes/integrations.ts:258-324`
 * - `packages/server/src/services/oauthService.ts:2424-2560`
 */
class RaftBrowserSignInApiTest {
    @Test
    fun lookupParsesReadyAccountModeConsentInstallRequiredSuspendedAndErrors() {
        val readyAccountBody =
            """
            {
              "clientId": "stamp",
              "appType": "third_party_global",
              "name": "Stamp",
              "description": "Code review for agents",
              "homepageUrl": "https://stamp.build",
              "returnUrl": "https://stamp.build/auth/callback",
              "logoUrl": "https://api.raft.build/logos/stamp.webp",
              "allowedScopes": ["openid", "profile"],
              "marketplace": true,
              "availability": "ready",
              "scopeValidation": { "allowed": true, "reason": null, "disallowedScopes": [] },
              "loginMode": "account",
              "consent": { "granted": true, "coversRequested": true }
            }
            """.trimIndent()
        val found =
            assertIs<RaftBrowserSignInLookupResult.Found>(
                parseRaftBrowserSignInLookupResponse("srv-1", HomeNetworkResponse(code = 200, bodyText = readyAccountBody)),
            ).client
        assertEquals("stamp", found.clientId)
        assertEquals("Stamp", found.name)
        assertEquals("https://stamp.build/auth/callback", found.returnUrl)
        assertTrue(found.marketplace)
        assertEquals(RaftSignInAvailability.Ready, found.availability)
        assertEquals(RaftSignInLoginMode.Account, found.loginMode)
        assertEquals(RaftSignInConsent(granted = true, coversRequested = true), found.consent)

        val installRequiredBody =
            """
            {
              "id": "uuid-1",
              "clientId": "stamp",
              "appType": "third_party_global",
              "name": "Stamp",
              "returnUrl": "https://stamp.build/auth/callback",
              "marketplace": true,
              "availability": "install_required",
              "installation": { "serverId": "srv-2", "canInstall": true },
              "bot": { "handle": "stamp-bot" },
              "scopeValidation": { "allowed": false, "reason": "not_allowed", "disallowedScopes": ["email"] }
            }
            """.trimIndent()
        val install =
            assertIs<RaftBrowserSignInLookupResult.Found>(
                parseRaftBrowserSignInLookupResponse("srv-2", HomeNetworkResponse(code = 200, bodyText = installRequiredBody)),
            ).client
        assertEquals(RaftSignInAvailability.InstallRequired, install.availability)
        assertTrue(install.canInstall)
        assertEquals("stamp-bot", install.botHandle)
        assertEquals(false, install.scopeValidation?.allowed)
        assertEquals(listOf("email"), install.scopeValidation?.disallowedScopes)
        assertNull(install.consent)

        val suspendedBody =
            """
            { "clientId": "stamp", "name": "Stamp", "returnUrl": "https://stamp.build/auth/callback", "availability": "suspended" }
            """.trimIndent()
        val suspended =
            assertIs<RaftBrowserSignInLookupResult.Found>(
                parseRaftBrowserSignInLookupResponse("srv-3", HomeNetworkResponse(code = 200, bodyText = suspendedBody)),
            ).client
        assertEquals(RaftSignInAvailability.Suspended, suspended.availability)

        // 403 app_not_available and 404 map to NotAvailable; 500 carries traceId as Failed.
        assertEquals(
            RaftBrowserSignInLookupResult.NotAvailable,
            parseRaftBrowserSignInLookupResponse(
                "srv-4",
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{"code":"app_not_available","error":"This app isn't available to you"}""",
                ),
            ),
        )
        assertEquals(
            RaftBrowserSignInLookupResult.NotAvailable,
            parseRaftBrowserSignInLookupResponse("srv-4", HomeNetworkResponse(code = 404, bodyText = null)),
        )
        val failed =
            assertIs<RaftBrowserSignInLookupResult.Failed>(
                parseRaftBrowserSignInLookupResponse(
                    "srv-4",
                    HomeNetworkResponse(
                        code = 500,
                        bodyText = null,
                        responseHeaders = mapOf("X-Trace-Id" to listOf("abcdef0123456789abcdef0123456789")),
                    ),
                ),
            )
        assertEquals("abcdef0123456789abcdef0123456789", failed.traceId)
    }

    @Test
    fun approveRequestBodiesFollowServerContractWithoutMobileBrowserSourceLabel() {
        val humanJson =
            Json.parseToJsonElement(
                buildRaftBrowserSignInApproveBody(
                    RaftBrowserSignInApproveRequest(
                        clientId = "stamp",
                        target = RaftBrowserSignInApproveTarget.Server("srv-1"),
                        returnUrl = "https://stamp.build/cb",
                        scopes = listOf("openid", "profile"),
                        oidcFlow = true,
                        nonce = "nonce-1",
                        codeChallenge = "a".repeat(43),
                        codeChallengeMethod = "S256",
                        serverHint = "hq",
                        confirmSkipped = true,
                    ),
                ),
            ).jsonObject
        assertEquals("stamp", humanJson["clientId"]?.jsonPrimitive?.content)
        assertEquals("srv-1", humanJson["serverId"]?.jsonPrimitive?.content)
        assertEquals("https://stamp.build/cb", humanJson["returnUrl"]?.jsonPrimitive?.content)
        assertEquals(listOf("openid", "profile"), humanJson["scopes"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("true", humanJson["oidc"]?.jsonPrimitive?.content)
        assertEquals("nonce-1", humanJson["nonce"]?.jsonPrimitive?.content)
        assertEquals("S256", humanJson["codeChallengeMethod"]?.jsonPrimitive?.content)
        assertEquals("hq", humanJson["server"]?.jsonPrimitive?.content)
        assertEquals("true", humanJson["confirmSkipped"]?.jsonPrimitive?.content)
        // Decision #5: never send a `source` label in v1.
        assertFalse("source" in humanJson)

        // Account mode must NEVER carry `serverId`, `server`, or `source` (slock oauth.ts:1719).
        val accountJson =
            Json.parseToJsonElement(
                buildRaftBrowserSignInApproveBody(
                    RaftBrowserSignInApproveRequest(
                        clientId = "stamp",
                        target = RaftBrowserSignInApproveTarget.Account,
                        returnUrl = "https://stamp.build/cb",
                        scopes = listOf("openid", "profile"),
                        oidcFlow = true,
                        serverHint = "hq",
                        confirmSkipped = false,
                    ),
                ),
            ).jsonObject
        assertEquals("stamp", accountJson["clientId"]?.jsonPrimitive?.content)
        assertFalse("serverId" in accountJson)
        assertFalse("server" in accountJson)
        assertFalse("source" in accountJson)
        assertFalse("confirmSkipped" in accountJson)
    }

    @Test
    fun approveResponseMapsSuccessStepUpAndAllOAuthRefusalCodes() {
        val traceId = "0123456789abcdef0123456789abcdef"

        val ok =
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 200,
                        bodyText = """{"code":"auth-code-1","returnUrl":"https://stamp.build/cb","scopes":["openid","profile"]}""",
                    ),
                ),
            ).data
        assertEquals(RaftBrowserSignInApproveOutcome.Approved("auth-code-1", "https://stamp.build/cb"), ok)

        // 403 second_factor_step_up_required maps to DataResult.Error carrying the challenge for SecondFactorStepUpCoordinator.
        val stepUp =
            parseRaftBrowserSignInApproveResponse(
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{"error":"Confirm it's you","code":"second_factor_step_up_required","methods":["totp","backup_code"]}""",
                ),
            )
        assertNotNull(stepUp.secondFactorStepUpChallengeOrNull())

        assertEquals(
            RaftBrowserSignInApproveOutcome.AppNotAvailable(traceId),
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText = """{"code":"app_not_available","error":"This app isn't available to you"}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    ),
                ),
            ).data,
        )

        assertEquals(
            RaftBrowserSignInApproveOutcome.LoginModeChanged,
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"code":"login_mode_changed","error":"switched"}""",
                    ),
                ),
            ).data,
        )

        assertEquals(
            RaftBrowserSignInApproveOutcome.ConsentRequired(traceId),
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"code":"consent_required","error":"needs confirmation"}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    ),
                ),
            ).data,
        )

        val suspended =
            assertIs<RaftBrowserSignInApproveOutcome.InstallationSuspended>(
                assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                    parseRaftBrowserSignInApproveResponse(
                        HomeNetworkResponse(
                            code = 403,
                            bodyText = null,
                            errorBodyText = """{"error":"installation_suspended","errorCode":"OAUTH_INSTALLATION_SUSPENDED","error_description":"Suspended here"}""",
                            responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                        ),
                    ),
                ).data,
            )
        assertEquals("Suspended here", suspended.description)
        assertEquals(traceId, suspended.traceId)

        val scopeNotApproved =
            assertIs<RaftBrowserSignInApproveOutcome.ScopeNotApproved>(
                assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                    parseRaftBrowserSignInApproveResponse(
                        HomeNetworkResponse(
                            code = 400,
                            bodyText = null,
                            errorBodyText = """{"error":"invalid_scope","errorCode":"OAUTH_SCOPE_NOT_APPROVED","error_description":"Not approved","returnUrl":"https://stamp.build/cb"}""",
                            responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                        ),
                    ),
                ).data,
            )
        assertEquals("https://stamp.build/cb", scopeNotApproved.returnUrl)
        assertEquals("Not approved", scopeNotApproved.description)

        assertEquals(
            RaftBrowserSignInApproveOutcome.RootIssuerRequired(traceId),
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText = """{"error":"invalid_request","code":"root_issuer_required"}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    ),
                ),
            ).data,
        )
    }

    @Test
    fun installedAppOriginCheckMatchesOwnAndInstalledMarketplaceAppsOnly() = runTest {
        val clientsBody =
            """
            [
              {"id":"c1","name":"Local Tool","homepageUrl":"https://local.example.com/start","logoUrl":"/api/logos/local.png","installStatus":null},
              {"id":"c2","name":"Suspended Own","homepageUrl":"https://suspended-own.example.com","installStatus":"suspended"}
            ]
            """.trimIndent()
        val marketplaceBody =
            """
            [
              {"id":"m1","name":"Stamp","homepageUrl":"https://stamp.build/docs","logoUrl":"https://api.raft.build/logos/stamp.webp","installationId":"inst-1","installStatus":"active"},
              {"id":"m2","name":"Uninstalled App","homepageUrl":"https://uninstalled.example.com","installationId":null,"installStatus":null},
              {"id":"m3","name":"Suspended Market","homepageUrl":"https://suspended-mkt.example.com","installationId":"inst-3","installStatus":"suspended"}
            ]
            """.trimIndent()

        // Active server-local app matches by exact origin and carries logoUrl.
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://local.example.com",
                appName = "Local Tool",
                logoUrl = "/api/logos/local.png",
            ),
            matchInstalledAppHomepageOrigin("https://local.example.com", clientsBody, marketplaceBody),
        )
        // Active installed marketplace app matches by exact origin and carries logoUrl.
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://stamp.build",
                appName = "Stamp",
                logoUrl = "https://api.raft.build/logos/stamp.webp",
            ),
            matchInstalledAppHomepageOrigin("https://stamp.build", clientsBody, marketplaceBody),
        )
        // Uninstalled marketplace app, suspended apps, and unrelated origins are rejected (fail closed).
        assertNull(matchInstalledAppHomepageOrigin("https://uninstalled.example.com", clientsBody, marketplaceBody))
        assertNull(matchInstalledAppHomepageOrigin("https://suspended-own.example.com", clientsBody, marketplaceBody))
        assertNull(matchInstalledAppHomepageOrigin("https://suspended-mkt.example.com", clientsBody, marketplaceBody))
        assertNull(matchInstalledAppHomepageOrigin("https://evil.example.com", clientsBody, marketplaceBody))
        assertNull(matchInstalledAppHomepageOrigin("https://stamp.build", null, null))

        // Verify NetworkRaftBrowserSignInApi calls both endpoints scoped to serverId and resolves relative logo URLs.
        val requestedPaths = mutableListOf<Pair<String, String?>>()
        val fakeClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    requestedPaths += path to serverIdOverride?.value
                    return when (path) {
                        "integrations/clients" -> HomeNetworkResponse(code = 200, bodyText = clientsBody)
                        "integrations/marketplace" -> HomeNetworkResponse(code = 200, bodyText = marketplaceBody)
                        else -> HomeNetworkResponse(code = 404, bodyText = null)
                    }
                }
            }
        val api =
            NetworkRaftBrowserSignInApi(
                networkClient = fakeClient,
                resolveLogoUrl = { logo ->
                    if (logo.startsWith("/")) "https://api.raft.build$logo" else logo
                },
            )
        val fetched = assertNotNull(api.fetchInstalledAppOrigins(serverId = "srv-1"))
        assertTrue(fetched.complete)
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://local.example.com",
                appName = "Local Tool",
                logoUrl = "https://api.raft.build/api/logos/local.png",
            ),
            fetched.origins["https://local.example.com"],
        )
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://stamp.build",
                appName = "Stamp",
                logoUrl = "https://api.raft.build/logos/stamp.webp",
            ),
            api.findInstalledAppByOrigin(serverId = "srv-1", targetOrigin = "https://stamp.build"),
        )
        assertEquals(
            setOf("integrations/clients" to "srv-1", "integrations/marketplace" to "srv-1"),
            requestedPaths.toSet(),
        )
    }

    @Test
    fun fetchInstalledAppOriginsMarksPartialFailureIncompleteAndTotalFailureNull() = runTest {
        val marketplaceOnlyClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    when (path) {
                        "integrations/clients" -> HomeNetworkResponse(code = 500, bodyText = null)
                        "integrations/marketplace" ->
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """[{"id":"m1","name":"Stamp","homepageUrl":"https://stamp.build","installationId":"inst-1","installStatus":"active"}]""",
                            )
                        else -> HomeNetworkResponse(code = 404, bodyText = null)
                    }
            }
        val partial = assertNotNull(NetworkRaftBrowserSignInApi(marketplaceOnlyClient).fetchInstalledAppOrigins("srv-1"))
        assertFalse(partial.complete)
        assertEquals("Stamp", partial.origins["https://stamp.build"]?.appName)

        val failingClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse = HomeNetworkResponse(code = 503, bodyText = null)
            }
        assertNull(NetworkRaftBrowserSignInApi(failingClient).fetchInstalledAppOrigins("srv-1"))
    }
}

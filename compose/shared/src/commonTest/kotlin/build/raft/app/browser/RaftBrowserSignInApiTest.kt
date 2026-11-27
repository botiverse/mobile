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

        // Refusal P3/P4 (#16898): loose code/errorCode decoding for installation_suspended, app_not_available, login_mode_changed.
        val suspendedCoded =
            assertIs<RaftBrowserSignInApproveOutcome.InstallationSuspended>(
                assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                    parseRaftBrowserSignInApproveResponse(
                        HomeNetworkResponse(
                            code = 403,
                            bodyText = null,
                            errorBodyText = """{"code":"installation_suspended","error":"Suspended by an admin."}""",
                            responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                        ),
                    ),
                ).data,
            )
        assertEquals("Suspended by an admin.", suspendedCoded.description)
        assertEquals(traceId, suspendedCoded.traceId)

        assertEquals(
            RaftBrowserSignInLookupResult.NotAvailable,
            parseRaftBrowserSignInLookupResponse(
                "srv-5",
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{"errorCode":"app_not_available","error":"Not available"}""",
                ),
            ),
        )
        assertEquals(
            RaftBrowserSignInApproveOutcome.LoginModeChanged,
            assertIs<DataResult.Content<RaftBrowserSignInApproveOutcome>>(
                parseRaftBrowserSignInApproveResponse(
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"errorCode":"login_mode_changed","error":"Switched mode"}""",
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
            setOf(
                "integrations/app-origins" to "srv-1",
                "integrations/clients" to "srv-1",
                "integrations/marketplace" to "srv-1",
            ),
            requestedPaths.toSet(),
        )
    }

    @Test
    fun fetchInstalledAppOriginsUsesServerAppOriginsEndpointWithIconsAndFailsClosed() = runTest {
        val requestedPaths = mutableListOf<Pair<String, String?>>()
        val appOriginsBody =
            """
            {
              "origins": [
                "https://stamp.build",
                "https://preview.example.test",
                "http://insecure.example.test",
                "https://stamp.build/with-path"
              ],
              "icons": {
                "https://stamp.build": "/api/integration-logos/stamp/a.webp",
                "https://preview.example.test": "https://api.raft.build/logos/preview.png",
                "https://unrelated.example.test": "/api/integration-logos/other/x.webp"
              }
            }
            """.trimIndent()
        val modernClient =
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
                        "integrations/app-origins" -> HomeNetworkResponse(code = 200, bodyText = appOriginsBody)
                        else -> error("modern server must not fall back to $path")
                    }
                }
            }
        val api =
            NetworkRaftBrowserSignInApi(
                networkClient = modernClient,
                resolveLogoUrl = { logo -> if (logo.startsWith("/")) "https://api.raft.build$logo" else logo },
            )
        val fetched = assertNotNull(api.fetchInstalledAppOrigins("srv-1"))
        assertTrue(fetched.complete)
        assertEquals(listOf<Pair<String, String?>>("integrations/app-origins" to "srv-1"), requestedPaths)
        assertEquals(setOf("https://stamp.build", "https://preview.example.test"), fetched.origins.keys)
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://stamp.build",
                appName = null,
                logoUrl = "https://api.raft.build/api/integration-logos/stamp/a.webp",
            ),
            fetched.origins["https://stamp.build"],
        )
        assertEquals(
            RaftInstalledAppOriginMatch(
                origin = "https://preview.example.test",
                appName = null,
                logoUrl = "https://api.raft.build/logos/preview.png",
            ),
            fetched.origins["https://preview.example.test"],
        )

        // Pre-#16687 servers omit `icons`; origins still parse cleanly with null logoUrl.
        val originsOnly =
            assertNotNull(parseInstalledAppOriginsEndpointResponse("""{"origins":["https://vault.example.test"]}"""))
        assertEquals(
            RaftInstalledAppOriginMatch(origin = "https://vault.example.test", appName = null, logoUrl = null),
            originsOnly["https://vault.example.test"],
        )

        // Malformed or missing `origins` fails closed (null) rather than poisoning negative cache.
        assertNull(parseInstalledAppOriginsEndpointResponse("""{"icons":{}}"""))
        assertNull(parseInstalledAppOriginsEndpointResponse("not-json"))
        assertNull(parseInstalledAppOriginsEndpointResponse(""))

        // Non-404/405 failure (e.g. 503) fails closed immediately without fanning out to legacy endpoints.
        val serverErrorPaths = mutableListOf<String>()
        val serverErrorClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    serverErrorPaths += path
                    return HomeNetworkResponse(code = 503, bodyText = null)
                }
            }
        assertNull(NetworkRaftBrowserSignInApi(serverErrorClient).fetchInstalledAppOrigins("srv-1"))
        assertEquals(listOf("integrations/app-origins"), serverErrorPaths)
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

    @Test
    fun batchLookupParsesMixedReadyRefusedAndServerErrorEntriesInRequestedOrder() = runTest {
        val traceId = "abcdef0123456789abcdef0123456789"
        val recordedQueries = mutableListOf<Map<String, String>>()
        val batchBody =
            """
            {
              "results": [
                {
                  "serverId": "srv-err",
                  "status": 500,
                  "body": { "error": "Failed to load OAuth client", "code": "lookup_failed", "correlationId": "corr-1" }
                },
                {
                  "serverId": "srv-ready",
                  "status": 200,
                  "body": {
                    "clientId": "stamp",
                    "appType": "third_party_global",
                    "name": "Stamp",
                    "returnUrl": "https://stamp.build/auth/callback",
                    "marketplace": true,
                    "availability": "ready",
                    "scopeValidation": { "allowed": true, "reason": null, "disallowedScopes": [] },
                    "loginMode": "account",
                    "consent": { "granted": true, "coversRequested": true }
                  }
                },
                {
                  "serverId": "srv-suspended",
                  "status": 200,
                  "body": {
                    "clientId": "stamp",
                    "name": "Stamp",
                    "returnUrl": "https://stamp.build/auth/callback",
                    "availability": "suspended"
                  }
                },
                {
                  "serverId": "srv-install",
                  "status": 200,
                  "body": {
                    "id": "uuid-1",
                    "clientId": "stamp",
                    "name": "Stamp",
                    "returnUrl": "https://stamp.build/auth/callback",
                    "marketplace": true,
                    "availability": "install_required",
                    "installation": { "serverId": "srv-install", "canInstall": true }
                  }
                },
                {
                  "serverId": "srv-refused",
                  "status": 403,
                  "body": { "error": "This app isn't available to you.", "code": "app_not_available" }
                },
                {
                  "serverId": "srv-forbidden",
                  "status": 403,
                  "body": { "error": "Forbidden", "code": "other_forbidden" }
                },
                {
                  "serverId": "srv-gone",
                  "status": 404,
                  "body": { "error": "Not found" }
                }
              ]
            }
            """.trimIndent()
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
                    assertEquals("oauth/clients/lookup", path)
                    assertEquals("GET", method)
                    recordedQueries += query.toMap()
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText = batchBody,
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    )
                }
            }

        val requested =
            listOf(
                "srv-ready",
                "srv-suspended",
                "srv-install",
                "srv-refused",
                "srv-forbidden",
                "srv-gone",
                "srv-err",
                "srv-missing",
            )
        val outcome =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                NetworkRaftBrowserSignInApi(fakeClient).lookupClientsBatch(
                    clientId = "stamp",
                    serverIds = requested,
                    scope = "openid profile",
                ),
            )
        assertEquals(1, recordedQueries.size)
        val q = recordedQueries.single()
        assertEquals("stamp", q["client_id"])
        assertEquals("openid profile", q["scope"])
        assertEquals(requested.joinToString(","), q["server_ids"])
        assertFalse("server_id" in q)
        assertEquals(requested, outcome.results.keys.toList())

        val ready = assertIs<RaftBrowserSignInLookupResult.Found>(outcome.results["srv-ready"]).client
        assertEquals("srv-ready", ready.serverId)
        assertEquals("Stamp", ready.name)
        assertEquals(RaftSignInLoginMode.Account, ready.loginMode)
        assertEquals(RaftSignInAvailability.Ready, ready.availability)
        assertEquals(RaftSignInConsent(granted = true, coversRequested = true), ready.consent)

        val suspended = assertIs<RaftBrowserSignInLookupResult.Found>(outcome.results["srv-suspended"]).client
        assertEquals(RaftSignInAvailability.Suspended, suspended.availability)

        val install = assertIs<RaftBrowserSignInLookupResult.Found>(outcome.results["srv-install"]).client
        assertEquals(RaftSignInAvailability.InstallRequired, install.availability)
        assertTrue(install.canInstall)

        assertEquals(RaftBrowserSignInLookupResult.NotAvailable, outcome.results["srv-refused"])
        assertEquals(RaftBrowserSignInLookupResult.Failed(traceId), outcome.results["srv-forbidden"])
        assertEquals(RaftBrowserSignInLookupResult.NotAvailable, outcome.results["srv-gone"])
        assertEquals(RaftBrowserSignInLookupResult.Failed(traceId), outcome.results["srv-err"])
        assertEquals(RaftBrowserSignInLookupResult.Failed(traceId), outcome.results["srv-missing"])
    }

    @Test
    fun batchLookupDeduplicatesCaseInsensitivelyChunksAtFiftyAndUsesSingleForOneServer() = runTest {
        val batchQueries = mutableListOf<Map<String, String>>()
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
                    val map = query.toMap()
                    batchQueries += map
                    val singleId = map["server_id"]
                    if (singleId != null) {
                        assertFalse("server_ids" in map)
                        return HomeNetworkResponse(
                            code = 200,
                            bodyText = """{"clientId":"stamp","name":"Stamp","returnUrl":"https://stamp.build/cb","availability":"ready"}""",
                        )
                    }
                    val ids = assertNotNull(map["server_ids"]).split(",")
                    assertFalse("server_id" in map)
                    assertTrue(ids.size <= RAFT_OAUTH_CLIENT_LOOKUP_BATCH_MAX)
                    assertEquals(ids.size, ids.map { it.lowercase() }.toSet().size)
                    val entriesJson =
                        ids.joinToString(",") { id ->
                            """{"serverId":"$id","status":200,"body":{"clientId":"stamp","name":"Stamp","returnUrl":"https://stamp.build/cb","availability":"ready"}}"""
                        }
                    return HomeNetworkResponse(code = 200, bodyText = """{"results":[$entriesJson]}""")
                }
            }
        val api = NetworkRaftBrowserSignInApi(fakeClient)

        // 120 unique servers + 1 case-insensitive duplicate ("S-3") -> 3 chunks of [50, 50, 20].
        val unique120 = (0 until 120).map { "s-$it" }
        val withCaseDup = unique120 + "S-3"
        val chunkedOutcome =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                api.lookupClientsBatch(clientId = "stamp", serverIds = withCaseDup, scope = "openid profile"),
            )
        assertEquals(
            listOf(50, 50, 20),
            batchQueries.map { assertNotNull(it["server_ids"]).split(",").size }.sortedDescending(),
        )
        assertEquals(121, chunkedOutcome.results.size)
        val upperDup = assertIs<RaftBrowserSignInLookupResult.Found>(chunkedOutcome.results["S-3"]).client
        assertEquals("S-3", upperDup.serverId)
        val lowerOrig = assertIs<RaftBrowserSignInLookupResult.Found>(chunkedOutcome.results["s-3"]).client
        assertEquals("s-3", lowerOrig.serverId)

        // Single server (and case-duplicate pair resolving to 1 unique server) uses single `server_id=` directly.
        batchQueries.clear()
        val singleOutcome =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                api.lookupClientsBatch(clientId = "stamp", serverIds = listOf("srv-1", "SRV-1"), scope = "openid profile"),
            )
        assertEquals(1, batchQueries.size)
        assertEquals("srv-1", batchQueries.single()["server_id"])
        assertFalse("server_ids" in batchQueries.single())
        assertEquals("srv-1", assertIs<RaftBrowserSignInLookupResult.Found>(singleOutcome.results["srv-1"]).client.serverId)
        assertEquals("SRV-1", assertIs<RaftBrowserSignInLookupResult.Found>(singleOutcome.results["SRV-1"]).client.serverId)

        // Empty list makes zero requests.
        batchQueries.clear()
        val emptyOutcome =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                api.lookupClientsBatch(clientId = "stamp", serverIds = emptyList(), scope = "openid profile"),
            )
        assertTrue(batchQueries.isEmpty())
        assertTrue(emptyOutcome.results.isEmpty())
    }

    @Test
    fun batchLookupReturnsUnsupportedOnHttp400AndFailsClosedOn401And503() = runTest {
        val traceId = "0123456789abcdef0123456789abcdef"
        var responseCode = 400
        var responseBody: String? = """{"code":"invalid_input","error":"serverId is required"}"""
        val fakeClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = responseCode,
                        bodyText = if (responseCode in 200..299) responseBody else null,
                        errorBodyText = if (responseCode in 200..299) null else responseBody,
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    )
            }
        val api = NetworkRaftBrowserSignInApi(fakeClient)

        // HTTP 400 from an older server without `server_ids` support signals Unsupported for fallback.
        assertEquals(
            RaftBrowserSignInBatchLookupResult.Unsupported,
            api.lookupClientsBatch("stamp", listOf("srv-1", "srv-2"), "openid profile"),
        )

        // HTTP 401 (unauthorized after refresh) fails closed without falling back to single lookups.
        responseCode = 401
        responseBody = """{"error":"Unauthorized"}"""
        val unauthorized =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                api.lookupClientsBatch("stamp", listOf("srv-1", "srv-2"), "openid profile"),
            )
        assertEquals(
            mapOf(
                "srv-1" to RaftBrowserSignInLookupResult.Failed(traceId),
                "srv-2" to RaftBrowserSignInLookupResult.Failed(traceId),
            ),
            unauthorized.results,
        )

        // HTTP 503 fails closed without falling back to single lookups.
        responseCode = 503
        responseBody = null
        val outage =
            assertIs<RaftBrowserSignInBatchLookupResult.Completed>(
                api.lookupClientsBatch("stamp", listOf("srv-1", "srv-2"), "openid profile"),
            )
        assertEquals(
            mapOf(
                "srv-1" to RaftBrowserSignInLookupResult.Failed(traceId),
                "srv-2" to RaftBrowserSignInLookupResult.Failed(traceId),
            ),
            outage.results,
        )
    }
}

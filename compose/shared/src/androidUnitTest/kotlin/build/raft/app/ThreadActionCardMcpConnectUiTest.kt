package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AppLocale
import build.raft.app.data.ConnectMcpActionCardType
import build.raft.app.data.DataResult
import build.raft.app.data.McpAccountKind
import build.raft.app.data.McpAccountOption
import build.raft.app.data.McpCardAccountChoice
import build.raft.app.data.McpConnectionAuthKind
import build.raft.app.data.McpConnectionAuthorizationApp
import build.raft.app.data.McpConnectionAuthorizationOptions
import build.raft.app.data.McpConnectionAuthorizationView
import build.raft.app.data.McpConnectionStatus
import build.raft.app.data.McpConnectionViewerRole
import build.raft.app.data.McpCurrentAccountSummary
import build.raft.app.data.McpOAuthAttemptResult
import build.raft.app.data.McpOAuthAttemptStatus
import build.raft.app.data.McpOAuthStartResult
import build.raft.app.data.McpPersonalAccountOptions
import build.raft.app.data.McpSharedAccountOption
import build.raft.app.data.McpSharedAccountOptions
import build.raft.app.data.MCP_OAUTH_ERROR_SERVER_FIELD
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.withExecutedMcpConnection
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.thread_action_card_mcp_accounts_allow
import build.raft.app.resources.thread_action_card_mcp_accounts_ask_admin
import build.raft.app.resources.thread_action_card_mcp_accounts_heading
import build.raft.app.resources.thread_action_card_mcp_accounts_new_shared
import build.raft.app.resources.thread_action_card_mcp_accounts_sign_in
import build.raft.app.resources.thread_action_card_mcp_headers_elsewhere
import build.raft.app.resources.thread_action_card_mcp_load_failed
import build.raft.app.resources.thread_action_card_mcp_locked
import build.raft.app.resources.thread_action_card_mcp_oauth_cancel_sign_in
import build.raft.app.resources.thread_action_card_mcp_oauth_open_sign_in_page
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.LocalThreadViewerServerId
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardMcpConnectAccountsTag
import build.raft.app.thread.ThreadActionCardMcpConnectAllowTag
import build.raft.app.thread.ThreadActionCardMcpConnectAskAdminTag
import build.raft.app.thread.ThreadActionCardMcpConnectCurrentAccountTag
import build.raft.app.thread.ThreadActionCardMcpConnectErrorTag
import build.raft.app.thread.ThreadActionCardMcpConnectLoadFailedTag
import build.raft.app.thread.ThreadActionCardMcpConnectLoadingTag
import build.raft.app.thread.ThreadActionCardMcpConnectLockedTag
import build.raft.app.thread.ThreadActionCardMcpConnectOAuthCancelTag
import build.raft.app.thread.ThreadActionCardMcpConnectOAuthOpenTag
import build.raft.app.thread.ThreadActionCardMcpConnectOAuthWaitingTag
import build.raft.app.thread.ThreadActionCardMcpConnectOpenWebTag
import build.raft.app.thread.ThreadActionCardMcpConnectReconfirmTag
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecState
import build.raft.app.thread.ThreadActionCardSpecStore
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.app.thread.ThreadActionCardView
import build.raft.app.thread.ThreadMcpConnectCardHost
import build.raft.app.thread.ThreadMcpConnectFinishOutcome
import build.raft.app.thread.actionCardSpecToRender
import build.raft.app.thread.dispatch
import build.raft.app.thread.threadActionCardMcpChoiceTag
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#228` (`integration:connect_mcp` native Connect
 * card account choice behind `mcp_personal_accounts`, parity with `botiverse/slock` `#16087` +
 * `#16241` + `#16986`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ThreadActionCardMcpConnectUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @BeforeTest
    fun useEnglishLocale() {
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val nowMillis = 1_794_218_580_000L // 2026-11-09 10:03 UTC
    private val names =
        ThreadActionCardNameLookup { kind, id ->
            when {
                kind == ThreadActionCardEntityKind.Agent && id == "ag-scout" -> "Scout"
                kind == ThreadActionCardEntityKind.Agent && id == "ag-1" -> "Atlas"
                kind == ThreadActionCardEntityKind.Agent && id == "ag-2" -> "Beacon"
                kind == ThreadActionCardEntityKind.Agent && id == "ag-3" -> "Comet"
                kind == ThreadActionCardEntityKind.Agent && id == "ag-4" -> "Drift"
                else -> null
            }
        }

    private fun connectSpecJson(): String =
        """
        {"catalog":"action-card.v1","icon":"plug",
         "title":{"key":"actionCard.title.connectMcp","text":{"en":"Authorize Notion for Scout"}},
         "spec":{"root":"root","elements":{
           "root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["props","notice"]},
           "props":{"type":"Stack","props":{"direction":"column","wrap":false,"role":"properties"},"children":["app"]},
           "app":{"type":"Field","props":{"label":{"key":"actionCard.field.app","text":{"en":"App"}},"value":"Notion"}},
           "notice":{"type":"Notice","props":{"tone":"info","text":{"key":"actionCard.mcp.loginRequired","text":{"en":"Open on web to connect Notion."}}}}
         }}}
        """.trimIndent()

    private fun connectCard(
        state: String = "prepared",
        sourceServerId: String? = "srv-host",
        targetServerName: String? = "Host Co",
        executedByUserName: String? = null,
    ) = ThreadActionCard(
        type = ConnectMcpActionCardType,
        state = state,
        mcpServerId = "app-notion",
        agentId = "ag-scout",
        agentName = "Scout",
        confirmationVersion = 3,
        sourceServerId = sourceServerId,
        targetServerName = targetServerName,
        executedByUserName = executedByUserName,
        presentationCardJson = connectSpecJson(),
    )

    private fun ComposeUiTest.advance(millis: Long = 100L) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private class FakeMcpHost(
        var onLoad: suspend () -> DataResult<McpConnectionAuthorizationView>,
        var onGrant: suspend (McpCardAccountChoice.Existing) -> DataResult<ThreadMcpConnectFinishOutcome> = {
            DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
        },
        var onStartOAuth: suspend (McpAccountKind, String) -> DataResult<McpOAuthStartResult> = { _, _ ->
            DataResult.Content(McpOAuthStartResult("https://auth.notion.so/start", "att-1", "c-new"))
        },
        var onPoll: suspend (String) -> DataResult<McpOAuthAttemptResult> = {
            DataResult.Content(McpOAuthAttemptResult("att-1", McpOAuthAttemptStatus.Pending, "c-new"))
        },
        var onFinishOAuth: suspend (McpAccountKind) -> DataResult<ThreadMcpConnectFinishOutcome> = {
            DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
        },
        override val supportsNativeOAuthStart: Boolean = true,
    ) : ThreadMcpConnectCardHost {
        val openedUrls = mutableListOf<String>()
        override val pollIntervalMillis: Long = 50L
        override val pollTimeoutMillis: Long = 200L

        override suspend fun loadAuthorization(appId: String, agentId: String, sourceServerId: String?): DataResult<McpConnectionAuthorizationView> =
            onLoad()

        override suspend fun grantExistingAccount(
            messageId: String,
            card: ThreadActionCard,
            appId: String,
            agentId: String,
            choice: McpCardAccountChoice.Existing,
        ): DataResult<ThreadMcpConnectFinishOutcome> = onGrant(choice)

        override suspend fun startNewOAuth(
            messageId: String,
            card: ThreadActionCard,
            appId: String,
            agentId: String,
            kind: McpAccountKind,
            connectionName: String,
        ): DataResult<McpOAuthStartResult> = onStartOAuth(kind, connectionName)

        override suspend fun pollOAuthAttempt(attemptId: String, sourceServerId: String?): DataResult<McpOAuthAttemptResult> =
            onPoll(attemptId)

        override suspend fun finishOAuthSignIn(
            messageId: String,
            card: ThreadActionCard,
            appId: String,
            agentId: String,
            kind: McpAccountKind,
        ): DataResult<ThreadMcpConnectFinishOutcome> = onFinishOAuth(kind)

        override fun openAuthorizationUrl(url: String) {
            openedUrls += url
        }
    }

    private fun ComposeUiTest.mountMcpCard(
        cardState: () -> ThreadActionCard,
        messageIdState: () -> String = { "m-mcp" },
        flagEnabled: () -> Boolean = { true },
        hostState: () -> ThreadMcpConnectCardHost?,
        viewerServerId: String = "srv-host",
        commitEnabled: () -> Boolean = { true },
        store: ThreadActionCardSpecStore? = null,
        onOpenInWeb: (String) -> Unit = {},
    ) {
        val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = nowMillis)
        setContent {
            val current = cardState()
            val messageId = messageIdState()
            val enabled = flagEnabled()
            val host = hostState()
            val spec = current.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = enabled)
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(
                            state = store?.state ?: MutableStateFlow(ThreadActionCardSpecState()),
                            onIntent = store?.let { it::dispatch } ?: {},
                            openInWeb = onOpenInWeb,
                            viewerUserId = "u-ada",
                            mcpPersonalAccountsEnabled = enabled,
                            mcpConnectCardHost = host,
                        ),
                    LocalThreadViewerServerId provides viewerServerId,
                    LocalThreadActionCardNames provides names,
                    LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                ) {
                    Column {
                        if (spec != null) {
                            ThreadActionCardSpecView(
                                messageId = messageId,
                                card = current,
                                spec = spec,
                                commitEnabled = commitEnabled(),
                                typedPending = false,
                                executedTimeLabel = null,
                                onAction = {},
                            )
                        } else {
                            ThreadActionCardView(
                                card = current,
                                pending = false,
                                commitEnabled = commitEnabled(),
                                executedTimeLabel = null,
                                onOpenInWeb = { onOpenInWeb(messageId) },
                                onCommit = {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun flagOffFallsBackToLegacyOpenOnWebCard() = runRobolectricComposeUiTest {
        val current by mutableStateOf(
            connectCard().copy(
                metadataItems =
                    listOf(
                        build.raft.app.data.ThreadActionCardMetadataItem(key = "mcpServerId", value = "app-notion"),
                        build.raft.app.data.ThreadActionCardMetadataItem(key = "agentId", value = "ag-scout"),
                    )
            )
        )
        assertNull(current.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = false))
        mountMcpCard(cardState = { current }, flagEnabled = { false }, hostState = { null })
        waitForIdle()
        onNodeWithText("Open on the web").assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()
        onNodeWithText("Mcp Server Id").assertDoesNotExist()
        onNodeWithText("Agent Id").assertDoesNotExist()
    }

    @Test
    fun loadingFailureRetriesAndServerFlagOffHandlesObserverVsManager() = runRobolectricComposeUiTest {
        val gate = CompletableDeferred<DataResult<McpConnectionAuthorizationView>>()
        var loadCalls = 0
        val host =
            FakeMcpHost(
                onLoad = {
                    loadCalls++
                    when (loadCalls) {
                        1 -> gate.await()
                        2 ->
                            DataResult.Content(
                                McpConnectionAuthorizationView(
                                    viewerRole = McpConnectionViewerRole.Observer,
                                    app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                                    options = null,
                                )
                            )
                        else ->
                            DataResult.Content(
                                McpConnectionAuthorizationView(
                                    viewerRole = McpConnectionViewerRole.ServerManager,
                                    app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                                    options = null,
                                )
                            )
                    }
                }
            )
        var current by mutableStateOf(connectCard())
        var messageId by mutableStateOf("m-mcp-1")
        val openedWeb = mutableListOf<String>()
        mountMcpCard(
            cardState = { current },
            messageIdState = { messageId },
            hostState = { host },
            onOpenInWeb = { openedWeb += it },
        )
        waitForIdle()
        onNodeWithTag(ThreadActionCardMcpConnectLoadingTag).assertExists()
        // Legacy loginRequired notice is stripped when native footer mounts.
        onNodeWithText("Open on web to connect Notion.").assertDoesNotExist()

        gate.complete(DataResult.Error(ScreenError(code = "http_500", traceId = "0123456789abcdef0123456789abcdef")))
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectLoadFailedTag).assertExists()
        onNodeWithText("Couldn't load accounts for this app.").assertExists()
        onNodeWithTag(RaftTraceIdNoteTestTag).assertExists()

        // Retry -> server flag OFF, observer sees locked notice.
        onNodeWithText("Retry").performClick()
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectLockedTag).assertExists()
        onNodeWithText("Someone who can manage this Agent needs to connect this App.").assertExists()

        // Switch to another message id -> fresh slot load -> server flag OFF, server manager sees Open on the web.
        messageId = "m-mcp-2"
        current = connectCard()
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectOpenWebTag).performClick()
        waitForIdle()
        assertEquals(listOf("m-mcp-2"), openedWeb.toList())
    }

    @Test
    fun memberAccountPickerFiltersExpiredShowsAlsoUsedByAndGrantsAccount() = runRobolectricComposeUiTest {
        var current by mutableStateOf(connectCard())
        var grantedChoice: McpCardAccountChoice.Existing? = null
        val host =
            FakeMcpHost(
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.AgentManager,
                            app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                            options =
                                McpConnectionAuthorizationOptions(
                                    personal =
                                        McpPersonalAccountOptions(
                                            connections =
                                                listOf(
                                                    McpAccountOption("p-exp", "Expired", "old@acme.com", McpConnectionStatus.Expired),
                                                    McpAccountOption(
                                                        connectionId = "p-ready",
                                                        name = "Notion",
                                                        label = "ada@acme.com",
                                                        status = McpConnectionStatus.Ready,
                                                        agentIds = listOf("ag-1", "ag-2", "ag-3", "ag-4"),
                                                    ),
                                                ),
                                            canConnectPersonal = true,
                                        ),
                                ),
                            account =
                                McpCurrentAccountSummary(
                                    kind = McpAccountKind.Personal,
                                    connectionId = "p-bob",
                                    ownerUserId = "u-bob",
                                    ownerDisplayName = "Bob",
                                ),
                        )
                    )
                },
                onGrant = { choice ->
                    grantedChoice = choice
                    current = current.withExecutedMcpConnection("app-notion", "ag-scout", "Ada")
                    DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
                },
            )
        mountMcpCard(cardState = { current }, hostState = { host })
        advance()

        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertExists()
        onNodeWithText("Which account Scout uses").assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectCurrentAccountTag).assertExists()
        onNodeWithText("Scout uses Bob's account now.").assertExists()
        // #16241: expired personal account is NOT listed.
        onNodeWithTag(threadActionCardMcpChoiceTag("p-exp")).assertDoesNotExist()
        // Ready personal account is preselected and truncates >3 other agents.
        onNodeWithTag(threadActionCardMcpChoiceTag("p-ready")).assertIsSelected()
        onNodeWithText("Your account · Also used by Atlas, Beacon, Comet and 1 more").assertExists()
        onNodeWithText("Sign in with another Notion account").assertExists()

        // Tap Allow -> grants personal account and marks card executed (picker disappears).
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).assertIsEnabled().performClick()
        advance()
        assertEquals("p-ready", grantedChoice?.option?.connectionId)
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()
    }

    @Test
    fun emptyChoicesShowAskAdminOrHeadersSettingsNoteWithZeroControls() = runRobolectricComposeUiTest {
        val memberHost =
            FakeMcpHost(
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.AgentManager,
                            app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                            options = McpConnectionAuthorizationOptions(personal = McpPersonalAccountOptions(emptyList(), false)),
                        )
                    )
                }
            )
        val headersHost =
            FakeMcpHost(
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.ServerManager,
                            app = McpConnectionAuthorizationApp("Internal API", McpConnectionAuthKind.Headers),
                            options =
                                McpConnectionAuthorizationOptions(
                                    personal = McpPersonalAccountOptions(emptyList(), false),
                                    shared = McpSharedAccountOptions(emptyList(), false),
                                ),
                        )
                    )
                }
            )
        val current by mutableStateOf(connectCard())
        var messageId by mutableStateOf("m-empty-1")
        var activeHost: ThreadMcpConnectCardHost by mutableStateOf(memberHost)
        mountMcpCard(cardState = { current }, messageIdState = { messageId }, hostState = { activeHost })
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectAskAdminTag).assertExists()
        onNodeWithText("Ask an admin to connect an account for Scout.").assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).assertDoesNotExist()

        activeHost = headersHost
        messageId = "m-empty-2"
        advance()
        onNodeWithText("API-key connections are added in the App's settings.").assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).assertDoesNotExist()
    }

    @Test
    fun newOAuthSignInShowsWaitingBannerHandlesCompletionAndSurfacesStartErrors() = runRobolectricComposeUiTest {
        var current by mutableStateOf(connectCard())
        var pollCount = 0
        var startShouldFail = false
        val host =
            FakeMcpHost(
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.ServerManager,
                            app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                            options =
                                McpConnectionAuthorizationOptions(
                                    personal = McpPersonalAccountOptions(emptyList(), canConnectPersonal = true),
                                    shared =
                                        McpSharedAccountOptions(
                                            connections =
                                                listOf(
                                                    McpSharedAccountOption("s-exp", "Broken", null, McpConnectionStatus.Expired, allAgents = true),
                                                ),
                                            canConnectShared = true,
                                        ),
                                ),
                        )
                    )
                },
                onStartOAuth = { _, _ ->
                    if (startShouldFail) {
                        DataResult.Error(
                            ScreenError(
                                code = "http_422",
                                serverCode = "mcp_oauth_dcr_rejected",
                                serverFields = mapOf(MCP_OAUTH_ERROR_SERVER_FIELD to "invalid_redirect_uri"),
                                traceId = "abcdef0123456789abcdef0123456789",
                            )
                        )
                    } else {
                        DataResult.Content(McpOAuthStartResult("https://auth.notion.so/start", "att-ui-1", "c-new"))
                    }
                },
                onPoll = {
                    pollCount++
                    if (pollCount >= 2) {
                        DataResult.Content(McpOAuthAttemptResult("att-ui-1", McpOAuthAttemptStatus.Connected, "c-new"))
                    } else {
                        DataResult.Content(McpOAuthAttemptResult("att-ui-1", McpOAuthAttemptStatus.Pending, "c-new"))
                    }
                },
                onFinishOAuth = {
                    current = current.withExecutedMcpConnection("app-notion", "ag-scout", "Ada")
                    DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
                },
            )
        mountMcpCard(cardState = { current }, hostState = { host })
        advance()

        // Non-ready shared choice is disabled; new:personal is preselected.
        onNodeWithTag(threadActionCardMcpChoiceTag("s-exp")).assertIsNotEnabled()
        onNodeWithTag(threadActionCardMcpChoiceTag("new:personal")).assertIsSelected()
        onNodeWithText("Sign in with Notion").assertExists()

        // First test start error with provider oauthError + traceId.
        startShouldFail = true
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).performClick()
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectErrorTag).assertExists()
        onNode(hasText("invalid_redirect_uri", substring = true)).assertExists()
        onNode(hasText("Ask an admin to check its sign-in settings.", substring = true)).assertExists()
        onNode(hasText("OAuth client", substring = true)).assertDoesNotExist()
        onNodeWithTag(RaftTraceIdNoteTestTag).assertExists()

        // Now succeed -> opens browser URL, shows waiting banner, polls connected -> executed.
        startShouldFail = false
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).performClick()
        advance(10L)
        assertEquals(listOf("https://auth.notion.so/start"), host.openedUrls.toList())
        onNodeWithTag(ThreadActionCardMcpConnectOAuthWaitingTag).assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectOAuthOpenTag).assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectOAuthCancelTag).assertExists()

        // Advance past second poll -> Connected -> card marked executed.
        advance(120L)
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()
    }

    @Test
    fun newOAuthHandsOffToWebWhenHostDoesNotSupportNativeOAuthStart() = runRobolectricComposeUiTest {
        var webOpens = 0
        var oauthStartCalls = 0
        val host =
            FakeMcpHost(
                supportsNativeOAuthStart = false,
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.AgentManager,
                            app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                            options =
                                McpConnectionAuthorizationOptions(
                                    personal = McpPersonalAccountOptions(emptyList(), canConnectPersonal = true),
                                ),
                        )
                    )
                },
                onStartOAuth = { _, _ ->
                    oauthStartCalls++
                    DataResult.Content(McpOAuthStartResult("https://auth.notion.so/start", "att-unused", "c-new"))
                },
            )
        mountMcpCard(
            cardState = { connectCard() },
            hostState = { host },
            onOpenInWeb = { webOpens++ },
        )
        advance()

        onNodeWithTag(threadActionCardMcpChoiceTag("new:personal")).assertIsSelected()
        onNodeWithText("Open on the web").assertExists()
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).performClick()
        advance()

        assertEquals(1, webOpens)
        assertEquals(0, oauthStartCalls, "must not call Ktor oauth/start when callback cookie cannot cross to external browser")
        onNodeWithTag(ThreadActionCardMcpConnectOAuthWaitingTag).assertDoesNotExist()
    }

    @Test
    fun cardLifecycleStatesReconfirmFrozenExpiredCrossServerAndReadOnly() = runRobolectricComposeUiTest {
        var current by mutableStateOf(connectCard(state = "reconfirm_required"))
        var commitEnabled by mutableStateOf(true)
        var reconfirmed = 0
        val host =
            FakeMcpHost(
                onLoad = {
                    DataResult.Content(
                        McpConnectionAuthorizationView(
                            viewerRole = McpConnectionViewerRole.AgentManager,
                            app = McpConnectionAuthorizationApp("Notion", McpConnectionAuthKind.OAuth),
                            options = McpConnectionAuthorizationOptions(personal = McpPersonalAccountOptions(emptyList(), true)),
                        )
                    )
                }
            )
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                reconfirmCard = { _, _ ->
                    reconfirmed++
                    DataResult.Content(connectCard(state = "prepared"))
                },
                applyCard = { _, updated -> current = updated },
            )
        mountMcpCard(cardState = { current }, hostState = { host }, commitEnabled = { commitEnabled }, store = store)
        waitForIdle()

        onNodeWithTag(ThreadActionCardMcpConnectReconfirmTag).assertIsEnabled().performClick()
        advance()
        assertEquals(1, reconfirmed)
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertExists()

        // Frozen disables button.
        current = connectCard(state = "frozen")
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectAllowTag).assertIsNotEnabled()

        // Read-only hides picker.
        current = connectCard(state = "prepared")
        commitEnabled = false
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()

        // Cross-server hides picker.
        commitEnabled = true
        current = connectCard(state = "prepared", sourceServerId = "srv-other", targetServerName = "Other Co")
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()

        // Expired hides picker.
        current = connectCard(state = "expired")
        advance()
        onNodeWithTag(ThreadActionCardMcpConnectAccountsTag).assertDoesNotExist()
    }

    @Test
    fun allEightLocalesResolveMcpConnectStrings() {
        val locales = listOf("en", "zh-CN", "zh-TW", "ja", "ko", "es", "pt-BR", "ru")
        for (tag in locales) {
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertTrue(Res.string.thread_action_card_mcp_locked.desc().resolve().isNotBlank(), "$tag locked")
            assertTrue(Res.string.thread_action_card_mcp_accounts_heading.format("Scout").resolve().contains("Scout"), "$tag heading")
            assertTrue(Res.string.thread_action_card_mcp_accounts_allow.desc().resolve().isNotBlank(), "$tag allow")
            assertTrue(Res.string.thread_action_card_mcp_accounts_sign_in.format("Notion").resolve().contains("Notion"), "$tag signIn")
            assertTrue(Res.string.thread_action_card_mcp_accounts_new_shared.desc().resolve().isNotBlank(), "$tag newShared")
            assertTrue(Res.string.thread_action_card_mcp_accounts_ask_admin.format("Scout").resolve().contains("Scout"), "$tag askAdmin")
            assertTrue(Res.string.thread_action_card_mcp_headers_elsewhere.desc().resolve().isNotBlank(), "$tag headers")
            assertTrue(Res.string.thread_action_card_mcp_load_failed.desc().resolve().isNotBlank(), "$tag loadFailed")
            assertTrue(Res.string.thread_action_card_mcp_oauth_open_sign_in_page.desc().resolve().isNotBlank(), "$tag openSignIn")
            assertTrue(Res.string.thread_action_card_mcp_oauth_cancel_sign_in.desc().resolve().isNotBlank(), "$tag cancelSignIn")
        }
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        assertEquals("Which account Scout uses", Res.string.thread_action_card_mcp_accounts_heading.format("Scout").resolve())
        assertEquals("Allow", Res.string.thread_action_card_mcp_accounts_allow.desc().resolve())
        assertEquals("Connect a new team account", Res.string.thread_action_card_mcp_accounts_new_shared.desc().resolve())

        StringDesc.localeType = StringDesc.LocaleType.Custom("zh-CN")
        assertEquals("Scout 使用哪个账号", Res.string.thread_action_card_mcp_accounts_heading.format("Scout").resolve())
        assertEquals("允许", Res.string.thread_action_card_mcp_accounts_allow.desc().resolve())
        assertEquals("连接新的团队账号", Res.string.thread_action_card_mcp_accounts_new_shared.desc().resolve())
    }
}

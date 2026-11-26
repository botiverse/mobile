package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardCloseOutcome
import build.raft.app.data.AppLocale
import build.raft.app.data.ChannelInviteExternalActionCardType
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAddressesOutcome
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.MemberConnectResponse
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.SentExternalInvite
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardClosedBy
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.joint_member_connect_empty
import build.raft.app.resources.resolve
import build.raft.app.resources.thread_action_card_blocked_need_federate_channels
import build.raft.app.resources.thread_action_card_busy_working
import build.raft.app.resources.thread_action_card_invite_external_declined_by
import build.raft.app.resources.thread_action_card_invite_external_handled_by
import build.raft.app.resources.thread_action_card_invite_external_unavailable
import build.raft.app.resources.thread_action_card_verb_connect_own_servers
import build.raft.app.resources.thread_action_card_verb_decline_invite
import build.raft.app.resources.thread_action_card_verb_invite_external
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#304` (`external_invite_card_v0` —
 * `channel:invite_external` action card, botiverse/slock #16530, #16543, #16662, #16671):
 * - Frame-drawn "Invite people" / "Connect servers" + "Decline" / "Reconfirm" buttons
 * - Permission gate (`needFederateChannels`), cross-server, read-only, own-request, frozen states
 * - Done ("Handled by ...", hiding zero-address result) & Declined ("Closed" + "Declined by ...")
 * - 404 unavailable recovery with Decline still working
 * - Prefilled `ExternalInviteSheet` (`addresses > 0`) & direct `JointMemberConnectSheet`
 *   (`addresses == 0`, including empty-servers copy with Connect button hidden)
 * - 8-locale string parity against `slock`
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ThreadActionCardInviteExternalUiTest {
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
                kind == ThreadActionCardEntityKind.Human && id == "u-ada" -> "Ada"
                kind == ThreadActionCardEntityKind.Channel && id == "ch-launch" -> "launch"
                else -> null
            }
        }

    private fun inviteSpecJson(addresses: Int, includeResult: Boolean = false): String {
        val title =
            if (addresses > 0) {
                "Invite $addresses people to #launch"
            } else {
                "Connect your servers to #launch"
            }
        val resultJson =
            if (includeResult) {
                ""","result":{"root":"res","elements":{"res":{"type":"Text","props":{"text":"Handled · $addresses addresses on the card"}}}}"""
            } else {
                ""
            }
        return """
            {"catalog":"action-card.v1","icon":"user_plus",
             "title":{"key":"actionCard.title.inviteExternal","text":{"en":"$title"}},
             "spec":{"root":"root","elements":{
               "root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["props"]},
               "props":{"type":"Stack","props":{"direction":"column","wrap":false,"role":"properties"},"children":["ch"]},
               "ch":{"type":"Field","props":{"label":{"key":"actionCard.field.channel","text":{"en":"Channel"}},"value":[{"kind":"channel","id":"ch-launch","fallback":"launch"}]}}
             }}$resultJson}
        """.trimIndent()
    }

    private fun inviteCard(
        state: String = "prepared",
        addresses: Int = 2,
        sourceServerId: String? = "srv-host",
        targetServerName: String? = "Host Co",
        requesterUserId: String? = null,
        executedByUserName: String? = null,
        resultKind: String? = null,
        resultAddresses: Int? = null,
        closedBy: ThreadActionCardClosedBy? = null,
        closedAt: String? = null,
        includeResultSpec: Boolean = false,
    ) = ThreadActionCard(
        type = ChannelInviteExternalActionCardType,
        state = state,
        channel = "ch-launch",
        externalInviteAddresses = addresses,
        confirmationVersion = 2,
        sourceServerId = sourceServerId,
        targetServerName = targetServerName,
        requesterUserId = requesterUserId,
        executedByUserName = executedByUserName,
        resultKind = resultKind,
        resultAddresses = resultAddresses,
        closedBy = closedBy,
        closedAt = closedAt,
        presentationCardJson = inviteSpecJson(addresses, includeResult = includeResultSpec),
    )

    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    private fun ComposeUiTest.mountCard(
        cardState: () -> ThreadActionCard,
        canInvite: () -> Boolean = { true },
        viewerUserId: String = "u-owner",
        viewerServerId: String = "srv-host",
        commitEnabled: () -> Boolean = { true },
        store: ThreadActionCardSpecStore? = null,
    ) {
        val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = nowMillis)
        setContent {
            val current = cardState()
            val spec = assertNotNull(current.actionCardSpecToRender(capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(
                    LocalThreadActionCardSpecHost provides
                        ThreadActionCardSpecHost(
                            state = store?.state ?: MutableStateFlow(ThreadActionCardSpecState()),
                            onIntent = store?.let { it::dispatch } ?: {},
                            viewerUserId = viewerUserId,
                            evaluateInviteExternalAccess = { InviteExternalCardAccess(canInvite = canInvite()) },
                        ),
                    LocalThreadViewerServerId provides viewerServerId,
                    LocalThreadActionCardNames provides names,
                    LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                ) {
                    Column {
                        ThreadActionCardSpecView(
                            messageId = "m-inv",
                            card = current,
                            spec = spec,
                            commitEnabled = commitEnabled(),
                            typedPending = false,
                            executedTimeLabel = null,
                            onAction = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun frameButtonsAndAccessGatesMatchWebForInviteAndConnectOnlyCards() = runRobolectricComposeUiTest {
        var current by mutableStateOf(inviteCard(addresses = 2))
        var canInvite by mutableStateOf(true)
        var commitEnabled by mutableStateOf(true)
        var addressFetchCount = 0
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                readExternalInviteAddresses = { _, _ ->
                    addressFetchCount++
                    DataResult.Content(ExternalInviteAddressesOutcome.Loaded(listOf("a@example.com", "b@example.com")))
                },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mountCard(cardState = { current }, canInvite = { canInvite }, commitEnabled = { commitEnabled }, store = store)

        // Open card with addresses > 0: "Invite people" + "Decline", never fetches on render.
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertExists().assertIsEnabled()
        onNodeWithText("Invite people").assertExists()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertExists().assertIsEnabled()
        onNodeWithText("Decline").assertExists()
        onNodeWithText("Open in web").assertDoesNotExist()
        assertEquals(0, addressFetchCount, "addresses must never be fetched on render")

        // addresses == 0: "Connect servers" + "Decline".
        current = inviteCard(addresses = 0)
        waitForIdle()
        onNodeWithText("Connect servers").assertExists()
        onNodeWithText("Invite people").assertDoesNotExist()
        onNodeWithText("Decline").assertExists()

        // Non-permitted viewer (member/guest): "View only" + needFederateChannels copy, no buttons.
        canInvite = false
        waitForIdle()
        onNodeWithText("View only").assertExists()
        onNodeWithText("Only a Server owner or admin can invite people outside the server.").assertExists()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertDoesNotExist()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertDoesNotExist()

        // Cross-server card: targetServer copy, no buttons.
        canInvite = true
        current = inviteCard(sourceServerId = "srv-other", targetServerName = "Other Co")
        waitForIdle()
        onNodeWithText("Only members of Other Co can use this card.").assertExists()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertDoesNotExist()

        // Read-only thread: no buttons.
        current = inviteCard()
        commitEnabled = false
        waitForIdle()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertDoesNotExist()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertDoesNotExist()

        // Own request: no buttons.
        commitEnabled = true
        current = inviteCard(requesterUserId = "u-owner")
        waitForIdle()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertDoesNotExist()

        // Frozen card: buttons stay rendered but disabled.
        current = inviteCard(state = "frozen")
        waitForIdle()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertExists().assertIsNotEnabled()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertExists().assertIsNotEnabled()
    }

    @Test
    fun reconfirmDoneDeclinedAnd404UnavailableStatesRenderAndActCorrectly() = runRobolectricComposeUiTest {
        var current by mutableStateOf(inviteCard(state = "reconfirm_required"))
        val store =
            ThreadActionCardSpecStore(
                scope = CoroutineScope(Dispatchers.Unconfined),
                execute = { _, _, _ -> error("not used") },
                readExternalInviteAddresses = { _, _ -> DataResult.Content(ExternalInviteAddressesOutcome.Unavailable) },
                declineExternalInvite = { _, card ->
                    DataResult.Content(
                        ActionCardCloseOutcome.Closed(
                            card.copy(
                                state = "expired",
                                closedBy = ThreadActionCardClosedBy("u-ada", "ada"),
                                closedAt = "2026-11-09T10:05:00.000Z",
                            ),
                        ),
                    )
                },
                reconfirmCard = { _, card -> DataResult.Content(card.copy(state = "prepared", confirmationVersion = 3)) },
                applyCard = { _, next -> current = next },
                nowMillis = { nowMillis },
            )
        mainClock.autoAdvance = false
        mountCard(cardState = { current }, store = store)
        advance(50)

        // Reconfirm card shows "Reconfirm" and transitions back to prepared on click.
        onNodeWithTag(ThreadActionCardInviteExternalReconfirmTag).assertExists().assertIsEnabled()
        onNodeWithText("Reconfirm").performClick()
        advance(50)
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertExists().assertIsEnabled()

        // 404 on Invite people shows unavailable banner and keeps Decline enabled.
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).performClick()
        advance(50)
        onNodeWithText("This card can’t be opened now. Reload to see where it stands.").assertExists()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertExists().assertIsEnabled()

        // Clicking Decline closes immediately -> status "Closed" (not "Expired"), "Declined by Ada", no buttons.
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).performClick()
        advance(50)
        onNodeWithText("Closed").assertExists()
        onNodeWithText("Expired").assertDoesNotExist()
        onNodeWithText("Declined by Ada", substring = true).assertExists()
        onNodeWithTag(ThreadActionCardInviteExternalOpenTag).assertDoesNotExist()
        onNodeWithTag(ThreadActionCardInviteExternalDeclineTag).assertDoesNotExist()

        // Done with addresses == 0 shows "Handled by Ada" (never "Committed by") and hides "0 addresses".
        current =
            inviteCard(
                state = "executed",
                addresses = 0,
                executedByUserName = "Ada",
                resultKind = "external-invites",
                resultAddresses = 0,
                includeResultSpec = true,
            )
        advance(50)
        onNodeWithText("Done").assertExists()
        onNodeWithText("Handled by Ada", substring = true).assertExists()
        onNodeWithText("Committed by Ada", substring = true).assertDoesNotExist()
        onNodeWithText("0 addresses", substring = true).assertDoesNotExist()
    }

    @Test
    fun prefilledInviteSheetSendsAndMarksCardExecutedOnce() = runRobolectricComposeUiTest {
        val inviteSource =
            FakeExternalInvitesDataSource().apply {
                previewAnswer =
                    DataResult.Content(
                        ExternalInviteAnswer.Ok(
                            ExternalInvitePreview(valid = listOf("a@example.com", "b@example.com"), slotsLeft = 20),
                        ),
                    )
                sendAnswer =
                    DataResult.Content(
                        ExternalInviteAnswer.Ok(
                            SendExternalInvitesResult(sent = listOf(SentExternalInvite("i1", "a@example.com"), SentExternalInvite("i2", "b@example.com"))),
                        ),
                    )
            }
        var markedCount = 0
        var dismissed = false
        val inviteStore =
            ExternalInvitesStore(
                scope = CoroutineScope(Dispatchers.Main.immediate),
                dataSource = inviteSource,
                onSent = {
                    markedCount++
                    dismissed = true
                },
            )
        inviteStore.dispatch(ExternalInvitesIntent.OpenDialog(joint = false, initialText = "a@example.com, b@example.com"))
        val connectStore = JointChannelConnectionsStore(FakeJointConnectionsDataSource(), CoroutineScope(Dispatchers.Main.immediate))

        setContent {
            val invState by inviteStore.state.collectAsState()
            val connState by connectStore.state.collectAsState()
            RaftDesignSystemTheme {
                ThreadInviteExternalActionDialogContent(
                    connectOnly = false,
                    serverName = "Host Co",
                    channelName = "launch",
                    willBecomePrivate = false,
                    joint = false,
                    externalInvitesState = invState,
                    onExternalInvitesIntent = inviteStore::dispatch,
                    connectState = connState,
                    onConnectIntent = connectStore::dispatch,
                    connectServersAvailable = false,
                    publicConnect = null,
                    onDismiss = { dismissed = true },
                    onUpgradeExternalLimit = {},
                    onUpgradeServer = {},
                )
            }
        }
        waitForIdle()

        // Emails are prefilled; Next -> Send calls send and triggers onSent/mark-executed once.
        onNodeWithTag(ExternalInviteSheetTag, useUnmergedTree = true).assertExists()
        onNodeWithText("a@example.com, b@example.com", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Next", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Invite", useUnmergedTree = true).performClick()
        waitForIdle()

        assertEquals(1, inviteSource.sendCalls.size)
        assertEquals(listOf("a@example.com", "b@example.com"), inviteSource.sendCalls.single().first)
        assertEquals(1, markedCount)
        assertTrue(dismissed)
    }

    @Test
    fun connectOnlySheetShowsEmptyCopyAndHidesConnectWhenNoServersExist() = runRobolectricComposeUiTest {
        val connections =
            JointChannelConnections(
                channelId = ChannelId("ch-launch"),
                channelName = "launch",
                isJoint = false,
                archived = false,
                servers = emptyList(),
                hasCurrentServerPendingInvites = false,
                isPrivate = true,
                joined = true,
            )
        val noOtherServers =
            MemberConnectAccount(
                currentServerId = "srv-host",
                currentServerName = "Host Co",
                currentServerSlug = "host-co",
                currentServerRole = "owner",
                servers = listOf(MemberConnectServer("srv-host", "Host Co", "host-co", null, "owner")),
            )
        var markedCount = 0
        var dismissed = false
        val connectStore =
            JointChannelConnectionsStore(
                dataSource = FakeJointConnectionsDataSource(connections),
                scope = CoroutineScope(Dispatchers.Main.immediate),
                onConnectionsChanged = { markedCount++ },
            )
        connectStore.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(noOtherServers))
        connectStore.dispatch(JointChannelConnectionsIntent.SeedChannel(connections))
        connectStore.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(sayWhenEmpty = true))
        val inviteStore = ExternalInvitesStore(CoroutineScope(Dispatchers.Main.immediate), FakeExternalInvitesDataSource())

        setContent {
            val invState by inviteStore.state.collectAsState()
            val connState by connectStore.state.collectAsState()
            RaftDesignSystemTheme {
                ThreadInviteExternalActionDialogContent(
                    connectOnly = true,
                    serverName = "Host Co",
                    channelName = "launch",
                    willBecomePrivate = false,
                    joint = false,
                    externalInvitesState = invState,
                    onExternalInvitesIntent = inviteStore::dispatch,
                    connectState = connState,
                    onConnectIntent = connectStore::dispatch,
                    connectServersAvailable = false,
                    publicConnect = null,
                    onDismiss = { dismissed = true },
                    onUpgradeExternalLimit = {},
                    onUpgradeServer = {},
                )
            }
        }
        waitForIdle()

        assertTrue(onAllNodes(hasTestTag(ExternalInviteSheetTag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(JointMemberConnectSheetTag, useUnmergedTree = true).assertExists()
        onNodeWithTag(JointMemberConnectEmptyTag, useUnmergedTree = true).assertExists()
        onNodeWithText(
            "You don't have a server that can connect to #launch. Servers you own or administer appear here.",
            useUnmergedTree = true,
        ).assertExists()
        assertTrue(onAllNodes(hasText("Connect"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "Connect button must be hidden when empty")
        assertTrue(onAllNodes(hasContentDescription("Back"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "No Back button in connect-only mode")

        onAllNodes(hasContentDescription("Close"), useUnmergedTree = true)[0].performClick()
        waitForIdle()
        assertTrue(dismissed)
        assertEquals(0, markedCount)
    }

    @Test
    fun connectOnlySheetConnectsServerAndMarksCardExecutedOnce() = runRobolectricComposeUiTest {
        val connections =
            JointChannelConnections(
                channelId = ChannelId("ch-launch"),
                channelName = "launch",
                isJoint = false,
                archived = false,
                servers = emptyList(),
                hasCurrentServerPendingInvites = false,
                isPrivate = true,
                joined = true,
            )
        val account =
            MemberConnectAccount(
                currentServerId = "srv-host",
                currentServerName = "Host Co",
                currentServerSlug = "host-co",
                currentServerRole = "owner",
                servers =
                    listOf(
                        MemberConnectServer("srv-host", "Host Co", "host-co", null, "owner"),
                        MemberConnectServer("srv-partner", "Partner Co", "partner-co", null, "owner"),
                    ),
            )
        val dataSource =
            FakeJointConnectionsDataSource(connections).apply {
                memberConnectResult =
                    DataResult.Content(
                        MemberConnectResponse.Answered(listOf(ServerConnectOutcome.Connected("srv-partner"))),
                    )
            }
        var markedCount = 0
        val connectStore =
            JointChannelConnectionsStore(
                dataSource = dataSource,
                scope = CoroutineScope(Dispatchers.Main.immediate),
                onConnectionsChanged = { markedCount++ },
            )
        connectStore.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(account))
        connectStore.dispatch(JointChannelConnectionsIntent.SeedChannel(connections))
        connectStore.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(sayWhenEmpty = true))
        val inviteStore = ExternalInvitesStore(CoroutineScope(Dispatchers.Main.immediate), FakeExternalInvitesDataSource())

        setContent {
            val invState by inviteStore.state.collectAsState()
            val connState by connectStore.state.collectAsState()
            RaftDesignSystemTheme {
                ThreadInviteExternalActionDialogContent(
                    connectOnly = true,
                    serverName = "Host Co",
                    channelName = "launch",
                    willBecomePrivate = false,
                    joint = false,
                    externalInvitesState = invState,
                    onExternalInvitesIntent = inviteStore::dispatch,
                    connectState = connState,
                    onConnectIntent = connectStore::dispatch,
                    connectServersAvailable = true,
                    publicConnect = null,
                    onDismiss = {},
                    onUpgradeExternalLimit = {},
                    onUpgradeServer = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("Partner Co", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Connect", useUnmergedTree = true).performClick()
        waitForIdle()

        assertEquals(1, markedCount)
        assertEquals(listOf(listOf("srv-partner")), dataSource.memberConnectCalls)
    }

    @Test
    fun allEightLocalesMatchSlockInviteExternalAndEmptyConnectStrings() {
        val expected =
            mapOf(
                "en" to
                    listOf(
                        "Only a Server owner or admin can invite people outside the server.",
                        "Invite people",
                        "Connect servers",
                        "Decline",
                        "Handled by Ada",
                        "Declined by Ada",
                        "This card can’t be opened now. Reload to see where it stands.",
                        "Working…",
                        "You don't have a server that can connect to #launch. Servers you own or administer appear here.",
                    ),
                "zh-CN" to
                    listOf(
                        "只有服务器所有者或管理员可以邀请服务器以外的人。",
                        "邀请",
                        "连接服务器",
                        "拒绝",
                        "由 Ada 处理",
                        "由 Ada 拒绝",
                        "现在无法打开这张卡片，请刷新查看它的状态。",
                        "处理中…",
                        "你没有能连接到 #launch 的服务器。你拥有或管理的服务器会显示在这里。",
                    ),
                "zh-TW" to
                    listOf(
                        "只有伺服器擁有者或管理員可以邀請伺服器以外的人。",
                        "邀請成員",
                        "連結伺服器",
                        "拒絕",
                        "由 Ada 處理",
                        "由 Ada 拒絕",
                        "目前無法開啟這張卡片。請重新載入，查看它目前的狀態。",
                        "處理中…",
                        "你沒有可以連結到 #launch 的伺服器。你擁有或管理的伺服器會顯示在這裡。",
                    ),
                "ja" to
                    listOf(
                        "サーバー外の人を招待できるのは、サーバーのオーナーまたは管理者のみです。",
                        "招待",
                        "サーバーを接続",
                        "拒否",
                        "Ada が対応",
                        "Ada が拒否",
                        "このカードは現在開けません。再読み込みして状況を確認してください。",
                        "処理中…",
                        "#launch に接続できるサーバーがありません。あなたがオーナーまたは管理者のサーバーがここに表示されます。",
                    ),
                "ko" to
                    listOf(
                        "서버 소유자나 관리자만 서버 외부 사람을 초대할 수 있어요.",
                        "사람 초대",
                        "서버 연결",
                        "거절",
                        "Ada 님이 처리함",
                        "Ada 님이 거절함",
                        "지금은 이 카드를 열 수 없어요. 새로고침해서 현재 상태를 확인하세요.",
                        "처리 중…",
                        "#launch 채널에 연결할 수 있는 서버가 없어요. 내가 소유하거나 관리하는 서버가 여기에 표시돼요.",
                    ),
                "es" to
                    listOf(
                        "Solo un propietario o administrador del servidor puede invitar a personas de fuera del servidor.",
                        "Invitar personas",
                        "Conectar servidores",
                        "Rechazar",
                        "Gestionado por Ada",
                        "Rechazado por Ada",
                        "Esta tarjeta no se puede abrir ahora. Recarga para ver en qué estado está.",
                        "Procesando…",
                        "No tienes ningún servidor que pueda conectarse a #launch. Aquí aparecen los servidores en los que eres propietario o administrador.",
                    ),
                "pt-BR" to
                    listOf(
                        "Só um proprietário ou administrador do servidor pode convidar pessoas de fora do servidor.",
                        "Convidar pessoas",
                        "Conectar servidores",
                        "Recusar",
                        "Tratado por Ada",
                        "Recusado por Ada",
                        "Não é possível abrir este cartão agora. Recarregue a página para ver a situação dele.",
                        "Processando…",
                        "Você não tem um servidor que possa se conectar a #launch. Os servidores em que você é proprietário ou administrador aparecem aqui.",
                    ),
                "ru" to
                    listOf(
                        "Приглашать людей не с этого сервера может только владелец или администратор сервера.",
                        "Пригласить людей",
                        "Подключить серверы",
                        "Отклонить",
                        "Обработано: Ada",
                        "Отклонено: Ada",
                        "Сейчас не удаётся открыть эту карточку. Обновите страницу, чтобы узнать её статус.",
                        "Обработка…",
                        "У вас нет серверов, которые можно подключить к каналу #launch. Здесь появляются серверы, в которых вы владелец или администратор.",
                    ),
            )
        expected.forEach { (tag, strings) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(strings[0], Res.string.thread_action_card_blocked_need_federate_channels.desc().resolve(), "$tag needFederateChannels")
            assertEquals(strings[1], Res.string.thread_action_card_verb_invite_external.desc().resolve(), "$tag inviteExternal")
            assertEquals(strings[2], Res.string.thread_action_card_verb_connect_own_servers.desc().resolve(), "$tag connectOwnServers")
            assertEquals(strings[3], Res.string.thread_action_card_verb_decline_invite.desc().resolve(), "$tag declineInvite")
            assertEquals(strings[4], Res.string.thread_action_card_invite_external_handled_by.format("Ada").resolve(), "$tag handledBy")
            assertEquals(strings[5], Res.string.thread_action_card_invite_external_declined_by.format("Ada").resolve(), "$tag declinedBy")
            assertEquals(strings[6], Res.string.thread_action_card_invite_external_unavailable.desc().resolve(), "$tag unavailable")
            assertEquals(strings[7], Res.string.thread_action_card_busy_working.desc().resolve(), "$tag working")
            assertEquals(strings[8], Res.string.joint_member_connect_empty.format("#launch").resolve(), "$tag memberConnectEmpty")
        }
    }
}

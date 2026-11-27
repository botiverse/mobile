package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentProfileHeader
import build.raft.app.members.RaftProfilePreviewCardContent
import build.raft.app.members.raftProfilePreviewCardModel
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.flow.flowOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for `runningWorkers` presence (botiverse/slock #16819):
 * 8-locale catalog parity (keeping "Worker" untranslated and avoiding forbidden verbs),
 * agent profile hero chip + precedence, Working now panel row, and profile preview card
 * including cross-server presence-only suppression.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentRunningWorkersUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun allEightLocalesMatchWebCatalogAndKeepWorkerUntranslated() =
        runRobolectricComposeUiTest {
            val forbidden =
                Regex(
                    "process|running|busy|procesa|ejecuta|ocupad|processa|executa|処理|実行|ビジー|처리|실행|바쁨|处理|處理|執行|执行|运行|忙|обрабат|выполня|занят",
                    RegexOption.IGNORE_CASE,
                )
            val expectedByLocale =
                listOf(
                    AppLocale.English to Triple("Working through a Worker", "Working through 3 Workers", "Working through Workers"),
                    AppLocale.Spanish to Triple("Trabajando a través de un Worker", "Trabajando a través de 3 Workers", "Trabajando a través de Workers"),
                    AppLocale.Japanese to Triple("Worker を通じて作業中", "3 個の Worker を通じて作業中", "Worker を通じて作業中"),
                    AppLocale.Korean to Triple("Worker를 통해 작업 중", "Worker 3개를 통해 작업 중", "Worker를 통해 작업 중"),
                    AppLocale.BrazilianPortuguese to Triple("Trabalhando por meio de um Worker", "Trabalhando por meio de 3 Workers", "Trabalhando por meio de Workers"),
                    AppLocale.Russian to Triple("Работает через Worker", "Работает через Workers: 3", "Работает через Workers"),
                    AppLocale.SimplifiedChinese to Triple("正通过 Worker 工作", "正通过 3 个 Worker 工作", "正通过 Worker 工作"),
                    AppLocale.TraditionalChinese to Triple("正透過 Worker 工作", "正透過 3 個 Worker 工作", "正透過 Worker 工作"),
                )
            expectedByLocale.forEach { (locale, expected) ->
                StringDesc.localeType = locale.stringDescLocaleType
                val one = AgentRunningWorkersDisplay(count = 1).textSpec()
                val three = AgentRunningWorkersDisplay(count = 3).textSpec()
                val peer = AgentRunningWorkersDisplay(count = 1, presenceOnly = true).textSpec()
                val actual =
                    Triple(
                        one.res.desc().resolve(),
                        three.res.format(three.countArg!!).resolve(),
                        peer.res.desc().resolve(),
                    )
                listOf(actual.first, actual.second, actual.third).forEach { copy ->
                    assertTrue(copy.contains("Worker"), "$locale copy '$copy' must keep 'Worker' untranslated")
                    assertFalse(forbidden.containsMatchIn(copy), "$locale copy '$copy' must not use forbidden verbs")
                }
                kotlin.test.assertEquals(expected, actual, "locale $locale mismatch")
            }
            // Verify Compose localizedText() renders the exact strings in English.
            StringDesc.localeType = AppLocale.English.stringDescLocaleType
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        androidx.compose.material3.Text(AgentRunningWorkersDisplay(count = 1).localizedText())
                        androidx.compose.material3.Text(AgentRunningWorkersDisplay(count = 3).localizedText())
                        androidx.compose.material3.Text(AgentRunningWorkersDisplay(count = 1, presenceOnly = true).localizedText())
                    }
                }
            }
            onNodeWithText("Working through a Worker").assertExists()
            onNodeWithText("Working through 3 Workers").assertExists()
            onNodeWithText("Working through Workers").assertExists()
        }

    @Test
    fun profileHeroShowsRunningWorkersAndRespectsDeliveryHoldPrecedence() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val member = MemberProfile(id = MemberId("agent-1"), displayName = "Kirby", handle = "kirby", kind = MemberKind.Agent)
            val busyState =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    runtime = "claude",
                    external = false,
                    runningWorkers = 2,
                ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
            val heldState =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    runtime = "claude",
                    external = false,
                    deliveryHold =
                        AgentDeliveryHold(
                            reason = AgentDeliveryHoldReason.ProviderLimit,
                            providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                            since = nowMs - 1_000L,
                        ),
                    runningWorkers = 2,
                ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
            val peerMember = member.copy(id = MemberId("agent-peer"), displayName = "Peer", inViewerServer = false)
            val peerState =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    external = false,
                    runningWorkers = 1,
                    runningWorkersPresenceOnly = true,
                ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
            val summary = AgentDetailSummary(id = "agent-1", name = "kirby", runtime = "claude", activity = "online", runningWorkers = 2)

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = member,
                            summary = summary,
                            canEdit = false,
                            agentStatusObservedState = busyState,
                            agentStatusIndicatorState = busyState,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            timeContext = clock,
                        )
                        MembersAgentProfileHeader(
                            member = member.copy(id = MemberId("agent-held"), displayName = "Held"),
                            summary = summary.copy(id = "agent-held", deliveryHold = heldState.deliveryHold),
                            canEdit = false,
                            agentStatusObservedState = heldState,
                            agentStatusIndicatorState = heldState,
                            onChangeAvatar = {},
                            showActivityDetail = false,
                            timeContext = clock,
                        )
                        MembersAgentProfileHeader(
                            member = peerMember,
                            summary = summary.copy(id = "agent-peer", runningWorkers = 1),
                            canEdit = false,
                            agentStatusObservedState = peerState,
                            agentStatusIndicatorState = peerState,
                            onChangeAvatar = {},
                            showActivityDetail = false,
                            timeContext = clock,
                        )
                    }
                }
            }
            onNodeWithText("Working through 2 Workers").assertExists()
            onNodeWithText("Can't respond").assertExists()
            onNodeWithText("Working through Workers").assertExists()
        }

    @Test
    fun workingNowPanelAndProfilePreviewRenderRunningWorkersCopy() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val ownItem =
                RaftLiveAgentActivityItem(
                    agentId = "agent-self",
                    name = "Niko",
                    avatarWireValue = null,
                    status = AgentStatus.Working,
                    startedAt = nowMs - 5_000L,
                )
            val workerItem =
                RaftLiveAgentActivityItem(
                    agentId = "agent-workers",
                    name = "Kirby",
                    avatarWireValue = null,
                    status = AgentStatus.Online,
                    startedAt = 0L,
                    runningWorkers = 2,
                )
            val localStatus =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    external = false,
                    runningWorkers = 1,
                ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
            val peerProfile =
                MemberProfile(
                    id = MemberId("agent-peer"),
                    displayName = "RemoteAgent",
                    handle = "remote_agent",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-2"),
                    inViewerServer = false,
                )
            val localProfile =
                MemberProfile(
                    id = MemberId("agent-local"),
                    displayName = "LocalAgent",
                    handle = "local_agent",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-1"),
                    inViewerServer = true,
                )
            val peerRawStatus =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    external = false,
                    runningWorkers = 3,
                ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
            val resolver: RaftAgentStatusResolver = { id ->
                when (id) {
                    "agent-workers" ->
                        flowOf(
                            SharedServerMemberStatus(
                                status = AgentStatus.Online,
                                presence = Presence.Active,
                                external = false,
                                runningWorkers = 2,
                            ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)
                        )
                    else -> flowOf(null)
                }
            }
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        RaftWorkingNowPanel(
                            items = listOf(ownItem, workerItem),
                            agentStatusResolver = resolver,
                            onOpenAgent = {},
                        )
                        RaftProfilePreviewCardContent(
                            raftProfilePreviewCardModel(
                                profile = localProfile,
                                status = localStatus,
                                viewerServerId = "server-1",
                            )
                        )
                        RaftProfilePreviewCardContent(
                            raftProfilePreviewCardModel(
                                profile = peerProfile,
                                status = peerRawStatus,
                                viewerServerId = "server-1",
                            )
                        )
                    }
                }
            }
            onNodeWithText("Niko").assertExists()
            onNodeWithText("Kirby").assertExists()
            onNodeWithText("Working through 2 Workers").assertExists()
            onNodeWithText("Working through a Worker").assertExists()
            onNodeWithText("Working through Workers").assertExists()
        }
}

package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentProviderLimitWindow
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent page's status for an unusable sign-in, plan or balance on a real composition (AGENTS.md
 * "Compose UI tests"), against web agentRuntimeErrorBanner.behavior.test.tsx "4b" (botiverse/slock
 * #13276, staging 853c6998b): the reason instead of Online / "Error: …", Retry for whoever may
 * restart, a plain Error for a viewer without the agent's details.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentCredentialHoldUiTest {
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

    private companion object {
        const val HeldDetail = "Codex CLI is not logged in on this computer. (Reported by another agent on the same sign-in.)"
        const val OwnAuthDetail = "Codex CLI is not logged in on this computer. Sign in, then retry starting this agent."
        const val PlanDetail =
            "Codex CLI reported that the provider plan for this agent does not include access to the configured model (gpt-x)."
    }

    private val agent = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)

    private fun live(
        status: AgentStatus,
        detail: String,
        detailKind: String,
        runtime: String = "codex",
        model: String? = "gpt-x",
        deliveryHold: AgentDeliveryHold? = null,
        nowMs: Long? = null,
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = status.presence,
            statusLabel = detail,
            detailKind = detailKind,
            runtime = runtime,
            model = model,
            external = false,
            deliveryHold = deliveryHold,
        ).toRaftAgentStatusIndicatorState(nowEpochMillis = nowMs)

    private fun ComposeUiTest.showHero(
        state: RaftAgentStatusIndicatorState,
        summary: AgentDetailSummary,
        onRetry: (() -> Unit)?,
        showActivityDetail: Boolean = true,
        timeContext: RaftTimeContext? = null,
    ) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentProfileHeader(
                        member = agent,
                        summary = summary,
                        canEdit = false,
                        agentStatusObservedState = state,
                        agentStatusIndicatorState = state,
                        onChangeAvatar = {},
                        showActivityDetail = showActivityDetail,
                        timeContext = timeContext,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    private fun summary(
        activity: String,
        detail: String,
        detailKind: String,
        runtime: String = "codex",
        model: String? = "gpt-x",
        hold: AgentCredentialHoldReason? = null,
        deliveryHold: AgentDeliveryHold? = null,
        errorMessage: String? = null,
        errorClass: String? = null,
        requiredAction: AgentCredentialHoldReason? = null,
    ) = AgentDetailSummary(
        id = "agent-1",
        name = "ruby",
        runtime = runtime,
        model = model,
        activity = activity,
        activityDetail = detail,
        activityDetailKind = detailKind,
        credentialHold = hold,
        deliveryHold = deliveryHold,
        lastRuntimeErrorMessage = errorMessage,
        lastRuntimeErrorClass = errorClass,
        lastRuntimeErrorRequiredAction = requiredAction,
    )

    @Test
    fun anOwnSignInOrPlanErrorNamesTheRuntimeOrTheModel() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Error, OwnAuthDetail, "runtime_error"),
                summary(
                    "error",
                    OwnAuthDetail,
                    "runtime_error",
                    errorMessage = OwnAuthDetail,
                    errorClass = "AuthError",
                    requiredAction = AgentCredentialHoldReason.UserReauth,
                ),
                onRetry = {},
            )
            onNodeWithText("Sign-in needed for Codex CLI").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun aViewerWithoutTheDetailsSeesAPlainErrorBesideRetry() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Online, HeldDetail, "idle"),
                summary("online", HeldDetail, "idle", hold = AgentCredentialHoldReason.UserReauth),
                onRetry = {},
                showActivityDetail = false,
            )
            onNodeWithText("Error").assertExists()
            onAllNodesWithText("Sign-in needed", substring = true).assertCountEquals(0)
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun deliveryHoldShowsCantRespondWithReasonWhileWorkingAndCompactWithoutDetails() =
        runRobolectricComposeUiTest {
            // 2026-11-23 09:00 UTC -> until 14:05 UTC (same day, 24h format)
            val nowMs = 1_795_424_400_000L
            val untilMs = 1_795_442_700_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val quota =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.ProviderLimit,
                    providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                    untilMs = untilMs,
                    since = nowMs - 60_000L,
                )
            showHero(
                state = live(AgentStatus.Working, "Running tests", "running_command", runtime = "claude", deliveryHold = quota, nowMs = nowMs),
                summary = summary("working", "Running tests", "running_command", runtime = "claude", deliveryHold = quota),
                onRetry = {},
                showActivityDetail = true,
                timeContext = clock,
            )
            onNodeWithText("Can't respond: Claude Code usage limit reached, until 14:05").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun deliveryHoldCompactBadgeShowsCantRespondWithReasonInSemanticsAndRetryForCredentialInvalid() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val signIn =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.CredentialInvalid,
                    credentialDetail = AgentCredentialHoldReason.UserReauth,
                    since = nowMs - 60_000L,
                )
            showHero(
                state = live(AgentStatus.Working, "Running tests", "running_command", runtime = "claude", deliveryHold = signIn, nowMs = nowMs),
                summary = summary("working", "Running tests", "running_command", runtime = "claude", deliveryHold = signIn),
                onRetry = {},
                showActivityDetail = false,
                timeContext = clock,
            )
            onNodeWithText("Can't respond").assertExists()
            onNodeWithContentDescription("Can't respond: Claude Code sign-in needs attention").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun deliveryHoldFormatsBillingWithRetry() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val billingHold =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.CredentialInvalid,
                    credentialDetail = AgentCredentialHoldReason.Billing,
                    since = nowMs - 60_000L,
                )
            showHero(
                state = live(AgentStatus.Online, "", "idle", runtime = "claude", deliveryHold = billingHold, nowMs = nowMs),
                summary = summary("online", "", "idle", runtime = "claude", deliveryHold = billingHold),
                onRetry = {},
                showActivityDetail = true,
                timeContext = clock,
            )
            onNodeWithText("Can't respond: Provider balance exhausted").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun deliveryHoldFormatsRateLimitWithoutRetry() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val untilMs = 1_795_442_700_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val rateLimit =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.ProviderLimit,
                    providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit,
                    untilMs = untilMs,
                    since = nowMs - 60_000L,
                )
            showHero(
                state = live(AgentStatus.Online, "", "idle", runtime = "claude", deliveryHold = rateLimit, nowMs = nowMs),
                summary = summary("online", "", "idle", runtime = "claude", deliveryHold = rateLimit),
                onRetry = {},
                showActivityDetail = true,
                timeContext = clock,
            )
            onNodeWithText("Can't respond: Claude Code rate limit, retrying 14:05").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun deliveryHoldClearsOnceUntilMsHasPassed() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val expired =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.ProviderLimit,
                    providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit,
                    untilMs = nowMs - 1_000L,
                    since = nowMs - 120_000L,
                )
            showHero(
                state = live(AgentStatus.Working, "Running tests", "running_command", runtime = "claude", deliveryHold = expired, nowMs = nowMs),
                summary = summary("working", "Running tests", "running_command", runtime = "claude", deliveryHold = expired),
                onRetry = {},
                showActivityDetail = true,
                timeContext = clock,
            )
            onAllNodesWithText("Can't respond", substring = true).assertCountEquals(0)
            onNodeWithText("Running tests").assertExists()
        }

    @Test
    fun deliveryHoldScopedProviderLimitNamesModelWindowAndReportedResetWithUntilMsFallback() =
        runRobolectricComposeUiTest {
            // 2026-11-23 09:00 UTC -> resets 19:00 UTC, untilMs 19:02 UTC (jitter past reset)
            val nowMs = 1_795_424_400_000L
            val resetMs = 1_795_460_400_000L
            val untilMs = resetMs + 120_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val fable =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.ProviderLimit,
                    providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                    untilMs = untilMs,
                    since = nowMs - 60_000L,
                    model = "Fable",
                    window = AgentProviderLimitWindow.SevenDay,
                    resetsAtMs = resetMs,
                )
            showHero(
                state = live(AgentStatus.Working, "Running tests", "running_command", runtime = "claude", model = "fable", deliveryHold = fable, nowMs = nowMs),
                summary = summary("working", "Running tests", "running_command", runtime = "claude", model = "fable", deliveryHold = fable),
                onRetry = {},
                showActivityDetail = true,
                timeContext = clock,
            )
            // Uses resetsAtMs (19:00), not untilMs (19:02)
            onNodeWithText("Can't respond: Claude Code Fable weekly limit, resets 19:00").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun deliveryHoldScopedProviderLimitFallsBackToUntilMsWhenResetsAtMsAbsentOrPassed() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val resetMs = 1_795_460_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val cases =
                listOf(
                    // Model's own window without window or resetsAtMs -> weekly + untilMs
                    AgentDeliveryHold(
                        reason = AgentDeliveryHoldReason.ProviderLimit,
                        providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                        untilMs = resetMs,
                        since = nowMs - 60_000L,
                        model = "Fable",
                        window = null,
                        resetsAtMs = null,
                    ) to "Can't respond: Claude Code Fable weekly limit, resets 19:00",
                    // Reported reset already passed while wait continues -> falls back to untilMs
                    AgentDeliveryHold(
                        reason = AgentDeliveryHoldReason.ProviderLimit,
                        providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                        untilMs = resetMs,
                        since = nowMs - 60_000L,
                        model = "Fable",
                        window = AgentProviderLimitWindow.SevenDay,
                        resetsAtMs = nowMs - 1L,
                    ) to "Can't respond: Claude Code Fable weekly limit, resets 19:00",
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        cases.forEach { (hold, _) ->
                            androidx.compose.material3.Text(
                                text =
                                    formatAgentDeliveryHoldText(
                                        hold = hold,
                                        runtime = "claude",
                                        model = "fable",
                                        timeContext = clock,
                                        nowMs = nowMs,
                                    )
                            )
                        }
                    }
                }
            }
            onAllNodesWithText("Can't respond: Claude Code Fable weekly limit, resets 19:00").assertCountEquals(2)
        }

    @Test
    fun deliveryHoldFormatsEveryWindowKindWithAndWithoutModelAndKeepsRateLimitWords() =
        runRobolectricComposeUiTest {
            val nowMs = 1_795_424_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            fun quotaHold(model: String? = null, window: AgentProviderLimitWindow? = null) =
                AgentDeliveryHold(
                    reason = AgentDeliveryHoldReason.ProviderLimit,
                    providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                    since = 1L,
                    model = model,
                    window = window,
                )
            val cases =
                listOf(
                    quotaHold(window = AgentProviderLimitWindow.FiveHour) to
                        "Can't respond: Claude Code 5-hour limit reached",
                    quotaHold(window = AgentProviderLimitWindow.SevenDay) to
                        "Can't respond: Claude Code weekly limit reached",
                    quotaHold(window = AgentProviderLimitWindow.SevenDayOpus) to
                        "Can't respond: Claude Code weekly Opus limit reached",
                    quotaHold(window = AgentProviderLimitWindow.SevenDaySonnet) to
                        "Can't respond: Claude Code weekly Sonnet limit reached",
                    quotaHold(window = AgentProviderLimitWindow.SevenDayOverageIncluded) to
                        "Can't respond: Claude Code weekly limit reached",
                    quotaHold(window = AgentProviderLimitWindow.Overage) to
                        "Can't respond: Claude Code extra usage limit reached",
                    quotaHold(model = "Fable", window = AgentProviderLimitWindow.FiveHour) to
                        "Can't respond: Claude Code Fable 5-hour limit reached",
                    quotaHold(model = "Fable", window = null) to
                        "Can't respond: Claude Code Fable weekly limit reached",
                    quotaHold(model = "Opus", window = AgentProviderLimitWindow.SevenDayOpus) to
                        "Can't respond: Claude Code Opus weekly limit reached",
                    quotaHold(model = "Sonnet", window = AgentProviderLimitWindow.SevenDaySonnet) to
                        "Can't respond: Claude Code Sonnet weekly limit reached",
                    quotaHold(model = "Fable", window = AgentProviderLimitWindow.SevenDayOverageIncluded) to
                        "Can't respond: Claude Code Fable weekly limit reached",
                    quotaHold(model = "Fable", window = AgentProviderLimitWindow.Overage) to
                        "Can't respond: Claude Code Fable extra usage limit reached",
                    AgentDeliveryHold(
                        reason = AgentDeliveryHoldReason.ProviderLimit,
                        providerLimitDetail = AgentProviderLimitHoldKind.TransientRateLimit,
                        since = 1L,
                        window = AgentProviderLimitWindow.FiveHour,
                    ) to "Can't respond: Claude Code rate limit, retrying soon",
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        cases.forEach { (hold, _) ->
                            androidx.compose.material3.Text(
                                text =
                                    formatAgentDeliveryHoldText(
                                        hold = hold,
                                        runtime = "claude",
                                        model = "fable",
                                        timeContext = clock,
                                        nowMs = nowMs,
                                    )
                            )
                        }
                    }
                }
            }
            cases.forEach { (_, expected) ->
                onAllNodesWithText(expected).assertCountEquals(
                    cases.count { it.second == expected }
                )
            }
        }

    @Test
    fun deliveryHoldScopedProviderLimitMatchesZhCnCatalogCopy() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
            val nowMs = 1_795_424_400_000L
            val resetMs = 1_795_460_400_000L
            val clock = RaftTimeContext(nowEpochMillis = nowMs, localOffsetMinutes = 0, timeFormat = RaftTimeFormat24Hour)
            val cases =
                listOf(
                    AgentDeliveryHold(
                        reason = AgentDeliveryHoldReason.ProviderLimit,
                        providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                        untilMs = resetMs + 120_000L,
                        since = 1L,
                        model = "Fable",
                        window = AgentProviderLimitWindow.SevenDay,
                        resetsAtMs = resetMs,
                    ) to "无法回复：Claude Code Fable 每周额度已用完，19:00 重置",
                    AgentDeliveryHold(
                        reason = AgentDeliveryHoldReason.ProviderLimit,
                        providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                        since = 1L,
                        window = AgentProviderLimitWindow.FiveHour,
                    ) to "无法回复：Claude Code 5 小时额度已用完",
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        cases.forEach { (hold, _) ->
                            androidx.compose.material3.Text(
                                text =
                                    formatAgentDeliveryHoldText(
                                        hold = hold,
                                        runtime = "claude",
                                        model = "fable",
                                        timeContext = clock,
                                        nowMs = nowMs,
                                    )
                            )
                        }
                    }
                }
            }
            cases.forEach { (_, expected) ->
                onNodeWithText(expected).assertExists()
            }
        }
}

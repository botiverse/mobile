package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.runtimeform.v2.ParsedRuntimeFormV2
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choice
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2OptionSourceKey
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#344` (`botiverse/slock` `#17086`,
 * `runtimeModelUpgradeHint.behavior.test.tsx`):
 * - Layer 1 (`unavailable_models`), Layer 2 (`suggested_version`), and `runtime_outdated` inline upgrade UX
 * - Non-owner 403 suppression (`canManage = false` skips `ops.view()` and renders owner guidance)
 * - Specific failure copy with retry (offline -> retry -> success) and non-retryable failure (`computer_upgrade_required`)
 * - Unified dropdown refresh UX: no header refresh button, localized unavailable model reasons,
 *   zh-CN `"这台 Computer 上不可用"` group heading, bottom `"Refresh list"` action, and `"Retrying…"` status
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h900dp")
class RuntimeFormV2ModelUpgradeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private class FakeUpgradeOps(
        var authView: RuntimeAuthView? =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                upgradableRuntimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex),
            ),
        var nextUpgrade: suspend (RuntimeAuthRuntime) -> RuntimeAuthCall<RuntimeUpgradeResult> = {
            RuntimeAuthCall.Done(RuntimeUpgradeResult.Upgraded(from = "2.1.283", to = "2.1.321"))
        },
    ) : RuntimeModelUpgradeOps {
        var viewCalls = 0
        var upgradeCalls = 0

        override suspend fun view(force: Boolean): RuntimeAuthView? {
            viewCalls += 1
            return authView
        }

        override suspend fun upgrade(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeUpgradeResult> {
            upgradeCalls += 1
            return nextUpgrade(runtime)
        }
    }

    private fun modelForm(
        runtimeId: String = "claude",
        sourceStatus: RuntimeFormV2SourceStatus? = null,
        unavailableOptions: List<RuntimeFormV2UnavailableChoice> = emptyList(),
    ): RuntimeFormV2Loaded =
        RuntimeFormV2Loaded(
            form =
                ParsedRuntimeFormV2(
                    runtimeId = runtimeId,
                    schemaVersion = "2",
                    fields =
                        listOf(
                            RuntimeFormV2Field(
                                key = "model",
                                kind = RuntimeFormV2FieldKind.Select,
                                required = false,
                                advanced = false,
                                label = "Model",
                                hint = "Generic model hint",
                                optionSourceId = "model",
                            )
                        ),
                    optionSources =
                        mapOf(
                            "model" to
                                RuntimeFormV2OptionSourceKey(
                                    sourceId = "model",
                                    kind = RuntimeFormV2SourceKind.Select,
                                    key = "model",
                                )
                        ),
                    blockingFieldKeys = emptyList(),
                ),
            sources =
                mapOf(
                    "model" to
                        RuntimeFormV2Source(
                            sourceId = "model",
                            kind = RuntimeFormV2SourceKind.Select,
                            pointer = "/properties/model",
                            options = listOf(RuntimeFormV2Choice("claude-sonnet-4-5", "Claude Sonnet 4.5")),
                            unavailableOptions = unavailableOptions,
                            status = sourceStatus,
                        )
                ),
        )

    @Test
    fun layer1InlineUpgradeRunsOnceRefreshesListAndShowsOutcome() = runRobolectricComposeUiTest {
        val upgradeGate = CompletableDeferred<RuntimeAuthCall<RuntimeUpgradeResult>>()
        val ops = FakeUpgradeOps(nextUpgrade = { upgradeGate.await() })
        var refreshing by mutableStateOf(emptySet<String>())
        val refreshedSources = mutableListOf<String>()
        val loaded =
            modelForm(
                runtimeId = "claude",
                unavailableOptions =
                    listOf(
                        RuntimeFormV2UnavailableChoice("claude-opus-4-6", "Claude Opus 4.6", reason = "Requires Claude Code 2.1.321+"),
                    ),
            )
        val target =
            RuntimeModelUpgradeTarget(
                serverId = "srv-1",
                machineId = "m-1",
                runtimeId = "claude",
                runtimeVersion = "2.1.283",
                canManage = true,
                ops = ops,
            )

        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    RuntimeFormV2Fields(
                        loaded = loaded,
                        values = mapOf("model" to JsonPrimitive("claude-sonnet-4-5")),
                        onChange = { _, _ -> },
                        errors = emptyMap(),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = false,
                        onAdvancedOpenChange = {},
                        enabled = true,
                        sourceRefresh =
                            RuntimeFormV2SourceRefresh(
                                refreshing = refreshing,
                                failed = emptySet(),
                                onRefresh = { id ->
                                    refreshedSources += id
                                    refreshing = refreshing + id
                                },
                            ),
                        upgradeTarget = target,
                    )
                }
            }
        }

        waitForIdle()
        // Generic hint is suppressed while upgrade hint is active, and header refresh icon is gone.
        onNodeWithText("Generic model hint").assertDoesNotExist()
        onNodeWithContentDescription("Refresh this list").assertDoesNotExist()
        onNodeWithText("1 model requires upgrading Claude Code. Upgrade").assertIsDisplayed()

        // Tap inline Upgrade link -> shows Upgrading... and replaces Upgrade link (preventing double-tap).
        onNodeWithTag("runtime-upgrade-action").assertIsDisplayed()
        onNodeWithText("Upgrade").performClick()
        waitForIdle()
        onNodeWithTag("runtime-upgrade-running").assertIsDisplayed()
        onNodeWithText("Upgrading…").assertIsDisplayed()
        onNodeWithText("Upgrade").assertDoesNotExist()
        assertEquals(1, ops.upgradeCalls)

        // Upgrade finishes -> automatically triggers onRefresh("model") once & shows Refreshing list...
        upgradeGate.complete(RuntimeAuthCall.Done(RuntimeUpgradeResult.Upgraded(from = "2.1.283", to = "2.1.321")))
        waitForIdle()
        assertEquals(listOf("model"), refreshedSources)
        onNodeWithTag("runtime-upgrade-refreshing").assertIsDisplayed()
        onNodeWithText("Refreshing list…").assertIsDisplayed()

        // Refresh finishes -> shows formatted upgrade outcome.
        refreshing = emptySet()
        waitForIdle()
        onNodeWithTag("runtime-upgrade-outcome").assertIsDisplayed()
        onNodeWithText("Upgraded Claude Code from 2.1.283 to 2.1.321.").assertIsDisplayed()
    }

    @Test
    fun zhCnSuggestedVersionOutdatedFallbackAndNonOwnerSkipAuthFetch() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.ChineseSimplified.stringDescLocaleType
        val nonOwnerOps = FakeUpgradeOps()
        val ownerOps = FakeUpgradeOps()
        var currentLoaded by mutableStateOf(modelForm(runtimeId = "codex"))
        var currentTarget by mutableStateOf(
            RuntimeModelUpgradeTarget(
                serverId = "srv-1",
                machineId = "m-1",
                runtimeId = "codex",
                runtimeVersion = "codex-cli 0.150.0",
                canManage = false,
                ops = nonOwnerOps,
            )
        )

        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    RuntimeFormV2Fields(
                        loaded = currentLoaded,
                        values = emptyMap(),
                        onChange = { _, _ -> },
                        errors = emptyMap(),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = false,
                        onAdvancedOpenChange = {},
                        enabled = true,
                        upgradeTarget = currentTarget,
                    )
                }
            }
        }

        waitForIdle()
        // Non-owner skips /runtime-auth completely and appends owner guidance without extra space after 。
        assertEquals(0, nonOwnerOps.viewCalls)
        onNodeWithTag("runtime-upgrade-no-permission").assertIsDisplayed()
        onNodeWithText("Codex CLI 版本较旧，可能缺少新模型。Computer 的所有者可以升级。").assertIsDisplayed()

        // Owner on Layer 2 suggested_version: no space after 。 before 升级.
        currentTarget = currentTarget.copy(canManage = true, ops = ownerOps)
        waitForIdle()
        onNodeWithTag("runtime-upgrade-action").assertIsDisplayed()
        onNodeWithText("Codex CLI 版本较旧，可能缺少新模型。升级").assertIsDisplayed()

        // State 2: runtime_outdated + fallback, zh-CN, single Upgrade action (no side-by-side Retry).
        currentLoaded =
            modelForm(
                runtimeId = "claude",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "runtime_outdated", unavailable = false, stale = false, retryable = true),
            )
        currentTarget =
            RuntimeModelUpgradeTarget(
                serverId = "srv-1",
                machineId = "m-1",
                runtimeId = "claude",
                runtimeVersion = "2.0.0",
                canManage = true,
                ops = ownerOps,
            )
        waitForIdle()
        onNodeWithText("Claude Code 版本太旧，列不出可选模型，先显示标准列表。升级").assertIsDisplayed()
    }

    @Test
    fun retryableOfflineFailureRetriesAndNonRetryableOmitsRetry() = runRobolectricComposeUiTest {
        var attempt = 0
        val ops =
            FakeUpgradeOps(
                nextUpgrade = {
                    attempt += 1
                    if (attempt == 1) {
                        RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = 409, code = "computer_offline"))
                    } else {
                        RuntimeAuthCall.Done(RuntimeUpgradeResult.Current(version = "2.1.321"))
                    }
                }
            )
        val loaded =
            modelForm(
                runtimeId = "claude",
                unavailableOptions =
                    listOf(RuntimeFormV2UnavailableChoice("m1", "M1", reason = "Update Claude Code to use this model")),
            )
        var target by mutableStateOf(
            RuntimeModelUpgradeTarget(
                serverId = "srv-1",
                machineId = "m-1",
                runtimeId = "claude",
                runtimeVersion = "2.1.200",
                canManage = true,
                ops = ops,
            )
        )

        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    RuntimeFormV2Fields(
                        loaded = loaded,
                        values = emptyMap(),
                        onChange = { _, _ -> },
                        errors = emptyMap(),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = false,
                        onAdvancedOpenChange = {},
                        enabled = true,
                        upgradeTarget = target,
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("runtime-upgrade-action").assertIsDisplayed()
        onNodeWithText("Upgrade").performClick()
        waitForIdle()
        onNodeWithText("Upgrade didn't finish: this Computer is offline. Retry").assertIsDisplayed()

        // Retry succeeds.
        onNodeWithTag("runtime-upgrade-outcome").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        waitForIdle()
        assertEquals(2, ops.upgradeCalls)
        onNodeWithText("Claude Code 2.1.321 is already the latest version.").assertIsDisplayed()

        // Non-retryable refusal (computer_upgrade_required) omits Retry.
        val oldComputerOps =
            FakeUpgradeOps(
                nextUpgrade = {
                    RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = 409, code = "computer_upgrade_required"))
                }
            )
        target = target.copy(machineId = "m-2", ops = oldComputerOps)
        waitForIdle()
        onNodeWithTag("runtime-upgrade-action").assertIsDisplayed()
        onNodeWithText("Upgrade").performClick()
        waitForIdle()
        onNodeWithText("Upgrade didn't finish: this Computer is too old; update the Computer first.").assertIsDisplayed()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun unknownCanManageSkipsAuthFetchWithoutFlashingNonOwnerGuidance() = runRobolectricComposeUiTest {
        val ops = FakeUpgradeOps()
        val loaded = modelForm(runtimeId = "codex")
        val target =
            RuntimeModelUpgradeTarget(
                serverId = "srv-1",
                machineId = "m-1",
                runtimeId = "codex",
                runtimeVersion = "0.150.0",
                canManage = null,
                ops = ops,
            )

        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    RuntimeFormV2Fields(
                        loaded = loaded,
                        values = emptyMap(),
                        onChange = { _, _ -> },
                        errors = emptyMap(),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = false,
                        onAdvancedOpenChange = {},
                        enabled = true,
                        upgradeTarget = target,
                    )
                }
            }
        }

        waitForIdle()
        assertEquals(0, ops.viewCalls)
        onNodeWithTag("runtime-upgrade-no-permission").assertDoesNotExist()
        onNodeWithText("Codex CLI is on an older version and may be missing new models. Update it on the Computer.").assertIsDisplayed()
    }

    @Test
    fun dropdownShowsLocalizedUnavailableReasonsBottomRefreshAndNonUpgradeRetryingState() = runRobolectricComposeUiTest {
        StringDesc.localeType = AppLocale.ChineseSimplified.stringDescLocaleType
        var refreshing by mutableStateOf(emptySet<String>())
        val refreshed = mutableListOf<String>()
        val loaded =
            modelForm(
                runtimeId = "claude",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "probe_timeout", unavailable = false, stale = false, retryable = true),
                unavailableOptions =
                    listOf(
                        RuntimeFormV2UnavailableChoice("m-generic", "Generic Model", reason = "Update Claude Code to use this model"),
                        RuntimeFormV2UnavailableChoice("m-ver", "Versioned Model", reason = "Requires Claude Code 2.1.321+"),
                        RuntimeFormV2UnavailableChoice("m-plan", "Plan Model", reason = "Requires Max plan"),
                    ),
            )

        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    RuntimeFormV2Fields(
                        loaded = loaded,
                        values = mapOf("model" to JsonPrimitive("claude-sonnet-4-5")),
                        onChange = { _, _ -> },
                        errors = emptyMap(),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = false,
                        onAdvancedOpenChange = {},
                        enabled = true,
                        sourceRefresh =
                            RuntimeFormV2SourceRefresh(
                                refreshing = refreshing,
                                failed = emptySet(),
                                onRefresh = { id ->
                                    refreshed += id
                                    refreshing = refreshing + id
                                },
                            ),
                    )
                }
            }
        }

        waitForIdle()
        // Open dropdown via trigger showing current selection.
        onNodeWithText("Claude Sonnet 4.5").performClick()
        waitForIdle()
        onNodeWithText("这台 Computer 上不可用").assertIsDisplayed()
        onNodeWithText("需要更新 Claude Code").assertIsDisplayed()
        onNodeWithText("需要 Claude Code 2.1.321 或更新版本").assertIsDisplayed()
        onNodeWithText("Requires Max plan").assertIsDisplayed()
        onNodeWithText("刷新列表").assertIsDisplayed()

        // Tap bottom Refresh list -> triggers onRefresh("model") and flips to 正在刷新列表… + 正在重试… status line.
        onNodeWithText("刷新列表").performClick()
        waitForIdle()
        assertEquals(listOf("model"), refreshed)
        onNodeWithText("正在刷新列表…").assertIsDisplayed()
        onNodeWithTag("runtime-form-v2-model-retrying").assertIsDisplayed()
    }
}

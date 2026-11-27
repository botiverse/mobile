package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantSettingChangesSource
import build.raft.app.assistant.LocalAssistantSettingChangesSource
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AssistantChangedSetting
import build.raft.app.data.AssistantOtterPrefsDataSource
import build.raft.app.data.AssistantOwnerSetting
import build.raft.app.data.AssistantSettingChange
import build.raft.app.data.AssistantSettingValue
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MyAssistantAnswer
import build.raft.app.data.MyAssistantCreateResult
import build.raft.app.data.MyAssistantDeleteResult
import build.raft.app.data.MyAssistantFetchResult
import build.raft.app.data.MyAssistantOwnerNotesSaveResult
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantSettingSaveResult
import build.raft.app.data.PERSONAL_ASSISTANT_FEATURE_FLAG_KEY
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftAssistantSettingChangesApi
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.RaftComposerSendKeyRuntime
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.RaftMyAssistantApi
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SharedAssistantOtterPrefsStore
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.settings.LocalSettingsAppearancePreviewTimeLabel
import build.raft.app.settings.LocalSettingsAppearanceVisualSwitches
import build.raft.app.settings.SettingsAppearanceAssistantPanelLinkTag
import build.raft.app.settings.SettingsAppearanceAssistantSectionTag
import build.raft.app.settings.SettingsAppearanceAssistantSource
import build.raft.app.settings.SettingsAppearanceContent
import build.raft.app.settings.SettingsAppearanceVisualSwitches
import build.raft.app.settings.settingsAppPreferenceVisibility
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings › Appearance › Assistant (web `AssistantOtterSettingsSection` parity, 1.29.0):
 * - Hidden when `personal_assistant` is off, viewer is a guest, viewer has no assistant, or in
 *   screenshot visual fixtures (`LocalSettingsAppearanceVisualSwitches`).
 * - Shown when viewer has an assistant: PawPrint section header, "Show {name} on every page"
 *   switch wired to account-level `otterPrefs` (with error + trace ID on save failure), "More
 *   {name} settings" link invoking `onOpenAssistantSettings(agentId)`, and "Changed by your
 *   assistant" (`ASSISTANT_ACTION_SETTINGS`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1600dp-xhdpi")
class SettingsAppearanceAssistantUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        storeScope.cancel()
        RaftComposerSendKeyRuntime.resetForTest(null)
    }

    @Test
    fun hiddenWhenUserHasNoAssistant() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val flags = featureFlags(enabled = true)
            val api = FakeMyAssistantApi(MyAssistantFetchResult.Answer(MyAssistantAnswer(enabled = true, assistant = null)))
            val store = SharedMyAssistantStore(api = api, scope = storeScope, otterPrefs = otterPrefsStore())

            setContent {
                AppearanceHost(
                    source =
                        SettingsAppearanceAssistantSource(
                            store = store,
                            featureFlags = flags,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            serverRole = "member",
                            onOpenAssistantSettings = {},
                        ),
                )
            }
            waitForIdle()

            assertEquals(1, api.fetchCalls)
            onNodeWithTag(SettingsAppearanceAssistantSectionTag).assertDoesNotExist()
            onNodeWithText("Assistant").assertDoesNotExist()
            onNodeWithTag(SettingsAppearanceAssistantPanelLinkTag).assertDoesNotExist()
        }

    @Test
    fun hiddenWhenPersonalAssistantFlagIsOff() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val flags = featureFlags(enabled = false)
            val api = FakeMyAssistantApi(MyAssistantFetchResult.Answer(MyAssistantAnswer(enabled = true, assistant = OTTIE)))
            val store = SharedMyAssistantStore(api = api, scope = storeScope, otterPrefs = otterPrefsStore())

            setContent {
                AppearanceHost(
                    source =
                        SettingsAppearanceAssistantSource(
                            store = store,
                            featureFlags = flags,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            serverRole = "member",
                            onOpenAssistantSettings = {},
                        ),
                )
            }
            waitForIdle()

            assertEquals(0, api.fetchCalls)
            onNodeWithTag(SettingsAppearanceAssistantSectionTag).assertDoesNotExist()
            onNodeWithText("Show Ottie on every page").assertDoesNotExist()
        }

    @Test
    fun hiddenForGuestViewer() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val flags = featureFlags(enabled = true)
            val api = FakeMyAssistantApi(MyAssistantFetchResult.Answer(MyAssistantAnswer(enabled = true, assistant = OTTIE)))
            val store = SharedMyAssistantStore(api = api, scope = storeScope, otterPrefs = otterPrefsStore())

            setContent {
                AppearanceHost(
                    source =
                        SettingsAppearanceAssistantSource(
                            store = store,
                            featureFlags = flags,
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            serverRole = "guest",
                            onOpenAssistantSettings = {},
                        ),
                )
            }
            waitForIdle()

            assertEquals(0, api.fetchCalls)
            onNodeWithTag(SettingsAppearanceAssistantSectionTag).assertDoesNotExist()
            onNodeWithText("Show Ottie on every page").assertDoesNotExist()
        }

    @Test
    fun hiddenInVisualScreenshotFixture() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val flags = featureFlags(enabled = true)
            val api = FakeMyAssistantApi(MyAssistantFetchResult.Answer(MyAssistantAnswer(enabled = true, assistant = OTTIE)))
            val store = SharedMyAssistantStore(api = api, scope = storeScope, otterPrefs = otterPrefsStore())

            setContent {
                CompositionLocalProvider(
                    LocalSettingsAppearanceVisualSwitches provides
                        SettingsAppearanceVisualSwitches(
                            showLiveAgentActivity = true,
                            showAgentModelName = true,
                            showSenderDescription = true,
                            hideEmptySidebarSections = false,
                            showAppOnlyRows = false,
                        ),
                ) {
                    AppearanceHost(
                        source =
                            SettingsAppearanceAssistantSource(
                                store = store,
                                featureFlags = flags,
                                serverId = SERVER_ID,
                                userId = USER_ID,
                                serverRole = "member",
                                onOpenAssistantSettings = {},
                            ),
                    )
                }
            }
            waitForIdle()

            assertEquals(0, api.fetchCalls)
            onNodeWithTag(SettingsAppearanceAssistantSectionTag).assertDoesNotExist()
        }

    @Test
    fun visibleWithAssistantTogglesOtterPrefsShowsSaveFailureOpensProfileAndShowsChanges() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val flags = featureFlags(enabled = true)
            val otterSource = FakeOtterPrefsSource(initialVisible = true)
            val otterStore = SharedAssistantOtterPrefsStore(otterSource, storeScope)
            val api = FakeMyAssistantApi(MyAssistantFetchResult.Answer(MyAssistantAnswer(enabled = true, assistant = OTTIE)))
            val store = SharedMyAssistantStore(api = api, scope = storeScope, otterPrefs = otterStore)
            val openedAgentIds = mutableListOf<String>()
            val changesApi =
                object : RaftAssistantSettingChangesApi {
                    override suspend fun fetch(serverId: ServerId): List<AssistantSettingChange> =
                        listOf(
                            AssistantSettingChange(
                                id = "c-move",
                                assistantName = "Ottie",
                                setting = AssistantChangedSetting.AgentMove,
                                serverReported = true,
                                serverId = SERVER_ID,
                                targetRef = "@sage",
                                previousValue = AssistantSettingValue.Text("MacBook"),
                                nextValue = AssistantSettingValue.Text("Studio Mac mini"),
                                createdAt = "2026-11-17T09:14:00.000Z",
                            ),
                            AssistantSettingChange(
                                id = "c-theme",
                                assistantName = "Ottie",
                                setting = AssistantChangedSetting.Theme,
                                serverReported = true,
                                serverId = SERVER_ID,
                                previousValue = AssistantSettingValue.Text("raft"),
                                nextValue = AssistantSettingValue.Text("space"),
                                createdAt = "2026-11-17T09:10:00.000Z",
                            ),
                        )
                }

            setContent {
                CompositionLocalProvider(
                    LocalAssistantSettingChangesSource provides AssistantSettingChangesSource(SERVER_ID, changesApi),
                ) {
                    AppearanceHost(
                        source =
                            SettingsAppearanceAssistantSource(
                                store = store,
                                featureFlags = flags,
                                serverId = SERVER_ID,
                                userId = USER_ID,
                                serverRole = "member",
                                onOpenAssistantSettings = { openedAgentIds += it },
                            ),
                    )
                }
            }
            waitForIdle()

            onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SettingsAppearanceAssistantSectionTag))
            waitForIdle()

            // Section header and both rows with full Settings descriptions.
            onNodeWithTag(SettingsAppearanceAssistantSectionTag).assertExists()
            onNodeWithText("Assistant").assertExists()
            onNodeWithText("Show Ottie on every page").assertExists()
            onNodeWithText(
                "Ottie rests on your message box, ready to open with a press. Turn this off to hide it on every page and every device. Ottie's DM still works.",
            ).assertExists()
            onNodeWithTag("assistant-otter-visible").assertIsOn()

            // Toggle off -> persists via SharedAssistantOtterPrefsStore.
            onNodeWithTag("assistant-otter-visible").performClick()
            waitForIdle()
            assertEquals(listOf(false), otterSource.saves)
            onNodeWithTag("assistant-otter-visible").assertIsOff()

            // Next toggle fails -> reverts switch and shows failure message + trace ID.
            otterSource.nextSaveResult =
                DataResult.Error(ScreenError("assistant_otter_prefs_http_500", retryable = true, traceId = FAILURE_TRACE_ID))
            onNodeWithTag("assistant-otter-visible").performClick()
            waitForIdle()
            assertEquals(listOf(false, true), otterSource.saves)
            onNodeWithTag("assistant-otter-visible").assertIsOff()
            onNodeWithText("Couldn't save this change. Try again.").assertExists()
            onNodeWithTag(RaftTraceIdNoteTestTag).assertExists()
            onNodeWithText(raftShortTraceId(FAILURE_TRACE_ID)).assertExists()

            // "More Ottie settings" opens the assistant's profile.
            onNodeWithText("More Ottie settings").assertExists()
            onNodeWithText("Permissions, extra instructions and WeChat are in Ottie's profile, next to its chat.").assertExists()
            onNodeWithTag(SettingsAppearanceAssistantPanelLinkTag).performClick()
            waitForIdle()
            assertEquals(listOf(OTTIE.agentId), openedAgentIds)

            // "Changed by your assistant" renders ASSISTANT_ACTION_SETTINGS only (AgentMove, not Theme).
            onNodeWithTag("assistant-setting-changes").assertExists()
            onNodeWithText("Changed by your assistant").assertExists()
            onNodeWithText("Ottie moved @sage to Studio Mac mini", substring = true).assertExists()
            onNodeWithText("space", substring = true).assertDoesNotExist()
        }

    @androidx.compose.runtime.Composable
    private fun AppearanceHost(source: SettingsAppearanceAssistantSource) {
        val phoneVisibility =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = false,
                supportsMultiShellHost = false,
                supportsIosNativeNav = false,
                largeScreenPossible = false,
                hardwareKeyboardConnected = false,
                isiOSAppOnMac = false,
            )
        CompositionLocalProvider(LocalSettingsAppearancePreviewTimeLabel provides "20:30") {
            RaftDesignSystemTheme {
                SettingsAppearanceContent(
                    state = ScreenLoadState.Content(SettingsAppearanceSnapshot(composerSendKey = RaftComposerSendKey.Enter)),
                    actionState = null,
                    fontSizeDraft = "md",
                    bottomContentPadding = 16.dp,
                    onFontSizeDraftChange = {},
                    onSave = {},
                    onRetry = {},
                    showModeCard = false,
                    preferences = null,
                    preferenceVisibility = phoneVisibility,
                    assistantSource = source,
                )
            }
        }
    }

    private fun featureFlags(enabled: Boolean): SharedServerFeatureFlags =
        SharedServerFeatureFlags(
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText = """{"evaluations":[{"key":"$PERSONAL_ASSISTANT_FEATURE_FLAG_KEY","enabled":$enabled,"reason":"server_rule"}]}""",
                    )
            },
        ).also { flags -> runBlocking { flags.prefetch(ServerId(SERVER_ID)) } }

    private fun otterPrefsStore(initialVisible: Boolean = true): SharedAssistantOtterPrefsStore =
        SharedAssistantOtterPrefsStore(FakeOtterPrefsSource(initialVisible), storeScope)

    private class FakeOtterPrefsSource(private val initialVisible: Boolean) : AssistantOtterPrefsDataSource {
        val saves = mutableListOf<Boolean>()
        var nextSaveResult: DataResult<Boolean>? = null

        override suspend fun readVisible(): DataResult<Boolean> = DataResult.Content(initialVisible)

        override suspend fun saveVisible(visible: Boolean): DataResult<Boolean> {
            saves += visible
            return nextSaveResult ?: DataResult.Content(visible)
        }
    }

    private class FakeMyAssistantApi(private val fetchResult: MyAssistantFetchResult) : RaftMyAssistantApi {
        var fetchCalls = 0

        override suspend fun fetch(serverId: ServerId): MyAssistantFetchResult {
            fetchCalls += 1
            return fetchResult
        }

        override suspend fun create(serverId: ServerId): MyAssistantCreateResult = MyAssistantCreateResult.Failed(500)

        override suspend fun delete(serverId: ServerId): MyAssistantDeleteResult = MyAssistantDeleteResult.Failed(500)

        override suspend fun updateOwnerNotes(serverId: ServerId, notes: String): MyAssistantOwnerNotesSaveResult =
            MyAssistantOwnerNotesSaveResult.Failed(500)

        override suspend fun updateSetting(
            serverId: ServerId,
            setting: AssistantOwnerSetting,
            value: Boolean,
        ): MyAssistantSettingSaveResult = MyAssistantSettingSaveResult.Failed(500)
    }

    private companion object {
        const val SERVER_ID = "server-1"
        const val USER_ID = "user-1"
        const val FAILURE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        val OTTIE =
            MyAssistantRecord(
                id = "assistant-ottie",
                name = "Ottie",
                agentId = "agent-ottie",
                dmChannelId = "dm-ottie",
            )
    }
}

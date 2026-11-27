package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.agents.AgentVoiceSection
import build.raft.app.agents.AgentVoiceSectionScope
import build.raft.app.agents.LocalAgentVoiceSectionScope
import build.raft.app.agents.MembersAgentVoiceSectionContent
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AGENT_VOICE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentVoiceQuestion
import build.raft.app.data.AgentVoiceQuestionApp
import build.raft.app.data.AgentVoiceQuestionAsker
import build.raft.app.data.AgentVoiceSettings
import build.raft.app.data.AgentVoiceSettingsDataSource
import build.raft.app.data.AppLocale
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class MembersAgentVoiceSectionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private val fixedTimeContext = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = 1_795_680_000_000L)

    @BeforeTest
    fun setup() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private fun flagsFor(enabled: Boolean): SharedServerFeatureFlags =
        SharedServerFeatureFlags(
            api =
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        HomeNetworkResponse(code = 200, bodyText = """{"evaluations":[]}""")
                },
            diagnosticOverride = { _, key, _ -> enabled && key == AGENT_VOICE_FEATURE_FLAG_KEY },
        )

    @Test
    fun rendersSwitchesAndCapsWhoAskedListAtTenEntriesWithFormerMemberFallback() =
        runRobolectricComposeUiTest {
            val questions =
                buildList {
                    add(
                        AgentVoiceQuestion(
                            id = "q-0",
                            at = "2026-11-26T10:15:00.000Z",
                            asker = AgentVoiceQuestionAsker(id = "u-0", name = "Alice"),
                            app = AgentVoiceQuestionApp(id = "app-0", name = "Companion"),
                        ),
                    )
                    add(
                        AgentVoiceQuestion(
                            id = "q-1",
                            at = "2026-11-26T09:00:00.000Z",
                            asker = null,
                            app = null,
                        ),
                    )
                    for (i in 2 until 12) {
                        add(
                            AgentVoiceQuestion(
                                id = "q-$i",
                                at = "2026-11-25T08:00:00.000Z",
                                asker = AgentVoiceQuestionAsker(id = "u-$i", name = "User $i"),
                                app = null,
                            ),
                        )
                    }
                }
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MembersAgentVoiceSectionContent(
                            settings = AgentVoiceSettings(askable = true, callable = false),
                            questions = questions,
                            onToggleField = { _, _ -> },
                            timeContext = fixedTimeContext,
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-section").assertIsDisplayed()
            onNodeWithText("Voice").assertIsDisplayed()
            onNodeWithText("Can be asked").assertIsDisplayed()
            onNodeWithText("Can be called").assertIsDisplayed()
            onNodeWithTag("agent-voice-askable").assertIsOn()
            onNodeWithTag("agent-voice-callable").assertIsOff()
            onNodeWithTag("agent-voice-questions").assertIsDisplayed()
            onNodeWithText("Who asked").assertIsDisplayed()
            onNodeWithText("Alice").assertIsDisplayed()
            onNodeWithText("Companion · Nov 26, 10:15").assertIsDisplayed()
            onNodeWithText("Former member").assertIsDisplayed()
            onNodeWithTag("agent-voice-question-row-9").assertExists()
            onNodeWithTag("agent-voice-question-row-10").assertDoesNotExist()
        }

    @Test
    fun togglesSaveOptimisticallyAndRollBackWithErrorOnFailure() =
        runRobolectricComposeUiTest {
            val api =
                FakeVoiceSettingsDataSource(
                    initialSettings = DataResult.Content(AgentVoiceSettings(askable = false, callable = false)),
                    initialQuestions = DataResult.Content(emptyList()),
                )
            api.failAskable = true

            val scope =
                AgentVoiceSectionScope(
                    api = api,
                    featureFlags = flagsFor(enabled = true),
                    serverId = "srv-1",
                    canManage = true,
                    timeContext = fixedTimeContext,
                )
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalAgentVoiceSectionScope provides scope) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AgentVoiceSection(agentId = "agent-1") { content -> content() }
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("agent-voice-section").assertIsDisplayed()
            onNodeWithText("No one has asked this agent a question yet.").assertIsDisplayed()
            onNodeWithTag("agent-voice-askable").assertIsOff()
            onNodeWithTag("agent-voice-callable").assertIsOff()

            // Failing askable update rolls switch back and shows error copy
            onNodeWithTag("agent-voice-askable").performClick()
            waitForIdle()
            onNodeWithTag("agent-voice-askable").assertIsOff()
            onNodeWithText("Couldn't save. Try again.").assertIsDisplayed()

            // Succeeding callable update persists ON state
            onNodeWithTag("agent-voice-callable").performClick()
            waitForIdle()
            onNodeWithTag("agent-voice-callable").assertIsOn()
        }

    @Test
    fun hidesSectionWhenSettingsEndpointReturnsNotFound() =
        runRobolectricComposeUiTest {
            val api =
                FakeVoiceSettingsDataSource(
                    initialSettings = DataResult.Content(null),
                    initialQuestions = DataResult.Content(null),
                )
            val scope =
                AgentVoiceSectionScope(
                    api = api,
                    featureFlags = flagsFor(enabled = true),
                    serverId = "srv-1",
                    canManage = true,
                    timeContext = fixedTimeContext,
                )
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalAgentVoiceSectionScope provides scope) {
                        Column(Modifier.fillMaxSize()) {
                            AgentVoiceSection(agentId = "agent-1") { content -> content() }
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("agent-voice-section").assertDoesNotExist()
        }

    private class FakeVoiceSettingsDataSource(
        private val initialSettings: DataResult<AgentVoiceSettings?>,
        private val initialQuestions: DataResult<List<AgentVoiceQuestion>?>,
    ) : AgentVoiceSettingsDataSource {
        var failAskable: Boolean = false
        private var current: AgentVoiceSettings =
            (initialSettings as? DataResult.Content)?.data ?: AgentVoiceSettings(askable = false, callable = false)

        override suspend fun readSettings(agentId: String, serverId: String?): DataResult<AgentVoiceSettings?> =
            initialSettings

        override suspend fun updateSettings(
            agentId: String,
            askable: Boolean?,
            callable: Boolean?,
            serverId: String?,
        ): DataResult<AgentVoiceSettings> {
            if (askable != null && failAskable) {
                return DataResult.Error(ScreenError("http_500", traceId = "4bf92f3577b34da6a3ce929d0e0e4736"))
            }
            current =
                AgentVoiceSettings(
                    askable = askable ?: current.askable,
                    callable = callable ?: current.callable,
                )
            return DataResult.Content(current)
        }

        override suspend fun readQuestions(agentId: String, serverId: String?): DataResult<List<AgentVoiceQuestion>?> =
            initialQuestions
    }
}

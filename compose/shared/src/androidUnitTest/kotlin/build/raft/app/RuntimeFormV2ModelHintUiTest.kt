package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.runtimeform.v2.ParsedRuntimeFormV2
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choice
import build.raft.app.data.runtimeform.v2.RuntimeFormV2ErrorCode
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2OptionSourceKey
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceKind
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test for Antigravity Orchestrator model hint (botiverse/slock #16311):
 * selecting an Orchestrator model on Antigravity replaces the generic model hint with the
 * slower mid-task reply hint; validation errors still take precedence.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h900dp")
class RuntimeFormV2ModelHintUiTest {
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

    private fun modelLoaded(runtimeId: String): RuntimeFormV2Loaded =
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
                                optionSourceId = "models",
                            )
                        ),
                    optionSources =
                        mapOf(
                            "models" to
                                RuntimeFormV2OptionSourceKey(
                                    sourceId = "models",
                                    kind = RuntimeFormV2SourceKind.Select,
                                    key = "model",
                                )
                        ),
                    blockingFieldKeys = emptyList(),
                ),
            sources =
                mapOf(
                    "models" to
                        RuntimeFormV2Source(
                            sourceId = "models",
                            kind = RuntimeFormV2SourceKind.Select,
                            pointer = "/properties/model",
                            options =
                                listOf(
                                    RuntimeFormV2Choice("gemini-pro-orchestrator-neon", "Orchestrator (Neon)"),
                                    RuntimeFormV2Choice("gemini-3.1-pro-high", "Gemini 3.1 Pro High"),
                                ),
                        )
                ),
        )

    @Test
    fun antigravityOrchestratorReplacesGenericHintAndErrorStillWins() =
        runRobolectricComposeUiTest {
            val orchestratorHint =
                "Mid-task messages hand back to the planner model before replying, so replies while it is working can be slower than a single model."
            var runtimeId by mutableStateOf("antigravity")
            var modelId by mutableStateOf("gemini-pro-orchestrator-neon")
            var errors by mutableStateOf<Map<String, RuntimeFormV2ErrorCode>>(emptyMap())

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        RuntimeFormV2Fields(
                            loaded = modelLoaded(runtimeId),
                            values = mapOf("model" to JsonPrimitive(modelId)),
                            onChange = { _, _ -> },
                            errors = errors,
                            serverErrorKeys = emptySet(),
                            serverMessage = null,
                            editing = false,
                            advancedOpen = false,
                            onAdvancedOpenChange = {},
                            enabled = true,
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText(orchestratorHint).assertIsDisplayed()
            onNodeWithText("Generic model hint").assertDoesNotExist()

            // Validation error takes precedence over the Orchestrator hint
            errors = mapOf("model" to RuntimeFormV2ErrorCode.Required)
            waitForIdle()
            onNodeWithText(orchestratorHint).assertDoesNotExist()

            // Single Antigravity model restores generic hint
            errors = emptyMap()
            modelId = "gemini-3.1-pro-high"
            waitForIdle()
            onNodeWithText("Generic model hint").assertIsDisplayed()
            onNodeWithText(orchestratorHint).assertDoesNotExist()

            // Non-Antigravity runtime with Orchestrator id keeps generic hint
            runtimeId = "claude"
            modelId = "gemini-pro-orchestrator-neon"
            waitForIdle()
            onNodeWithText("Generic model hint").assertIsDisplayed()
            onNodeWithText(orchestratorHint).assertDoesNotExist()
        }
}

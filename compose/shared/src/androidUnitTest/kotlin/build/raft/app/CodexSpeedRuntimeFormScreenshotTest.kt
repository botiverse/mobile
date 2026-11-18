package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.runtimeform.v2.CODEX_V2_FALLBACK_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V3_FORM_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V3_REASONING_EFFORT_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V3_SPEED_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Source
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `agent.create.runtime-form-v3.codex-fast-mode`: the create-agent runtime section as
 * SharedCreateAgentDialog draws it (runtime picker, then [RuntimeFormV2Fields]) on the Codex
 * protocol v3 create form (slock #15382 fixtures codex.form.v3 and codex.option-source.speed.v3),
 * with GPT-6.2 Astra picked and Fast mode on: the Speed picker is shown (rule SHOW while `fastMode`
 * is `const: true`) at the model's primary tier. The model list is the bundled fallback source (the
 * Codex model fixture that lists GPT-6.2 Astra), so its fallback status line is drawn too.
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CodexSpeedRuntimeFormScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun fastModeOnLight() =
        RaftRobolectricScreenshot.capture(
            "agent.create.runtime-form-v3.codex-fast-mode",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val loaded =
                remember {
                    RuntimeFormV2Loaded(
                        form = checkNotNull(parseRuntimeFormV2(CODEX_V3_FORM_JSON)),
                        sources =
                            mapOf(
                                "model" to CODEX_V2_FALLBACK_SOURCE_JSON,
                                "reasoningEffort" to CODEX_V3_REASONING_EFFORT_SOURCE_JSON,
                                "speed" to CODEX_V3_SPEED_SOURCE_JSON
                            ).mapValues { (_, json) -> checkNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json))) }
                    )
                }
            var values by remember {
                val initial = initialRuntimeFormV2Values(loaded.form, loaded.sources)
                val astra = applyRuntimeFormV2Change(loaded.form, loaded.sources, initial, "model", JsonPrimitive("gpt-6.2-astra"))
                mutableStateOf(applyRuntimeFormV2Change(loaded.form, loaded.sources, astra, "fastMode", JsonPrimitive(true)))
            }
            var advancedOpen by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                        RuntimeFormV2RuntimeSection(
                            runtime = "codex",
                            onRuntimeChange = {},
                            machineRuntimeIds = listOf("claude", "codex"),
                            runtimeAvailabilityError = null,
                            enabled = true
                        ) {
                            RuntimeFormV2Fields(
                                loaded = loaded,
                                values = values,
                                onChange = { key, value -> values = applyRuntimeFormV2Change(loaded.form, loaded.sources, values, key, value) },
                                errors = emptyMap(),
                                serverErrorKeys = emptySet(),
                                serverMessage = null,
                                editing = false,
                                advancedOpen = advancedOpen,
                                onAdvancedOpenChange = { advancedOpen = it },
                                enabled = true
                            )
                        }
                    }
                }
            }
        }
}

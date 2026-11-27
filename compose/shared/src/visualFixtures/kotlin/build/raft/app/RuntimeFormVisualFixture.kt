package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import build.raft.app.thread.key
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runtime form visual cases (slock packages/runtime-form/fixtures/visual-cases.v3.json): the
 * generated kitchen sink pages (every field kind in every state) and each runtime's real v3 create
 * form, drawn by the production [RuntimeFormV2Fields] straight from the shared fixtures
 * (RuntimeFormVisualFixtures.g.kt, synced by scripts/runtime_form/sync_v2_fixtures.py), no API.
 * The React side (slock packages/web/visual-testing/RuntimeFormCases.tsx) renders the same
 * files the same way, so a capture difference is a renderer difference.
 *
 * pageData `caseId`: an id from visual-cases.v3.json. A case with a `state` file sets its
 * `values` over the form's initial values, shows its `retryingSourceIds` as being re-requested
 * and, with `showValidationErrors`, every validation error (web `showValidationErrors`). A case
 * with a `picker` is drawn closed here too; the capture opens it ([runtimeFormVisualPicker]).
 */
@Page("compose_runtime_form_visual", supportInLocal = true)
internal class ComposeRuntimeFormVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val case = runtimeFormVisualCase(pageData.params.optString("caseId"))
        setContent { RuntimeFormVisualContent(case) }
    }
}

private data class RuntimeFormVisualCase(
    val loaded: RuntimeFormV2Loaded,
    val values: RuntimeFormV2Values,
    val retryingSourceIds: Set<String>,
    val showValidationErrors: Boolean,
    val editing: Boolean
)

private val runtimeFormVisualJson = Json { ignoreUnknownKeys = true }

private fun runtimeFormVisualFixture(name: String): String =
    RUNTIME_FORM_VISUAL_FIXTURES[name] ?: error("runtime form visual fixture $name is not synced (run scripts/runtime_form/sync_v2_fixtures.py)")

private fun runtimeFormVisualEntry(caseId: String): JsonObject {
    val cases = runtimeFormVisualJson.parseToJsonElement(runtimeFormVisualFixture("visual-cases.v3.json")).jsonObject["cases"]!!.jsonArray
    return cases.map { it.jsonObject }.firstOrNull { it["caseId"]?.jsonPrimitive?.contentOrNull == caseId }
        ?: error("unknown runtime form visual case: $caseId")
}

/**
 * A picker case's choice field (visual-cases.v3.json `picker`): its key, the label its trigger is
 * announced with ("Provider, Required, DeepSeek"), and the text typed into the open list's search
 * (null: the list is captured unfiltered).
 */
data class RuntimeFormVisualPicker(val fieldKey: String, val fieldLabel: String, val query: String?)

/**
 * The `picker` of runtime form visual case [caseId], or null for a case that captures the form at
 * rest. The page draws the form closed; the emulator capture (KmpVisualScreenshotCaptureTest)
 * opens the field with a tap and types the query into the sheet's search row, as web's capture
 * (slock react-provider.spec.ts openRuntimeFormPicker) clicks it and types into the combobox.
 */
fun runtimeFormVisualPicker(caseId: String): RuntimeFormVisualPicker? {
    val entry = runtimeFormVisualEntry(caseId)
    val picker = entry["picker"] as? JsonObject ?: return null
    val fieldKey = picker["field"]!!.jsonPrimitive.content
    val formFile = entry["form"]!!.jsonPrimitive.content
    val form = parseRuntimeFormV2(runtimeFormVisualFixture(formFile)) ?: error("$formFile is not a runtime form")
    val field = form.fields.firstOrNull { it.key == fieldKey } ?: error("$caseId: $formFile has no field $fieldKey")
    return RuntimeFormVisualPicker(fieldKey, field.label, (picker["query"] as? JsonPrimitive)?.contentOrNull)
}

private fun runtimeFormVisualCase(caseId: String): RuntimeFormVisualCase {
    val entry = runtimeFormVisualEntry(caseId)
    val formFile = entry["form"]!!.jsonPrimitive.content
    val form = parseRuntimeFormV2(runtimeFormVisualFixture(formFile)) ?: error("$formFile is not a runtime form")
    val sources =
        entry["sources"]!!.jsonObject.mapValues { (sourceId, file) ->
            parseRuntimeFormV2Source(runtimeFormVisualJson.parseToJsonElement(runtimeFormVisualFixture(file.jsonPrimitive.content)))
                ?: error("${file.jsonPrimitive.content} is not an option source for $sourceId")
        }
    val state =
        entry["state"]?.jsonPrimitive?.contentOrNull?.let {
            runtimeFormVisualJson.parseToJsonElement(runtimeFormVisualFixture(it)).jsonObject
        }
    val overrides: Map<String, JsonElement> = (state?.get("values") as? JsonObject).orEmpty()
    return RuntimeFormVisualCase(
        loaded = RuntimeFormV2Loaded(form, sources),
        values = initialRuntimeFormV2Values(form, sources) + overrides,
        retryingSourceIds = (state?.get("retryingSourceIds") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
        showValidationErrors = (state?.get("showValidationErrors") as? JsonPrimitive)?.booleanOrNull == true,
        editing = (state?.get("editing") as? JsonPrimitive)?.booleanOrNull == true
    )
}

@Composable
private fun RuntimeFormVisualContent(case: RuntimeFormVisualCase) {
    var values by remember(case) { mutableStateOf(case.values) }
    var advancedOpen by remember(case) { mutableStateOf(false) }
    val errors = validateRuntimeFormV2(case.loaded.form, case.loaded.sources, values, editing = case.editing)
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            RuntimeFormV2Fields(
                loaded = case.loaded,
                values = values,
                onChange = { key, value -> values = applyRuntimeFormV2Change(case.loaded.form, case.loaded.sources, values, key, value) },
                // Web shows every error with showValidationErrors, else only those shown before a submit attempt.
                errors = if (case.showValidationErrors) errors else runtimeFormV2ShownErrors(errors, emptySet()),
                serverErrorKeys = emptySet(),
                serverMessage = null,
                editing = case.editing,
                advancedOpen = advancedOpen,
                onAdvancedOpenChange = { advancedOpen = it },
                enabled = true,
                sourceRefresh = RuntimeFormV2SourceRefresh(refreshing = case.retryingSourceIds, failed = emptySet(), onRefresh = {})
            )
        }
    }
}

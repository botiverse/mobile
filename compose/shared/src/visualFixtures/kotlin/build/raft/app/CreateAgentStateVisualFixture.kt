package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.getDefaultModel

/**
 * Create Agent dialog states paired with the React CREATE_AGENT_CASES in
 * packages/web/visual-testing/VisualTestingCases.tsx (slock). `variant` only
 * selects the fixture; every value is typed here, never parsed from a product
 * route. All variants keep the shared fixture's Free-plan 2/2 capacity seed so
 * they stay comparable with components.members.create-agent.dialog.
 *
 *  - `error`            dialog-error: invalid prefilled name -> inline name error
 *  - `onboarding`       dialog-onboarding: React renders its onboarding step; the
 *                       shared dialog has no onboarding layout, so this captures the
 *                       standard form with the same seed (the gap stays visible)
 *  - `empty`            dialog-empty: empty description; the harness clears the
 *                       name field because a blank initial name is replaced by a
 *                       random suggestion
 *  - `no-computer`      dialog-no-computer: zero machines
 *  - `builtin-provider` builtin-provider-dialog: runtime builtin
 *  - `pi-provider`      pi-provider-dialog: runtime pi
 */
@Page("compose_create_agent_state_visual", supportInLocal = true)
internal class ComposeCreateAgentStateVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val spec = createAgentStateVisualSpec(pageData.params.optString("variant"))
        setContent { CreateAgentStateVisualContent(spec) }
    }
}

private data class CreateAgentStateVisualSpec(
    val runtime: String,
    val machineRuntimes: List<String>,
    val name: String = CREATE_AGENT_STATE_VISUAL_NAME,
    val description: String = CREATE_AGENT_STATE_VISUAL_DESCRIPTION,
    val noMachines: Boolean = false
)

private const val CREATE_AGENT_STATE_VISUAL_NAME = "Product-QA-Bot"
private const val CREATE_AGENT_STATE_VISUAL_DESCRIPTION =
    "Watches release screenshots and reports visual regressions before merge."

private fun createAgentStateVisualSpec(variant: String): CreateAgentStateVisualSpec =
    when (variant) {
        "error" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"), name = "bad name!!")
        "onboarding" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"))
        "empty" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"), description = "")
        "no-computer" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"), noMachines = true)
        "builtin-provider" -> CreateAgentStateVisualSpec("builtin", listOf("builtin", "claude", "codex"))
        "pi-provider" -> CreateAgentStateVisualSpec("pi", listOf("pi", "claude", "codex"))
        else -> error("unknown create-agent visual variant: $variant")
    }

@androidx.compose.runtime.Composable
private fun CreateAgentStateVisualContent(spec: CreateAgentStateVisualSpec) {
    val primary = VisualFixtureData.Machines.Primary
    val studio = VisualFixtureData.Machines.Studio
    val runtimeModels =
        RUNTIME_MODELS[spec.runtime].orEmpty().map { model ->
            SharedCreateAgentRuntimeModelOption(
                id = model.id,
                label = model.label,
                verified = model.verified,
                supportedReasoningEfforts = model.supportedReasoningEfforts,
                defaultReasoningEffort = model.defaultReasoningEffort
            )
        }
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = null,
            initialName = spec.name,
            initialDescription = spec.description,
            initialRuntime = spec.runtime,
            initialModel = getDefaultModel(spec.runtime),
            machines =
                if (spec.noMachines) {
                    emptyList()
                } else {
                    listOf(
                        SharedCreateAgentMachineOption(
                            id = primary.ID,
                            name = primary.NAME,
                            status = primary.STATUS,
                            runtimes = spec.machineRuntimes
                        ),
                        SharedCreateAgentMachineOption(
                            id = studio.ID,
                            name = studio.NAME,
                            status = studio.STATUS,
                            runtimes = studio.RUNTIMES
                        )
                    )
                },
            runtimeModelsByMachineAndRuntime =
                if (spec.noMachines) {
                    emptyMap()
                } else {
                    mapOf(
                        (primary.ID to spec.runtime) to
                            SharedCreateAgentRuntimeModelCatalog(
                                models = runtimeModels,
                                defaultModel = getDefaultModel(spec.runtime)
                            )
                    )
                },
            builtInFormOverride = if (spec.runtime == "builtin") createAgentBuiltInVisualForm() else null,
            currentAgentCount = 2,
            maxAgents = 2,
            limitPlanLabel = "Free",
            onDismiss = {},
            onSubmit = {}
        )
    }
}

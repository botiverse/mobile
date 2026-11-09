package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page

/**
 * Create Agent dialog states paired with the React CREATE_AGENT_CASES in
 * packages/web/visual-testing/VisualTestingCases.tsx (slock). `variant` only
 * selects the fixture; every value is typed here, never parsed from a product
 * route. All variants keep the shared fixture's Free-plan 2/2 capacity seed so
 * they stay comparable with components.members.create-agent.dialog.
 *
 *  - `error`            dialog-error: invalid prefilled name -> inline name error
 *  - `empty`            dialog-empty: empty description; the harness clears the
 *                       name field because a blank initial name is replaced by a
 *                       random suggestion
 *  - `no-computer`      dialog-no-computer: zero machines (loaded, no error) -> the short
 *                       centred "Connect a computer first" dialog with Cancel + Add Computer
 *
 * builtin-provider-dialog and pi-provider-dialog are React-only in slock's case list (no Android
 * capture), so they have no variant here.
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
        "empty" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"), description = "")
        "no-computer" -> CreateAgentStateVisualSpec("codex", listOf("codex", "claude"), noMachines = true)
        else -> error("unknown create-agent visual variant: $variant")
    }

@androidx.compose.runtime.Composable
private fun CreateAgentStateVisualContent(spec: CreateAgentStateVisualSpec) {
    val primary = VisualFixtureData.Machines.Primary
    val studio = VisualFixtureData.Machines.Studio
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = null,
            initialName = spec.name,
            initialDescription = spec.description,
            initialRuntime = spec.runtime,
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
            currentAgentCount = 2,
            maxAgents = 2,
            limitPlanLabel = "Free",
            // React's fixture offers Add Computer; a non-null callback shows the button.
            onAddComputer = {},
            onDismiss = {},
            onSubmit = {}
        )
    }
}

package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.base.BasePager
import build.raft.app.data.CREATE_AGENT_KIMI_SDK_RUNTIME_ID
import build.raft.app.data.SharedAgentCreateFormCapabilities
import build.raft.app.data.SharedAgentCreateFormDefinition
import build.raft.app.data.SharedAgentCreateFormOption
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedAgentCreateFormOptionSourceRef
import build.raft.app.data.SharedAgentCreateDataSchema
import build.raft.app.data.SharedAgentCreateUiSchema
import build.raft.app.data.SharedResolvedAgentCreateForm
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.Page

/**
 * task #275 visual evidence pair for the Kimi reasoning-effort selector.
 * - compose_kimi_create_capability_visual: live catalog capable (non-empty efforts, valid
 *   default) + exact form ref -> the selector MUST render.
 * - compose_kimi_create_no_capability_visual: no form + empty catalog -> selector hidden
 *   (today's served shape; fail-closed).
 */
@Page("compose_kimi_create_capability_visual", supportInLocal = true)
internal class ComposeKimiCreateCapabilityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { KimiCreateCapabilityVisualContent(capable = true) }
    }
}

@Page("compose_kimi_create_no_capability_visual", supportInLocal = true)
internal class ComposeKimiCreateNoCapabilityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { KimiCreateCapabilityVisualContent(capable = false) }
    }
}

private val kimiVisualFormRef =
    SharedRuntimeFormDefinitionRef(
        protocolVersion = 1,
        runtimeId = "kimi-sdk",
        schemaVersion = "kimi-sdk.create.v1"
    )

private val kimiVisualResolvedForm =
    SharedResolvedAgentCreateForm(
        definition =
            SharedAgentCreateFormDefinition(
                ref = kimiVisualFormRef,
                dataSchema =
                    SharedAgentCreateDataSchema(
                        required = setOf("model"),
                        properties = emptyMap()
                    ),
                uiSchema =
                    SharedAgentCreateUiSchema(
                        order = listOf("model", "reasoningEffort"),
                        advancedPointers = emptySet(),
                        visibility = emptyList(),
                        localization = emptyMap()
                    ),
                capabilities =
                    SharedAgentCreateFormCapabilities(
                        providerKinds = emptySet(),
                        writeOnlyPointers = emptySet(),
                        forbiddenPointers = setOf("/hostUserState")
                    ),
                optionSources =
                    mapOf(
                        "model" to
                            SharedAgentCreateFormOptionSourceRef.Select(
                                ref = kimiVisualFormRef,
                                sourceId = "model",
                                pointer = "/model"
                            )
                    )
            ),
        optionSources =
            mapOf(
                "model" to
                    SharedAgentCreateFormOptionSource.Select(
                        ref = kimiVisualFormRef,
                        sourceId = "model",
                        pointer = "/model",
                        options =
                            listOf(
                                SharedAgentCreateFormOption(
                                    value = "kimi-k2",
                                    label = "Kimi K2",
                                    providerKind = null
                                )
                            ),
                        defaultValue = "kimi-k2"
                    )
            )
    )

@Composable
private fun KimiCreateCapabilityVisualContent(capable: Boolean) {
    val catalog =
        if (capable) {
            SharedCreateAgentRuntimeModelCatalog(
                models =
                    listOf(
                        SharedCreateAgentRuntimeModelOption(
                            id = "kimi-k2",
                            label = "Kimi K2",
                            supportedReasoningEfforts = listOf("low", "medium", "high"),
                            defaultReasoningEffort = "medium"
                        )
                    ),
                defaultModel = "kimi-k2"
            )
        } else {
            SharedCreateAgentRuntimeModelCatalog(
                models = listOf(SharedCreateAgentRuntimeModelOption(id = "kimi-k2", label = "Kimi K2")),
                defaultModel = "kimi-k2"
            )
        }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = null,
            initialName = "Kimi-Bot",
            initialDescription = "",
            initialRuntime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
            initialModel = "kimi-k2",
            initialAdvancedOpen = true,
            machines =
                listOf(
                    SharedCreateAgentMachineOption(
                        id = "computer-mbp",
                        name = "Jiachengs-MacBook-Pro",
                        status = "online",
                        runtimes = listOf(CREATE_AGENT_KIMI_SDK_RUNTIME_ID)
                    )
                ),
            runtimeModelsByMachineAndRuntime =
                mapOf(("computer-mbp" to CREATE_AGENT_KIMI_SDK_RUNTIME_ID) to catalog),
            kimiSdkForm = if (capable) kimiVisualResolvedForm else null,
            onDismiss = {},
            onSubmit = {}
        )
    }
}

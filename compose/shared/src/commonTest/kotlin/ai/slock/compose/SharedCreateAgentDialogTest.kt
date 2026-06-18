package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedCreateAgentDialogTest {
    @Test
    fun modelDropdownKeepsCustomAsLastOption() {
        val items = createAgentModelDropdownItems("claude")

        assertTrue(items.size > 1)
        assertEquals(CREATE_AGENT_CUSTOM_MODEL_ID, items.last().id)
        assertEquals("Custom...", items.last().label)
        assertTrue(items.last().custom)
        assertFalse(items.dropLast(1).any { it.custom })
    }

    @Test
    fun modelDropdownUsesRuntimeOverrideBeforeCustomOption() {
        val items =
            createAgentModelDropdownItems(
                runtime = "claude",
                runtimeModelsOverride =
                    listOf(
                        SharedCreateAgentRuntimeModelOption("server-sonnet", "Server Sonnet"),
                        SharedCreateAgentRuntimeModelOption("server-opus", "Server Opus")
                    )
            )

        assertEquals(
            listOf("server-sonnet", "server-opus", CREATE_AGENT_CUSTOM_MODEL_ID),
            items.map { it.id }
        )
    }

    @Test
    fun unknownModelValueRehydratesAsCustomDraft() {
        val (customMode, modelValue) =
            createAgentModelSelectionForValue(runtime = "claude", model = "vendor/private-model")

        assertTrue(customMode)
        assertEquals("vendor/private-model", modelValue)
    }

    @Test
    fun blankModelValueFallsBackToRuntimeDefaultPreset() {
        val (customMode, modelValue) =
            createAgentModelSelectionForValue(runtime = "claude", model = "   ")

        assertFalse(customMode)
        assertEquals("opus", modelValue)
    }
}

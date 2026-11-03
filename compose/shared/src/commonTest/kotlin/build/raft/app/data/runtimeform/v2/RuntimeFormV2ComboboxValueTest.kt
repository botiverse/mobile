package build.raft.app.data.runtimeform.v2

import build.raft.app.runtimeFormV2ComboboxValue
import build.raft.designsystem.components.RaftComboboxOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A select stored as "" shows its "" option (e.g. reasoning "Default") as the selection, as v1.19.0 did;
 * a select without a "" option still shows its placeholder while nothing is chosen.
 */
class RuntimeFormV2ComboboxValueTest {
    private val reasoning = listOf(
        RaftComboboxOption("", "Default"),
        RaftComboboxOption("low", "Low"),
        RaftComboboxOption("high", "High")
    )
    private val models = listOf(RaftComboboxOption("sonnet", "Claude Sonnet"), RaftComboboxOption("opus", "Claude Opus"))

    @Test
    fun emptyValueSelectsTheServersEmptyOption() {
        assertEquals("", runtimeFormV2ComboboxValue("", reasoning))
    }

    @Test
    fun emptyValueWithoutAnEmptyOptionShowsThePlaceholder() {
        assertNull(runtimeFormV2ComboboxValue("", models))
    }

    @Test
    fun aChosenValueIsKept() {
        assertEquals("high", runtimeFormV2ComboboxValue("high", reasoning))
        assertEquals("opus", runtimeFormV2ComboboxValue("opus", models))
    }

    @Test
    fun theAllowEmptyDefaultRowStillCounts() {
        val withDefault = listOf(RaftComboboxOption("", "Default")) + models
        assertEquals("", runtimeFormV2ComboboxValue("", withDefault))
    }
}

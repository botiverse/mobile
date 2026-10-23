package build.raft.designsystem

import build.raft.designsystem.components.RaftChoicePickerRadioMax
import build.raft.designsystem.components.RaftChoicePickerVariant
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.designsystem.components.raftChoicePickerVariant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The picker rule by option count (Jasper, #proj-mobile): radios, a sheet list, a searchable sheet. */
class RaftChoicePickerTest {
    @Test
    fun variantFollowsTheOptionCountAtEachBoundary() {
        assertEquals(5, RaftChoicePickerRadioMax)
        assertEquals(10, RaftComboboxSheetThreshold)
        val expected =
            mapOf(
                0 to RaftChoicePickerVariant.RadioGroup,
                1 to RaftChoicePickerVariant.RadioGroup,
                5 to RaftChoicePickerVariant.RadioGroup,
                6 to RaftChoicePickerVariant.SheetList,
                10 to RaftChoicePickerVariant.SheetList,
                11 to RaftChoicePickerVariant.SearchSheet,
                40 to RaftChoicePickerVariant.SearchSheet,
            )
        expected.forEach { (count, variant) -> assertEquals(variant, raftChoicePickerVariant(count), "options=$count") }
    }
}

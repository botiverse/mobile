package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextFieldValueLengthTest {
    @Test
    fun underLimitAndAtLimitReturnOriginalSnapshot() {
        val underLimit =
            TextFieldValue(
                text = "draft",
                selection = TextRange(4, 1),
                composition = TextRange(1, 4)
            )
        val atLimit = TextFieldValue(text = "12345", selection = TextRange(2))

        assertSame(underLimit, underLimit.coerceTextLength(6))
        assertSame(atLimit, atLimit.coerceTextLength(5))
    }

    @Test
    fun cumulativeDeleteAndRetypeSnapshotsRemainExact() {
        val nativeValues =
            listOf(
                TextFieldValue(text = "a", selection = TextRange(1)),
                TextFieldValue(text = "ab", selection = TextRange(2)),
                TextFieldValue(text = "abc", selection = TextRange(3)),
                TextFieldValue(text = "ab", selection = TextRange(2)),
                TextFieldValue(text = "abx", selection = TextRange(3))
            )

        val coerced = nativeValues.map { it.coerceTextLength(3) }

        assertEquals(listOf("a", "ab", "abc", "ab", "abx"), coerced.map { it.text })
        nativeValues.zip(coerced).forEach { (native, result) -> assertSame(native, result) }
    }

    @Test
    fun oversizeActiveCjkCompositionIsNotMutated() {
        val composing =
            TextFieldValue(
                text = "拼音输入中",
                selection = TextRange(5),
                composition = TextRange(0, 5)
            )

        val result = composing.coerceTextLength(3)

        assertSame(composing, result)
        assertEquals(TextRange(5), result.selection)
        assertEquals(TextRange(0, 5), result.composition)
    }

    @Test
    fun committedOversizeClampsMidTextAndReversedSelections() {
        val midText =
            TextFieldValue(
                text = "abcdef",
                selection = TextRange(1, 3)
            )
        val reversed =
            TextFieldValue(
                text = "abcdef",
                selection = TextRange(6, 2)
            )

        val midTextResult = midText.coerceTextLength(4)
        val reversedResult = reversed.coerceTextLength(4)

        assertNotSame(midText, midTextResult)
        assertEquals("abcd", midTextResult.text)
        assertEquals(TextRange(1, 3), midTextResult.selection)
        assertNull(midTextResult.composition)
        assertEquals("abcd", reversedResult.text)
        assertEquals(TextRange(4, 2), reversedResult.selection)
        assertTrue(reversedResult.selection.reversed)
        assertNull(reversedResult.composition)
    }

    @Test
    fun createAgentAndAgreementLimitsRetainExistingThresholds() {
        val createAgent =
            TextFieldValue(
                text = "a".repeat(CREATE_AGENT_DESCRIPTION_MAX_LENGTH + 1),
                selection = TextRange(CREATE_AGENT_DESCRIPTION_MAX_LENGTH + 1)
            ).coerceTextLength(CREATE_AGENT_DESCRIPTION_MAX_LENGTH)
        val agreementTitle =
            TextFieldValue(
                text = "t".repeat(PRE_JOIN_AGREEMENT_TITLE_INPUT_MAX_LENGTH + 1),
                selection = TextRange(PRE_JOIN_AGREEMENT_TITLE_INPUT_MAX_LENGTH + 1)
            ).coerceTextLength(PRE_JOIN_AGREEMENT_TITLE_INPUT_MAX_LENGTH)

        assertEquals(CREATE_AGENT_DESCRIPTION_MAX_LENGTH, createAgent.text.length)
        assertEquals(TextRange(CREATE_AGENT_DESCRIPTION_MAX_LENGTH), createAgent.selection)
        assertEquals(PRE_JOIN_AGREEMENT_TITLE_INPUT_MAX_LENGTH, agreementTitle.text.length)
        assertEquals(TextRange(PRE_JOIN_AGREEMENT_TITLE_INPUT_MAX_LENGTH), agreementTitle.selection)
        assertTrue(agreementTitle.text.length > PRE_JOIN_AGREEMENT_TITLE_VALIDATION_MAX_LENGTH)
    }

    @Test
    fun externalReplacementStartsAtEndAndNegativeLimitFailsClosed() {
        val replacement = textFieldValueAtEnd("server copy")

        assertEquals(TextRange(replacement.text.length), replacement.selection)
        assertNull(replacement.composition)
        assertFailsWith<IllegalArgumentException> { replacement.coerceTextLength(-1) }
    }
}

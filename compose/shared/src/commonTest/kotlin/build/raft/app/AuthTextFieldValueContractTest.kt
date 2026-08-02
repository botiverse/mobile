package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AuthTextFieldValueContractTest {
    @Test
    fun authWrapperForwardsEveryNativeSnapshotWithoutReconstruction() {
        val nativeValues =
            listOf(
                TextFieldValue("n", selection = TextRange(1), composition = TextRange(0, 1)),
                TextFieldValue("ni", selection = TextRange(2), composition = TextRange(0, 2)),
                TextFieldValue("你", selection = TextRange(1)),
                TextFieldValue("", selection = TextRange.Zero),
                TextFieldValue("new", selection = TextRange(1, 3)),
            )
        val forwarded = mutableListOf<TextFieldValue>()

        nativeValues.forEach { value ->
            authTextFieldForwardValueChange(value, forwarded::add)
        }

        assertEquals(listOf("n", "ni", "你", "", "new"), forwarded.map(TextFieldValue::text))
        nativeValues.zip(forwarded).forEach { (native, delivered) -> assertSame(native, delivered) }
        assertEquals(TextRange(0, 2), forwarded[1].composition)
        assertEquals(TextRange(1, 3), forwarded.last().selection)
    }

    @Test
    fun callerOwnerInstallsExactValueBeforePlainTextProjection() {
        val events = mutableListOf<String>()
        var owned: TextFieldValue? = null
        val next = TextFieldValue("拼音", selection = TextRange(1), composition = TextRange(0, 2))

        applyAuthTextFieldValue(
            nextValue = next,
            updateOwner = {
                owned = it
                events += "owner"
            },
            projectText = {
                assertSame(next, owned)
                assertEquals("拼音", it)
                events += "projection"
            },
        )

        assertSame(next, owned)
        assertEquals(listOf("owner", "projection"), events)
    }

    @Test
    fun slugNormalizationDoesNotTouchActiveComposition() {
        val composing =
            TextFieldValue(
                text = "My 工作",
                selection = TextRange(5),
                composition = TextRange(3, 5),
            )

        assertSame(composing, authCreateServerSlugFieldValue(composing))
    }

    @Test
    fun alreadyNormalizedSlugKeepsExactNativeSnapshot() {
        val native = TextFieldValue("already-fine", selection = TextRange(4))

        assertSame(native, authCreateServerSlugFieldValue(native))
    }

    @Test
    fun slugNormalizationMapsCommittedSelectionThroughTransformedPrefix() {
        val committed = TextFieldValue("A B!C", selection = TextRange(2, 5))

        val normalized = authCreateServerSlugFieldValue(committed)

        assertEquals("a-b-c", normalized.text)
        assertEquals(TextRange(2, 5), normalized.selection)
        assertEquals(null, normalized.composition)
    }
}

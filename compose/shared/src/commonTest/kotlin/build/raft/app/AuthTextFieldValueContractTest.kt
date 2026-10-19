package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AuthTextFieldValueContractTest {
    @Test
    fun authKeyboardViewportConvertsNativeDpHeightToRootPixels() {
        assertEquals(
            Rect(10f, 100f, 310f, 700f),
            slockAuthKeyboardReducedViewport(
                viewport = Rect(10f, 100f, 310f, 1000f),
                keyboardHeightDp = 300f,
                density = 1f
            )
        )
        assertEquals(
            Rect(10f, 100f, 310f, 400f),
            slockAuthKeyboardReducedViewport(
                viewport = Rect(10f, 100f, 310f, 1000f),
                keyboardHeightDp = 300f,
                density = 2f
            )
        )
    }

    @Test
    fun authKeyboardViewportNeverExtendsAboveItsTopOrOnClosedKeyboard() {
        val viewport = Rect(10f, 100f, 310f, 200f)

        assertEquals(
            viewport,
            slockAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 0f,
                density = 2f
            )
        )
        assertEquals(
            Rect(10f, 100f, 310f, 100f),
            slockAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 300f,
                density = 2f
            )
        )
    }

    @Test
    fun authKeyboardViewportAddsBottomSafeAreaWhenNativeInsetExcludesIt() {
        assertEquals(
            Rect(10f, 100f, 310f, 352f),
            slockAuthKeyboardReducedViewport(
                viewport = Rect(10f, 100f, 310f, 1000f),
                keyboardHeightDp = 300f,
                density = 2f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true
            )
        )
    }

    @Test
    fun authKeyboardViewportDoesNotDoubleCountIncludedSafeAreaOrClosedKeyboard() {
        val viewport = Rect(10f, 100f, 310f, 1000f)

        assertEquals(
            Rect(10f, 100f, 310f, 400f),
            slockAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 300f,
                density = 2f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = false
            )
        )
        assertEquals(
            viewport,
            slockAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 0f,
                density = 2f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true
            )
        )
    }

    @Test
    fun effectiveKeyboardInsetClampsNegativeInputsAndSafeArea() {
        assertEquals(0.dp, slockEffectiveKeyboardInsetDp(-1f, (-24).dp, true))
        assertEquals(300.dp, slockEffectiveKeyboardInsetDp(300f, (-24).dp, true))
        assertEquals(324.dp, slockEffectiveKeyboardInsetDp(300f, 24.dp, true))
        assertEquals(300.dp, slockEffectiveKeyboardInsetDp(300f, 24.dp, false))
    }

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

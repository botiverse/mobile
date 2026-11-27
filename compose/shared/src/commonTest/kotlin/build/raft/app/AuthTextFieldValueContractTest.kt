package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.settings.authCreateServerSlugFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AuthTextFieldValueContractTest {
    @Test
    fun authKeyboardViewportConvertsNativeDpHeightToRootPixels() {
        assertEquals(
            Rect(10f, 100f, 310f, 700f),
            raftAuthKeyboardReducedViewport(
                viewport = Rect(10f, 100f, 310f, 1000f),
                keyboardHeightDp = 300f,
                density = 1f
            )
        )
        assertEquals(
            Rect(10f, 100f, 310f, 400f),
            raftAuthKeyboardReducedViewport(
                viewport = Rect(10f, 100f, 310f, 1000f),
                keyboardHeightDp = 300f,
                density = 2f
            )
        )
    }

    @Test
    fun authKeyboardViewportDoesNotDoubleCountIncludedSafeAreaOrClosedKeyboard() {
        val viewport = Rect(10f, 100f, 310f, 1000f)

        assertEquals(
            Rect(10f, 100f, 310f, 400f),
            raftAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 300f,
                density = 2f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = false
            )
        )
        assertEquals(
            viewport,
            raftAuthKeyboardReducedViewport(
                viewport = viewport,
                keyboardHeightDp = 0f,
                density = 2f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true
            )
        )
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
    fun slugNormalizationDoesNotTouchActiveComposition() {
        val composing =
            TextFieldValue(
                text = "My 工作",
                selection = TextRange(5),
                composition = TextRange(3, 5),
            )

        assertSame(composing, authCreateServerSlugFieldValue(composing))
    }
}

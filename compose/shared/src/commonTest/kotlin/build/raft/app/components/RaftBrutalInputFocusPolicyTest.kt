package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RaftBrutalInputFocusPolicyTest {
    @Test
    fun multilineVisibleSurfaceOwnsFocusAndEditorHeightOnlyWhenEditable() {
        val vectors =
            listOf(
                FocusVector(enabled = true, readOnly = false, acceptsFocus = true, containerTap = true),
                FocusVector(enabled = false, readOnly = false, acceptsFocus = false, containerTap = false),
                FocusVector(enabled = true, readOnly = true, acceptsFocus = false, containerTap = false)
            )

        vectors.forEach { vector ->
            assertEquals(
                RaftBrutalInputFocusPolicy(
                    acceptsFocus = vector.acceptsFocus,
                    fillEditorHeight = true,
                    containerTapRequestsFocus = vector.containerTap
                ),
                raftBrutalInputFocusPolicy(
                    maxLines = 4,
                    enabled = vector.enabled,
                    readOnly = vector.readOnly
                )
            )
        }
    }

    @Test
    fun singleLineKeepsContainerFocusAsAnExplicitEditableOptIn() {
        assertEquals(
            RaftBrutalInputFocusPolicy(
                acceptsFocus = true,
                fillEditorHeight = false,
                containerTapRequestsFocus = false
            ),
            raftBrutalInputFocusPolicy(maxLines = 1, enabled = true, readOnly = false)
        )
        assertEquals(
            RaftBrutalInputFocusPolicy(
                acceptsFocus = true,
                fillEditorHeight = false,
                containerTapRequestsFocus = true
            ),
            raftBrutalInputFocusPolicy(
                maxLines = 1,
                enabled = true,
                readOnly = false,
                explicitContainerTapRequestsFocus = true
            )
        )
        assertEquals(
            false,
            raftBrutalInputFocusPolicy(
                maxLines = 1,
                enabled = true,
                readOnly = true,
                explicitContainerTapRequestsFocus = true
            ).containerTapRequestsFocus
        )
    }

    @Test
    fun multilineValueAwareEditorForwardsExactNativeSnapshotsWhileEditable() {
        val focusPolicy = raftBrutalInputFocusPolicy(maxLines = 4, enabled = true, readOnly = false)
        val nativeValues =
            listOf(
                TextFieldValue(text = "a", selection = TextRange(1)),
                TextFieldValue(
                    text = "ab",
                    selection = TextRange(2),
                    composition = TextRange(0, 2)
                ),
                TextFieldValue(text = "abc", selection = TextRange(3)),
                TextFieldValue(text = "ab", selection = TextRange(2)),
                TextFieldValue(text = "abx", selection = TextRange(3))
            )
        val forwarded = mutableListOf<TextFieldValue>()

        nativeValues.forEach { nextValue ->
            raftBrutalInputForwardValueChange(
                focusPolicy = focusPolicy,
                nextValue = nextValue,
                onValueChange = forwarded::add
            )
        }

        assertEquals(listOf("a", "ab", "abc", "ab", "abx"), forwarded.map { it.text })
        assertEquals(true, focusPolicy.fillEditorHeight)
        assertEquals(true, focusPolicy.containerTapRequestsFocus)
        nativeValues.zip(forwarded).forEach { (nativeValue, forwardedValue) ->
            assertSame(nativeValue, forwardedValue)
        }
        assertEquals(TextRange(2), forwarded[1].selection)
        assertEquals(TextRange(0, 2), forwarded[1].composition)
    }

    private data class FocusVector(
        val enabled: Boolean,
        val readOnly: Boolean,
        val acceptsFocus: Boolean,
        val containerTap: Boolean
    )

    @Composable
    @Suppress("unused")
    private fun valueAwareOrdinaryMultilineContractCompiles() {
        RaftBrutalTextField(
            label = "Description",
            value = TextFieldValue("draft", selection = TextRange(2), composition = TextRange(0, 2)),
            placeholder = "",
            onValueChange = {},
            maxLines = 4,
            password = false
        )
    }
}

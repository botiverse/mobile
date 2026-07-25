package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockBrutalInputFocusPolicyTest {
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
                SlockBrutalInputFocusPolicy(
                    acceptsFocus = vector.acceptsFocus,
                    fillEditorHeight = true,
                    containerTapRequestsFocus = vector.containerTap
                ),
                slockBrutalInputFocusPolicy(
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
            SlockBrutalInputFocusPolicy(
                acceptsFocus = true,
                fillEditorHeight = false,
                containerTapRequestsFocus = false
            ),
            slockBrutalInputFocusPolicy(maxLines = 1, enabled = true, readOnly = false)
        )
        assertEquals(
            SlockBrutalInputFocusPolicy(
                acceptsFocus = true,
                fillEditorHeight = false,
                containerTapRequestsFocus = true
            ),
            slockBrutalInputFocusPolicy(
                maxLines = 1,
                enabled = true,
                readOnly = false,
                explicitContainerTapRequestsFocus = true
            )
        )
        assertEquals(
            false,
            slockBrutalInputFocusPolicy(
                maxLines = 1,
                enabled = true,
                readOnly = true,
                explicitContainerTapRequestsFocus = true
            ).containerTapRequestsFocus
        )
    }

    private data class FocusVector(
        val enabled: Boolean,
        val readOnly: Boolean,
        val acceptsFocus: Boolean,
        val containerTap: Boolean
    )
}

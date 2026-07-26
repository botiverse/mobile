package build.raft.app

import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPasswordFieldContractTest {

    @Test
    fun explicitPasswordFlagEnablesMaskingForDefaultKeyboard() {
        assertTrue(
            authTextFieldUsesPasswordMode(
                password = true,
                keyboardType = KeyboardType.Unspecified
            )
        )
    }

    @Test
    fun passwordKeyboardFailsSafeToMaskedModeWithoutExplicitFlag() {
        assertTrue(
            authTextFieldUsesPasswordMode(
                password = false,
                keyboardType = KeyboardType.Password
            )
        )
    }

    @Test
    fun ordinaryTextFieldRemainsUnmasked() {
        assertFalse(
            authTextFieldUsesPasswordMode(
                password = false,
                keyboardType = KeyboardType.Email
            )
        )
    }

    @Test
    fun passwordVisibilityToggleRemainsOptIn() {
        assertFalse(
            authTextFieldShowsPasswordToggle(
                passwordMode = true,
                showPasswordToggle = false
            )
        )
        assertTrue(
            authTextFieldShowsPasswordToggle(
                passwordMode = true,
                showPasswordToggle = true
            )
        )
    }
}

package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginContractTest {
    @Test
    fun identitySetupShortUsernameShowsInlineError() {
        val update =
            IdentitySetupFormReducer().reduce(
                IdentitySetupFormState(handle = "Art", displayName = "Artin User"),
                IdentitySetupFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.UsernameTooShort, update.state.handleError)
        assertEquals(IdentitySetupFormEffect.FocusHandle, update.effect)
    }

    @Test
    fun identitySetupRejectsInvalidAndReservedUsernames() {
        val invalid =
            IdentitySetupFormReducer().reduce(
                IdentitySetupFormState(handle = "1invalid", displayName = "Artin User"),
                IdentitySetupFormEvent.Submit
            )
        assertEquals(
            AuthValidationError.UsernameInvalidFormat,
            invalid.state.handleError
        )
        assertEquals(IdentitySetupFormEffect.FocusHandle, invalid.effect)

        val reserved =
            IdentitySetupFormReducer().reduce(
                IdentitySetupFormState(handle = "pending_user", displayName = "Artin User"),
                IdentitySetupFormEvent.Submit
            )
        assertEquals(AuthValidationError.UsernameReserved, reserved.state.handleError)
        assertEquals(IdentitySetupFormEffect.FocusHandle, reserved.effect)
    }

    @Test
    fun identityHandleValidationKeepsUnicodeLettersWithoutPlatformRegexClasses() {
        assertEquals(null, identityHandleValidationError("西施用户一"))
        assertEquals(null, identityHandleValidationError("Artin_用户1"))
        assertEquals(null, identityHandleValidationError("Artin²"))
        assertEquals(null, identityHandleValidationError("ArtinⅫ"))
        assertEquals(null, identityHandleValidationError("𐐀user"))
        assertEquals(null, identityHandleValidationError("𑎀user"))
        assertEquals(null, identityHandleValidationError("Ᲊuser"))
        assertEquals(null, identityHandleValidationError("𑯀user"))
        assertEquals(null, identityHandleValidationError("Artin𐵀"))
        assertEquals(
            AuthValidationError.UsernameInvalidFormat,
            identityHandleValidationError("Artin🙂")
        )
        assertEquals(
            AuthValidationError.UsernameInvalidFormat,
            identityHandleValidationError("²Artin")
        )
        assertEquals(
            AuthValidationError.UsernameInvalidFormat,
            identityHandleValidationError("\uD800user")
        )
    }

    @Test
    fun identitySetupUsesServerDisplayNameMaximum() {
        val update =
            IdentitySetupFormReducer().reduce(
                IdentitySetupFormState(handle = "artin", displayName = "x".repeat(81)),
                IdentitySetupFormEvent.Submit
            )

        assertEquals(AuthValidationError.DisplayNameTooLong, update.state.displayNameError)
        assertEquals(IdentitySetupFormEffect.FocusDisplayName, update.effect)
    }

    @Test
    fun validIdentitySetupTrimsFieldsAndSubmits() {
        val update =
            IdentitySetupFormReducer().reduce(
                IdentitySetupFormState(handle = " @artin ", displayName = " Artin Chen "),
                IdentitySetupFormEvent.Submit
            )
        assertEquals(
            IdentitySetupFormEffect.SubmitIdentity(handle = "artin", displayName = "Artin Chen"),
            update.effect
        )
        assertEquals(true, update.state.submitting)
    }
}

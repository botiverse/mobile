package build.raft.app

import build.raft.app.reduce
import build.raft.app.thread.reduce
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
        // Supplementary single-script handles pass both canonical character and UTS #39 checks.
        assertEquals(null, identityHandleValidationError("𐐀𐐀𐐀𐐀𐐀"))
        assertEquals(null, identityHandleValidationError("𑎀𑎀𑎀𑎀𑎀"))
        assertEquals(null, identityHandleValidationError("ᲉᲉᲉᲉᲉ"))
        assertEquals(null, identityHandleValidationError("𑯀𑯀𑯀𑯀𑯀"))
        assertEquals(null, identityHandleValidationError("𐵀𐵀𐵀𐵀𐵀"))
        // Mixing non-CJK scripts with Latin is refused by UTS #39 Highly Restrictive single-script (#17270).
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("𐐀user"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("𑎀user"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("Ᲊuser"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("𑯀user"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("Artin𐵀"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("оwner"))
        assertEquals(AuthValidationError.UsernameMixedScript, identityHandleValidationError("raft한국かな"))
        // Format rules run before mixed-script, and two-tier reserved + confusable checks apply to people (#17291).
        assertEquals(AuthValidationError.UsernameReserved, identityHandleValidationError("admin"))
        assertEquals(AuthValidationError.UsernameReserved, identityHandleValidationError("Adrnin"))
        assertEquals(AuthValidationError.UsernameReserved, identityHandleValidationError("SIack"))
        assertEquals(AuthValidationError.UsernameReserved, identityHandleValidationError("reminders"))
        assertEquals(AuthValidationError.UsernameReserved, identityHandleValidationError("external"))
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

package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginContractTest {
    @Test
    fun blankSubmitShowsRequiredFieldsError() {
        val update = LoginFormReducer().reduce(LoginFormState(), LoginFormEvent.Submit)

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.InvalidEmailFormat, update.state.emailFormatError)
        assertEquals(LoginFormEffect.FocusEmail, update.effect)
    }

    @Test
    fun invalidLoginEmailShowsReactInlineEmailError() {
        val update =
            LoginFormReducer().reduce(
                LoginFormState(email = "not-an-email", password = "secret"),
                LoginFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.InvalidEmailFormat, update.state.emailFormatError)
        assertEquals(LoginFormEffect.FocusEmail, update.effect)
    }

    @Test
    fun loginSubmitWithoutPasswordShowsInlinePasswordError() {
        val update =
            LoginFormReducer().reduce(
                LoginFormState(email = "user@example.com", password = ""),
                LoginFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.PasswordRequired, update.state.passwordError)
        assertEquals(LoginFormEffect.FocusPassword, update.effect)
    }

    @Test
    fun validSubmitTrimsEmailAndStartsLogin() {
        val update =
            LoginFormReducer().reduce(
                LoginFormState(email = " user@example.com ", password = "secret"),
                LoginFormEvent.Submit
            )
        val effect = update.effect as LoginFormEffect.SubmitLogin

        assertEquals(true, update.state.submitting)
        assertEquals(null, update.state.errorMessage)
        assertEquals(null, update.state.emailFormatError)
        assertEquals(null, update.state.passwordError)
        assertEquals("user@example.com", effect.email)
        assertEquals("secret", effect.password)
    }

    @Test
    fun submittingSubmitKeepsCurrentStateWithoutRequiredError() {
        val state = LoginFormState(
            email = "user@example.com",
            password = "secret",
            submitting = true,
            errorMessage = null
        )
        val update = LoginFormReducer().reduce(state, LoginFormEvent.Submit)

        assertEquals(state, update.state)
        assertEquals(null, update.effect)
    }

    @Test
    fun loginFormCanSubmitOnlyWhenFieldsAreReady() {
        assertEquals(false, canSubmitLoginForm(email = "user@example.com", password = "", formBusy = false))
        assertEquals(false, canSubmitLoginForm(email = "", password = "secret", formBusy = false))
        assertEquals(false, canSubmitLoginForm(email = "user@example.com", password = "secret", formBusy = true))
        assertEquals(false, canSubmitLoginForm(email = "bad-email", password = "secret", formBusy = false, emailFormatError = "Invalid email format"))
        assertEquals(true, canSubmitLoginForm(email = "user@example.com", password = "secret", formBusy = false))
    }

    @Test
    fun registerFormCanSubmitOnlyWhenTermsAndFieldsAreReady() {
        assertEquals(
            false,
            canSubmitRegisterForm(
                email = "new@slock.ai",
                password = "password123",
                termsAccepted = false,
                formBusy = false
            )
        )
        assertEquals(
            false,
            canSubmitRegisterForm(
                email = "new@slock.ai",
                password = "password123",
                termsAccepted = true,
                formBusy = true
            )
        )
        assertEquals(
            false,
            canSubmitRegisterForm(
                email = "new@slock.ai",
                password = "password123",
                termsAccepted = true,
                formBusy = false,
                emailFormatError = "Invalid email format"
            )
        )
        assertEquals(
            true,
            canSubmitRegisterForm(
                email = "new@slock.ai",
                password = "password123",
                termsAccepted = true,
                formBusy = false
            )
        )
    }

    @Test
    fun currentDraftSubmitAlwaysForwardsSnapshotWhenNotBusy() {
        assertEquals(
            LoginDraftSubmitDecision.Forward(email = "a", password = "secret"),
            resolveLoginDraftSubmit(email = "a", password = "secret", formBusy = false)
        )
        assertEquals(
            LoginDraftSubmitDecision.Forward(email = "a", password = ""),
            resolveLoginDraftSubmit(email = "a", password = "", formBusy = false)
        )
        assertEquals(
            LoginDraftSubmitDecision.Forward(email = "", password = "secret"),
            resolveLoginDraftSubmit(email = "", password = "secret", formBusy = false)
        )
    }

    @Test
    fun currentDraftSubmitIgnoresBusyForm() {
        assertEquals(
            LoginDraftSubmitDecision.IgnoreBusy,
            resolveLoginDraftSubmit(email = "user@example.com", password = "secret", formBusy = true)
        )
    }

    @Test
    fun loginSubmitInFlightIncludesPendingAndSocialAuth() {
        assertEquals(false, isLoginSubmitInFlight(submitting = false, pendingLogin = false, socialSubmitting = false))
        assertEquals(true, isLoginSubmitInFlight(submitting = true, pendingLogin = false, socialSubmitting = false))
        assertEquals(true, isLoginSubmitInFlight(submitting = false, pendingLogin = true, socialSubmitting = false))
        assertEquals(true, isLoginSubmitInFlight(submitting = false, pendingLogin = false, socialSubmitting = true))
    }

    @Test
    fun createAccountInvalidEmailShowsInlineEmailError() {
        val update =
            CreateAccountFormReducer().reduce(
                CreateAccountFormState(email = "artin", password = "password123"),
                CreateAccountFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.InvalidEmailFormat, update.state.emailFormatError)
        assertEquals(CreateAccountFormEffect.FocusEmail, update.effect)
    }

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
    fun createAccountShortPasswordShowsInlinePasswordError() {
        val update =
            CreateAccountFormReducer().reduce(
                CreateAccountFormState(email = "artin@example.com", password = "short"),
                CreateAccountFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.PasswordTooShort, update.state.passwordError)
        assertEquals(CreateAccountFormEffect.FocusPassword, update.effect)
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

    @Test
    fun forgotPasswordInvalidEmailShowsInlineEmailError() {
        val update =
            ForgotPasswordFormReducer().reduce(
                ForgotPasswordFormState(email = "missing-at"),
                ForgotPasswordFormEvent.Submit
            )

        assertEquals(null, update.state.errorMessage)
        assertEquals(AuthValidationError.InvalidEmailFormat, update.state.emailFormatError)
        assertEquals(ForgotPasswordFormEffect.FocusEmail, update.effect)
    }

    @Test
    fun authEmailFormatMatchesReactTrimmedShape() {
        assertEquals(true, isValidAuthEmailFormat(" user@example.com "))
        assertEquals(false, isValidAuthEmailFormat("user@example"))
        assertEquals(false, isValidAuthEmailFormat("user example@example.com"))
    }
}

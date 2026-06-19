package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginContractTest {
    @Test
    fun blankSubmitShowsRequiredFieldsError() {
        val update = LoginFormReducer().reduce(LoginFormState(), LoginFormEvent.Submit)

        assertEquals("Email and password are required.", update.state.errorMessage)
        assertEquals(LoginFormEffect.FocusEmail, update.effect)
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
        assertEquals(true, canSubmitLoginForm(email = "user@example.com", password = "secret", formBusy = false))
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
}

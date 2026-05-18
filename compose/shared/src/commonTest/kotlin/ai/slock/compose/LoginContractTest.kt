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
}

package ai.slock.compose

data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val emailFormatError: String? = null,
    val passwordError: String? = null
) {
    val normalizedEmail: String
        get() = email.trim()

    val canSubmit: Boolean
        get() = normalizedEmail.isNotEmpty() &&
            password.isNotEmpty() &&
            emailFormatError == null &&
            passwordError == null &&
            !submitting
}

data class CreateAccountFormState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val nameError: String? = null,
    val emailFormatError: String? = null,
    val passwordError: String? = null
) {
    val normalizedName: String
        get() = name.trim()

    val normalizedEmail: String
        get() = email.trim()

    val canSubmit: Boolean
        get() = normalizedName.isNotEmpty() &&
            normalizedEmail.isNotEmpty() &&
            password.isNotEmpty() &&
            nameError == null &&
            emailFormatError == null &&
            passwordError == null &&
            !submitting
}

private const val AUTH_NAME_MIN_LENGTH = 5

data class ForgotPasswordFormState(
    val email: String = "",
    val submitting: Boolean = false,
    val sent: Boolean = false,
    val errorMessage: String? = null,
    val emailFormatError: String? = null
) {
    val normalizedEmail: String
        get() = email.trim()

    val canSubmit: Boolean
        get() = normalizedEmail.isNotEmpty() && emailFormatError == null && !submitting && !sent
}

sealed class LoginFormEvent {
    data class EmailChanged(val value: String) : LoginFormEvent()
    data class PasswordChanged(val value: String) : LoginFormEvent()
    data object Submit : LoginFormEvent()
    data object ForgotPassword : LoginFormEvent()
    data object CreateAccount : LoginFormEvent()
}

sealed class LoginFormEffect {
    data class SubmitLogin(val email: String, val password: String) : LoginFormEffect()
    data object FocusEmail : LoginFormEffect()
    data object FocusPassword : LoginFormEffect()
}

internal fun LoginFormEffect?.loginEffectName(): String =
    when (this) {
        is LoginFormEffect.SubmitLogin -> "submit_login"
        LoginFormEffect.FocusEmail -> "focus_email"
        LoginFormEffect.FocusPassword -> "focus_password"
        null -> "none"
    }

data class LoginFormUpdate(
    val state: LoginFormState,
    val effect: LoginFormEffect? = null
)

class LoginFormReducer {
    fun reduce(state: LoginFormState, event: LoginFormEvent): LoginFormUpdate =
        when (event) {
            is LoginFormEvent.EmailChanged ->
                LoginFormUpdate(state.copy(email = event.value, errorMessage = null, emailFormatError = null))

            is LoginFormEvent.PasswordChanged ->
                LoginFormUpdate(state.copy(password = event.value, errorMessage = null, passwordError = null))

            LoginFormEvent.Submit ->
                when {
                    state.submitting -> LoginFormUpdate(state)
                    !isValidAuthEmailFormat(state.email) ->
                        LoginFormUpdate(
                            state = state.copy(
                                errorMessage = null,
                                emailFormatError = AUTH_INVALID_EMAIL_FORMAT,
                                passwordError = null
                            ),
                            effect = LoginFormEffect.FocusEmail
                        )
                    state.password.isBlank() ->
                        LoginFormUpdate(
                            state = state.copy(
                                errorMessage = null,
                                emailFormatError = null,
                                passwordError = AUTH_PASSWORD_REQUIRED
                            ),
                            effect = LoginFormEffect.FocusPassword
                        )
                    state.normalizedEmail.isNotEmpty() && state.password.isNotBlank() ->
                        LoginFormUpdate(
                            state = state.copy(
                                submitting = true,
                                errorMessage = null,
                                emailFormatError = null,
                                passwordError = null
                            ),
                            effect = LoginFormEffect.SubmitLogin(
                                email = state.normalizedEmail,
                                password = state.password
                            )
                        )

                    else ->
                        LoginFormUpdate(
                            state = state.copy(errorMessage = "Email and password are required."),
                            effect = LoginFormEffect.FocusEmail
                        )
                }

            LoginFormEvent.ForgotPassword,
            LoginFormEvent.CreateAccount -> LoginFormUpdate(state)
        }
}

internal fun canSubmitLoginForm(
    email: String,
    password: String,
    formBusy: Boolean,
    emailFormatError: String? = null
): Boolean =
    email.isNotBlank() && password.isNotBlank() && emailFormatError == null && !formBusy

internal sealed class LoginDraftSubmitDecision {
    data class Forward(val email: String, val password: String) : LoginDraftSubmitDecision()
    data object IgnoreBusy : LoginDraftSubmitDecision()
}

internal fun resolveLoginDraftSubmit(
    email: String,
    password: String,
    formBusy: Boolean
): LoginDraftSubmitDecision =
    if (formBusy) {
        LoginDraftSubmitDecision.IgnoreBusy
    } else {
        LoginDraftSubmitDecision.Forward(email = email, password = password)
    }

internal fun isLoginSubmitInFlight(
    submitting: Boolean,
    pendingLogin: Boolean,
    socialSubmitting: Boolean
): Boolean =
    submitting || pendingLogin || socialSubmitting

sealed class CreateAccountFormEvent {
    data class NameChanged(val value: String) : CreateAccountFormEvent()
    data class EmailChanged(val value: String) : CreateAccountFormEvent()
    data class PasswordChanged(val value: String) : CreateAccountFormEvent()
    data object Submit : CreateAccountFormEvent()
}

sealed class CreateAccountFormEffect {
    data class SubmitCreateAccount(
        val name: String,
        val email: String,
        val password: String
    ) : CreateAccountFormEffect()
    data object FocusName : CreateAccountFormEffect()
    data object FocusEmail : CreateAccountFormEffect()
    data object FocusPassword : CreateAccountFormEffect()
}

data class CreateAccountFormUpdate(
    val state: CreateAccountFormState,
    val effect: CreateAccountFormEffect? = null
)

class CreateAccountFormReducer {
    fun reduce(
        state: CreateAccountFormState,
        event: CreateAccountFormEvent
    ): CreateAccountFormUpdate =
        when (event) {
            is CreateAccountFormEvent.NameChanged ->
                CreateAccountFormUpdate(state.copy(name = event.value, errorMessage = null, nameError = null))

            is CreateAccountFormEvent.EmailChanged ->
                CreateAccountFormUpdate(state.copy(email = event.value, errorMessage = null, emailFormatError = null))

            is CreateAccountFormEvent.PasswordChanged ->
                CreateAccountFormUpdate(state.copy(password = event.value, errorMessage = null, passwordError = null))

            CreateAccountFormEvent.Submit ->
                when {
                    state.normalizedName.isEmpty() ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
                                nameError = "Name is required",
                                emailFormatError = null,
                                passwordError = null
                            ),
                            CreateAccountFormEffect.FocusName
                        )
                    state.normalizedName.length < AUTH_NAME_MIN_LENGTH ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
                                nameError = "Name must be at least $AUTH_NAME_MIN_LENGTH characters",
                                emailFormatError = null,
                                passwordError = null
                            ),
                            CreateAccountFormEffect.FocusName
                        )
                    !isValidAuthEmailFormat(state.email) ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
                                nameError = null,
                                emailFormatError = AUTH_INVALID_EMAIL_FORMAT,
                                passwordError = null
                            ),
                            CreateAccountFormEffect.FocusEmail
                        )
                    state.password.length < 8 ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
                                nameError = null,
                                emailFormatError = null,
                                passwordError = AUTH_PASSWORD_TOO_SHORT
                            ),
                            CreateAccountFormEffect.FocusPassword
                        )
                    else ->
                        CreateAccountFormUpdate(
                            state = state.copy(
                                submitting = true,
                                errorMessage = null,
                                nameError = null,
                                emailFormatError = null,
                                passwordError = null
                            ),
                            effect = CreateAccountFormEffect.SubmitCreateAccount(
                                name = state.normalizedName,
                                email = state.normalizedEmail,
                                password = state.password
                            )
                        )
                }
        }
}

sealed class ForgotPasswordFormEvent {
    data class EmailChanged(val value: String) : ForgotPasswordFormEvent()
    data object Submit : ForgotPasswordFormEvent()
}

sealed class ForgotPasswordFormEffect {
    data class SubmitForgotPassword(val email: String) : ForgotPasswordFormEffect()
    data object FocusEmail : ForgotPasswordFormEffect()
}

data class ForgotPasswordFormUpdate(
    val state: ForgotPasswordFormState,
    val effect: ForgotPasswordFormEffect? = null
)

class ForgotPasswordFormReducer {
    fun reduce(
        state: ForgotPasswordFormState,
        event: ForgotPasswordFormEvent
    ): ForgotPasswordFormUpdate =
        when (event) {
            is ForgotPasswordFormEvent.EmailChanged ->
                ForgotPasswordFormUpdate(
                    state.copy(email = event.value, errorMessage = null, emailFormatError = null, sent = false)
                )

            ForgotPasswordFormEvent.Submit ->
                if (isValidAuthEmailFormat(state.email) && !state.submitting && !state.sent) {
                    ForgotPasswordFormUpdate(
                        state = state.copy(submitting = true, errorMessage = null, emailFormatError = null),
                        effect = ForgotPasswordFormEffect.SubmitForgotPassword(state.normalizedEmail)
                    )
                } else {
                    ForgotPasswordFormUpdate(
                        state.copy(errorMessage = null, emailFormatError = AUTH_INVALID_EMAIL_FORMAT),
                        ForgotPasswordFormEffect.FocusEmail
                    )
                }
        }
}

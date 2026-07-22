package build.raft.app

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

private const val AUTH_IDENTITY_NAME_MIN_LENGTH = 5
private const val AUTH_IDENTITY_NAME_MAX_LENGTH = 32
private const val AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH = 80
private val AUTH_IDENTITY_RESERVED_NAMES =
    setOf("all", "human", "humans", "agent", "agents", "here", "idle", "busy", "system")

internal fun identityHandleValidationError(value: String): String? {
    val handle = value.trim().removePrefix("@")
    return when {
        handle.length < AUTH_IDENTITY_NAME_MIN_LENGTH ->
            "Username must be at least $AUTH_IDENTITY_NAME_MIN_LENGTH characters"
        handle.length > AUTH_IDENTITY_NAME_MAX_LENGTH ->
            "Username must be at most $AUTH_IDENTITY_NAME_MAX_LENGTH characters"
        !handle.isCanonicalIdentityHandle() ->
            "Username must start with a letter and can only contain letters, numbers, hyphens, and underscores"
        handle.lowercase().startsWith("pending_") || handle.lowercase() in AUTH_IDENTITY_RESERVED_NAMES ->
            "This username is reserved. Choose another name."
        else -> null
    }
}

internal fun identityDisplayNameValidationError(value: String): String? {
    val displayName = value.trim()
    return when {
        displayName.isEmpty() -> "Display name is required"
        displayName.length > AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH ->
            "Display name must be at most $AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH characters"
        else -> null
    }
}

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

internal fun canSubmitRegisterForm(
    email: String,
    password: String,
    termsAccepted: Boolean,
    formBusy: Boolean,
    emailFormatError: String? = null,
    passwordError: String? = null
): Boolean =
    email.isNotBlank() &&
        password.isNotBlank() &&
        termsAccepted &&
        !formBusy &&
        emailFormatError == null &&
        passwordError == null

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
    data class EmailChanged(val value: String) : CreateAccountFormEvent()
    data class PasswordChanged(val value: String) : CreateAccountFormEvent()
    data object Submit : CreateAccountFormEvent()
}

sealed class CreateAccountFormEffect {
    data class SubmitCreateAccount(
        val email: String,
        val password: String
    ) : CreateAccountFormEffect()
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
            is CreateAccountFormEvent.EmailChanged ->
                CreateAccountFormUpdate(state.copy(email = event.value, errorMessage = null, emailFormatError = null))

            is CreateAccountFormEvent.PasswordChanged ->
                CreateAccountFormUpdate(state.copy(password = event.value, errorMessage = null, passwordError = null))

            CreateAccountFormEvent.Submit ->
                when {
                    !isValidAuthEmailFormat(state.email) ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
                                emailFormatError = AUTH_INVALID_EMAIL_FORMAT,
                                passwordError = null
                            ),
                            CreateAccountFormEffect.FocusEmail
                        )
                    state.password.length < 8 ->
                        CreateAccountFormUpdate(
                            state.copy(
                                errorMessage = null,
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
                                emailFormatError = null,
                                passwordError = null
                            ),
                            effect = CreateAccountFormEffect.SubmitCreateAccount(
                                email = state.normalizedEmail,
                                password = state.password
                            )
                        )
                }
        }
}

data class IdentitySetupFormState(
    val handle: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val handleError: String? = null,
    val displayNameError: String? = null
) {
    val normalizedHandle: String
        get() = handle.trim().removePrefix("@")

    val normalizedDisplayName: String
        get() = displayName.trim()
}

sealed class IdentitySetupFormEvent {
    data class HandleChanged(val value: String) : IdentitySetupFormEvent()
    data class DisplayNameChanged(val value: String) : IdentitySetupFormEvent()
    data object Submit : IdentitySetupFormEvent()
}

sealed class IdentitySetupFormEffect {
    data class SubmitIdentity(val handle: String, val displayName: String) : IdentitySetupFormEffect()
    data object FocusHandle : IdentitySetupFormEffect()
    data object FocusDisplayName : IdentitySetupFormEffect()
}

data class IdentitySetupFormUpdate(
    val state: IdentitySetupFormState,
    val effect: IdentitySetupFormEffect? = null
)

class IdentitySetupFormReducer {
    fun reduce(state: IdentitySetupFormState, event: IdentitySetupFormEvent): IdentitySetupFormUpdate =
        when (event) {
            is IdentitySetupFormEvent.HandleChanged ->
                IdentitySetupFormUpdate(
                    state.copy(handle = event.value.removePrefix("@"), errorMessage = null, handleError = null)
                )
            is IdentitySetupFormEvent.DisplayNameChanged ->
                IdentitySetupFormUpdate(
                    state.copy(displayName = event.value, errorMessage = null, displayNameError = null)
                )
            IdentitySetupFormEvent.Submit ->
                when {
                    state.submitting -> IdentitySetupFormUpdate(state)
                    identityHandleValidationError(state.normalizedHandle) != null ->
                        IdentitySetupFormUpdate(
                            state.copy(handleError = identityHandleValidationError(state.normalizedHandle)),
                            IdentitySetupFormEffect.FocusHandle
                        )
                    identityDisplayNameValidationError(state.normalizedDisplayName) != null ->
                        IdentitySetupFormUpdate(
                            state.copy(displayNameError = identityDisplayNameValidationError(state.normalizedDisplayName)),
                            IdentitySetupFormEffect.FocusDisplayName
                        )
                    else ->
                        IdentitySetupFormUpdate(
                            state.copy(submitting = true, errorMessage = null, handleError = null, displayNameError = null),
                            IdentitySetupFormEffect.SubmitIdentity(
                                handle = state.normalizedHandle,
                                displayName = state.normalizedDisplayName
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

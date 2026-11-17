package build.raft.app

internal const val AUTH_IDENTITY_NAME_MIN_LENGTH = 5
internal const val AUTH_IDENTITY_NAME_MAX_LENGTH = 32
internal const val AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH = 80
private val AUTH_IDENTITY_RESERVED_NAMES =
    setOf("all", "human", "humans", "agent", "agents", "here", "idle", "busy", "system")

enum class AuthValidationError {
    UsernameTooShort,
    UsernameTooLong,
    UsernameInvalidFormat,
    UsernameReserved,
    DisplayNameRequired,
    DisplayNameTooLong
}

internal fun identityHandleValidationError(value: String): AuthValidationError? {
    val handle = value.trim().removePrefix("@")
    return when {
        handle.length < AUTH_IDENTITY_NAME_MIN_LENGTH ->
            AuthValidationError.UsernameTooShort
        handle.length > AUTH_IDENTITY_NAME_MAX_LENGTH ->
            AuthValidationError.UsernameTooLong
        !handle.isCanonicalIdentityHandle() ->
            AuthValidationError.UsernameInvalidFormat
        handle.lowercase().startsWith("pending_") || handle.lowercase() in AUTH_IDENTITY_RESERVED_NAMES ->
            AuthValidationError.UsernameReserved
        else -> null
    }
}

internal fun identityDisplayNameValidationError(value: String): AuthValidationError? {
    val displayName = value.trim()
    return when {
        displayName.isEmpty() -> AuthValidationError.DisplayNameRequired
        displayName.length > AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH ->
            AuthValidationError.DisplayNameTooLong
        else -> null
    }
}

data class IdentitySetupFormState(
    val handle: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val handleError: AuthValidationError? = null,
    val displayNameError: AuthValidationError? = null
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

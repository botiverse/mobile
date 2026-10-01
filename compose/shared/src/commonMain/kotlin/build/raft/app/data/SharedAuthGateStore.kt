package build.raft.app.data

import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SharedAuthGateStore(
    private val restoreSession: suspend () -> SharedAuthResult<StoredAuthSession>,
    private val cachedSession: suspend () -> StoredAuthSession? = { null },
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<SharedAuthGateState>(SharedAuthGateState.Restoring)
    val state: StateFlow<SharedAuthGateState> = _state.asStateFlow()

    private var restoreJob: Job? = null

    fun dispatch(intent: SharedAuthGateIntent) {
        when (intent) {
            SharedAuthGateIntent.Restore,
            SharedAuthGateIntent.Retry -> {
                restoreJob?.cancel()
                restoreJob =
                    scope.launch {
                        restore()
                    }
            }
            SharedAuthGateIntent.RefreshSilently -> {
                restoreJob?.cancel()
                restoreJob =
                    scope.launch {
                        refreshSilently()
                    }
            }
            is SharedAuthGateIntent.SignedIn ->
                setExplicitGateState(intent.session.asGateState(), source = "signed_in")
            is SharedAuthGateIntent.ServerReady ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session), source = "server_ready")
            is SharedAuthGateIntent.ResumeQaBootstrap ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session.copy(qaBootstrapLaunchAuthorized = true)), source = "qa_bootstrap_resume")
            SharedAuthGateIntent.ShowSignIn ->
                setExplicitGateState(SharedAuthGateState.AuthRequired, source = "show_sign_in")
            SharedAuthGateIntent.SignedOut ->
                setExplicitGateState(SharedAuthGateState.AuthRequired, source = "signed_out")
        }
    }

    fun clear() {
        restoreJob?.cancel()
        restoreJob = null
    }

    suspend fun accept(intent: SharedAuthGateIntent) {
        when (intent) {
            SharedAuthGateIntent.Restore,
            SharedAuthGateIntent.Retry -> restore()
            SharedAuthGateIntent.RefreshSilently -> refreshSilently()
            is SharedAuthGateIntent.SignedIn ->
                setExplicitGateState(intent.session.asGateState(), source = "signed_in")
            is SharedAuthGateIntent.ServerReady ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session), source = "server_ready")
            is SharedAuthGateIntent.ResumeQaBootstrap ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session.copy(qaBootstrapLaunchAuthorized = true)), source = "qa_bootstrap_resume")
            SharedAuthGateIntent.ShowSignIn,
            SharedAuthGateIntent.SignedOut ->
                setExplicitGateState(SharedAuthGateState.AuthRequired, source = intent.logSource)
        }
    }

    private suspend fun restore() {
        setGateState(SharedAuthGateState.Restoring, source = "restore_start")
        val cachedReady =
            runCatchingPreservingCancellation { cachedSession()?.takeIf { it.isColdStartCachedReadyEligible() } }
                .getOrElse { throwable ->
                    diagnosticsLogger(
                        "auth_gate_cached_ready_exception errorType=${throwable.safeAuthGateDiagnosticsType()} fallback=restore"
                    )
                    null
                }
        if (cachedReady != null) {
            setGateState(
                SharedAuthGateState.Ready(
                    session = cachedReady,
                    phase = SharedAuthReadyPhase.CachedRestorePending
                ),
                source = "restore_cached_ready"
            )
            restoreAfterCachedReady(cachedReady)
            return
        }
        restoreCold()
    }

    private suspend fun restoreCold() {
        try {
            setGateState(
                when (val result = restoreSession()) {
                    is SharedAuthResult.Success -> result.data.asGateState()
                    SharedAuthResult.Unauthenticated -> SharedAuthGateState.AuthRequired
                    is SharedAuthResult.Failure -> SharedAuthGateState.Error(result.message)
                },
                source = "restore_result"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            diagnosticsLogger(
                "auth_gate_restore_exception errorType=${throwable.safeAuthGateDiagnosticsType()} mapped=restore_error"
            )
            setGateState(
                SharedAuthGateState.Error("Could not restore session. Retry or sign in again."),
                source = "restore_exception"
            )
        }
    }

    private suspend fun restoreAfterCachedReady(previousReady: StoredAuthSession) {
        try {
            when (val result = restoreSession()) {
                is SharedAuthResult.Success ->
                    setGateState(
                        result.data.asGateState().withCompletedCachedRestore(),
                        source = "restore_background_result"
                    )
                SharedAuthResult.Unauthenticated ->
                    setGateState(SharedAuthGateState.AuthRequired, source = "restore_background_result")
                is SharedAuthResult.Failure -> {
                    diagnosticsLogger(
                        "auth_gate_restore_background_failure code=${result.code} message=${result.message.authGateDiagnosticsToken()} keeping_ready=true"
                    )
                    setGateState(
                        SharedAuthGateState.Ready(
                            session = previousReady,
                            phase = SharedAuthReadyPhase.CachedRestoreCompleted
                        ),
                        source = "restore_background_keep_ready"
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            diagnosticsLogger(
                "auth_gate_restore_background_exception errorType=${throwable.safeAuthGateDiagnosticsType()} keeping_ready=true"
            )
            setGateState(
                SharedAuthGateState.Ready(
                    session = previousReady,
                    phase = SharedAuthReadyPhase.CachedRestoreCompleted
                ),
                source = "restore_background_exception_keep_ready"
            )
        }
    }

    private suspend fun refreshSilently() {
        val previousReady = _state.value as? SharedAuthGateState.Ready
        if (previousReady == null) {
            restore()
            return
        }
        try {
            when (val result = restoreSession()) {
                is SharedAuthResult.Success ->
                    setGateState(result.data.asGateState(), source = "silent_refresh_result")
                SharedAuthResult.Unauthenticated ->
                    setGateState(SharedAuthGateState.AuthRequired, source = "silent_refresh_result")
                is SharedAuthResult.Failure -> {
                    diagnosticsLogger(
                        "auth_gate_silent_refresh_failure code=${result.code} message=${result.message.authGateDiagnosticsToken()} keeping_ready=true"
                    )
                    setGateState(
                        previousReady.completedAfterCachedRestore(),
                        source = "silent_refresh_keep_ready"
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            diagnosticsLogger(
                "auth_gate_silent_refresh_exception errorType=${throwable.safeAuthGateDiagnosticsType()} keeping_ready=true"
            )
            setGateState(
                previousReady.completedAfterCachedRestore(),
                source = "silent_refresh_exception_keep_ready"
            )
        }
    }

    private fun setGateState(nextState: SharedAuthGateState, source: String) {
        _state.value = nextState
        diagnosticsLogger(
            "auth_gate_state source=$source state=${nextState.diagnosticsName()} selectedServer=${nextState.hasSelectedServer()}"
        )
    }

    private fun setExplicitGateState(nextState: SharedAuthGateState, source: String) {
        restoreJob?.cancel()
        restoreJob = null
        setGateState(nextState, source)
    }

}

class SharedAuthGateViewModel(
    private val authDataSource: SharedAuthDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedAuthGateStore(
                restoreSession = { authDataSource.restoreSession() },
                cachedSession = {
                    (authDataSource.currentSession() as? SharedAuthResult.Success)?.data
                },
                scope = viewModelScope,
                diagnosticsLogger = { message -> authDataSource.logDiagnostics(message) }
            )
        }
    private val store: SharedAuthGateStore by storeDelegate
    val state: StateFlow<SharedAuthGateState>
        get() = store.state

    fun dispatch(intent: SharedAuthGateIntent) {
        store.dispatch(intent)
    }

    suspend fun accept(intent: SharedAuthGateIntent) {
        store.accept(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

sealed interface SharedAuthGateIntent {
    data object Restore : SharedAuthGateIntent
    data object Retry : SharedAuthGateIntent
    data object RefreshSilently : SharedAuthGateIntent
    data object ShowSignIn : SharedAuthGateIntent
    data object SignedOut : SharedAuthGateIntent
    data class SignedIn(val session: StoredAuthSession) : SharedAuthGateIntent
    data class ServerReady(val session: StoredAuthSession) : SharedAuthGateIntent
    data class ResumeQaBootstrap(val session: StoredAuthSession) : SharedAuthGateIntent
}

sealed interface SharedAuthGateState {
    data object Restoring : SharedAuthGateState
    data object AuthRequired : SharedAuthGateState
    data class EmailVerificationRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class ProfileSetupRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class ServerRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class Ready(
        val session: StoredAuthSession,
        val phase: SharedAuthReadyPhase = SharedAuthReadyPhase.Confirmed
    ) : SharedAuthGateState
    data class BootstrapRecoveryRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class Error(val message: String) : SharedAuthGateState
}

enum class SharedAuthReadyPhase {
    Confirmed,
    CachedRestorePending,
    CachedRestoreCompleted
}

private fun SharedAuthGateState.withCompletedCachedRestore(): SharedAuthGateState =
    if (this is SharedAuthGateState.Ready) {
        copy(phase = SharedAuthReadyPhase.CachedRestoreCompleted)
    } else {
        this
    }

private fun SharedAuthGateState.Ready.completedAfterCachedRestore(): SharedAuthGateState.Ready =
    if (phase == SharedAuthReadyPhase.CachedRestorePending) {
        copy(phase = SharedAuthReadyPhase.CachedRestoreCompleted)
    } else {
        this
    }

private fun StoredAuthSession.asGateState(): SharedAuthGateState =
    if (qaBootstrap != null && !qaBootstrapLaunchAuthorized) {
        SharedAuthGateState.BootstrapRecoveryRequired(this)
    } else if (user.emailVerified == false) {
        SharedAuthGateState.EmailVerificationRequired(this)
    } else if (user.requiresProfileSetup) {
        SharedAuthGateState.ProfileSetupRequired(this)
    } else if (selectedServerId.isNullOrBlank()) {
        SharedAuthGateState.ServerRequired(this)
    } else {
        SharedAuthGateState.Ready(this)
    }

internal fun StoredAuthSession.isColdStartCachedReadyEligible(): Boolean =
    qaBootstrap == null &&
    user.id.isNotBlank() &&
        accessToken.isNotBlank() &&
        !selectedServerId.isNullOrBlank() &&
        servers.any { it.id == selectedServerId } &&
        asGateState() is SharedAuthGateState.Ready

private val SharedAuthGateIntent.logSource: String
    get() =
        when (this) {
            SharedAuthGateIntent.Restore -> "restore"
            SharedAuthGateIntent.Retry -> "retry"
            SharedAuthGateIntent.RefreshSilently -> "refresh_silently"
            SharedAuthGateIntent.ShowSignIn -> "show_sign_in"
            SharedAuthGateIntent.SignedOut -> "signed_out"
            is SharedAuthGateIntent.SignedIn -> "signed_in"
            is SharedAuthGateIntent.ServerReady -> "server_ready"
            is SharedAuthGateIntent.ResumeQaBootstrap -> "qa_bootstrap_resume"
        }

private fun SharedAuthGateState.diagnosticsName(): String =
    when (this) {
        SharedAuthGateState.Restoring -> "restoring"
        SharedAuthGateState.AuthRequired -> "auth_required"
        is SharedAuthGateState.EmailVerificationRequired -> "email_verification_required"
        is SharedAuthGateState.ProfileSetupRequired -> "profile_setup_required"
        is SharedAuthGateState.ServerRequired -> "server_required"
        is SharedAuthGateState.Ready -> "ready"
        is SharedAuthGateState.BootstrapRecoveryRequired -> "qa_bootstrap_recovery"
        is SharedAuthGateState.Error -> "error"
    }

private fun SharedAuthGateState.hasSelectedServer(): Boolean =
    when (this) {
        is SharedAuthGateState.Ready -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.ServerRequired -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.EmailVerificationRequired -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.ProfileSetupRequired -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.BootstrapRecoveryRequired -> false
        SharedAuthGateState.AuthRequired,
        is SharedAuthGateState.Error,
        SharedAuthGateState.Restoring -> false
    }

private fun Throwable.safeAuthGateDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

private fun String.authGateDiagnosticsToken(): String =
    trim()
        .replace(Regex("\\s+"), "_")
        .take(64)
        .ifBlank { "blank" }

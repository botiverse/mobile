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
            is SharedAuthGateIntent.SignedIn ->
                setExplicitGateState(intent.session.asGateState(), source = "signed_in")
            is SharedAuthGateIntent.ServerReady ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session), source = "server_ready")
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
            is SharedAuthGateIntent.SignedIn ->
                setExplicitGateState(intent.session.asGateState(), source = "signed_in")
            is SharedAuthGateIntent.ServerReady ->
                setExplicitGateState(SharedAuthGateState.Ready(intent.session), source = "server_ready")
            SharedAuthGateIntent.ShowSignIn,
            SharedAuthGateIntent.SignedOut ->
                setExplicitGateState(SharedAuthGateState.AuthRequired, source = intent.logSource)
        }
    }

    private suspend fun restore() {
        setGateState(SharedAuthGateState.Restoring, source = "restore_start")
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

    private fun StoredAuthSession.asGateState(): SharedAuthGateState =
        if (user.emailVerified == false) {
            SharedAuthGateState.EmailVerificationRequired(this)
        } else if (selectedServerId.isNullOrBlank()) {
            SharedAuthGateState.ServerRequired(this)
        } else {
            SharedAuthGateState.Ready(this)
        }
}

class SharedAuthGateViewModel(
    private val authDataSource: SharedAuthDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedAuthGateStore(
                restoreSession = { authDataSource.restoreSession() },
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
    data object ShowSignIn : SharedAuthGateIntent
    data object SignedOut : SharedAuthGateIntent
    data class SignedIn(val session: StoredAuthSession) : SharedAuthGateIntent
    data class ServerReady(val session: StoredAuthSession) : SharedAuthGateIntent
}

sealed interface SharedAuthGateState {
    data object Restoring : SharedAuthGateState
    data object AuthRequired : SharedAuthGateState
    data class EmailVerificationRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class ServerRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class Ready(val session: StoredAuthSession) : SharedAuthGateState
    data class Error(val message: String) : SharedAuthGateState
}

private val SharedAuthGateIntent.logSource: String
    get() =
        when (this) {
            SharedAuthGateIntent.Restore -> "restore"
            SharedAuthGateIntent.Retry -> "retry"
            SharedAuthGateIntent.ShowSignIn -> "show_sign_in"
            SharedAuthGateIntent.SignedOut -> "signed_out"
            is SharedAuthGateIntent.SignedIn -> "signed_in"
            is SharedAuthGateIntent.ServerReady -> "server_ready"
        }

private fun SharedAuthGateState.diagnosticsName(): String =
    when (this) {
        SharedAuthGateState.Restoring -> "restoring"
        SharedAuthGateState.AuthRequired -> "auth_required"
        is SharedAuthGateState.EmailVerificationRequired -> "email_verification_required"
        is SharedAuthGateState.ServerRequired -> "server_required"
        is SharedAuthGateState.Ready -> "ready"
        is SharedAuthGateState.Error -> "error"
    }

private fun SharedAuthGateState.hasSelectedServer(): Boolean =
    when (this) {
        is SharedAuthGateState.Ready -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.ServerRequired -> !session.selectedServerId.isNullOrBlank()
        is SharedAuthGateState.EmailVerificationRequired -> !session.selectedServerId.isNullOrBlank()
        SharedAuthGateState.AuthRequired,
        is SharedAuthGateState.Error,
        SharedAuthGateState.Restoring -> false
    }

private fun Throwable.safeAuthGateDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

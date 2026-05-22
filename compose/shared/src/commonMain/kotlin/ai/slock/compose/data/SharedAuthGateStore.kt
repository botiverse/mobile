package ai.slock.compose.data

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
    private val scope: CoroutineScope
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
                _state.value = intent.session.asGateState()
            is SharedAuthGateIntent.ServerReady ->
                _state.value = SharedAuthGateState.Ready(intent.session)
            SharedAuthGateIntent.ShowSignIn ->
                _state.value = SharedAuthGateState.AuthRequired
            SharedAuthGateIntent.SignedOut ->
                _state.value = SharedAuthGateState.AuthRequired
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
                _state.value = intent.session.asGateState()
            is SharedAuthGateIntent.ServerReady ->
                _state.value = SharedAuthGateState.Ready(intent.session)
            SharedAuthGateIntent.ShowSignIn,
            SharedAuthGateIntent.SignedOut ->
                _state.value = SharedAuthGateState.AuthRequired
        }
    }

    private suspend fun restore() {
        _state.value = SharedAuthGateState.Restoring
        try {
            _state.value =
                when (val result = restoreSession()) {
                    is SharedAuthResult.Success -> result.data.asGateState()
                    SharedAuthResult.Unauthenticated -> SharedAuthGateState.AuthRequired
                    is SharedAuthResult.Failure -> SharedAuthGateState.Error(result.message)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            _state.value = SharedAuthGateState.Error("Network unavailable. Check your connection.")
        }
    }

    private fun StoredAuthSession.asGateState(): SharedAuthGateState =
        if (selectedServerId.isNullOrBlank()) {
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
                scope = viewModelScope
            )
        }
    private val store: SharedAuthGateStore by storeDelegate
    val state: StateFlow<SharedAuthGateState>
        get() = store.state

    fun dispatch(intent: SharedAuthGateIntent) {
        store.dispatch(intent)
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
    data class ServerRequired(val session: StoredAuthSession) : SharedAuthGateState
    data class Ready(val session: StoredAuthSession) : SharedAuthGateState
    data class Error(val message: String) : SharedAuthGateState
}

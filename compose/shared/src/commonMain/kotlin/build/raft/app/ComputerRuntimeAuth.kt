package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthLogoutResult
import build.raft.app.data.RuntimeAuthProvider
import build.raft.app.data.RuntimeAuthProviderConnectResult
import build.raft.app.data.RuntimeAuthProviderKeyEntry
import build.raft.app.data.RuntimeAuthProviderOAuthFlow
import build.raft.app.data.RuntimeAuthProvidersResult
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRealtimeDomainEventOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.isPiLiteralApiKey
import build.raft.app.data.isRuntimeAuthPastedCode
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RuntimeAuthAccountLabel
import build.raft.app.model.RuntimeAuthLoginEventPushed
import build.raft.app.model.RuntimeAuthLoginOutcome
import build.raft.app.model.RuntimeAuthLoginResultPushed
import build.raft.app.model.RuntimeAuthLoginStep
import build.raft.app.model.RuntimeAuthUpdated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Signing a Computer's runtimes in and out from its runtime cards, as the web does (botiverse/slock
 * staging 853c6998bcef45df5c25e63b1035e2388608721d: packages/web/src/components/machine/
 * RuntimeAccountUsageChip.tsx + RuntimeUsageSurface.tsx, features/computer/RuntimeSignInDialog.tsx,
 * RuntimeSignOutDialog.tsx, PiSignInDialog.tsx, providerConnectFailure.ts; contract
 * docs/contracts/runtime-auth.md). The login runs on the Computer; the app only shows its link or
 * device code, relays a pasted code, and reads the result from this person's sockets.
 *
 * Qwen Code's provider connect is not offered here yet: its badge stays as it was.
 */

/** What the runtime cards need from the shell to sign runtimes in: the socket's events and a browser. */
internal class ComputerRuntimeAuthHost(
    /** Where `machine:runtime_auth:updated` and this person's login pushes arrive; null shows no live updates. */
    val realtime: SharedRealtimeDomainEventOwner?,
    /** Opens a sign-in page in the browser. */
    val openUrl: (String) -> Unit,
)

/** The runtime-auth calls of one Computer for one signed-in person (the store, bound). */
internal interface ComputerRuntimeAuthOps {
    suspend fun view(force: Boolean): RuntimeAuthView?

    suspend fun invalidate()

    suspend fun startLogin(runtime: RuntimeAuthRuntime, region: RuntimeAuthLoginRegion?, providerId: String?): RuntimeAuthCall<String>

    suspend fun submitCode(runtime: RuntimeAuthRuntime, loginId: String, code: String): RuntimeAuthCall<Unit>

    suspend fun cancelLogin(runtime: RuntimeAuthRuntime, loginId: String)

    suspend fun logout(runtime: RuntimeAuthRuntime, providerId: String?): RuntimeAuthCall<RuntimeAuthLogoutResult>

    suspend fun providers(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeAuthProvidersResult>

    suspend fun connect(runtime: RuntimeAuthRuntime, providerId: String, apiKey: String): RuntimeAuthCall<RuntimeAuthProviderConnectResult>
}

internal class StoreComputerRuntimeAuthOps(
    private val store: SharedRuntimeAccountUsageStore,
    private val owner: SharedComputersOwner,
    private val serverId: String,
    private val machineId: String,
) : ComputerRuntimeAuthOps {
    override suspend fun view(force: Boolean) = store.runtimeAuthView(owner, serverId, machineId, force)

    override suspend fun invalidate() = store.invalidateRuntimeAuthView(machineId)

    override suspend fun startLogin(runtime: RuntimeAuthRuntime, region: RuntimeAuthLoginRegion?, providerId: String?) =
        store.startRuntimeLogin(owner, serverId, machineId, runtime, region, providerId)

    override suspend fun submitCode(runtime: RuntimeAuthRuntime, loginId: String, code: String) =
        store.submitRuntimeLoginCode(owner, serverId, machineId, runtime, loginId, code)

    override suspend fun cancelLogin(runtime: RuntimeAuthRuntime, loginId: String) =
        store.cancelRuntimeLogin(owner, serverId, machineId, runtime, loginId)

    override suspend fun logout(runtime: RuntimeAuthRuntime, providerId: String?) =
        store.logoutRuntime(owner, serverId, machineId, runtime, providerId)

    override suspend fun providers(runtime: RuntimeAuthRuntime) = store.runtimeAuthProviders(owner, serverId, machineId, runtime)

    override suspend fun connect(runtime: RuntimeAuthRuntime, providerId: String, apiKey: String) =
        store.connectRuntimeProvider(owner, serverId, machineId, runtime, providerId, apiKey)
}

/**
 * The sign-in runtime a runtime card offers for a Raft runtime (web `runtimeAuthRuntimeFor`), or
 * null. Qwen Code's provider connect is not on mobile yet, so its badge keeps no sign-in.
 */
internal fun computerRuntimeCardAuthRuntime(raftRuntimeId: String): RuntimeAuthRuntime? =
    RuntimeAuthRuntime.forRaftRuntime(raftRuntimeId)?.takeUnless { it.hasProviderKey }

/** Why a sign-in, a sign-out or a provider step stopped, in the web's words (one message each). */
internal enum class ComputerRuntimeAuthProblem {
    ComputerOffline,
    UpgradeRequired,
    NotAttached,
    Busy,
    PiBusy,
    RateLimited,
    Forbidden,
    NotFound,
    StartFailed,
    LoginGone,
    Cancelled,
    VersionUnsupported,
    NotInstalled,
    UnsupportedInstallation,
    TimedOut,
    Rejected,
    NotLoggedIn,
    InteractionFailed,
    ProcessFailed,
    SignOutStillLoggedIn,
    SignOutStillLoggedInAntigravity,
    SignOutStillLoggedInGrok,
    SignOutTimedOut,
    SignOutFailed,
    ProviderUpgradeRequired,
    ProviderNotAttached,
    ProviderForbidden,
    ProviderRateLimited,
    ProviderComputerTimeout,
    ProviderRequestFailed,
    ProviderInvalidInput,
    ProviderRejected,
    ProviderListTimedOut,
    ProviderConnectTimedOut,
    ProviderProcessFailed,
    ProviderInvalidResponse,
    ProviderUnsupported,
    ProviderBusy,
}

/** A stopped step: what to say, whether starting over can help, and the failed request's trace id. */
internal data class ComputerRuntimeAuthFailure(
    val problem: ComputerRuntimeAuthProblem,
    val retryable: Boolean,
    val traceId: String? = null,
)

/** Web `startFailure`: the start request was refused. */
internal fun RuntimeAuthRequestError.signInStartFailure(perProvider: Boolean): ComputerRuntimeAuthFailure {
    val (problem, retryable) =
        when {
            code == "computer_offline" -> ComputerRuntimeAuthProblem.ComputerOffline to true
            code == "computer_upgrade_required" -> ComputerRuntimeAuthProblem.UpgradeRequired to false
            code == "computer_not_attached" -> ComputerRuntimeAuthProblem.NotAttached to false
            code == "login_in_progress" -> (if (perProvider) ComputerRuntimeAuthProblem.PiBusy else ComputerRuntimeAuthProblem.Busy) to true
            code == "rate_limited" || status == 429 -> ComputerRuntimeAuthProblem.RateLimited to true
            status == 403 -> ComputerRuntimeAuthProblem.Forbidden to false
            status == 404 -> ComputerRuntimeAuthProblem.NotFound to false
            else -> ComputerRuntimeAuthProblem.StartFailed to true
        }
    return ComputerRuntimeAuthFailure(problem, retryable, traceId)
}

/** Web `resultFailure`: the Computer ended the login without signing in. */
internal fun RuntimeAuthLoginOutcome.signInResultFailure(perProvider: Boolean): ComputerRuntimeAuthFailure? =
    when (this) {
        is RuntimeAuthLoginOutcome.LoggedIn -> null
        RuntimeAuthLoginOutcome.Busy ->
            ComputerRuntimeAuthFailure(if (perProvider) ComputerRuntimeAuthProblem.PiBusy else ComputerRuntimeAuthProblem.Busy, true)
        RuntimeAuthLoginOutcome.Cancelled -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.Cancelled, true)
        is RuntimeAuthLoginOutcome.Unsupported -> ComputerRuntimeAuthFailure(RuntimeAuthUnavailableReason.fromWire(reason).unsupportedProblem(), false)
        is RuntimeAuthLoginOutcome.Failed ->
            when (reason) {
                "timed_out" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.TimedOut, true)
                "rejected" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.Rejected, true)
                "not_logged_in" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.NotLoggedIn, true)
                "interaction_failed" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.InteractionFailed, false)
                else -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProcessFailed, true)
            }
    }

private fun RuntimeAuthUnavailableReason?.unsupportedProblem(): ComputerRuntimeAuthProblem =
    when (this) {
        RuntimeAuthUnavailableReason.VersionUnsupported -> ComputerRuntimeAuthProblem.VersionUnsupported
        RuntimeAuthUnavailableReason.NotInstalled -> ComputerRuntimeAuthProblem.NotInstalled
        else -> ComputerRuntimeAuthProblem.UnsupportedInstallation
    }

/** Web `logoutFailureMessageId`: still signed in after the logout is the Computer's to fix, never "try again". */
internal fun RuntimeAuthLogoutResult.signOutProblem(runtime: RuntimeAuthRuntime): ComputerRuntimeAuthProblem? =
    when (this) {
        RuntimeAuthLogoutResult.LoggedOut -> null
        is RuntimeAuthLogoutResult.Unsupported -> reason.unsupportedProblem()
        is RuntimeAuthLogoutResult.Failed ->
            when (reason) {
                "still_logged_in" ->
                    when (runtime) {
                        RuntimeAuthRuntime.Antigravity -> ComputerRuntimeAuthProblem.SignOutStillLoggedInAntigravity
                        RuntimeAuthRuntime.Grok -> ComputerRuntimeAuthProblem.SignOutStillLoggedInGrok
                        else -> ComputerRuntimeAuthProblem.SignOutStillLoggedIn
                    }
                "timed_out" -> ComputerRuntimeAuthProblem.SignOutTimedOut
                else -> ComputerRuntimeAuthProblem.SignOutFailed
            }
    }

/** Web `logoutRequestMessageId`. */
internal fun RuntimeAuthRequestError.signOutProblem(): ComputerRuntimeAuthProblem =
    when {
        code == "computer_offline" -> ComputerRuntimeAuthProblem.ComputerOffline
        code == "computer_upgrade_required" -> ComputerRuntimeAuthProblem.UpgradeRequired
        code == "computer_not_attached" -> ComputerRuntimeAuthProblem.NotAttached
        code == "computer_timeout" || status == 504 -> ComputerRuntimeAuthProblem.SignOutTimedOut
        status == 403 -> ComputerRuntimeAuthProblem.Forbidden
        else -> ComputerRuntimeAuthProblem.SignOutFailed
    }

/** Web `providerRequestFailure`: null for a refused API key (the field says so instead). */
internal fun RuntimeAuthRequestError.providerFailure(connect: Boolean): ComputerRuntimeAuthFailure? {
    val (problem, retryable) =
        when {
            code == "invalid_api_key" -> return null
            code == "computer_offline" -> ComputerRuntimeAuthProblem.ComputerOffline to true
            code == "computer_upgrade_required" -> ComputerRuntimeAuthProblem.ProviderUpgradeRequired to false
            code == "computer_not_attached" -> ComputerRuntimeAuthProblem.ProviderNotAttached to false
            code == "computer_timeout" || status == 504 ->
                (if (connect) ComputerRuntimeAuthProblem.ProviderConnectTimedOut else ComputerRuntimeAuthProblem.ProviderComputerTimeout) to true
            code == "rate_limited" || status == 429 -> ComputerRuntimeAuthProblem.ProviderRateLimited to true
            status == 400 -> ComputerRuntimeAuthProblem.ProviderInvalidInput to true
            status == 403 -> ComputerRuntimeAuthProblem.ProviderForbidden to false
            status == 404 -> ComputerRuntimeAuthProblem.NotFound to false
            else -> ComputerRuntimeAuthProblem.ProviderRequestFailed to true
        }
    return ComputerRuntimeAuthFailure(problem, retryable, traceId)
}

/** Web `providerResultFailure`: the Computer answered without a list or a save. */
internal fun providerResultFailure(
    failedReason: String?,
    unsupported: RuntimeAuthUnavailableReason?,
    busy: Boolean,
    connect: Boolean,
): ComputerRuntimeAuthFailure =
    when {
        busy -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderBusy, true)
        unsupported == RuntimeAuthUnavailableReason.NotInstalled -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.NotInstalled, false)
        unsupported != null -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderUnsupported, false)
        failedReason == "rejected" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderRejected, true)
        failedReason == "timed_out" ->
            ComputerRuntimeAuthFailure(if (connect) ComputerRuntimeAuthProblem.ProviderConnectTimedOut else ComputerRuntimeAuthProblem.ProviderListTimedOut, true)
        failedReason == "process_failed" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderProcessFailed, true)
        failedReason == "invalid_response" -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderInvalidResponse, false)
        else -> ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderRequestFailed, true)
    }

/** The Pi provider a sign-in is for: named after its account sign-in, and what its paste step takes. */
internal data class ComputerRuntimeSignInProvider(
    val id: String,
    val label: String,
    /** `paste_code` / `paste_address`: what the code field asks for; null for a device code. */
    val pastesAddress: Boolean = false,
)

/** Web `CodeState`: `Invalid` the server refused the value; `SendFailed` it never got there (kept to resend). */
internal enum class ComputerRuntimeSignInCodeState { Idle, Submitting, Sent, Invalid, SendFailed }

/** Web `SignInState`. */
internal data class ComputerRuntimeSignInState(
    /** A runtime whose login needs a region (Kimi) asks for it first, with nothing chosen. */
    val choosingRegion: Boolean = false,
    val region: RuntimeAuthLoginRegion? = null,
    /** True once the Computer sent its first event; until then the dialog says it is starting. */
    val started: Boolean = false,
    val authUrl: String? = null,
    val deviceCode: RuntimeAuthLoginStep.DeviceCode? = null,
    val info: String? = null,
    val codePrompt: RuntimeAuthLoginStep.CodePrompt? = null,
    /** The pasted code, until it is sent or the login ends. Never logged. */
    val code: String = "",
    val codeState: ComputerRuntimeSignInCodeState = ComputerRuntimeSignInCodeState.Idle,
    /** Signed in; the account, when the Computer named one. */
    val done: Boolean = false,
    val account: RuntimeAuthAccountLabel? = null,
    val failure: ComputerRuntimeAuthFailure? = null,
) {
    val ended: Boolean
        get() = done || failure != null

    /** The runtime did not take the last code: the field says so until the person types again. */
    val codeRejected: Boolean
        get() =
            codeState == ComputerRuntimeSignInCodeState.Invalid ||
                (codeState == ComputerRuntimeSignInCodeState.Idle && codePrompt?.retry == true)

    /** Web: one pink button per screen; Open Sign-in Page leads until a code is pasted. */
    val codeEntered: Boolean
        get() = codePrompt != null && code.isNotBlank()

    fun withStep(step: RuntimeAuthLoginStep): ComputerRuntimeSignInState {
        if (ended) return this
        val next = copy(started = true)
        return when (step) {
            is RuntimeAuthLoginStep.AuthUrl -> next.copy(authUrl = step.url)
            is RuntimeAuthLoginStep.DeviceCode -> next.copy(deviceCode = step)
            is RuntimeAuthLoginStep.Info -> next.copy(info = step.message)
            // A new prompt (first, or after a code the runtime did not accept) asks again.
            is RuntimeAuthLoginStep.CodePrompt -> next.copy(codePrompt = step, codeState = ComputerRuntimeSignInCodeState.Idle)
        }
    }

    fun withOutcome(outcome: RuntimeAuthLoginOutcome, perProvider: Boolean): ComputerRuntimeSignInState {
        if (ended) return this
        return if (outcome is RuntimeAuthLoginOutcome.LoggedIn) {
            copy(started = true, code = "", done = true, account = outcome.account)
        } else {
            copy(started = true, code = "", failure = outcome.signInResultFailure(perProvider))
        }
    }
}

/** Keeps at most this many events that arrive before the start request answers (web `EARLY_EVENT_LIMIT`). */
internal const val COMPUTER_RUNTIME_SIGN_IN_EARLY_EVENT_LIMIT = 16

/**
 * One sign-in dialog (web `RuntimeSignInDialog`): starts the login once (and again from Try Again),
 * keeps the events that arrive before its id and replays only that login's, drops every other
 * login's, and cancels a running login when it closes — a login that started after it closed is
 * cancelled when its id arrives.
 */
internal class ComputerRuntimeSignInSession(
    val serverId: String,
    val machineId: String,
    val runtime: RuntimeAuthRuntime,
    /** Pi: the provider to sign in; null for every other runtime. */
    val provider: ComputerRuntimeSignInProvider?,
    private val ops: ComputerRuntimeAuthOps,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onSuperseded: () -> Unit = {},
) {
    var state by mutableStateOf(ComputerRuntimeSignInState(choosingRegion = runtime.regionChoices.isNotEmpty()))
        private set

    private class Shown(val id: String, var running: Boolean)

    private var shown: Shown? = null
    private var early: MutableList<RealtimeEvent>? = null
    private var attempt = 0
    private var closed = false
    private var startedRegion: RuntimeAuthLoginRegion? = null

    /** Opens the dialog: a runtime that asks no region starts at once. */
    fun open() {
        if (attempt == 0 && runtime.regionChoices.isEmpty()) begin(null)
    }

    fun chooseRegion(region: RuntimeAuthLoginRegion) {
        if (state.choosingRegion && region in runtime.regionChoices) state = state.copy(region = region)
    }

    fun continueWithRegion() {
        val region = state.region ?: return
        if (!state.choosingRegion) return
        startedRegion = region
        state = ComputerRuntimeSignInState(region = region)
        begin(region)
    }

    /** Try Again starts over in the same region. */
    fun retry() {
        if (closed) return
        state = ComputerRuntimeSignInState(region = startedRegion)
        begin(startedRegion)
    }

    private fun begin(region: RuntimeAuthLoginRegion?) {
        val current = ++attempt
        shown = null
        early = mutableListOf()
        scope.launch {
            val result = ops.startLogin(runtime, region, provider?.id)
            if (attempt != current) return@launch
            val pending = early.orEmpty()
            early = null
            log("computer_runtime_sign_in_start runtime=${runtime.wire} result=${result.diagnosticsLabel()}")
            when (result) {
                is RuntimeAuthCall.Done -> {
                    val loginId = result.value
                    if (closed) {
                        // Closed while starting: the login began anyway, so end it.
                        ops.cancelLogin(runtime, loginId)
                        return@launch
                    }
                    shown = Shown(loginId, running = true)
                    pending.forEach(::route)
                }
                is RuntimeAuthCall.Refused ->
                    if (!closed) state = state.copy(code = "", failure = result.error.signInStartFailure(runtime.perProvider))
                RuntimeAuthCall.Superseded -> onSuperseded()
            }
        }
    }

    /** A realtime push; only this login's are shown. */
    fun onRealtime(event: RealtimeEvent) {
        val (eventServer, eventMachine, eventRuntime) =
            when (event) {
                is RuntimeAuthLoginEventPushed -> Triple(event.serverId, event.machineId, event.runtime)
                is RuntimeAuthLoginResultPushed -> Triple(event.serverId, event.machineId, event.runtime)
                else -> return
            }
        if (eventServer != serverId || eventMachine != machineId || eventRuntime != runtime.wire) return
        if (shown == null) {
            early?.takeIf { it.size < COMPUTER_RUNTIME_SIGN_IN_EARLY_EVENT_LIMIT }?.add(event)
            return
        }
        route(event)
    }

    private fun route(event: RealtimeEvent) {
        val login = shown ?: return
        when (event) {
            is RuntimeAuthLoginEventPushed -> if (event.loginId == login.id) state = state.withStep(event.event)
            is RuntimeAuthLoginResultPushed ->
                if (event.loginId == login.id) {
                    login.running = false
                    state = state.withOutcome(event.result, runtime.perProvider)
                    log("computer_runtime_sign_in_result runtime=${runtime.wire} outcome=${event.result.diagnosticsLabel()}")
                    scope.launch { ops.invalidate() }
                }
            else -> Unit
        }
    }

    fun codeChanged(code: String) {
        val current = state
        state =
            current.copy(
                code = code,
                codeState = if (current.codeState == ComputerRuntimeSignInCodeState.Submitting) current.codeState else ComputerRuntimeSignInCodeState.Idle,
            )
    }

    fun submitCode() {
        val trimmed = state.code.trim()
        val login = shown
        if (login == null || !login.running || trimmed.isEmpty() || state.codeState == ComputerRuntimeSignInCodeState.Submitting) return
        if (!isRuntimeAuthPastedCode(trimmed)) {
            state = state.copy(codeState = ComputerRuntimeSignInCodeState.Invalid)
            return
        }
        state = state.copy(codeState = ComputerRuntimeSignInCodeState.Submitting)
        scope.launch {
            when (val result = ops.submitCode(runtime, login.id, trimmed)) {
                // Sent: the code has left the app, so it is not kept.
                is RuntimeAuthCall.Done -> state = state.copy(codeState = ComputerRuntimeSignInCodeState.Sent, code = "")
                RuntimeAuthCall.Superseded -> onSuperseded()
                is RuntimeAuthCall.Refused -> {
                    // Only the status and the server's code are read: never the request or the value.
                    val error = result.error
                    when {
                        error.code == "invalid_code" || error.status == 400 ->
                            state = state.copy(codeState = ComputerRuntimeSignInCodeState.Invalid)
                        error.status == 404 -> {
                            login.running = false
                            state = state.copy(code = "", failure = ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.LoginGone, true, error.traceId))
                        }
                        error.code == "computer_offline" -> {
                            // An offline Computer has already ended the login on its side.
                            login.running = false
                            state = state.copy(code = "", failure = ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ComputerOffline, true, error.traceId))
                        }
                        else -> state = state.copy(codeState = ComputerRuntimeSignInCodeState.SendFailed)
                    }
                }
            }
        }
    }

    /** Closing it, by any route, cancels a running login. */
    fun close() {
        if (closed) return
        closed = true
        val login = shown
        if (login != null && login.running) {
            login.running = false
            scope.launch { ops.cancelLogin(runtime, login.id) }
        }
    }
}

/** The open sign-out confirm (web `RuntimeSignOutDialog`): one runtime, or one Pi provider. */
internal data class ComputerRuntimeSignOutState(
    val provider: RuntimeAuthSignedInProvider? = null,
    val submitting: Boolean = false,
    val problem: ComputerRuntimeAuthProblem? = null,
    val traceId: String? = null,
)

/** Web `PiSignInDialog`'s list. */
internal sealed interface ComputerRuntimePiProviderList {
    data object Loading : ComputerRuntimePiProviderList

    data class Failed(val failure: ComputerRuntimeAuthFailure) : ComputerRuntimePiProviderList

    data class Listed(val providers: List<RuntimeAuthProvider>) : ComputerRuntimePiProviderList
}

/** A row of Pi's Add Provider: an account sign-in, or an API key, of one provider. */
internal data class ComputerRuntimePiChoice(val account: Boolean, val providerId: String) {
    val value: String
        get() = (if (account) "account:" else "key:") + providerId
}

internal enum class ComputerRuntimePiStep { Choose, Key, Done }

/**
 * Pi's Add Provider (web `PiSignInDialog`): every provider pi lists on the Computer, an account
 * (the sign-in dialog takes over) or an API key typed here. The key lives in [apiKey] until it is
 * sent, and is dropped once saved or when the dialog closes; it is never logged.
 */
internal class ComputerRuntimePiSession(
    private val runtime: RuntimeAuthRuntime,
    private val ops: ComputerRuntimeAuthOps,
    private val scope: CoroutineScope,
    private val onAccount: (ComputerRuntimeSignInProvider) -> Unit,
    private val log: (String) -> Unit = {},
    private val onSuperseded: () -> Unit = {},
) {
    var list by mutableStateOf<ComputerRuntimePiProviderList>(ComputerRuntimePiProviderList.Loading)
        private set
    var chosen by mutableStateOf<ComputerRuntimePiChoice?>(null)
        private set
    var step by mutableStateOf(ComputerRuntimePiStep.Choose)
        private set
    var apiKey by mutableStateOf("")
        private set
    var connecting by mutableStateOf(false)
        private set
    var failure by mutableStateOf<ComputerRuntimeAuthFailure?>(null)
        private set
    var keyRefusedByServer by mutableStateOf(false)
        private set
    private var listAttempt = 0
    private var closed = false

    /** Pi would run a key starting with ! and fill in a $ name: said at once, never sent. */
    val keyRefusedHere: Boolean
        get() = apiKey.trim().let { it.isNotEmpty() && !isPiLiteralApiKey(it) }

    val chosenProvider: RuntimeAuthProvider?
        get() = chosen?.let { choice -> (list as? ComputerRuntimePiProviderList.Listed)?.providers?.firstOrNull { it.id == choice.providerId } }

    fun load() {
        val attempt = ++listAttempt
        list = ComputerRuntimePiProviderList.Loading
        scope.launch {
            val result = ops.providers(runtime)
            if (closed || attempt != listAttempt) return@launch
            log("computer_runtime_pi_providers result=${result.diagnosticsLabel()}")
            list =
                when (result) {
                    is RuntimeAuthCall.Done ->
                        when (val value = result.value) {
                            is RuntimeAuthProvidersResult.Listed -> ComputerRuntimePiProviderList.Listed(value.providers)
                            is RuntimeAuthProvidersResult.Failed ->
                                ComputerRuntimePiProviderList.Failed(providerResultFailure(value.reason, null, busy = false, connect = false))
                            is RuntimeAuthProvidersResult.Unsupported ->
                                ComputerRuntimePiProviderList.Failed(providerResultFailure(null, value.reason, busy = false, connect = false))
                        }
                    is RuntimeAuthCall.Refused ->
                        ComputerRuntimePiProviderList.Failed(
                            result.error.providerFailure(connect = false)
                                ?: ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ProviderInvalidInput, true, result.error.traceId)
                        )
                    RuntimeAuthCall.Superseded -> {
                        onSuperseded()
                        return@launch
                    }
                }
        }
    }

    fun choose(choice: ComputerRuntimePiChoice) {
        chosen = choice
    }

    fun continueWithChoice() {
        val choice = chosen ?: return
        val provider = chosenProvider ?: return
        failure = null
        keyRefusedByServer = false
        apiKey = ""
        if (choice.account) {
            val flow = provider.oauthFlow ?: return
            if (flow == RuntimeAuthProviderOAuthFlow.ComputerOnly) return
            onAccount(
                ComputerRuntimeSignInProvider(
                    id = provider.id,
                    label = provider.oauthLabel ?: provider.label,
                    pastesAddress = flow == RuntimeAuthProviderOAuthFlow.PasteAddress,
                )
            )
        } else {
            if (provider.apiKeyEntry != RuntimeAuthProviderKeyEntry.Key) return
            step = ComputerRuntimePiStep.Key
        }
    }

    fun back() {
        if (connecting) return
        failure = null
        keyRefusedByServer = false
        apiKey = ""
        step = ComputerRuntimePiStep.Choose
    }

    fun apiKeyChanged(value: String) {
        apiKey = value
        keyRefusedByServer = false
    }

    fun connect() {
        val choice = chosen ?: return
        val key = apiKey.trim()
        if (choice.account || key.isEmpty() || keyRefusedHere || connecting) return
        connecting = true
        failure = null
        keyRefusedByServer = false
        scope.launch {
            val result = ops.connect(runtime, choice.providerId, key)
            if (closed) return@launch
            connecting = false
            when (result) {
                is RuntimeAuthCall.Done ->
                    when (val value = result.value) {
                        RuntimeAuthProviderConnectResult.Connected -> {
                            apiKey = ""
                            step = ComputerRuntimePiStep.Done
                        }
                        RuntimeAuthProviderConnectResult.Busy -> failure = providerResultFailure(null, null, busy = true, connect = true)
                        is RuntimeAuthProviderConnectResult.Failed -> failure = providerResultFailure(value.reason, null, busy = false, connect = true)
                        is RuntimeAuthProviderConnectResult.Unsupported -> failure = providerResultFailure(null, value.reason, busy = false, connect = true)
                    }
                is RuntimeAuthCall.Refused -> {
                    val refused = result.error.providerFailure(connect = true)
                    if (refused == null) keyRefusedByServer = true else failure = refused
                }
                RuntimeAuthCall.Superseded -> onSuperseded()
            }
        }
    }

    /** The key goes with the dialog. */
    fun close() {
        closed = true
        apiKey = ""
    }
}

/** The card's open dialog. */
internal sealed interface ComputerRuntimeAuthDialog {
    data class SignIn(val session: ComputerRuntimeSignInSession) : ComputerRuntimeAuthDialog

    data class SignOut(val state: ComputerRuntimeSignOutState) : ComputerRuntimeAuthDialog

    data class AddProvider(val session: ComputerRuntimePiSession) : ComputerRuntimeAuthDialog
}

/**
 * One runtime card's sign-in (web `useRuntimeUsageCard`'s auth half): the Computer's runtime-auth
 * view (read through the store's 30 s cache, again on `machine:runtime_auth:updated` and after a
 * login or logout; an older read that lands after a newer one started is dropped), this runtime's
 * status, and the open dialog.
 */
internal class ComputerRuntimeAuthController(
    val serverId: String,
    val machineId: String,
    val raftRuntimeId: String,
    val runtime: RuntimeAuthRuntime,
    private val ops: ComputerRuntimeAuthOps,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onIdentityChanged: () -> Unit = {},
) {
    var view by mutableStateOf<RuntimeAuthView?>(null)
        private set
    var dialog by mutableStateOf<ComputerRuntimeAuthDialog?>(null)
        private set
    private var readSeq = 0

    val status: RuntimeAuthStatus?
        get() = view?.statusOf(runtime)

    /** Signed out on the Computer: Pi with no stored sign-in may still run on a key in the environment. */
    val signedOut: Boolean
        get() = status == RuntimeAuthStatus.LoggedOut && !runtime.perProvider && !runtime.hasProviderKey

    /** A server-gated runtime (Antigravity) gets a card only once the server lists it for this Computer. */
    val offered: Boolean
        get() = !runtime.serverGated || view?.computerSupports(runtime) == true

    fun read(force: Boolean = false) {
        val seq = ++readSeq
        scope.launch {
            val next = ops.view(force)
            // Not allowed to see it, or the read failed: the card stays as it is.
            if (seq == readSeq && next != null) view = next
        }
    }

    /** `machine:runtime_auth:updated` for this Computer, and this person's login pushes. */
    fun onRealtime(event: RealtimeEvent) {
        when (event) {
            is RuntimeAuthUpdated ->
                if (event.machineId == machineId) {
                    scope.launch {
                        ops.invalidate()
                        read()
                    }
                }
            is RuntimeAuthLoginEventPushed, is RuntimeAuthLoginResultPushed -> {
                (dialog as? ComputerRuntimeAuthDialog.SignIn)?.session?.onRealtime(event)
                if (event is RuntimeAuthLoginResultPushed && event.machineId == machineId && event.serverId == serverId) read(force = true)
            }
            else -> Unit
        }
    }

    fun openSignIn() {
        dialog =
            if (runtime.perProvider) {
                val session =
                    ComputerRuntimePiSession(
                        runtime = runtime,
                        ops = ops,
                        scope = scope,
                        onAccount = { provider -> openProviderSignIn(provider) },
                        log = log,
                        onSuperseded = ::superseded,
                    )
                session.load()
                ComputerRuntimeAuthDialog.AddProvider(session)
            } else {
                ComputerRuntimeAuthDialog.SignIn(newSignIn(null).also { it.open() })
            }
    }

    /** Pi's account: the sign-in dialog takes over, named after the provider's sign-in. */
    private fun openProviderSignIn(provider: ComputerRuntimeSignInProvider) {
        (dialog as? ComputerRuntimeAuthDialog.AddProvider)?.session?.close()
        dialog = ComputerRuntimeAuthDialog.SignIn(newSignIn(provider).also { it.open() })
    }

    private fun newSignIn(provider: ComputerRuntimeSignInProvider?) =
        ComputerRuntimeSignInSession(
            serverId = serverId,
            machineId = machineId,
            runtime = runtime,
            provider = provider,
            ops = ops,
            scope = scope,
            log = log,
            onSuperseded = ::superseded,
        )

    fun openSignOut(provider: RuntimeAuthSignedInProvider? = null) {
        if (runtime.perProvider && provider == null) return
        dialog = ComputerRuntimeAuthDialog.SignOut(ComputerRuntimeSignOutState(provider = provider))
    }

    /** The confirmed Sign Out: a refusal or a result other than signed out keeps the dialog open saying why. */
    fun confirmSignOut() {
        val open = dialog as? ComputerRuntimeAuthDialog.SignOut ?: return
        if (open.state.submitting) return
        dialog = open.copy(state = open.state.copy(submitting = true, problem = null, traceId = null))
        scope.launch {
            val result = ops.logout(runtime, open.state.provider?.id)
            log("computer_runtime_sign_out runtime=${runtime.wire} result=${result.diagnosticsLabel()}")
            val current = dialog as? ComputerRuntimeAuthDialog.SignOut
            read(force = true)
            when (result) {
                RuntimeAuthCall.Superseded -> superseded()
                is RuntimeAuthCall.Refused ->
                    if (current != null) {
                        dialog = current.copy(state = current.state.copy(submitting = false, problem = result.error.signOutProblem(), traceId = result.error.traceId))
                    }
                is RuntimeAuthCall.Done -> {
                    val problem = result.value.signOutProblem(runtime)
                    dialog =
                        when {
                            current == null -> dialog
                            problem == null -> null
                            else -> current.copy(state = current.state.copy(submitting = false, problem = problem))
                        }
                }
            }
        }
    }

    fun closeDialog() {
        when (val open = dialog) {
            is ComputerRuntimeAuthDialog.SignIn -> open.session.close()
            is ComputerRuntimeAuthDialog.AddProvider -> open.session.close()
            is ComputerRuntimeAuthDialog.SignOut -> if (open.state.submitting) return
            null -> Unit
        }
        val signedIn = (dialog as? ComputerRuntimeAuthDialog.SignIn)?.session?.state?.done == true
        val saved = (dialog as? ComputerRuntimeAuthDialog.AddProvider)?.session?.step == ComputerRuntimePiStep.Done
        dialog = null
        if (signedIn || saved) read(force = true)
    }

    /** The page went away: a running login ends too. */
    fun dispose() {
        (dialog as? ComputerRuntimeAuthDialog.SignIn)?.session?.close()
        (dialog as? ComputerRuntimeAuthDialog.AddProvider)?.session?.close()
    }

    private fun superseded() {
        dispose()
        dialog = null
        view = null
        onIdentityChanged()
    }
}

private fun RuntimeAuthCall<*>.diagnosticsLabel(): String =
    when (this) {
        is RuntimeAuthCall.Done -> "done"
        is RuntimeAuthCall.Refused -> "refused:${error.status ?: "none"}:${error.code ?: "none"}"
        RuntimeAuthCall.Superseded -> "superseded"
    }

private fun RuntimeAuthLoginOutcome.diagnosticsLabel(): String =
    when (this) {
        is RuntimeAuthLoginOutcome.LoggedIn -> "logged_in"
        is RuntimeAuthLoginOutcome.Failed -> "failed:$reason"
        RuntimeAuthLoginOutcome.Cancelled -> "cancelled"
        is RuntimeAuthLoginOutcome.Unsupported -> "unsupported:$reason"
        RuntimeAuthLoginOutcome.Busy -> "busy"
    }

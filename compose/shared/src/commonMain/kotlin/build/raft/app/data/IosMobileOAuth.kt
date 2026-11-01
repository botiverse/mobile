package build.raft.app.data

import build.raft.app.RaftHostStrings
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val RAFT_IOS_MOBILE_OAUTH_MMKV_NAMESPACE = "slockIosMobileOAuth"
internal const val RAFT_MOBILE_OAUTH_COMPLETION_EVENT = "slockMobileOAuthCompletion"

private const val IOS_MOBILE_OAUTH_LOG_TAG = "SlockIosOAuth"
private const val IOS_MOBILE_OAUTH_LINK_MODE = "link"
private const val IOS_MOBILE_OAUTH_RELEASE_RETURN_URI = "raft://oauth/callback"
private const val IOS_MOBILE_OAUTH_DEBUG_RETURN_URI = "raft-debug://oauth/callback"
private const val IOS_MOBILE_OAUTH_CALLBACK_HOST = "oauth"
private const val IOS_MOBILE_OAUTH_CALLBACK_PATH = "/callback"
private const val IOS_MOBILE_OAUTH_PENDING_TTL_MILLIS = 10L * 60L * 1000L
private const val IOS_MOBILE_OAUTH_CLOCK_SKEW_MILLIS = 60L * 1000L

private val IosBrowserOAuthProviders = setOf("google", "github")
private val IosMobileOAuthProviders = IosBrowserOAuthProviders + "apple"
/** Provider linking (Settings -> Account) is the only mode; sign-in goes through the browser web login. */
private val IosMobileOAuthModes = setOf(IOS_MOBILE_OAUTH_LINK_MODE)
private val IosMobileOAuthPkceValue = Regex("^[A-Za-z0-9_-]{43,128}$")

/** Swift-facing start result. Authorization URLs and PKCE material are never logged. */
data class RaftIosMobileOAuthStartResult(
    val status: String,
    val code: Int,
    val presentation: String? = null,
    val authorizationUrl: String? = null,
    val callbackScheme: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    /** With [errorCode] `second_factor_step_up_required`: the server's `methods`, comma-joined. */
    val stepUpMethods: String? = null,
)

/** Swift-facing terminal result. */
data class RaftIosMobileOAuthCompletionResult(
    val status: String,
    val message: String? = null,
    val provider: String? = null,
    val generation: String? = null,
)

internal data class IosMobileOAuthPendingRequest(
    val provider: String,
    val requestId: String,
    val codeVerifier: String,
    val requestToken: String?,
    val mode: String,
    val generation: String,
    val callbackScheme: String,
    val environmentKey: String,
    val startedAtMillis: Long,
)

internal class IosMobileOAuthPendingStore(
    private val store: RaftKeyValueStore,
) {
    fun save(request: IosMobileOAuthPendingRequest) {
        // Commit marker is written last so a process death during a multi-key MMKV write cannot
        // expose a partially replaced request as current.
        store.putString(KEY_COMMITTED_GENERATION, null)
        store.putString(KEY_PROVIDER, request.provider)
        store.putString(KEY_REQUEST_ID, request.requestId)
        store.putString(KEY_CODE_VERIFIER, request.codeVerifier)
        store.putString(KEY_REQUEST_TOKEN, request.requestToken)
        store.putString(KEY_MODE, request.mode)
        store.putString(KEY_GENERATION, request.generation)
        store.putString(KEY_CALLBACK_SCHEME, request.callbackScheme)
        store.putString(KEY_ENVIRONMENT_KEY, request.environmentKey)
        store.putString(KEY_STARTED_AT_MILLIS, request.startedAtMillis.toString())
        store.putString(KEY_COMMITTED_GENERATION, request.generation)
    }

    fun read(): IosMobileOAuthPendingRequest? {
        val committedGeneration = store.getString(KEY_COMMITTED_GENERATION).orEmpty()
        val generation = store.getString(KEY_GENERATION).orEmpty()
        if (committedGeneration.isBlank() || committedGeneration != generation) return null
        val provider = store.getString(KEY_PROVIDER).orEmpty()
        val requestId = store.getString(KEY_REQUEST_ID).orEmpty()
        val codeVerifier = store.getString(KEY_CODE_VERIFIER).orEmpty()
        val requestToken = store.getString(KEY_REQUEST_TOKEN)?.takeIf { it.isNotBlank() }
        val mode = store.getString(KEY_MODE).orEmpty()
        val callbackScheme = store.getString(KEY_CALLBACK_SCHEME).orEmpty()
        val environmentKey = store.getString(KEY_ENVIRONMENT_KEY).orEmpty()
        val startedAtMillis = store.getString(KEY_STARTED_AT_MILLIS)?.toLongOrNull() ?: return null
        if (
            provider !in IosMobileOAuthProviders ||
            requestId.isBlank() ||
            !IosMobileOAuthPkceValue.matches(codeVerifier) ||
            (provider == "apple") != (requestToken != null) ||
            mode !in IosMobileOAuthModes ||
            callbackScheme !in setOf("raft", "raft-alpha", "raft-beta", "raft-debug") ||
            environmentKey.isBlank()
        ) {
            return null
        }
        return IosMobileOAuthPendingRequest(
            provider = provider,
            requestId = requestId,
            codeVerifier = codeVerifier,
            requestToken = requestToken,
            mode = mode,
            generation = generation,
            callbackScheme = callbackScheme,
            environmentKey = environmentKey,
            startedAtMillis = startedAtMillis,
        )
    }

    fun clear() {
        store.putString(KEY_COMMITTED_GENERATION, null)
        store.putString(KEY_PROVIDER, null)
        store.putString(KEY_REQUEST_ID, null)
        store.putString(KEY_CODE_VERIFIER, null)
        store.putString(KEY_REQUEST_TOKEN, null)
        store.putString(KEY_MODE, null)
        store.putString(KEY_GENERATION, null)
        store.putString(KEY_CALLBACK_SCHEME, null)
        store.putString(KEY_ENVIRONMENT_KEY, null)
        store.putString(KEY_STARTED_AT_MILLIS, null)
    }

    private companion object {
        const val KEY_COMMITTED_GENERATION = "committedGeneration"
        const val KEY_PROVIDER = "provider"
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_CODE_VERIFIER = "codeVerifier"
        const val KEY_REQUEST_TOKEN = "requestToken"
        const val KEY_MODE = "mode"
        const val KEY_GENERATION = "generation"
        const val KEY_CALLBACK_SCHEME = "callbackScheme"
        const val KEY_ENVIRONMENT_KEY = "environmentKey"
        const val KEY_STARTED_AT_MILLIS = "startedAtMillis"
    }
}

internal sealed interface IosMobileOAuthStartOutcome {
    data class Success(val response: AuthMobileOAuthStartResponse) : IosMobileOAuthStartOutcome
    data class Failure(val message: String, val stepUpMethods: String? = null) : IosMobileOAuthStartOutcome
    data object Unauthenticated : IosMobileOAuthStartOutcome
}

internal sealed interface IosNativeAppleStartOutcome {
    data class Success(val response: AuthNativeAppleMobileOAuthStartResponse) : IosNativeAppleStartOutcome
    data class Failure(val message: String) : IosNativeAppleStartOutcome
    data object Unauthenticated : IosNativeAppleStartOutcome
}

internal sealed interface IosNativeAppleAuthorizationOutcome {
    data class Success(
        val response: AuthNativeAppleMobileOAuthAuthorizationResponse,
    ) : IosNativeAppleAuthorizationOutcome
    data class Failure(val message: String) : IosNativeAppleAuthorizationOutcome
    data object Unauthenticated : IosNativeAppleAuthorizationOutcome
}

internal sealed interface IosMobileOAuthLinkOutcome {
    data object Success : IosMobileOAuthLinkOutcome
    data class Failure(val message: String) : IosMobileOAuthLinkOutcome
    data object Unauthenticated : IosMobileOAuthLinkOutcome
}

internal interface IosMobileOAuthRuntime {
    val environmentKey: String

    suspend fun startBrowserLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
    ): IosMobileOAuthStartOutcome

    suspend fun startNativeApple(
        mode: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
    ): IosNativeAppleStartOutcome

    suspend fun authorizeNativeApple(
        mode: String,
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
    ): IosNativeAppleAuthorizationOutcome

    suspend fun completeLink(
        provider: String,
        code: String,
        codeVerifier: String,
    ): IosMobileOAuthLinkOutcome
}

private class SharedIosMobileOAuthRuntime(
    provider: RaftSharedStoreProvider,
) : IosMobileOAuthRuntime {
    private val authDataSource = provider.createAuthDataSourceForCurrentEnvironment()

    override val environmentKey: String = provider.apiEnvironmentStore.current().key

    override suspend fun startBrowserLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
    ): IosMobileOAuthStartOutcome =
        authDataSource.startMobileOAuthLink(
            provider = provider,
            returnUri = returnUri,
            codeChallenge = codeChallenge,
        ).toIosStartOutcome()

    override suspend fun startNativeApple(
        mode: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
    ): IosNativeAppleStartOutcome =
        authDataSource.startNativeAppleMobileOAuthLink(
            appEnv = appEnv,
            returnUri = returnUri,
            codeChallenge = codeChallenge,
        ).toIosNativeAppleStartOutcome()

    override suspend fun authorizeNativeApple(
        mode: String,
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
    ): IosNativeAppleAuthorizationOutcome =
        authDataSource.authorizeNativeAppleMobileOAuthLink(
            requestId = requestId,
            requestToken = requestToken,
            authorizationCode = authorizationCode,
            identityToken = identityToken,
        ).toIosNativeAppleAuthorizationOutcome()

    override suspend fun completeLink(
        provider: String,
        code: String,
        codeVerifier: String,
    ): IosMobileOAuthLinkOutcome =
        when (
            val result =
                authDataSource.completeMobileOAuthLink(
                    provider = provider,
                    code = code,
                    codeVerifier = codeVerifier,
                )
        ) {
            is SharedAuthResult.Success -> IosMobileOAuthLinkOutcome.Success
            is SharedAuthResult.Failure -> IosMobileOAuthLinkOutcome.Failure(result.message)
            SharedAuthResult.Unauthenticated -> IosMobileOAuthLinkOutcome.Unauthenticated
        }
}

private fun SharedAuthResult<AuthMobileOAuthStartResponse>.toIosStartOutcome(): IosMobileOAuthStartOutcome =
    when (this) {
        is SharedAuthResult.Success -> IosMobileOAuthStartOutcome.Success(data)
        is SharedAuthResult.Failure ->
            IosMobileOAuthStartOutcome.Failure(message, secondFactorStepUp?.methods?.joinToString(","))
        SharedAuthResult.Unauthenticated -> IosMobileOAuthStartOutcome.Unauthenticated
    }

private fun SharedAuthResult<AuthNativeAppleMobileOAuthStartResponse>.toIosNativeAppleStartOutcome(): IosNativeAppleStartOutcome =
    when (this) {
        is SharedAuthResult.Success -> IosNativeAppleStartOutcome.Success(data)
        is SharedAuthResult.Failure -> IosNativeAppleStartOutcome.Failure(message)
        SharedAuthResult.Unauthenticated -> IosNativeAppleStartOutcome.Unauthenticated
    }

private fun SharedAuthResult<AuthNativeAppleMobileOAuthAuthorizationResponse>.toIosNativeAppleAuthorizationOutcome(): IosNativeAppleAuthorizationOutcome =
    when (this) {
        is SharedAuthResult.Success -> IosNativeAppleAuthorizationOutcome.Success(data)
        is SharedAuthResult.Failure -> IosNativeAppleAuthorizationOutcome.Failure(message)
        SharedAuthResult.Unauthenticated -> IosNativeAppleAuthorizationOutcome.Unauthenticated
    }

internal data class IosMobileOAuthCallback(
    val scheme: String?,
    val host: String?,
    val path: String?,
    val provider: String?,
    val mode: String?,
    val code: String?,
    val error: String?,
    val expectedGeneration: String?,
)

private sealed interface IosMobileOAuthCallbackAdmission {
    data object Ignore : IosMobileOAuthCallbackAdmission
    data object Expired : IosMobileOAuthCallbackAdmission
    data object Invalid : IosMobileOAuthCallbackAdmission
    data class ProviderError(val error: String) : IosMobileOAuthCallbackAdmission
    data class Complete(val code: String) : IosMobileOAuthCallbackAdmission
}

private fun admitIosMobileOAuthCallback(
    pending: IosMobileOAuthPendingRequest,
    callback: IosMobileOAuthCallback,
    nowMillis: Long,
): IosMobileOAuthCallbackAdmission {
    if (pending.provider !in IosBrowserOAuthProviders || pending.requestToken != null) {
        return IosMobileOAuthCallbackAdmission.Ignore
    }
    if (
        callback.scheme?.lowercase() != pending.callbackScheme ||
        callback.host?.lowercase() != IOS_MOBILE_OAUTH_CALLBACK_HOST ||
        callback.path != IOS_MOBILE_OAUTH_CALLBACK_PATH
    ) {
        return IosMobileOAuthCallbackAdmission.Ignore
    }
    if (
        !callback.expectedGeneration.isNullOrBlank() &&
        callback.expectedGeneration != pending.generation
    ) {
        return IosMobileOAuthCallbackAdmission.Ignore
    }
    // The canonical server return currently guarantees only `code` or `error`; provider, mode,
    // requestId and the native generation are not echoed on a cold-process callback. The persisted
    // request remains the authority, while any optional echoed provider/mode must still match.
    val callbackProvider = callback.provider?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    if (callbackProvider != null && callbackProvider != pending.provider) {
        return IosMobileOAuthCallbackAdmission.Ignore
    }
    val callbackMode = callback.mode?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    if (callbackMode != null && callbackMode != pending.mode) {
        return IosMobileOAuthCallbackAdmission.Ignore
    }
    val ageMillis = nowMillis - pending.startedAtMillis
    if (ageMillis >= IOS_MOBILE_OAUTH_PENDING_TTL_MILLIS || ageMillis < -IOS_MOBILE_OAUTH_CLOCK_SKEW_MILLIS) {
        return IosMobileOAuthCallbackAdmission.Expired
    }
    callback.error?.trim()?.takeIf { it.isNotBlank() }?.let {
        return IosMobileOAuthCallbackAdmission.ProviderError(it)
    }
    val code = callback.code?.trim().orEmpty()
    return if (code.isBlank()) {
        IosMobileOAuthCallbackAdmission.Invalid
    } else {
        IosMobileOAuthCallbackAdmission.Complete(code)
    }
}

internal class IosMobileOAuthCoordinator(
    private val pendingStore: IosMobileOAuthPendingStore,
    private val runtimeProvider: () -> IosMobileOAuthRuntime?,
    private val nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val mutex = Mutex()

    suspend fun startBrowser(
        provider: String,
        mode: String,
        generation: String,
        codeVerifier: String,
        codeChallenge: String,
        returnUriOverride: String? = null,
    ): RaftIosMobileOAuthStartResult =
        mutex.withLock {
            pendingStore.clear()
            val normalizedProvider = provider.trim().lowercase()
            val normalizedMode = mode.trim().lowercase()
            if (
                normalizedProvider !in IosBrowserOAuthProviders ||
                normalizedMode !in IosMobileOAuthModes ||
                generation.isBlank() ||
                !IosMobileOAuthPkceValue.matches(codeVerifier) ||
                !IosMobileOAuthPkceValue.matches(codeChallenge)
            ) {
                return@withLock startError(
                    code = "ios_mobile_oauth_invalid_request",
                    message = "Mobile sign-in could not be prepared.",
                )
            }
            val runtime = runtimeProvider()
                ?: return@withLock startError(
                    code = "ios_mobile_oauth_runtime_not_ready",
                    message = "Raft is still starting. Try again.",
                )
            val environmentKey = runtime.environmentKey.trim().ifBlank { "production" }
            val returnUri = when {
                returnUriOverride == null -> iosMobileOAuthReturnUri(environmentKey)
                isApprovedMobileOAuthReturnUri(returnUriOverride) -> returnUriOverride
                else -> return@withLock startError(
                    code = "ios_mobile_oauth_invalid_return_uri",
                    message = "Mobile sign-in could not be prepared.",
                )
            }
            val outcome =
                runCatchingPreservingCancellation {
                    runtime.startBrowserLink(
                        provider = normalizedProvider,
                        returnUri = returnUri,
                        codeChallenge = codeChallenge,
                    )
                }.getOrElse {
                    return@withLock startError(
                        code = "ios_mobile_oauth_start_failed",
                        message = iosMobileOAuthActionFailedMessage(normalizedProvider, normalizedMode),
                    )
                }
            when (outcome) {
                is IosMobileOAuthStartOutcome.Success -> {
                    val response = outcome.response
                    if (
                        response.requestId.isBlank() ||
                        !response.authorizationUrl.startsWith("https://") ||
                        response.returnUri != returnUri
                    ) {
                        return@withLock startError(
                            code = "ios_mobile_oauth_start_contract_mismatch",
                            message = "Mobile sign-in could not be prepared.",
                        )
                    }
                    val callbackScheme = returnUri.substringBefore("://")
                    pendingStore.save(
                        IosMobileOAuthPendingRequest(
                            provider = normalizedProvider,
                            requestId = response.requestId,
                            codeVerifier = codeVerifier,
                            requestToken = null,
                            mode = normalizedMode,
                            generation = generation,
                            callbackScheme = callbackScheme,
                            environmentKey = environmentKey,
                            startedAtMillis = nowMillis(),
                        )
                    )
                    RaftIosMobileOAuthStartResult(
                        status = "started",
                        code = 202,
                        presentation = "browser",
                        authorizationUrl = response.authorizationUrl,
                        callbackScheme = callbackScheme,
                    )
                }
                is IosMobileOAuthStartOutcome.Failure ->
                    if (outcome.stepUpMethods != null) {
                        // Shared settings opens "Verify it's you" and starts the link again.
                        startError(
                            code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                            message = outcome.message,
                        ).copy(stepUpMethods = outcome.stepUpMethods)
                    } else {
                        startError(
                            code = "ios_mobile_oauth_start_failed",
                            message = outcome.message,
                        )
                    }
                IosMobileOAuthStartOutcome.Unauthenticated ->
                    startError(
                        code = "ios_mobile_oauth_auth_required",
                        message = "Sign in again to continue.",
                    )
            }
        }

    suspend fun startNativeApple(
        mode: String,
        generation: String,
        codeVerifier: String,
        codeChallenge: String,
        returnUriOverride: String? = null,
    ): RaftIosMobileOAuthStartResult =
        mutex.withLock {
            pendingStore.clear()
            val normalizedMode = mode.trim().lowercase()
            if (
                normalizedMode !in IosMobileOAuthModes ||
                generation.isBlank() ||
                !IosMobileOAuthPkceValue.matches(codeVerifier) ||
                !IosMobileOAuthPkceValue.matches(codeChallenge)
            ) {
                return@withLock startError(
                    code = "ios_mobile_oauth_invalid_request",
                    message = "Mobile sign-in could not be prepared.",
                )
            }
            val runtime = runtimeProvider()
                ?: return@withLock startError(
                    code = "ios_mobile_oauth_runtime_not_ready",
                    message = "Raft is still starting. Try again.",
                )
            val environmentKey = runtime.environmentKey.trim().ifBlank { "production" }
            val returnUri = when {
                returnUriOverride == null -> iosMobileOAuthReturnUri(environmentKey)
                isApprovedMobileOAuthReturnUri(returnUriOverride) -> returnUriOverride
                else -> return@withLock startError(
                    code = "ios_mobile_oauth_invalid_return_uri",
                    message = "Mobile sign-in could not be prepared.",
                )
            }
            val outcome =
                runCatchingPreservingCancellation {
                    runtime.startNativeApple(
                        mode = normalizedMode,
                        appEnv = environmentKey,
                        returnUri = returnUri,
                        codeChallenge = codeChallenge,
                    )
                }.getOrElse {
                    return@withLock startError(
                        code = "ios_native_apple_start_failed",
                        message = iosMobileOAuthActionFailedMessage("apple", normalizedMode),
                    )
                }
            when (outcome) {
                is IosNativeAppleStartOutcome.Success -> {
                    val response = outcome.response
                    if (
                        response.requestId.isBlank() ||
                        response.requestToken.isBlank() ||
                        response.returnUri != returnUri
                    ) {
                        return@withLock startError(
                            code = "ios_native_apple_start_contract_mismatch",
                            message = "Mobile sign-in could not be prepared.",
                        )
                    }
                    pendingStore.save(
                        IosMobileOAuthPendingRequest(
                            provider = "apple",
                            requestId = response.requestId,
                            codeVerifier = codeVerifier,
                            requestToken = response.requestToken,
                            mode = normalizedMode,
                            generation = generation,
                            callbackScheme = returnUri.substringBefore("://"),
                            environmentKey = environmentKey,
                            startedAtMillis = nowMillis(),
                        )
                    )
                    RaftIosMobileOAuthStartResult(
                        status = "started",
                        code = 202,
                        presentation = "native_apple",
                    )
                }
                is IosNativeAppleStartOutcome.Failure ->
                    startError(
                        code = "ios_native_apple_start_failed",
                        message = iosMobileOAuthActionFailedMessage("apple", normalizedMode),
                    )
                IosNativeAppleStartOutcome.Unauthenticated ->
                    startError(
                        code = "ios_mobile_oauth_auth_required",
                        message = "Sign in again to continue.",
                    )
            }
        }

    suspend fun completeNativeApple(
        generation: String,
        authorizationCode: String,
        identityToken: String,
    ): RaftIosMobileOAuthCompletionResult =
        mutex.withLock {
            val pending = pendingStore.read() ?: return@withLock ignoredCompletion()
            val requestToken = pending.requestToken ?: return@withLock ignoredCompletion()
            if (
                pending.provider != "apple" ||
                requestToken.isBlank() ||
                generation.isBlank() ||
                generation != pending.generation
            ) {
                return@withLock ignoredCompletion()
            }
            val ageMillis = nowMillis() - pending.startedAtMillis
            if (ageMillis >= IOS_MOBILE_OAUTH_PENDING_TTL_MILLIS || ageMillis < -IOS_MOBILE_OAUTH_CLOCK_SKEW_MILLIS) {
                pendingStore.clear()
                return@withLock errorCompletion(pending, "Sign-in request expired. Try again.")
            }
            if (authorizationCode.isBlank() || identityToken.isBlank()) {
                pendingStore.clear()
                return@withLock errorCompletion(
                    pending,
                    iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                )
            }
            val runtime = runtimeProvider()
                ?: return@withLock RaftIosMobileOAuthCompletionResult(
                    status = "retry",
                    provider = pending.provider,
                    generation = pending.generation,
                )
            if (runtime.environmentKey.trim() != pending.environmentKey) {
                pendingStore.clear()
                return@withLock errorCompletion(
                    pending,
                    "The API environment changed. Start sign-in again.",
                )
            }
            val result =
                runCatchingPreservingCancellation {
                    when (
                        val outcome =
                            runtime.authorizeNativeApple(
                                mode = pending.mode,
                                requestId = pending.requestId,
                                requestToken = requestToken,
                                authorizationCode = authorizationCode,
                                identityToken = identityToken,
                            )
                    ) {
                        is IosNativeAppleAuthorizationOutcome.Success -> {
                            val response = outcome.response
                            if (
                                response.requestId != pending.requestId ||
                                response.provider != "apple" ||
                                response.mode != pending.mode ||
                                response.handoffCode.isBlank()
                            ) {
                                errorCompletion(
                                    pending,
                                    iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                                )
                            } else {
                                completeLink(runtime, pending, response.handoffCode)
                            }
                        }
                        is IosNativeAppleAuthorizationOutcome.Failure ->
                            errorCompletion(
                                pending,
                                iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                            )
                        IosNativeAppleAuthorizationOutcome.Unauthenticated ->
                            errorCompletion(
                                pending,
                                iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                            )
                    }
                }.getOrElse {
                    errorCompletion(
                        pending,
                        iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                    )
                }
            pendingStore.clear()
            result
        }

    suspend fun complete(callback: IosMobileOAuthCallback): RaftIosMobileOAuthCompletionResult =
        mutex.withLock {
            val pending = pendingStore.read() ?: return@withLock ignoredCompletion()
            when (val admission = admitIosMobileOAuthCallback(pending, callback, nowMillis())) {
                IosMobileOAuthCallbackAdmission.Ignore -> ignoredCompletion()
                IosMobileOAuthCallbackAdmission.Expired -> {
                    pendingStore.clear()
                    errorCompletion(pending, "Sign-in request expired. Try again.")
                }
                IosMobileOAuthCallbackAdmission.Invalid -> {
                    pendingStore.clear()
                    errorCompletion(pending, iosMobileOAuthActionFailedMessage(pending.provider, pending.mode))
                }
                is IosMobileOAuthCallbackAdmission.ProviderError -> {
                    pendingStore.clear()
                    errorCompletion(
                        pending,
                        iosMobileOAuthProviderErrorMessage(
                            provider = pending.provider,
                            mode = pending.mode,
                            error = admission.error,
                        ),
                    )
                }
                is IosMobileOAuthCallbackAdmission.Complete -> {
                    val runtime = runtimeProvider()
                        ?: return@withLock RaftIosMobileOAuthCompletionResult(
                            status = "retry",
                            provider = pending.provider,
                            generation = pending.generation,
                        )
                    if (runtime.environmentKey.trim() != pending.environmentKey) {
                        pendingStore.clear()
                        return@withLock errorCompletion(
                            pending,
                            "The API environment changed. Start sign-in again.",
                        )
                    }
                    val terminal =
                        runCatchingPreservingCancellation {
                            completeLink(runtime, pending, admission.code)
                        }.getOrElse {
                            errorCompletion(
                                pending,
                                iosMobileOAuthActionFailedMessage(pending.provider, pending.mode),
                            )
                        }
                    pendingStore.clear()
                    terminal
                }
            }
        }

    suspend fun cancel(
        generation: String,
        error: String,
    ): RaftIosMobileOAuthCompletionResult =
        mutex.withLock {
            val pending = pendingStore.read() ?: return@withLock ignoredCompletion()
            if (generation.isBlank() || generation != pending.generation) {
                return@withLock ignoredCompletion()
            }
            pendingStore.clear()
            errorCompletion(
                pending,
                iosMobileOAuthProviderErrorMessage(pending.provider, pending.mode, error),
            )
        }

    private suspend fun completeLink(
        runtime: IosMobileOAuthRuntime,
        pending: IosMobileOAuthPendingRequest,
        code: String,
    ): RaftIosMobileOAuthCompletionResult =
        when (
            val outcome =
                runtime.completeLink(
                    provider = pending.provider,
                    code = code,
                    codeVerifier = pending.codeVerifier,
                )
        ) {
            IosMobileOAuthLinkOutcome.Success ->
                RaftIosMobileOAuthCompletionResult(
                    status = "settings_account",
                    message = "${pending.provider.iosMobileOAuthProviderName()} account linked.",
                    provider = pending.provider,
                )
            is IosMobileOAuthLinkOutcome.Failure -> errorCompletion(pending, outcome.message)
            IosMobileOAuthLinkOutcome.Unauthenticated ->
                errorCompletion(
                    pending,
                    "Sign in again to link ${pending.provider.iosMobileOAuthProviderName()}.",
                )
        }
}

internal fun iosMobileOAuthReturnUri(environmentKey: String): String =
    if (environmentKey.trim().lowercase() == "production") {
        IOS_MOBILE_OAUTH_RELEASE_RETURN_URI
    } else {
        IOS_MOBILE_OAUTH_DEBUG_RETURN_URI
    }

private fun isApprovedMobileOAuthReturnUri(value: String): Boolean =
    value in
        setOf(
            IOS_MOBILE_OAUTH_RELEASE_RETURN_URI,
            "raft-alpha://oauth/callback",
            "raft-beta://oauth/callback",
            IOS_MOBILE_OAUTH_DEBUG_RETURN_URI,
        )

private fun startError(code: String, message: String): RaftIosMobileOAuthStartResult =
    RaftIosMobileOAuthStartResult(
        status = "error",
        code = 0,
        errorCode = code,
        message = message,
    )

private fun ignoredCompletion(): RaftIosMobileOAuthCompletionResult =
    RaftIosMobileOAuthCompletionResult(status = "ignored")

private fun errorCompletion(
    pending: IosMobileOAuthPendingRequest,
    message: String,
): RaftIosMobileOAuthCompletionResult =
    RaftIosMobileOAuthCompletionResult(
        status = if (pending.mode == IOS_MOBILE_OAUTH_LINK_MODE) "settings_account" else "login_error",
        message = message,
        provider = pending.provider,
    )

private fun iosMobileOAuthActionFailedMessage(provider: String, mode: String): String =
    if (mode == IOS_MOBILE_OAUTH_LINK_MODE) {
        "Unable to link ${provider.iosMobileOAuthProviderName()}. Try again."
    } else {
        "${provider.iosMobileOAuthProviderName()} sign-in failed. Try again."
    }

internal fun iosMobileOAuthProviderErrorMessage(
    provider: String,
    mode: String,
    error: String,
    accountConflictMessage: (String) -> String = RaftHostStrings::mobileOAuthAccountConflict,
    providerConflictMessage: (String) -> String = RaftHostStrings::mobileOAuthProviderConflict,
): String {
    val providerName = provider.iosMobileOAuthProviderName()
    return when (error.trim().lowercase()) {
        "access_denied", "provider_cancelled", "user_cancelled" ->
            if (mode == IOS_MOBILE_OAUTH_LINK_MODE) {
                "$providerName account linking was cancelled."
            } else {
                "$providerName sign-in was cancelled."
            }
        "expired", "request_not_pending", "handoff_code_expired", "handoff_code_consumed" ->
            "Sign-in request expired. Try again."
        // Delegated to the shared mapper so Android resolves the identical
        // branch and copy; see MobileOAuthConflictMessages.kt.
        "account_conflict", "provider_conflict" ->
            mobileOAuthConflictMessageOrNull(
                providerName = providerName,
                error = error,
                accountConflictMessage = accountConflictMessage,
                providerConflictMessage = providerConflictMessage,
            ) ?: iosMobileOAuthActionFailedMessage(provider, mode)
        "registration_disabled" ->
            "New account registration is currently unavailable."
        "runtime_not_ready" ->
            "Raft is still starting. Try sign-in again."
        else -> iosMobileOAuthActionFailedMessage(provider, mode)
    }
}

private fun String.iosMobileOAuthProviderName(): String =
    when (lowercase()) {
        "google" -> "Google"
        "github" -> "GitHub"
        "apple" -> "Apple"
        else -> "Social"
    }

/**
 * Native iOS entry point. Swift owns AuthenticationServices presentation and cryptographic PKCE
 * generation; shared owns request state, API/session SSOT, callback admission and route outcome.
 */
object RaftIosMobileOAuthBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator: IosMobileOAuthCoordinator by lazy {
        IosMobileOAuthCoordinator(
            pendingStore =
                IosMobileOAuthPendingStore(
                    RaftSharedKeyValueStoreProvider.create(
                        namespace = RAFT_IOS_MOBILE_OAUTH_MMKV_NAMESPACE,
                        storageName = "iOS mobile OAuth",
                    ).store
                ),
            runtimeProvider = {
                RaftSharedStoreRegistry.currentOrNull()?.let(::SharedIosMobileOAuthRuntime)
            },
        )
    }

    fun startBrowser(
        provider: String,
        mode: String,
        generation: String,
        returnUri: String,
        codeVerifier: String,
        codeChallenge: String,
        completion: (RaftIosMobileOAuthStartResult) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatchingPreservingCancellation {
                    coordinator.startBrowser(
                        provider,
                        mode,
                        generation,
                        codeVerifier,
                        codeChallenge,
                        returnUriOverride = returnUri,
                    )
                }.getOrElse {
                    startError(
                        code = "ios_mobile_oauth_bridge_unavailable",
                        message = "Mobile sign-in is unavailable. Try again.",
                    )
                }
            raftLogInfo(
                IOS_MOBILE_OAUTH_LOG_TAG,
                "oauth_start_result provider=${provider.safeIosOAuthDiagnosticToken()} " +
                    "mode=${mode.safeIosOAuthDiagnosticToken()} status=${result.status}",
            )
            completion(result)
        }
    }

    fun startNativeApple(
        mode: String,
        generation: String,
        returnUri: String,
        codeVerifier: String,
        codeChallenge: String,
        completion: (RaftIosMobileOAuthStartResult) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatchingPreservingCancellation {
                    coordinator.startNativeApple(
                        mode,
                        generation,
                        codeVerifier,
                        codeChallenge,
                        returnUriOverride = returnUri,
                    )
                }.getOrElse {
                    startError(
                        code = "ios_mobile_oauth_bridge_unavailable",
                        message = "Mobile sign-in is unavailable. Try again.",
                    )
                }
            raftLogInfo(
                IOS_MOBILE_OAUTH_LOG_TAG,
                "oauth_native_apple_start_result mode=${mode.safeIosOAuthDiagnosticToken()} status=${result.status}",
            )
            completion(result)
        }
    }

    fun completeNativeApple(
        generation: String,
        authorizationCode: String,
        identityToken: String,
        completion: (RaftIosMobileOAuthCompletionResult) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatchingPreservingCancellation {
                    coordinator.completeNativeApple(
                        generation = generation,
                        authorizationCode = authorizationCode,
                        identityToken = identityToken,
                    )
                }.getOrElse {
                    raftLogWarn(
                        IOS_MOBILE_OAUTH_LOG_TAG,
                        "oauth_native_apple_complete_failed errorType=${it::class.simpleName ?: "unknown"}",
                    )
                    RaftIosMobileOAuthCompletionResult(
                        status = "login_error",
                        message = "Mobile sign-in failed. Try again.",
                        provider = "apple",
                    )
                }
            raftLogInfo(
                IOS_MOBILE_OAUTH_LOG_TAG,
                "oauth_native_apple_complete_result status=${result.status}",
            )
            completion(result)
        }
    }

    fun completeCallback(
        scheme: String?,
        host: String?,
        path: String?,
        provider: String?,
        mode: String?,
        code: String?,
        error: String?,
        expectedGeneration: String?,
        completion: (RaftIosMobileOAuthCompletionResult) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatchingPreservingCancellation {
                    coordinator.complete(
                        IosMobileOAuthCallback(
                            scheme = scheme,
                            host = host,
                            path = path,
                            provider = provider,
                            mode = mode,
                            code = code,
                            error = error,
                            expectedGeneration = expectedGeneration,
                        )
                    )
                }.getOrElse {
                    raftLogWarn(IOS_MOBILE_OAUTH_LOG_TAG, "oauth_callback_failed errorType=${it::class.simpleName ?: "unknown"}")
                    RaftIosMobileOAuthCompletionResult(
                        status = "login_error",
                        message = "Mobile sign-in failed. Try again.",
                    )
                }
            raftLogInfo(
                IOS_MOBILE_OAUTH_LOG_TAG,
                "oauth_callback_result provider=${provider.safeIosOAuthDiagnosticToken()} " +
                    "mode=${mode.safeIosOAuthDiagnosticToken()} status=${result.status}",
            )
            completion(result)
        }
    }

    fun cancel(
        generation: String,
        error: String,
        completion: (RaftIosMobileOAuthCompletionResult) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatchingPreservingCancellation { coordinator.cancel(generation, error) }
                    .getOrElse { ignoredCompletion() }
            raftLogInfo(IOS_MOBILE_OAUTH_LOG_TAG, "oauth_cancel_result status=${result.status}")
            completion(result)
        }
    }
}

private fun String?.safeIosOAuthDiagnosticToken(): String =
    this
        ?.trim()
        ?.lowercase()
        ?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        ?.take(32)
        ?.ifBlank { "none" }
        ?: "none"

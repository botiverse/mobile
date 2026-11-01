package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val secondFactorRecoveryJson = Json { ignoreUnknownKeys = true }

/**
 * A pending email reset of two-step verification on the signed-in account (slock rollout PR 6,
 * `packages/server/src/routes/secondFactor.ts` header, `services/secondFactor/recovery.ts`
 * `RecoveryView` / `getRecoveryView`):
 *
 *     GET    /api/auth/second-factor/recovery -> { recovery: RecoveryView | null }
 *     DELETE /api/auth/second-factor/recovery -> { cancelled: boolean, recovery: null }  (one step, no step-up)
 *     RecoveryView = { status: "waiting" | "ready", requestedAt, eligibleAt, expiresAt (ISO),
 *                      firstFactorRequired: "set_password" | null }
 *
 * GET /status carries the same `recovery`; the web reads GET /recovery (slock #12295
 * `useSecondFactorRecoveryNotification`), and so does the app. The router answers 404 while
 * `auth_second_factor_v1` is off for the person: nothing to show.
 */
private const val SECOND_FACTOR_RECOVERY_PATH = "auth/second-factor/recovery"

@Serializable
internal enum class SecondFactorRecoveryStatusDto {
    @SerialName("waiting") Waiting,
    @SerialName("ready") Ready,
}

@Serializable
internal data class SecondFactorRecoveryViewDto(
    val status: SecondFactorRecoveryStatusDto,
    val requestedAt: String,
    val eligibleAt: String,
    val expiresAt: String,
    val firstFactorRequired: String? = null,
)

@Serializable
internal data class SecondFactorRecoveryResponseDto(
    /** Always sent (null: no request); required, so a wrapped body is a mismatch, not "nothing". */
    val recovery: SecondFactorRecoveryViewDto?,
)

@Serializable
internal data class SecondFactorRecoveryCancelResponseDto(val cancelled: Boolean)

enum class SecondFactorRecoveryStatus {
    /** The 72-hour wait is running: a sign-in can finish it after [SecondFactorRecovery.eligibleAt]. */
    Waiting,

    /** The wait is over: the next sign-in can finish it, until [SecondFactorRecovery.expiresAt]. */
    Ready,
}

data class SecondFactorRecovery(
    val status: SecondFactorRecoveryStatus,
    val requestedAt: String,
    val eligibleAt: String,
    val expiresAt: String,
    /** `firstFactorRequired: "set_password"`: no password yet; Forgot Password sets one first. */
    val setPasswordFirst: Boolean,
)

/**
 * GET /recovery's answer: [DataResult.Content] with the request (null: none, or the router is off
 * for this person, 404), else an error (the caller keeps what it shows).
 */
fun parseSharedSecondFactorRecoveryResponse(statusCode: Int, bodyText: String?): DataResult<SecondFactorRecovery?> {
    if (statusCode == 404) return DataResult.Content(null)
    if (statusCode !in 200..299) {
        return DataResult.Error(ScreenError(code = "second_factor_recovery_http_$statusCode", retryable = statusCode >= 500))
    }
    val dto =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { secondFactorRecoveryJson.decodeFromString(SecondFactorRecoveryResponseDto.serializer(), body) }.getOrNull()
        } ?: return DataResult.Error(ScreenError(code = "second_factor_recovery_contract_mismatch", retryable = false))
    return DataResult.Content(dto.recovery?.toDomain())
}

/** DELETE /recovery's answer: [DataResult.Content] once nothing is pending (`cancelled` false: there was nothing). */
fun parseSharedSecondFactorRecoveryCancelResponse(statusCode: Int, bodyText: String?): DataResult<Boolean> {
    if (statusCode !in 200..299) {
        return DataResult.Error(ScreenError(code = "second_factor_recovery_cancel_http_$statusCode", retryable = statusCode >= 500))
    }
    val dto =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { secondFactorRecoveryJson.decodeFromString(SecondFactorRecoveryCancelResponseDto.serializer(), body) }.getOrNull()
        } ?: return DataResult.Error(ScreenError(code = "second_factor_recovery_cancel_contract_mismatch", retryable = false))
    return DataResult.Content(dto.cancelled)
}

private fun SecondFactorRecoveryViewDto.toDomain(): SecondFactorRecovery =
    SecondFactorRecovery(
        status =
            when (status) {
                SecondFactorRecoveryStatusDto.Waiting -> SecondFactorRecoveryStatus.Waiting
                SecondFactorRecoveryStatusDto.Ready -> SecondFactorRecoveryStatus.Ready
            },
        requestedAt = requestedAt,
        eligibleAt = eligibleAt,
        expiresAt = expiresAt,
        setPasswordFirst = firstFactorRequired == "set_password",
    )

internal interface SecondFactorRecoveryDataSource {
    suspend fun pendingRecovery(): DataResult<SecondFactorRecovery?>
    suspend fun cancelRecovery(): DataResult<Boolean>
}

internal class NetworkSecondFactorRecoveryDataSource(private val networkClient: RaftNetworkClient) : SecondFactorRecoveryDataSource {
    override suspend fun pendingRecovery(): DataResult<SecondFactorRecovery?> =
        networkCall {
            // A refresh on foreground / opening the notification center: nothing waits on it.
            val response = networkClient.request(DataRequestPriority.Background, SECOND_FACTOR_RECOVERY_PATH)
            parseSharedSecondFactorRecoveryResponse(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText)
        }

    override suspend fun cancelRecovery(): DataResult<Boolean> =
        networkCall {
            val response = networkClient.request(DataRequestPriority.Foreground, SECOND_FACTOR_RECOVERY_PATH, method = "DELETE")
            parseSharedSecondFactorRecoveryCancelResponse(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText)
        }

    private suspend fun <T> networkCall(call: suspend () -> DataResult<T>): DataResult<T> =
        try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
}

internal sealed interface SharedSecondFactorRecoveryEffect {
    /** Web toast `auth.recovery.cancelled`. */
    data object Cancelled : SharedSecondFactorRecoveryEffect

    /** Web toast `auth.recovery.cancelFailed`. */
    data object CancelFailed : SharedSecondFactorRecoveryEffect
}

internal data class SharedSecondFactorRecoveryState(
    val recovery: SecondFactorRecovery? = null,
    val cancelling: Boolean = false,
    val effect: SharedSecondFactorRecoveryEffect? = null,
)

internal sealed interface SharedSecondFactorRecoveryIntent {
    /** Home shown, the app back in the foreground, the notification center opened. */
    data object Refresh : SharedSecondFactorRecoveryIntent

    /** Cancel Reset: DELETE /recovery, one step. */
    data object Cancel : SharedSecondFactorRecoveryIntent

    data object ConsumeEffect : SharedSecondFactorRecoveryIntent
}

/**
 * The account's pending two-step verification reset, for the first entry of the Home
 * notification center (web slock #12295 `useSecondFactorRecoveryNotification`). Reads are
 * single-flight; a failed read keeps what is shown. Cancel clears it on success and outdates a
 * read in flight; a read is not started while a cancel runs.
 */
internal class SharedSecondFactorRecoveryStore(
    private val dataSource: SecondFactorRecoveryDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedSecondFactorRecoveryState())
    val state: StateFlow<SharedSecondFactorRecoveryState> = mutableState.asStateFlow()

    private var generation = 0L
    private var refreshJob: Job? = null

    fun dispatch(intent: SharedSecondFactorRecoveryIntent) {
        when (intent) {
            SharedSecondFactorRecoveryIntent.Refresh -> refresh()
            SharedSecondFactorRecoveryIntent.Cancel -> cancel()
            SharedSecondFactorRecoveryIntent.ConsumeEffect ->
                if (mutableState.value.effect != null) mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true || mutableState.value.cancelling) return
        val mine = ++generation
        refreshJob =
            scope.launch {
                val result = dataSource.pendingRecovery()
                if (mine != generation || result !is DataResult.Content) return@launch
                if (mutableState.value.recovery != result.data) {
                    mutableState.value = mutableState.value.copy(recovery = result.data)
                }
            }
    }

    private fun cancel() {
        val current = mutableState.value
        if (current.cancelling || current.recovery == null) return
        generation += 1
        refreshJob?.cancel()
        refreshJob = null
        mutableState.value = current.copy(cancelling = true, effect = null)
        scope.launch {
            val result = dataSource.cancelRecovery()
            val latest = mutableState.value
            mutableState.value =
                if (result is DataResult.Content) {
                    latest.copy(recovery = null, cancelling = false, effect = SharedSecondFactorRecoveryEffect.Cancelled)
                } else {
                    latest.copy(cancelling = false, effect = SharedSecondFactorRecoveryEffect.CancelFailed)
                }
        }
    }
}

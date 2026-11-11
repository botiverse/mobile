package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val accountLoginSecurityJson = Json { ignoreUnknownKeys = true }

/**
 * Settings › Account › Login & security: whether two-step verification is on and how many
 * passkeys the account has, read from the status the web's Account page reads (slock
 * `packages/server/src/routes/secondFactor.ts` header and `GET /status`,
 * `services/secondFactor/management.ts` `getSecondFactorStatus` / `getSessionSecondFactorStatus`;
 * web `components/settings/secondFactorApi.ts` `fetchSecondFactorStatus`):
 *
 *     GET /api/auth/second-factor/status
 *       -> { enabled, methods: { totp, passkeys, backupCodesRemaining }, sessionVerified, recovery }
 *
 * `methods.passkeys` is `countActivePasskeys` (`services/secondFactor/passkeys.ts`): the same
 * not-revoked rows `GET /passkeys` lists, so the count needs no second request. The router answers
 * 404 while `auth_second_factor_v1` is off for the person (web: the sections render nothing).
 * Changing either happens on the web (WebAuthn), so the app only reads.
 */
private const val SECOND_FACTOR_STATUS_PATH = "auth/second-factor/status"

@Serializable
internal data class SecondFactorStatusMethodsDto(
    val totp: Boolean,
    val passkeys: Int,
    val backupCodesRemaining: Int,
)

@Serializable
internal data class SecondFactorStatusDto(
    val enabled: Boolean,
    val methods: SecondFactorStatusMethodsDto,
)

/** What the Login & security rows show. */
data class AccountLoginSecurity(
    val twoStepEnabled: Boolean,
    val passkeyCount: Int,
)

/**
 * GET /status's answer: [DataResult.Content] with the account's setup, or null when the router is
 * off for this person (404: the group is not shown); else an error carrying [traceId].
 */
fun parseSharedSecondFactorStatusResponse(
    statusCode: Int,
    bodyText: String?,
    traceId: String? = null,
): DataResult<AccountLoginSecurity?> {
    if (statusCode == 404) return DataResult.Content(null)
    if (statusCode !in 200..299) {
        return DataResult.Error(
            ScreenError(code = "second_factor_status_http_$statusCode", retryable = statusCode >= 500, traceId = traceId)
        )
    }
    val dto =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { accountLoginSecurityJson.decodeFromString(SecondFactorStatusDto.serializer(), body) }.getOrNull()
        } ?: return DataResult.Error(
            ScreenError(code = "second_factor_status_contract_mismatch", retryable = false, traceId = traceId)
        )
    return DataResult.Content(
        AccountLoginSecurity(twoStepEnabled = dto.enabled, passkeyCount = dto.methods.passkeys.coerceAtLeast(0))
    )
}

internal interface AccountLoginSecurityDataSource {
    suspend fun loginSecurity(): DataResult<AccountLoginSecurity?>
}

internal class NetworkAccountLoginSecurityDataSource(private val networkClient: RaftNetworkClient) : AccountLoginSecurityDataSource {
    override suspend fun loginSecurity(): DataResult<AccountLoginSecurity?> =
        try {
            // On-screen rows of the open Account page, read again when the app comes back.
            val response = networkClient.request(DataRequestPriority.Visible, SECOND_FACTOR_STATUS_PATH)
            parseSharedSecondFactorStatusResponse(
                response.code,
                response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText,
                response.traceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }
}

internal data class SharedAccountLoginSecurityState(
    /** The last setup read; kept when a later read fails. */
    val security: AccountLoginSecurity? = null,
    /** The server said the feature is off for this person (404): no group. */
    val unavailable: Boolean = false,
    val loading: Boolean = false,
    /** The last read failed; shown only while there is nothing to show (web: a failed reload keeps the rows). */
    val error: ScreenError? = null,
) {
    /** The rows, the failure card, or nothing (still loading the first time, or off for this person). */
    val visible: Boolean
        get() = !unavailable && (security != null || error != null)
}

internal sealed interface SharedAccountLoginSecurityIntent {
    /** The page shown, the app back in the foreground (the person may have changed it in the browser), Retry. */
    data object Refresh : SharedAccountLoginSecurityIntent
}

/**
 * Settings › Account's Login & security group. Reads are single-flight (a resume while one runs
 * adds nothing); success patches the setup only when it changed; a failure keeps what is shown and
 * attaches the error (with its trace id).
 */
internal class SharedAccountLoginSecurityStore(
    private val dataSource: AccountLoginSecurityDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedAccountLoginSecurityState())
    val state: StateFlow<SharedAccountLoginSecurityState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null

    fun dispatch(intent: SharedAccountLoginSecurityIntent) {
        when (intent) {
            SharedAccountLoginSecurityIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(loading = true)
        refreshJob =
            scope.launch {
                val result = dataSource.loginSecurity()
                val current = mutableState.value
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            result.data?.let { security ->
                                current.copy(security = security, unavailable = false, loading = false, error = null)
                            } ?: SharedAccountLoginSecurityState(unavailable = true)
                        is DataResult.Error -> current.copy(loading = false, error = result.error)
                        else -> current.copy(loading = false, error = ScreenError(code = "second_factor_status_unavailable", traceId = null))
                    }
            }
    }
}

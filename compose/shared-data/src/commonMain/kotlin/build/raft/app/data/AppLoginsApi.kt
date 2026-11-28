package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val appLoginsJson = Json { ignoreUnknownKeys = true }

/**
 * Settings › Account › Apps you signed in to with Raft (account-mode Login with Raft, RFC 079
 * phase 3a; botiverse/slock staging 33f441958f82ce0178cc7f48232d41b20907e50c, server
 * `routes/me.ts`, web `features/settings/account/appLoginsApi.ts`):
 *
 *     GET    /api/me/app-logins           -> [{ id, clientId, name, logoUrl, scopes, grantedAt, kind? }] (newest first)
 *     DELETE /api/me/app-logins/:clientId -> 204, known App or not (no step-up: it only removes access)
 */
private const val APP_LOGINS_PATH = "me/app-logins"

/**
 * How the App knows the person (`GET /api/me/app-logins`, botiverse/slock#17336):
 * [SignIn] for an account-mode Login with Raft (also the default when an older server omits
 * `kind`), [Card] when pressing the App's card made the person known to it.
 */
enum class AppLoginKind(val wire: String) {
    SignIn("sign_in"),
    Card("card");

    companion object {
        fun fromWire(value: String?): AppLoginKind =
            when (value?.trim()?.lowercase()) {
                "card" -> Card
                else -> SignIn
            }
    }
}

@Serializable
@RaftCoreInternalApi data class AppLoginDto(
    val id: String,
    val clientId: String,
    val name: String,
    val logoUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val grantedAt: String,
    val kind: String? = null,
)

/** One App the person signed in to with their Raft account (or that knows them through its card). */
data class AppLogin(
    val id: String,
    /** The App's public client key: what DELETE takes. */
    val clientId: String,
    val name: String,
    val logoUrl: String?,
    val grantedAt: String,
    val kind: AppLoginKind = AppLoginKind.SignIn,
)

/** GET /me/app-logins's array; null when the body is not that shape. */
fun parseSharedAppLogins(bodyText: String?): List<AppLogin>? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { appLoginsJson.decodeFromString(ListSerializer(AppLoginDto.serializer()), body) }
        .getOrNull()
        ?.map {
            AppLogin(
                id = it.id,
                clientId = it.clientId,
                name = it.name,
                logoUrl = it.logoUrl?.takeIf(String::isNotBlank),
                grantedAt = it.grantedAt,
                kind = AppLoginKind.fromWire(it.kind),
            )
        }
}

@RaftCoreInternalApi interface AppLoginsDataSource {
    suspend fun list(): DataResult<List<AppLogin>>
    suspend fun revoke(clientId: String): DataResult<Unit>
}

@RaftCoreInternalApi class NetworkAppLoginsDataSource(private val networkClient: RaftNetworkClient) : AppLoginsDataSource {
    override suspend fun list(): DataResult<List<AppLogin>> =
        try {
            val response = networkClient.request(DataRequestPriority.Visible, APP_LOGINS_PATH)
            val logins = if (response.isSuccessful) parseSharedAppLogins(response.bodyText) else null
            if (logins != null) {
                DataResult.Content(logins)
            } else {
                val envelope = response.parseHttpErrorEnvelope()
                DataResult.Error(
                    ScreenError(
                        code = "app_logins_http_${response.code}",
                        retryable = response.code == 0 || response.code >= 500,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }

    override suspend fun revoke(clientId: String): DataResult<Unit> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "$APP_LOGINS_PATH/${encodeSignedInDevicePathSegment(clientId)}",
                    method = "DELETE",
                )
            if (response.isSuccessful) {
                DataResult.Content(Unit)
            } else {
                val envelope = response.parseHttpErrorEnvelope()
                DataResult.Error(
                    ScreenError(
                        code = "app_login_revoke_http_${response.code}",
                        retryable = response.code == 0 || response.code >= 500,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }
}

@RaftCoreInternalApi data class SharedAppLoginsState(
    val logins: List<AppLogin> = emptyList(),
    /** The App whose Revoke confirm is open. */
    val pending: AppLogin? = null,
    val revoking: Boolean = false,
    /** The last revoke of [pending] failed: the dialog says so and stays open. */
    val revokeFailed: Boolean = false,
    /** The App just revoked: the page toasts it once. */
    val revoked: AppLogin? = null,
)

@RaftCoreInternalApi sealed interface SharedAppLoginsIntent {
    data object Refresh : SharedAppLoginsIntent
    data class OpenRevoke(val login: AppLogin) : SharedAppLoginsIntent
    data object CloseRevoke : SharedAppLoginsIntent
    data object ConfirmRevoke : SharedAppLoginsIntent
    data object RevokedShown : SharedAppLoginsIntent
}

/**
 * Web AppLoginsSection: the section shows only while the list has an entry; a failed read shows
 * nothing either (and keeps what was shown). Revoke → confirm → DELETE; the row goes on success, the
 * dialog stays open with the failure otherwise.
 */
@RaftCoreInternalApi class SharedAppLoginsStore(
    private val dataSource: AppLoginsDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedAppLoginsState())
    val state: StateFlow<SharedAppLoginsState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null

    fun dispatch(intent: SharedAppLoginsIntent) {
        when (intent) {
            SharedAppLoginsIntent.Refresh -> refresh()
            is SharedAppLoginsIntent.OpenRevoke ->
                if (mutableState.value.pending == null) {
                    mutableState.value = mutableState.value.copy(pending = intent.login, revokeFailed = false)
                }
            SharedAppLoginsIntent.CloseRevoke ->
                if (!mutableState.value.revoking) mutableState.value = mutableState.value.copy(pending = null, revokeFailed = false)
            SharedAppLoginsIntent.ConfirmRevoke -> revoke()
            SharedAppLoginsIntent.RevokedShown -> mutableState.value = mutableState.value.copy(revoked = null)
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob =
            scope.launch {
                val result = dataSource.list()
                // Nothing to show on a failure: the section keeps what it had (hidden when empty).
                if (result is DataResult.Content) mutableState.value = mutableState.value.copy(logins = result.data)
            }
    }

    private fun revoke() {
        val current = mutableState.value
        val login = current.pending ?: return
        if (current.revoking) return
        mutableState.value = current.copy(revoking = true, revokeFailed = false)
        scope.launch {
            val result = dataSource.revoke(login.clientId)
            val state = mutableState.value
            mutableState.value =
                if (result is DataResult.Content) {
                    state.copy(
                        logins = state.logins.filterNot { it.clientId == login.clientId },
                        pending = null,
                        revoking = false,
                        revoked = login,
                    )
                } else {
                    state.copy(revoking = false, revokeFailed = true)
                }
        }
    }
}

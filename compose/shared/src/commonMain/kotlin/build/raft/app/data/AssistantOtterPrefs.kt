package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Whether the personal assistant's otter shows over every page: an account setting, one value for
// every server and device (#proj-assistant:#21). Contract at botiverse/slock staging
// 34511fe7e4a36d0d5c054f836be70821fa8b4608:
//
//   GET   /api/auth/me   -> the user, with `assistantOtterVisible: boolean` (userService.ts
//                           `toPublicUser`: `user.assistantOtterVisible ?? true`; absent on a server
//                           from before it, which web reads as shown)
//   PATCH /api/auth/me   { assistantOtterVisible: boolean } -> the user (auth.ts
//                           `parseAssistantOtterVisible`: anything but a boolean is a 400; only the
//                           fields sent are written)
//
// Web: assistant/assistantOtterPrefs.ts (`useOtterVisiblePref`, `saveAssistantOtterPrefs`) and
// AssistantOtterSettings.tsx `OtterVisibleRow`. The shortcut (`assistantOtterShortcut`) is a
// keyboard binding; the app has no otter shortcut, so it is not read here.

private val assistantOtterPrefsJson = Json { ignoreUnknownKeys = true }

/**
 * The part of `GET/PATCH /api/auth/me`'s user the otter reads. `id` is required so a body that is
 * not the user is a mismatch; `assistantOtterVisible` is null on a server that doesn't send it.
 */
@Serializable
internal data class AssistantOtterPrefsAccountDto(
    val id: String,
    val assistantOtterVisible: Boolean? = null,
)

/** `PATCH /api/auth/me`'s body: the switch, and nothing else. */
@Serializable
internal data class AssistantOtterVisibleUpdateBody(val assistantOtterVisible: Boolean)

internal fun buildSharedAssistantOtterVisibleUpdateBody(visible: Boolean): String =
    assistantOtterPrefsJson.encodeToString(AssistantOtterVisibleUpdateBody.serializer(), AssistantOtterVisibleUpdateBody(visible))

/**
 * `GET/PATCH /api/auth/me`'s answer: whether the otter shows (web `assistantOtterVisible !== false`:
 * absent is shown), else an error carrying the response's trace ID.
 */
internal fun parseSharedAssistantOtterVisible(statusCode: Int, bodyText: String?, traceId: String? = null): DataResult<Boolean> {
    if (statusCode !in 200..299) {
        val envelope = if (statusCode > 0) parseHttpErrorEnvelope(bodyText) else null
        return DataResult.Error(
            ScreenError(
                code = "assistant_otter_prefs_http_$statusCode",
                retryable = statusCode >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
    }
    val dto =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { assistantOtterPrefsJson.decodeFromString(AssistantOtterPrefsAccountDto.serializer(), body) }.getOrNull()
        } ?: return DataResult.Error(ScreenError(code = "assistant_otter_prefs_contract_mismatch", retryable = false, traceId = traceId))
    return DataResult.Content(dto.assistantOtterVisible != false)
}

internal interface AssistantOtterPrefsDataSource {
    /** Whether the otter shows, as the account says (`GET auth/me`). */
    suspend fun readVisible(): DataResult<Boolean>

    /** Saves the switch (`PATCH auth/me`); the account's value back. */
    suspend fun saveVisible(visible: Boolean): DataResult<Boolean>
}

internal class NetworkAssistantOtterPrefsDataSource(private val networkClient: RaftNetworkClient) : AssistantOtterPrefsDataSource {
    // Read behind the otter and the owner's section; nothing waits on it.
    override suspend fun readVisible(): DataResult<Boolean> =
        networkCall { networkClient.settingsAccount(DataRequestPriority.Background) }

    // The switch the person just flipped.
    override suspend fun saveVisible(visible: Boolean): DataResult<Boolean> =
        networkCall { networkClient.updateSettingsAccount(DataRequestPriority.Foreground, buildSharedAssistantOtterVisibleUpdateBody(visible)) }

    private suspend fun networkCall(call: suspend () -> HomeNetworkResponse): DataResult<Boolean> =
        try {
            val response = call()
            parseSharedAssistantOtterVisible(
                response.code,
                if (response.isSuccessful) response.bodyText else response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText,
                response.traceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // No answer, no trace ID.
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }
}

/**
 * The account's otter setting as the app knows it. [visible] is null until it is known for
 * [accountId] (the otter waits rather than showing something the person turned off elsewhere); a
 * read that fails counts as shown, the server's own default.
 */
data class AssistantOtterPrefsState(
    val accountId: String? = null,
    val visible: Boolean? = null,
    /** A save of the switch is on its way: the switch is disabled (web `disabled={saving}`). */
    val saving: Boolean = false,
    /** The last save failed and the switch is back; cleared by the next flip. */
    val saveFailure: ScreenError? = null,
)

/**
 * The otter's account setting (web `assistantOtterPrefs.ts`): read once per account with `GET
 * auth/me` (single flight), then kept. A flip shows at once and sends one `PATCH auth/me`; the
 * account's answer wins, and a failed save puts the previous value back and says so under the
 * switch. One save at a time (web disables the switch while it saves).
 */
class SharedAssistantOtterPrefsStore internal constructor(
    private val dataSource: AssistantOtterPrefsDataSource,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(AssistantOtterPrefsState())
    val state: StateFlow<AssistantOtterPrefsState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()
    private var readJob: Job? = null

    /** Reads the setting for [accountId] unless it is known or being read; another account starts over. */
    fun ensureLoaded(accountId: String) {
        val account = accountId.trim().takeIf(String::isNotEmpty) ?: return
        val job =
            synchronized(lock) {
                val current = mutableState.value
                if (current.accountId != account) {
                    readJob?.cancel()
                    readJob = null
                    mutableState.value = AssistantOtterPrefsState(accountId = account)
                } else if (current.visible != null || readJob?.isActive == true) {
                    return
                }
                scope.launch(start = CoroutineStart.LAZY) {
                    val result = dataSource.readVisible()
                    mutableState.update { now ->
                        // Another account, or a save answered first: this read is out of date.
                        if (now.accountId != account || now.visible != null) {
                            now
                        } else {
                            if (result !is DataResult.Content) logger("assistant_otter_prefs read_failed")
                            now.copy(visible = (result as? DataResult.Content)?.data ?: true)
                        }
                    }
                }.also { readJob = it }
            }
        job.start()
    }

    /**
     * Turns the otter on or off for [accountId] (web `saveAssistantOtterPrefs`). False when
     * nothing was sent (a save is on its way, or another account); otherwise the save runs in the
     * store's scope, so it finishes (and reverts on failure) even if the page closes.
     */
    suspend fun setVisible(accountId: String, visible: Boolean): Boolean {
        val account = accountId.trim().takeIf(String::isNotEmpty) ?: return false
        val previous =
            synchronized(lock) {
                val current = mutableState.value
                if (current.accountId != account || current.saving) return false
                readJob?.cancel()
                readJob = null
                mutableState.value = current.copy(visible = visible, saving = true, saveFailure = null)
                current.visible ?: true
            }
        logger("assistant_otter_prefs save_start visible=$visible")
        scope.async {
            val result = dataSource.saveVisible(visible)
            mutableState.update { now ->
                if (now.accountId != account) return@update now
                when (result) {
                    is DataResult.Content -> now.copy(visible = result.data, saving = false)
                    is DataResult.Error -> now.copy(visible = previous, saving = false, saveFailure = result.error)
                    else -> now.copy(visible = previous, saving = false, saveFailure = ScreenError("assistant_otter_prefs_save_failed", traceId = null))
                }
            }
            logger("assistant_otter_prefs save_result result=${if (result is DataResult.Content) "content" else "error"}")
        }.await()
        return true
    }
}

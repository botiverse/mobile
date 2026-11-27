package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

private val signedInDevicesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Settings › Account › Signed-in devices: the person's own sign-ins (botiverse/slock staging
 * 33f441958f82ce0178cc7f48232d41b20907e50c, server `routes/mySessions.ts` /
 * `services/mySessions.ts`, web `features/settings/account/mySessionsApi.ts`):
 *
 *     GET    /api/me/sessions                          -> { sessions, othersLostDeviceReach }
 *     DELETE /api/me/sessions/:familyId { reason }     -> { cascade }
 *     POST   /api/me/sessions/revoke-others { reason } -> { revoked, cascade }
 *
 * `reason` is the person's choice, never defaulted: `sign_out` (what the sign-in approved keeps
 * working) or `lost_device` (that goes too: the Computers and agent keys of `lostDeviceReach`).
 * Refusals by code: 403 `reauth_required` (POST /api/auth/second-factor/reauth/password first),
 * 403 `second_factor_step_up_required` (`lost_device` only: [SecondFactorStepUpCoordinator]),
 * 404 `session_not_found` (gone meanwhile), 400 `current_session`, 403 `session_family_required`.
 * `platform` and `version` are the client's own claims, display only.
 */
private const val MY_SESSIONS_PATH = "me/sessions"
private const val DISCONNECT_PREVIEW_PATH = "me/sessions/disconnect-preview"
private const val REAUTH_PASSWORD_PATH = "auth/second-factor/reauth/password"
private const val FORGOT_PASSWORD_PATH = "auth/forgot-password"
private const val SECOND_FACTOR_RECOVERY_REQUEST_PATH = "auth/second-factor/recovery"

@RaftCoreInternalApi const val SIGNED_IN_DEVICES_REAUTH_REQUIRED_CODE = "reauth_required"
@RaftCoreInternalApi const val SIGNED_IN_DEVICES_SESSION_NOT_FOUND_CODE = "session_not_found"
@RaftCoreInternalApi const val SIGNED_IN_DEVICES_CURRENT_SESSION_CODE = "current_session"

/** Why a sign-in is signed out (the wire `reason`). */
enum class SignedInDeviceRevokeReason(val wireName: String) {
    /** Logout-like: what it approved keeps working. */
    SignOut("sign_out"),
    /** A security revoke: the Computers and agent keys it approved are disconnected too. */
    LostDevice("lost_device"),
}

/** Live Computers and agent keys a `lost_device` revoke also turns off. */
data class SignedInDeviceLostReach(val computers: Int = 0, val agentKeys: Int = 0) {
    val any: Boolean get() = computers > 0 || agentKeys > 0
}

/** What the device is, by the client's own claim (web `signedInDeviceLabel`); a browser tab and an installed web app are both Web. */
enum class SignedInDevicePlatform { Web, Desktop, Ios, Android, HarmonyOs, Unknown }

/** One sign-in (a session family) as GET /me/sessions lists it. */
data class SignedInDevice(
    val id: String,
    /** The raw claim (`browser`, `pwa`, `desktop_app`, `ios`, `android`, `harmonyos`); null when unknown. */
    val platformClaim: String?,
    val version: String?,
    val createdAt: String,
    val lastActiveAt: String,
    /** The sign-in this app is using. */
    val current: Boolean,
    val lostDeviceReach: SignedInDeviceLostReach,
) {
    val platform: SignedInDevicePlatform
        get() = signedInDevicePlatform(platformClaim)
}

data class SignedInDeviceList(
    val sessions: List<SignedInDevice>,
    val othersLostDeviceReach: SignedInDeviceLostReach,
) {
    /** Another sign-in is listed (not just this one). */
    val othersListed: Boolean get() = sessions.any { !it.current }

    /**
     * Sign out of all other devices shows: another sign-in, or (with none left) Computers or agent
     * keys that earlier signed-out ones approved, which `lost_device` still reaches.
     */
    val canSignOutOthers: Boolean get() = othersListed || othersLostDeviceReach.any
}

/** done: the lost-device revoke finished; pending: the hourly repair finishes it; none: `sign_out`. */
enum class SignedInDeviceCascade { Done, Pending, None }

data class SignedInDevicesRevokedOthers(val revoked: Int, val cascade: SignedInDeviceCascade)

/** Kind of Computer row returned by `GET /api/me/sessions/disconnect-preview` (`slock#17061`). */
enum class SecurityRevokePreviewComputerKind { Computer, LegacyMachine }

/** One Computer that a security action (`lost_device` or password change) would disconnect. */
data class SecurityRevokePreviewComputer(
    val kind: SecurityRevokePreviewComputerKind = SecurityRevokePreviewComputerKind.Computer,
    val id: String,
    val name: String,
    val serverId: String,
    val serverName: String,
    val agentCount: Int = 0,
)

/**
 * Named preview of what a security action disconnects (`GET /api/me/sessions/disconnect-preview`,
 * `botiverse/slock` #17061 / #17069 / #17081). `agentKeys` is parsed for wire fidelity but never
 * named in UI (`#17081`).
 */
data class SecurityRevokePreview(
    val computers: List<SecurityRevokePreviewComputer> = emptyList(),
    val agentKeys: Int = 0,
)

/** Query target for `GET /api/me/sessions/disconnect-preview`. */
sealed interface SignedInDisconnectPreviewTarget {
    data class LostDeviceOne(val familyId: String) : SignedInDisconnectPreviewTarget
    data object LostDeviceOthers : SignedInDisconnectPreviewTarget
    data object PasswordChange : SignedInDisconnectPreviewTarget
}

/** POST /auth/second-factor/reauth/password's answer (web PasskeysSection `ReauthDialog` with `useFailureCopy`). */
sealed interface SignedInDevicesReauthResult {
    data object Verified : SignedInDevicesReauthResult
    data object PasswordIncorrect : SignedInDevicesReauthResult
    /** [lockedUntilEpochMillis]: null when the time could not be read. */
    data class Locked(val lockedUntilEpochMillis: Long?) : SignedInDevicesReauthResult
    data object Failed : SignedInDevicesReauthResult
}

@Serializable
@RaftCoreInternalApi data class SignedInDeviceRevokeBody(val reason: String)

@Serializable
@RaftCoreInternalApi data class SignedInDevicesReauthBody(val password: String)

@Serializable
@RaftCoreInternalApi data class SignedInDevicesEmailBody(val email: String)

@Serializable
private data class SignedInDevicesRefusalDto(
    val error: String? = null,
    val code: String? = null,
    val lockedUntil: String? = null,
)

/** `browser` / `pwa` are Web, and so on; anything else (or none, a sign-in from before platforms were recorded) is Unknown. */
fun signedInDevicePlatform(claim: String?): SignedInDevicePlatform =
    when (claim) {
        "browser", "pwa" -> SignedInDevicePlatform.Web
        "desktop_app" -> SignedInDevicePlatform.Desktop
        "ios" -> SignedInDevicePlatform.Ios
        "android" -> SignedInDevicePlatform.Android
        "harmonyos" -> SignedInDevicePlatform.HarmonyOs
        else -> SignedInDevicePlatform.Unknown
    }

private fun JsonElement?.countOrZero(): Int {
    val number = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return 0
    return if (number.isFinite() && number > 0) number.toInt() else 0
}

private fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonElement?.reach(): SignedInDeviceLostReach {
    val reach = this as? JsonObject ?: return SignedInDeviceLostReach()
    return SignedInDeviceLostReach(computers = reach["computers"].countOrZero(), agentKeys = reach["agentKeys"].countOrZero())
}

/**
 * GET /me/sessions's body (web `parseMySessions`): rows without an id or times are left out, counts
 * that are not positive numbers read as 0. Null when the body is not an object.
 */
fun parseSharedSignedInDevices(bodyText: String?): SignedInDeviceList? {
    val body =
        bodyText?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { signedInDevicesJson.parseToJsonElement(text).jsonObject }.getOrNull()
        } ?: return null
    val rows = body["sessions"] as? JsonArray ?: JsonArray(emptyList())
    val sessions =
        rows.mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val createdAt = (row["createdAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val lastActiveAt = (row["lastActiveAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            SignedInDevice(
                id = id,
                platformClaim = row["platform"].textOrNull(),
                version = row["version"].textOrNull(),
                createdAt = createdAt,
                lastActiveAt = lastActiveAt,
                current = (row["current"] as? JsonPrimitive)?.booleanOrNull == true,
                lostDeviceReach = row["lostDeviceReach"].reach(),
            )
        }
    return SignedInDeviceList(sessions = sessions, othersLostDeviceReach = body["othersLostDeviceReach"].reach())
}

private fun JsonObject?.cascade(): SignedInDeviceCascade =
    when ((this?.get("cascade") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "done" -> SignedInDeviceCascade.Done
        "pending" -> SignedInDeviceCascade.Pending
        else -> SignedInDeviceCascade.None
    }

private fun String?.jsonObjectOrNull(): JsonObject? =
    this?.takeIf { it.isNotBlank() }?.let { text -> runCatching { signedInDevicesJson.parseToJsonElement(text).jsonObject }.getOrNull() }

/** DELETE /me/sessions/:id's `{ cascade }` (anything unknown reads as none). */
fun parseSharedSignedInDeviceCascade(bodyText: String?): SignedInDeviceCascade = bodyText.jsonObjectOrNull().cascade()

/** POST /me/sessions/revoke-others's `{ revoked, cascade }`. */
fun parseSharedSignedInDevicesRevokedOthers(bodyText: String?): SignedInDevicesRevokedOthers {
    val body = bodyText.jsonObjectOrNull()
    return SignedInDevicesRevokedOthers(revoked = body?.get("revoked").countOrZero(), cascade = body.cascade())
}

/**
 * Parses `GET /api/me/sessions/disconnect-preview` (web `parseDisconnectPreview`, `slock#17069`):
 * rows without string `id`, `name`, `serverId`, and `serverName` are omitted; non-positive counts
 * read as 0. Returns `null` when the body is not a JSON object.
 */
fun parseSharedDisconnectPreview(bodyText: String?): SecurityRevokePreview? {
    val body = bodyText.jsonObjectOrNull() ?: return null
    val rows = body["computers"] as? JsonArray ?: JsonArray(emptyList())
    val computers =
        rows.mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val serverId = (row["serverId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val serverName = (row["serverName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val kind =
                if ((row["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "legacy_machine") {
                    SecurityRevokePreviewComputerKind.LegacyMachine
                } else {
                    SecurityRevokePreviewComputerKind.Computer
                }
            SecurityRevokePreviewComputer(
                kind = kind,
                id = id,
                name = name,
                serverId = serverId,
                serverName = serverName,
                agentCount = row["agentCount"].countOrZero(),
            )
        }
    return SecurityRevokePreview(computers = computers, agentKeys = body["agentKeys"].countOrZero())
}

/** Query path for `GET /api/me/sessions/disconnect-preview`. */
@RaftCoreInternalApi fun signedInDisconnectPreviewPath(target: SignedInDisconnectPreviewTarget): String =
    when (target) {
        is SignedInDisconnectPreviewTarget.LostDeviceOne ->
            "$DISCONNECT_PREVIEW_PATH?action=lost_device_one&familyId=${encodeSignedInDevicePathSegment(target.familyId)}"
        SignedInDisconnectPreviewTarget.LostDeviceOthers ->
            "$DISCONNECT_PREVIEW_PATH?action=lost_device_others"
        SignedInDisconnectPreviewTarget.PasswordChange ->
            "$DISCONNECT_PREVIEW_PATH?action=password_change"
    }

/**
 * Fetches `GET /api/me/sessions/disconnect-preview` (web `fetchSignedInDisconnectPreview`).
 * Returns `null` on any non-2xx or network error so the caller keeps its general notice.
 */
@RaftCoreInternalApi suspend fun RaftNetworkClient.fetchSharedSignedInDisconnectPreview(
    target: SignedInDisconnectPreviewTarget,
): SecurityRevokePreview? =
    try {
        val response = request(DataRequestPriority.Visible, signedInDisconnectPreviewPath(target))
        if (response.isSuccessful) parseSharedDisconnectPreview(response.bodyText) else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

fun buildSharedSignedInDeviceRevokeBody(reason: SignedInDeviceRevokeReason): String =
    signedInDevicesJson.encodeToString(SignedInDeviceRevokeBody.serializer(), SignedInDeviceRevokeBody(reason.wireName))

private fun parseRefusal(bodyText: String?): SignedInDevicesRefusalDto? =
    bodyText?.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { signedInDevicesJson.decodeFromString(SignedInDevicesRefusalDto.serializer(), text) }.getOrNull()
    }

/** A refused sign-in request: the step-up challenge when it is one, else the server's code and sentence. */
@RaftCoreInternalApi fun <T> HomeNetworkResponse.signedInDevicesError(prefix: String): DataResult<T> {
    secondFactorStepUpErrorOrNull<T>()?.let { return it }
    val body = bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText
    val refusal = parseRefusal(body)
    return DataResult.Error(
        ScreenError(
            code = "${prefix}_http_$code",
            retryable = code >= 500,
            serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
            serverCode = refusal?.code,
            traceId = traceId,
        )
    )
}

/** POST /reauth/password's answer by status and code (204 verified; `password_incorrect`, `reauth_locked`). */
fun parseSharedSignedInDevicesReauthResult(statusCode: Int, bodyText: String?): SignedInDevicesReauthResult {
    if (statusCode in 200..299) return SignedInDevicesReauthResult.Verified
    val refusal = parseRefusal(bodyText)
    return when (refusal?.code) {
        "password_incorrect" -> SignedInDevicesReauthResult.PasswordIncorrect
        "reauth_locked" ->
            SignedInDevicesReauthResult.Locked(
                refusal.lockedUntil?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
            )
        else -> SignedInDevicesReauthResult.Failed
    }
}

/** A recovery email's answer: sent, 429 (too many requests), or anything else. */
fun parseSharedRecoveryEmailResult(statusCode: Int): SecondFactorRecoveryEmailResult =
    when {
        statusCode in 200..299 -> SecondFactorRecoveryEmailResult.Sent
        statusCode == 429 -> SecondFactorRecoveryEmailResult.TooManyRequests
        else -> SecondFactorRecoveryEmailResult.Failed
    }

@RaftCoreInternalApi interface SignedInDevicesDataSource {
    /** The list; Content(null) when the server has no such list (404). */
    suspend fun list(): DataResult<SignedInDeviceList?>
    /** Named disconnect preview for a `lost_device` revoke; null on failure so the dialog keeps its count line only. */
    suspend fun disconnectPreview(target: SignedInDisconnectPreviewTarget): SecurityRevokePreview? = null
    suspend fun revoke(id: String, reason: SignedInDeviceRevokeReason): DataResult<SignedInDeviceCascade>
    suspend fun revokeOthers(reason: SignedInDeviceRevokeReason): DataResult<SignedInDevicesRevokedOthers>
    suspend fun reauthenticateWithPassword(password: String): SignedInDevicesReauthResult
    suspend fun stepUp(answer: SecondFactorStepUpAnswer): SecondFactorStepUpResult
    /** The step-up's passkey assertion options (SecondFactorPasskeyStepUpApi.kt). */
    suspend fun passkeyAssertionOptions(rp: String?): SecondFactorPasskeyOptionsResult =
        SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.Failed(serverCode = "second_factor_step_up_unavailable"))
    /** The step-up's Continue in browser (AppWebStepUp.kt); null where there is none. */
    fun webStepUp(): SecondFactorWebStepUp? = null
    suspend fun requestPasswordEmail(email: String): SecondFactorRecoveryEmailResult
    suspend fun requestTwoStepReset(email: String): SecondFactorRecoveryEmailResult
}

@RaftCoreInternalApi class NetworkSignedInDevicesDataSource(private val networkClient: RaftNetworkClient) : SignedInDevicesDataSource {
    private val stepUpRepository = SecondFactorStepUpRepository(networkClient)

    private suspend inline fun <T> guarded(failure: () -> T, block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure()
        }

    override suspend fun list(): DataResult<SignedInDeviceList?> =
        guarded({ DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null)) }) {
            // On-screen rows of the open Account page, read again after every sign-out.
            val response = networkClient.request(DataRequestPriority.Visible, MY_SESSIONS_PATH)
            when {
                response.code == 404 -> DataResult.Content<SignedInDeviceList?>(null)
                !response.isSuccessful -> response.signedInDevicesError<SignedInDeviceList?>("signed_in_devices")
                else ->
                    parseSharedSignedInDevices(response.bodyText)?.let { DataResult.Content<SignedInDeviceList?>(it) }
                        ?: DataResult.Error(
                            ScreenError(code = "signed_in_devices_contract_mismatch", retryable = false, traceId = response.traceId)
                        )
            }
        }

    override suspend fun disconnectPreview(target: SignedInDisconnectPreviewTarget): SecurityRevokePreview? =
        networkClient.fetchSharedSignedInDisconnectPreview(target)

    override suspend fun revoke(id: String, reason: SignedInDeviceRevokeReason): DataResult<SignedInDeviceCascade> =
        guarded({ DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null)) }) {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "$MY_SESSIONS_PATH/${encodeSignedInDevicePathSegment(id)}",
                    method = "DELETE",
                    bodyText = buildSharedSignedInDeviceRevokeBody(reason),
                )
            if (response.isSuccessful) {
                DataResult.Content(parseSharedSignedInDeviceCascade(response.bodyText))
            } else {
                response.signedInDevicesError<SignedInDeviceCascade>("signed_in_device_revoke")
            }
        }

    override suspend fun revokeOthers(reason: SignedInDeviceRevokeReason): DataResult<SignedInDevicesRevokedOthers> =
        guarded({ DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null)) }) {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "$MY_SESSIONS_PATH/revoke-others",
                    method = "POST",
                    bodyText = buildSharedSignedInDeviceRevokeBody(reason),
                )
            if (response.isSuccessful) {
                DataResult.Content(parseSharedSignedInDevicesRevokedOthers(response.bodyText))
            } else {
                response.signedInDevicesError<SignedInDevicesRevokedOthers>("signed_in_devices_revoke_others")
            }
        }

    override suspend fun reauthenticateWithPassword(password: String): SignedInDevicesReauthResult =
        guarded({ SignedInDevicesReauthResult.Failed }) {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = REAUTH_PASSWORD_PATH,
                    method = "POST",
                    bodyText = signedInDevicesJson.encodeToString(SignedInDevicesReauthBody.serializer(), SignedInDevicesReauthBody(password)),
                )
            parseSharedSignedInDevicesReauthResult(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText)
        }

    override suspend fun stepUp(answer: SecondFactorStepUpAnswer): SecondFactorStepUpResult = stepUpRepository.stepUp(answer)

    override suspend fun passkeyAssertionOptions(rp: String?): SecondFactorPasskeyOptionsResult =
        stepUpRepository.passkeyAssertionOptions(rp)

    override fun webStepUp(): SecondFactorWebStepUp? = stepUpRepository.webStepUp()

    override suspend fun requestPasswordEmail(email: String): SecondFactorRecoveryEmailResult = email(FORGOT_PASSWORD_PATH, email)

    override suspend fun requestTwoStepReset(email: String): SecondFactorRecoveryEmailResult =
        email(SECOND_FACTOR_RECOVERY_REQUEST_PATH, email)

    private suspend fun email(path: String, email: String): SecondFactorRecoveryEmailResult =
        guarded({ SecondFactorRecoveryEmailResult.Failed }) {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = path,
                    method = "POST",
                    bodyText = signedInDevicesJson.encodeToString(SignedInDevicesEmailBody.serializer(), SignedInDevicesEmailBody(email.trim())),
                )
            parseSharedRecoveryEmailResult(response.code)
        }
}

/**
 * Settings › Account's "Set password by email" and "Set a password first" (web AccountSection
 * `handleSendPasswordSetup`, botiverse/slock staging 4be606f7fd789488e69df1095d4af028cc21ee49,
 * server `routes/auth.ts` `POST /forgot-password`): `{ email }` mails the link that sets a first
 * password. The server answers `{ ok: true, message }` for any address; a refusal keeps its own
 * words (`error`: 400 "Email is required", the limiter's 429 "Too many password reset requests.
 * Please try again later.") and the request's trace ID, which the page shows (web
 * `err.response.data.error`). [SignedInDevicesDataSource.requestPasswordEmail] ("Verify it's
 * you" › Can't verify?) only tells sent, too many and failed apart.
 */
@RaftCoreInternalApi suspend fun RaftNetworkClient.sendSharedPasswordSetupEmail(email: String): DataResult<Unit> =
    try {
        val response =
            request(
                priority = DataRequestPriority.Foreground,
                path = FORGOT_PASSWORD_PATH,
                method = "POST",
                bodyText = signedInDevicesJson.encodeToString(SignedInDevicesEmailBody.serializer(), SignedInDevicesEmailBody(email.trim())),
            )
        if (response.isSuccessful) DataResult.Content(Unit) else response.signedInDevicesError<Unit>("account_password_setup")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
    }

/** A sign-in id in a path (web `encodeURIComponent`): ids are UUIDs, but anything else is escaped. */
@RaftCoreInternalApi fun encodeSignedInDevicePathSegment(id: String): String =
    buildString {
        id.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }

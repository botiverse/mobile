package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable

/**
 * The platform port for a native passkey assertion: the server's assertion options in, the
 * browser-shaped `AuthenticationResponseJSON` out (SecondFactorPasskeyStepUpApi.kt), or why not.
 * The relying party and the credentials come only from [RaftNativePasskeyRequest]; no actual picks
 * an RP ID of its own.
 *
 * - Android: Credential Manager (`GetPublicKeyCredentialOption(requestJson)`) from the current
 *   Activity, on the main thread. Digital Asset Links with `get_login_creds` on the RP ID's domain
 *   let the app's `android:apk-key-hash:` origin assert (slock #15468 allow-list).
 * - iOS: `ASAuthorizationPlatformPublicKeyCredentialProvider` for the options' `rpId` (the current
 *   RP group only, [raftNativePasskeyRpGroup]). An RP ID the app's `webcredentials:` entitlement
 *   and that domain's AASA don't associate is refused by the system and becomes [Unsupported]
 *   (the dialog then falls back to the other methods or the web, as before).
 * - Desktop: always [Unsupported].
 */
fun interface RaftNativePasskeyAssertion {
    suspend fun assert(request: RaftNativePasskeyRequest): RaftNativePasskeyAssertionResult
}

/** [requestJson]: the server's `options` verbatim; [options]: its checked fields. */
data class RaftNativePasskeyRequest(
    val requestJson: String,
    val options: SecondFactorPasskeyRequestOptionsDto,
)

sealed interface RaftNativePasskeyAssertionResult {
    /** [responseJson]: `AuthenticationResponseJSON` for the step-up's `webauthn`. */
    data class Success(val responseJson: String) : RaftNativePasskeyAssertionResult
    /** The person closed the system prompt. */
    data object Cancelled : RaftNativePasskeyAssertionResult
    /** No passkey for this relying party on this device (or in its password manager). */
    data object NoCredential : RaftNativePasskeyAssertionResult
    /** This device or build can't assert for this relying party. */
    data object Unsupported : RaftNativePasskeyAssertionResult
    /** Anything else; [reason] is a log-safe code. */
    data class Error(val reason: String) : RaftNativePasskeyAssertionResult
}

/** Whether this platform offers Use passkey at all (Android, iOS: true; desktop: false). */
@RaftCoreInternalApi expect fun raftNativePasskeyAssertionAvailable(): Boolean

/**
 * The RP group this platform asks the options call for (`rp`, [SECOND_FACTOR_PASSKEY_RP_CURRENT] /
 * [SECOND_FACTOR_PASSKEY_RP_LEGACY]): null on Android (the server's default; both RP IDs assert
 * there, and the coordinator tries the other group when this device has none in the first),
 * `current` on iOS (its `webcredentials:` entitlement names the current RP ID only; a person with
 * only legacy passkeys gets 404 `passkey_rp_unavailable` and answers on the web).
 */
@RaftCoreInternalApi expect fun raftNativePasskeyRpGroup(): String?

/** The platform's port for the current screen (Android: its Activity; iOS: its window). */
@Composable
@RaftCoreInternalApi expect fun rememberRaftNativePasskeyAssertion(): RaftNativePasskeyAssertion

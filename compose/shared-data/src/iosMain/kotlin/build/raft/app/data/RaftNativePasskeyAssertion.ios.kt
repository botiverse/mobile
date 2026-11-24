@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import build.raft.app.raftLogInfo
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialAssertion
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialDescriptor
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialProvider
import platform.AuthenticationServices.ASAuthorizationPublicKeyCredentialUserVerificationPreferenceRequired
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.posix.memcpy

@RaftCoreInternalApi actual fun raftNativePasskeyAssertionAvailable(): Boolean = true

/**
 * iOS asks for the current RP group only: `compose/iosApp/iosApp/iosApp.entitlements` lists
 * `webcredentials:` for the current RP ID's domain, never the legacy one. The RP ID itself comes
 * from the server's options; one the entitlement and that domain's AASA don't associate is refused
 * by AuthenticationServices (not handled / failed) and becomes
 * [RaftNativePasskeyAssertionResult.Unsupported].
 */
@RaftCoreInternalApi actual fun raftNativePasskeyRpGroup(): String? = SECOND_FACTOR_PASSKEY_RP_CURRENT

private const val PASSKEY_LOG_TAG = "RaftPasskey"
private const val AS_AUTHORIZATION_ERROR_DOMAIN = "com.apple.AuthenticationServices.AuthorizationError"
private const val AS_AUTHORIZATION_ERROR_CANCELED = 1001L
private const val AS_AUTHORIZATION_ERROR_NOT_HANDLED = 1003L
private const val AS_AUTHORIZATION_ERROR_FAILED = 1004L

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
private val base64UrlOut = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@Composable
@RaftCoreInternalApi actual fun rememberRaftNativePasskeyAssertion(): RaftNativePasskeyAssertion {
    val viewController = LocalUIViewController.current
    return remember(viewController) { IosRaftNativePasskeyAssertion { viewController.view.window } }
}

/** Sessions in flight: ASAuthorizationController holds its delegate weakly. Main thread only. */
private val inFlight = mutableSetOf<IosPasskeySession>()

private class IosRaftNativePasskeyAssertion(private val window: () -> UIWindow?) : RaftNativePasskeyAssertion {
    override suspend fun assert(request: RaftNativePasskeyRequest): RaftNativePasskeyAssertionResult {
        val options = request.options
        val rpId = options.rpId
        val result =
            if (rpId.isNullOrBlank()) {
                RaftNativePasskeyAssertionResult.Error("options_without_rp_id")
            } else {
                val challenge = options.challenge.base64UrlBytes()
                val allowed = options.allowCredentials.map { it.id.base64UrlBytes() }
                if (challenge == null || allowed.any { it == null }) {
                    RaftNativePasskeyAssertionResult.Error("options_not_base64url")
                } else {
                    withContext(Dispatchers.Main) { perform(rpId, challenge, allowed.filterNotNull()) }
                }
            }
        log(
            when (result) {
                is RaftNativePasskeyAssertionResult.Success -> "success"
                RaftNativePasskeyAssertionResult.Cancelled -> "cancelled"
                RaftNativePasskeyAssertionResult.NoCredential -> "no_credential"
                RaftNativePasskeyAssertionResult.Unsupported -> "unsupported"
                is RaftNativePasskeyAssertionResult.Error -> "error:${result.reason}"
            }
        )
        return result
    }

    private suspend fun perform(rpId: String, challenge: ByteArray, allowed: List<ByteArray>): RaftNativePasskeyAssertionResult =
        suspendCancellableCoroutine { continuation ->
            val provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier = rpId)
            val assertionRequest = provider.createCredentialAssertionRequestWithChallenge(challenge.toNSData())
            assertionRequest.allowedCredentials =
                allowed.map { id -> ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID = id.toNSData()) }
            ASAuthorizationPublicKeyCredentialUserVerificationPreferenceRequired?.let { assertionRequest.userVerificationPreference = it }
            val controller = ASAuthorizationController(authorizationRequests = listOf(assertionRequest))
            lateinit var session: IosPasskeySession
            session =
                IosPasskeySession(window) { result ->
                    inFlight.remove(session)
                    if (continuation.isActive) continuation.resume(result)
                }
            inFlight += session
            controller.delegate = session
            controller.presentationContextProvider = session
            controller.performRequests()
        }

    private fun log(outcome: String) {
        raftLogInfo(PASSKEY_LOG_TAG, "passkey_assertion_native platform=ios outcome=$outcome")
    }
}

private class IosPasskeySession(
    private val window: () -> UIWindow?,
    private val onDone: (RaftNativePasskeyAssertionResult) -> Unit,
) : NSObject(), ASAuthorizationControllerDelegateProtocol, ASAuthorizationControllerPresentationContextProvidingProtocol {
    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
        val credential = didCompleteWithAuthorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion
        onDone(credential?.toResult() ?: RaftNativePasskeyAssertionResult.Error("unexpected_credential_type"))
    }

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
        val code = didCompleteWithError.code
        onDone(
            when {
                didCompleteWithError.domain != AS_AUTHORIZATION_ERROR_DOMAIN -> RaftNativePasskeyAssertionResult.Error("ns:$code")
                code == AS_AUTHORIZATION_ERROR_CANCELED -> RaftNativePasskeyAssertionResult.Cancelled
                // Not handled / failed: among others the app is not associated with the RP ID's
                // domain (no webcredentials in its AASA yet). The web path still works.
                code == AS_AUTHORIZATION_ERROR_NOT_HANDLED || code == AS_AUTHORIZATION_ERROR_FAILED ->
                    RaftNativePasskeyAssertionResult.Unsupported.also {
                        raftLogInfo(PASSKEY_LOG_TAG, "passkey_assertion_native platform=ios as_error=$code")
                    }
                else -> RaftNativePasskeyAssertionResult.Error("as:$code")
            }
        )
    }

    override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor =
        window() ?: UIApplication.sharedApplication.keyWindow ?: UIWindow()
}

/** The same `AuthenticationResponseJSON` the web sends (`PublicKeyCredential.toJSON()`), base64url. */
private fun ASAuthorizationPlatformPublicKeyCredentialAssertion.toResult(): RaftNativePasskeyAssertionResult {
    val id = credentialID.base64UrlString()
    val clientData = rawClientDataJSON.base64UrlString()
    val authenticatorData = rawAuthenticatorData.base64UrlString()
    val signature = signature.base64UrlString()
    if (id == null || clientData == null || authenticatorData == null || signature == null) {
        return RaftNativePasskeyAssertionResult.Error("assertion_incomplete")
    }
    val assertion =
        SecondFactorPasskeyAssertionDto(
            id = id,
            rawId = id,
            response =
                SecondFactorPasskeyAssertionResponseDto(
                    clientDataJSON = clientData,
                    authenticatorData = authenticatorData,
                    signature = signature,
                    userHandle = userID.base64UrlString(),
                ),
            authenticatorAttachment = "platform",
        )
    return RaftNativePasskeyAssertionResult.Success(encodeSharedPasskeyAssertion(assertion))
}

private fun String.base64UrlBytes(): ByteArray? = runCatching { base64Url.decode(this) }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun NSData?.base64UrlString(): String? {
    val data = this ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return base64UrlOut.encode(bytes)
}

private fun ByteArray.toNSData(): NSData =
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import build.raft.app.raftLogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RaftCoreInternalApi actual fun raftNativePasskeyAssertionAvailable(): Boolean = true

// Android asserts for either RP ID (assetlinks with get_login_creds on both domains).
@RaftCoreInternalApi actual fun raftNativePasskeyRpGroup(): String? = null

private const val PASSKEY_LOG_TAG = "RaftPasskey"

@Composable
@RaftCoreInternalApi actual fun rememberRaftNativePasskeyAssertion(): RaftNativePasskeyAssertion {
    val activity = LocalContext.current.findRaftActivity()
    return remember(activity) { AndroidRaftNativePasskeyAssertion(activity) }
}

private fun Context.findRaftActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Credential Manager's passkey `getCredential`, from [activity] (it hosts the system sheet) on
 * the main thread. Google Password Manager passkeys need Play services (`credentials-play-services-auth`);
 * Android 14+ also offers third-party providers and another device's passkey (hybrid).
 */
private class AndroidRaftNativePasskeyAssertion(private val activity: Activity?) : RaftNativePasskeyAssertion {
    override suspend fun assert(request: RaftNativePasskeyRequest): RaftNativePasskeyAssertionResult {
        val host = activity ?: return RaftNativePasskeyAssertionResult.Unsupported.also { log("no_activity") }
        return withContext(Dispatchers.Main) {
            val result =
                try {
                    val response =
                        CredentialManager.create(host).getCredential(
                            host,
                            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request.requestJson)))
                        )
                    when (val credential = response.credential) {
                        is PublicKeyCredential -> RaftNativePasskeyAssertionResult.Success(credential.authenticationResponseJson)
                        else -> RaftNativePasskeyAssertionResult.Error("unexpected_credential_type")
                    }
                } catch (_: GetCredentialCancellationException) {
                    RaftNativePasskeyAssertionResult.Cancelled
                } catch (_: NoCredentialException) {
                    RaftNativePasskeyAssertionResult.NoCredential
                } catch (_: GetCredentialProviderConfigurationException) {
                    RaftNativePasskeyAssertionResult.Unsupported
                } catch (_: GetCredentialUnsupportedException) {
                    RaftNativePasskeyAssertionResult.Unsupported
                } catch (_: GetCredentialInterruptedException) {
                    RaftNativePasskeyAssertionResult.Error("interrupted")
                } catch (error: GetPublicKeyCredentialDomException) {
                    // NotAllowedError: the prompt was dismissed or timed out, as the web's isPasskeyCancel.
                    if (error.domError is NotAllowedError) {
                        RaftNativePasskeyAssertionResult.Cancelled
                    } else {
                        RaftNativePasskeyAssertionResult.Error("dom:${error.domError.type.substringAfterLast('.')}")
                    }
                } catch (error: GetCredentialException) {
                    RaftNativePasskeyAssertionResult.Error(error.type.substringAfterLast('.'))
                }
            log(result.logName)
            result
        }
    }

    private fun log(outcome: String) {
        raftLogInfo(PASSKEY_LOG_TAG, "passkey_assertion_native platform=android outcome=$outcome")
    }
}

private val RaftNativePasskeyAssertionResult.logName: String
    get() =
        when (this) {
            is RaftNativePasskeyAssertionResult.Success -> "success"
            RaftNativePasskeyAssertionResult.Cancelled -> "cancelled"
            RaftNativePasskeyAssertionResult.NoCredential -> "no_credential"
            RaftNativePasskeyAssertionResult.Unsupported -> "unsupported"
            is RaftNativePasskeyAssertionResult.Error -> "error:$reason"
        }

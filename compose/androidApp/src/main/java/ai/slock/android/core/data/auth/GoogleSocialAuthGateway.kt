package ai.slock.android.core.data.auth

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import ai.slock.android.BuildConfig
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom

class GoogleSocialAuthGateway(
    private val credentialManagerFactory: (Activity) -> CredentialManager = { activity ->
        CredentialManager.create(activity)
    },
    private val clientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
) {
    fun isNativeConfigured(): Boolean = clientId.isNotBlank()

    suspend fun signIn(activity: Activity): GoogleSocialAuthResult {
        if (clientId.isBlank()) {
            return GoogleSocialAuthResult.Failure(
                "Google native sign-in is not configured for this build."
            )
        }

        val nonce = generateSecureRandomNonce()
        val option =
            GetSignInWithGoogleOption.Builder(serverClientId = clientId)
                .setNonce(nonce)
                .build()
        val request =
            GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

        return try {
            val response =
                credentialManagerFactory(activity).getCredential(
                    context = activity,
                    request = request
                )
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleSocialAuthResult.Failure("Google sign-in returned an unsupported credential.")
            }

            val googleCredential =
                try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (_: GoogleIdTokenParsingException) {
                    return GoogleSocialAuthResult.Failure("Google sign-in returned an invalid ID token.")
                }

            GoogleSocialAuthResult.IdToken(
                idToken = googleCredential.idToken,
                nonce = nonce
            )
        } catch (_: GetCredentialCancellationException) {
            GoogleSocialAuthResult.Cancelled
        } catch (error: GetCredentialException) {
            GoogleSocialAuthResult.Failure(
                error.message ?: "Google sign-in is unavailable on this device."
            )
        }
    }

    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(
            randomBytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }
}

sealed interface GoogleSocialAuthResult {
    data class IdToken(
        val idToken: String,
        val nonce: String
    ) : GoogleSocialAuthResult

    data object Cancelled : GoogleSocialAuthResult

    data class Failure(
        val message: String
    ) : GoogleSocialAuthResult
}

suspend fun SharedAuthDataSource.completeGoogleSocialAuth(
    result: GoogleSocialAuthResult
): SharedAuthResult<ai.slock.compose.data.StoredAuthSession> =
    when (result) {
        is GoogleSocialAuthResult.IdToken ->
            completeGoogleIdTokenLogin(idToken = result.idToken, nonce = result.nonce)
        GoogleSocialAuthResult.Cancelled ->
            SharedAuthResult.Failure(
                ai.slock.compose.data.SharedAuthErrorCode.Validation,
                "Google sign-in canceled."
            )
        is GoogleSocialAuthResult.Failure ->
            SharedAuthResult.Failure(
                ai.slock.compose.data.SharedAuthErrorCode.Network,
                result.message
            )
    }

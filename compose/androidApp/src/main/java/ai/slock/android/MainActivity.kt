package ai.slock.android

import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SlockDiagnosticsLogger.d("SlockBoot", "main_activity_redirect_to_shared_shell")
        if (!handleSocialAuthCallback(intent)) {
            openSharedShell()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleSocialAuthCallback(intent)) {
            openSharedShell()
        }
    }

    private fun openSharedShell() {
        SlockKuiklyActivity.startAppShell(this, clearTask = true)
        finish()
    }

    private fun handleSocialAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!uri.isSlockSocialAuthCallback()) return false
        setIntent(Intent(this, MainActivity::class.java))
        val provider = uri.getQueryParameter("provider").orEmpty().lowercase()
        if (provider != "google") {
            Toast.makeText(this, "This sign-in provider is not available on Android yet", Toast.LENGTH_SHORT).show()
            return true
        }
        val error = uri.getQueryParameter("error") ?: uri.getQueryParameter("error_description")
        if (!error.isNullOrBlank()) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Google sign-in did not return an authorization code", Toast.LENGTH_SHORT).show()
            return true
        }
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SharedAuthDataSource(
                        api = AndroidAuthApiFactory.createAuthApi(),
                        sessionStore = EncryptedAuthSessionStore(applicationContext)
                    ).completeSocialLogin(provider = provider, code = code)
                }
            when (result) {
                is SharedAuthResult.Success -> {
                    Toast.makeText(this@MainActivity, "Signed in with Google", Toast.LENGTH_SHORT).show()
                    openSharedShell()
                }
                is SharedAuthResult.Failure ->
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                SharedAuthResult.Unauthenticated ->
                    Toast.makeText(this@MainActivity, "Google sign-in failed. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }
}

private fun Uri.isSlockSocialAuthCallback(): Boolean =
    scheme == "https" &&
        host in setOf("app.slock.ai", "app.raft.build") &&
        getQueryParameter("auth_callback") == "social"

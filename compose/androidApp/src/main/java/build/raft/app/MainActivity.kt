package build.raft.app

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.shared.SlockKuiklyActivity
import build.raft.app.shared.SlockKuiklyRouteKeys
import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import build.raft.app.shared.withAndroidShellLaunchTarget
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthSession
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SlockAppLog.d(
            "SlockBoot",
            "main_activity_created " +
                "source=${intent.slockLaunchSource()} " +
                "clearTask=${intent.shouldClearTaskForSharedShell()} " +
                "data=${intent.safeDataString()}"
        )
        if (!handleMobileOAuthCallback(intent) && !handleSocialAuthCallback(intent)) {
            openSharedShell(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SlockAppLog.d(
            "SlockBoot",
            "main_activity_new_intent " +
                "source=${intent.slockLaunchSource()} " +
                "clearTask=${intent.shouldClearTaskForSharedShell()} " +
                "data=${intent.safeDataString()}"
        )
        if (!handleMobileOAuthCallback(intent) && !handleSocialAuthCallback(intent)) {
            openSharedShell(intent)
        }
    }

    private fun openSharedShell(launchIntent: Intent? = intent) {
        val pageData = SlockKuiklyActivity.appShellPageDataFromIntent(launchIntent)
        val target = resolveAndroidShellLaunchTarget(pageData)
        val taskLaunch = target.taskLaunchPlan(launchIntent.shouldClearTaskForSharedShell())
        SlockKuiklyActivity.startAppShell(
            this,
            pageData = pageData.withAndroidShellLaunchTarget(target),
            clearTask = taskLaunch.clearTask,
            documentShellId = taskLaunch.documentShellId,
        )
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
                        api = AndroidAuthApiFactory.createAuthApi(applicationContext),
                        sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext)),
                        diagnosticsLogger = ::logSharedAuthDiagnostics
                    ).completeSocialLogin(provider = provider, code = code)
                }
            when (result) {
                is SharedAuthResult.Success ->
                    openPostLoginDestination(result.data)
                is SharedAuthResult.Failure ->
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                SharedAuthResult.Unauthenticated ->
                    Toast.makeText(this@MainActivity, "Google sign-in failed. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun handleMobileOAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!uri.isSlockMobileOAuthCallback()) return false
        setIntent(Intent(this, MainActivity::class.java))
        val pendingStore = AndroidMobileOAuthPendingStore(applicationContext)
        val pending = pendingStore.read()
        if (pending == null) {
            pendingStore.clear()
            openAfterMobileOAuthError("Sign-in request expired. Try again.", AndroidMobileOAuth.ModeLogin)
            return true
        }
        val providerName = pending.provider.socialProviderDisplayName()
        val error = uri.getQueryParameter("error") ?: uri.getQueryParameter("error_description")
        if (!error.isNullOrBlank()) {
            pendingStore.clear()
            openAfterMobileOAuthError(error, pending.mode)
            return true
        }
        val handoffCode = uri.getQueryParameter("code")
        if (handoffCode.isNullOrBlank()) {
            pendingStore.clear()
            openAfterMobileOAuthError("$providerName did not return a handoff code", pending.mode)
            return true
        }
        if (pending.mode == AndroidMobileOAuth.ModeLink) {
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        SharedAuthDataSource(
                            api = AndroidAuthApiFactory.createAuthApi(applicationContext),
                            sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext)),
                            diagnosticsLogger = ::logSharedAuthDiagnostics
                        ).completeMobileOAuthLink(
                            provider = pending.provider,
                            code = handoffCode,
                            codeVerifier = pending.codeVerifier
                        )
                    }
                pendingStore.clear()
                when (result) {
                    is SharedAuthResult.Success -> {
                        Toast.makeText(this@MainActivity, "$providerName account linked.", Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                    is SharedAuthResult.Failure -> {
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                    SharedAuthResult.Unauthenticated -> {
                        Toast.makeText(this@MainActivity, "Sign in again to link $providerName.", Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                }
            }
            return true
        }
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SharedAuthDataSource(
                        api = AndroidAuthApiFactory.createAuthApi(applicationContext),
                        sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext)),
                        diagnosticsLogger = ::logSharedAuthDiagnostics
                    ).completeMobileOAuthLogin(
                        code = handoffCode,
                        codeVerifier = pending.codeVerifier
                    )
                }
            when (result) {
                is SharedAuthResult.Success -> {
                    pendingStore.clear()
                    openPostLoginDestination(result.data)
                }
                is SharedAuthResult.Failure -> {
                    if (result.code == SharedAuthErrorCode.LegalAcceptanceRequired) {
                        pendingStore.clear()
                        SlockKuiklyActivity.startAuthLegalAcceptance(
                            this@MainActivity,
                            pageData = mobileOAuthLegalAcceptancePageData(
                                provider = pending.provider,
                                code = handoffCode,
                                codeVerifier = pending.codeVerifier
                            ),
                            clearTask = true
                        )
                        finish()
                    } else {
                        pendingStore.clear()
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
                SharedAuthResult.Unauthenticated -> {
                    pendingStore.clear()
                    Toast.makeText(this@MainActivity, "$providerName sign-in failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun openPostLoginDestination(session: StoredAuthSession) {
        when (postLoginDestination(session.selectedServerId)) {
            PostLoginDestination.AppShell ->
                SlockKuiklyActivity.startAppShell(
                    this,
                    pageData = postLoginAppShellPageData(),
                    clearTask = true
                )
            PostLoginDestination.ChooseServer ->
                SlockKuiklyActivity.startAuthChooseServer(
                    this,
                    clearTask = true
                )
        }
        finish()
    }

    private fun openLoginAfterMobileOAuthError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        SlockKuiklyActivity.start(
            this,
            SlockKuiklyActivity.PAGE_LOGIN,
            pageData = mobileOAuthLoginErrorPageData(message),
            clearTask = true
        )
        finish()
    }

    private fun openAfterMobileOAuthError(message: String, mode: String) {
        if (mode == AndroidMobileOAuth.ModeLink) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            openSettingsAccountAfterMobileOAuthLink()
        } else {
            openLoginAfterMobileOAuthError(message)
        }
    }

    private fun openSettingsAccountAfterMobileOAuthLink() {
        SlockKuiklyActivity.startAppShell(
            this,
            pageData = settingsAccountAppShellPageData(),
            clearTask = true
        )
        finish()
    }
}

internal enum class PostLoginDestination {
    AppShell,
    ChooseServer
}

internal fun postLoginDestination(selectedServerId: String?): PostLoginDestination =
    if (selectedServerId.isNullOrBlank()) {
        PostLoginDestination.ChooseServer
    } else {
        PostLoginDestination.AppShell
    }

internal fun postLoginAppShellPageData(): JSONObject =
    JSONObject()
        .put("slockSuppressRestoreLanding", true)
        .put("slockAuthGateInstanceId", System.nanoTime().toString())

internal fun settingsAccountAppShellPageData(): JSONObject =
    JSONObject()
        .put("slockSuppressRestoreLanding", true)
        .put("slockAuthGateInstanceId", System.nanoTime().toString())
        .put("slockInitialTab", "settings")
        .put("slockInitialSettingsDestination", "account")

internal fun mobileOAuthLoginErrorPageData(message: String): JSONObject =
    JSONObject()
        .put("slockAuthMessage", message)
        .put(SlockKuiklyRouteKeys.AUTH_SOCIAL_PROVIDERS, "google,github")

internal fun mobileOAuthLegalAcceptancePageData(
    provider: String,
    code: String,
    codeVerifier: String
): JSONObject =
    JSONObject()
        .put(SlockKuiklyRouteKeys.AUTH_LEGAL_PROVIDER, provider)
        .put(SlockKuiklyRouteKeys.AUTH_LEGAL_PLATFORM, "android")
        .put(SlockKuiklyRouteKeys.AUTH_LEGAL_CODE, code)
        .put(SlockKuiklyRouteKeys.AUTH_LEGAL_CODE_VERIFIER, codeVerifier)
        .put(SlockKuiklyRouteKeys.AUTH_SOCIAL_PROVIDERS, "google,github")

private fun logSharedAuthDiagnostics(message: String) {
    SlockAppLog.d("SlockKuikly", "shared_auth $message")
}

internal fun Intent?.shouldClearTaskForSharedShell(): Boolean =
    shouldClearTaskForSharedShell(
        action = this?.action,
        dataScheme = this?.data?.scheme,
        dataHost = this?.data?.host,
        appShellPageData = this.isAppShellPageDataIntent()
    )

internal fun Intent?.slockLaunchSource(): String =
    slockLaunchSource(
        action = this?.action,
        dataScheme = this?.data?.scheme,
        dataHost = this?.data?.host,
        appShellPageData = this.isAppShellPageDataIntent()
    )

internal fun shouldClearTaskForSharedShell(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    appShellPageData: Boolean = false
): Boolean =
    !appShellPageData && !isSlockAppWebViewIntent(action, dataScheme, dataHost)

internal fun slockLaunchSource(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    appShellPageData: Boolean = false
): String =
    when {
        appShellPageData -> "app_shell_intent"
        action == null -> "missing"
        isSlockAppWebViewIntent(action, dataScheme, dataHost) -> "app_web_view"
        action == Intent.ACTION_VIEW -> "external_view"
        action == Intent.ACTION_MAIN -> "launcher"
        else -> action.ifBlank { "unknown" }
    }

private fun Intent?.isAppShellPageDataIntent(): Boolean =
    this?.getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME) == SlockKuiklyActivity.PAGE_APP_SHELL

private fun isSlockAppWebViewIntent(action: String?, dataScheme: String?, dataHost: String?): Boolean =
    action == Intent.ACTION_VIEW && isSlockAppWebLink(dataScheme, dataHost)

internal fun Uri.isSlockAppWebLink(): Boolean =
    isSlockAppWebLink(scheme, host)

private fun isSlockAppWebLink(scheme: String?, host: String?): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        host?.lowercase(Locale.US) in setOf("app.slock.ai", "app.raft.build")

private fun Intent?.safeDataString(): String =
    this?.data?.buildUpon()?.clearQuery()?.fragment(null)?.build()?.toString().orEmpty()

private fun Uri.isSlockSocialAuthCallback(): Boolean =
    scheme == "https" &&
        host in setOf("app.slock.ai", "app.raft.build") &&
        getQueryParameter("auth_callback") == "social"

internal fun Uri.isSlockMobileOAuthCallback(): Boolean =
    AndroidMobileOAuth.isCallbackUri(
            scheme = scheme,
            host = host,
            path = path,
            code = getQueryParameter("code"),
            error = getQueryParameter("error") ?: getQueryParameter("error_description")
        )

private fun String.socialProviderDisplayName(): String =
    when (lowercase(Locale.US)) {
        "github" -> "GitHub"
        "google" -> "Google"
        else -> "Social"
    }

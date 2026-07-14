package build.raft.app

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.shared.AndroidShellWindowRegistry
import build.raft.app.shared.SlockKuiklyActivity
import build.raft.app.shared.SlockKuiklyRouteKeys
import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import build.raft.app.shared.withAndroidShellLaunchTarget
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedEmailVerificationResult
import build.raft.app.data.StoredAuthSession
import android.content.Intent
import android.content.pm.PackageManager
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
        if (
            !handleMobileOAuthCallback(intent) &&
            !handleSocialAuthCallback(intent) &&
            !handleEmailVerificationLink(intent) &&
            !handleUnsupportedSlockWebLink(intent)
        ) {
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
        if (
            !handleMobileOAuthCallback(intent) &&
            !handleSocialAuthCallback(intent) &&
            !handleEmailVerificationLink(intent) &&
            !handleUnsupportedSlockWebLink(intent)
        ) {
            openSharedShell(intent)
        }
    }

    /**
     * The manifest intentionally receives the whole Raft web origin so typed callbacks can be
     * added without another install. Anything not consumed above must stay on the web instead of
     * silently opening an empty app shell. The browser component is explicit so Android App Links
     * cannot resolve the fallback straight back to this activity.
     */
    private fun handleUnsupportedSlockWebLink(intent: Intent?): Boolean {
        if (!intent.shouldOpenUnsupportedSlockWebLinkExternally()) return false
        val uri = checkNotNull(intent?.data)
        setIntent(Intent(this, MainActivity::class.java))
        val browserIntent = externalBrowserIntent(uri)
        if (browserIntent == null) {
            SlockAppLog.w(
                "SlockBoot",
                "app_web_link_external_fallback result=no_external_handler " +
                    "target=${uri.safeWebLinkTarget()}"
            )
            Toast.makeText(this, "No browser is available to open this link", Toast.LENGTH_SHORT).show()
        } else {
            SlockAppLog.d(
                "SlockBoot",
                "app_web_link_external_fallback result=opened " +
                    "target=${uri.safeWebLinkTarget()} package=${browserIntent.component?.packageName.orEmpty()}"
            )
            startActivity(browserIntent)
        }
        finish()
        return true
    }

    private fun externalBrowserIntent(uri: Uri): Intent? {
        val implicit =
            Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates =
            packageManager.queryIntentActivities(implicit, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultPackage =
            packageManager
                .resolveActivity(implicit, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        val selectedPackage =
            selectExternalWebHandlerPackage(
                ownPackage = packageName,
                defaultPackage = defaultPackage,
                candidatePackages = candidates.map { it.activityInfo.packageName }
            ) ?: return null
        val selected =
            candidates.firstOrNull { candidate ->
                candidate.activityInfo.packageName == selectedPackage
            } ?: return null
        return implicit.setClassName(
            selected.activityInfo.packageName,
            selected.activityInfo.name
        )
    }

    private fun openSharedShell(launchIntent: Intent? = intent) {
        val pageData = SlockKuiklyActivity.appShellPageDataFromIntent(launchIntent)
        val target = resolveAndroidShellLaunchTarget(pageData)
        val taskLaunch = target.taskLaunchPlan(launchIntent.shouldClearTaskForSharedShell())
        SlockKuiklyActivity.startAppShell(
            this,
            pageData = pageData.withAndroidShellLaunchTarget(target),
            clearTask = taskLaunch.clearTask,
        )
        finish()
    }

    private fun handleEmailVerificationLink(intent: Intent?): Boolean {
        val token = intent.emailVerificationToken() ?: return false
        val hasLiveShellInTask = AndroidShellWindowRegistry.hasShellInTask(taskId)
        // Remove the token-bearing URL from the retained Activity intent before any async work.
        setIntent(Intent(this, MainActivity::class.java))
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SharedAuthDataSource(
                        api = AndroidAuthApiFactory.createAuthApi(applicationContext),
                        sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext)),
                        diagnosticsLogger = ::logSharedAuthDiagnostics
                    ).verifyEmail(token)
            }
            when (result) {
                is SharedEmailVerificationResult.Verified -> {
                    Toast.makeText(this@MainActivity, "Email verified", Toast.LENGTH_SHORT).show()
                    when (emailVerificationNavigation(hasLiveShellInTask, EmailVerificationOutcome.Verified)) {
                        EmailVerificationNavigation.FinishTrampoline -> finish()
                        EmailVerificationNavigation.OpenPostLoginDestination -> openPostLoginDestination(result.session)
                        EmailVerificationNavigation.OpenAuthShell -> error("Success must not open the auth shell")
                    }
                }
                is SharedEmailVerificationResult.Failure -> {
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    when (emailVerificationNavigation(hasLiveShellInTask, EmailVerificationOutcome.Failure)) {
                        EmailVerificationNavigation.FinishTrampoline -> finish()
                        EmailVerificationNavigation.OpenAuthShell ->
                            openSharedShell(Intent(this@MainActivity, MainActivity::class.java))
                        EmailVerificationNavigation.OpenPostLoginDestination ->
                            error("Failure must not open a post-login destination")
                    }
                }
                is SharedEmailVerificationResult.RefreshPending -> {
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    when (emailVerificationNavigation(hasLiveShellInTask, EmailVerificationOutcome.RefreshPending)) {
                        EmailVerificationNavigation.FinishTrampoline -> finish()
                        EmailVerificationNavigation.OpenAuthShell ->
                            openSharedShell(Intent(this@MainActivity, MainActivity::class.java))
                        EmailVerificationNavigation.OpenPostLoginDestination ->
                            error("Pending verification refresh must not open a post-login destination")
                    }
                }
                SharedEmailVerificationResult.LoginRequired -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Email verified. Sign in to continue.",
                        Toast.LENGTH_LONG
                    ).show()
                    when (emailVerificationNavigation(hasLiveShellInTask, EmailVerificationOutcome.LoginRequired)) {
                        EmailVerificationNavigation.FinishTrampoline -> finish()
                        EmailVerificationNavigation.OpenAuthShell ->
                            openSharedShell(Intent(this@MainActivity, MainActivity::class.java))
                        EmailVerificationNavigation.OpenPostLoginDestination ->
                            error("Unauthenticated verification must not open a post-login destination")
                    }
                }
            }
        }
        return true
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
    SlockAppLog.auth("SlockKuikly", "shared_auth $message")
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

internal fun Intent?.shouldOpenUnsupportedSlockWebLinkExternally(): Boolean =
    shouldOpenUnsupportedSlockWebLinkExternally(
        action = this?.action,
        dataScheme = this?.data?.scheme,
        dataHost = this?.data?.host,
        dataPath = this?.data?.path,
        verifyToken = this?.data?.getQueryParameter("verify")
    )

internal fun shouldOpenUnsupportedSlockWebLinkExternally(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    dataPath: String? = null,
    verifyToken: String? = null
): Boolean =
    action == Intent.ACTION_VIEW &&
        isSlockAppWebLink(dataScheme, dataHost) &&
        emailVerificationToken(action, dataScheme, dataHost, dataPath, verifyToken) == null

internal fun Intent?.emailVerificationToken(): String? =
    emailVerificationToken(
        action = this?.action,
        dataScheme = this?.data?.scheme,
        dataHost = this?.data?.host,
        dataPath = this?.data?.path,
        verifyToken = this?.data?.getQueryParameter("verify")
    )

internal fun emailVerificationToken(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    dataPath: String?,
    verifyToken: String?
): String? =
    verifyToken?.takeIf {
        it.isNotBlank() &&
            action == Intent.ACTION_VIEW &&
            isSlockAppWebLink(dataScheme, dataHost) &&
            isRootWebPath(dataPath)
    }

internal enum class EmailVerificationOutcome {
    Verified,
    Failure,
    RefreshPending,
    LoginRequired,
}

internal enum class EmailVerificationNavigation {
    FinishTrampoline,
    OpenPostLoginDestination,
    OpenAuthShell,
}

internal fun emailVerificationNavigation(
    hasLiveShellInTask: Boolean,
    result: EmailVerificationOutcome,
): EmailVerificationNavigation =
    when {
        hasLiveShellInTask -> EmailVerificationNavigation.FinishTrampoline
        result == EmailVerificationOutcome.Verified -> EmailVerificationNavigation.OpenPostLoginDestination
        else -> EmailVerificationNavigation.OpenAuthShell
    }

private fun isRootWebPath(path: String?): Boolean = path.isNullOrEmpty() || path == "/"

internal fun selectExternalWebHandlerPackage(
    ownPackage: String,
    defaultPackage: String?,
    candidatePackages: List<String>
): String? {
    val externalPackages = candidatePackages.filter { it.isNotBlank() && it != ownPackage }
    return defaultPackage?.takeIf { it in externalPackages }
        ?: externalPackages.firstOrNull()
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

private fun Uri.safeWebLinkTarget(): String =
    buildUpon().clearQuery().fragment(null).build().toString()

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

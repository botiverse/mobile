package build.raft.app

import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.shared.AndroidShellRouteOwner
import build.raft.app.shared.AndroidShellWindowRegistry
import build.raft.app.shared.AndroidLoginTransitionRouting
import build.raft.app.shared.SlockKuiklyActivity
import build.raft.app.shared.SlockKuiklyRouteKeys
import build.raft.app.shared.currentAndroidShellRouteOwner
import build.raft.app.shared.withAndroidShellRouteOwner
import build.raft.app.data.SlockAppLoginBridge
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.mobileOAuthConflictMessageOrNull
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedEmailVerificationResult
import build.raft.app.data.StoredAuthSession
import android.content.Intent
import android.content.Context
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
    private lateinit var externalShareAdmissionOwner: AndroidExternalShareAdmissionOwner<Intent>
    private val externalShareResultListener: (Long, AndroidExternalShareIntakeResult) -> Unit =
        { generation, result -> handleExternalShareAdmissionResult(generation, result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!AndroidLoginTransitionRouting.observeHost(this)) {
            finish()
            return
        }
        externalShareAdmissionOwner = AndroidExternalShareProcessOwner.admissionOwner(applicationContext)
        val rawShare = intent.takeIf(Intent::isAndroidExternalShareIntent)
        if (rawShare != null) {
            setIntent(Intent(this, MainActivity::class.java))
            externalShareAdmissionOwner.submit(rawShare)
        }
        val resumedExternalShare = externalShareAdmissionOwner.attach(externalShareResultListener)
        SlockAppLog.d(
            "SlockBoot",
            "main_activity_created " +
                "source=${intent.slockLaunchSource()} " +
                "clearTask=${intent.shouldClearTaskForSharedShell()} " +
                "data=${intent.safeDataString()}"
        )
        if (rawShare == null && !resumedExternalShare) {
            val durablePending = AndroidExternalShareProcessOwner.inbox(applicationContext).pendingLaunchOrNull()
            if (durablePending == null) {
                handleLaunchIntent(intent)
            } else {
                openSharedShellPageData(androidExternalSharePageData(durablePending))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidLoginTransitionRouting.observeNewIntent(this)
        SlockAppLog.d(
            "SlockBoot",
            "main_activity_new_intent " +
                "source=${intent.slockLaunchSource()} " +
                "clearTask=${intent.shouldClearTaskForSharedShell()} " +
                "data=${intent.safeDataString()}"
        )
        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        if (::externalShareAdmissionOwner.isInitialized) {
            externalShareAdmissionOwner.detach(externalShareResultListener)
        }
        super.onDestroy()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (
            !handleExternalShareIntent(intent) &&
            !handleAppLoginCallback(intent) &&
            !handleMobileOAuthCallback(intent) &&
            !handleSocialAuthCallback(intent) &&
            !handleEmailVerificationLink(intent) &&
            !handleUnsupportedSlockWebLink(intent)
        ) {
            openSharedShell(intent)
        }
    }

    private fun handleExternalShareIntent(intent: Intent?): Boolean {
        val shareIntent = intent?.takeIf(Intent::isAndroidExternalShareIntent) ?: return false
        // Remove raw text/provider URIs and temporary grants from retained Activity state before
        // the asynchronous copy. The process owner keeps the one in-flight Intent only.
        setIntent(Intent(this, MainActivity::class.java))
        externalShareAdmissionOwner.submit(shareIntent)
        return true
    }

    private fun handleExternalShareAdmissionResult(
        generation: Long,
        result: AndroidExternalShareIntakeResult
    ) {
        when (result) {
            is AndroidExternalShareIntakeResult.Admitted ->
                openSharedShellPageData(androidExternalSharePageData(result.launch))
            is AndroidExternalShareIntakeResult.Rejected -> {
                SlockAppLog.w("SlockBoot", "external_share_rejected code=${result.code}")
                Toast.makeText(
                    this,
                    getString(R.string.external_share_import_failed_toast),
                    Toast.LENGTH_LONG
                ).show()
                openSharedShell(Intent(this, MainActivity::class.java))
            }
        }
        externalShareAdmissionOwner.acknowledge(generation)
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
            Toast.makeText(this, getString(R.string.browser_unavailable_toast), Toast.LENGTH_SHORT).show()
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

    private fun externalBrowserIntent(uri: Uri): Intent? = androidExternalBrowserIntent(this, uri)

    private fun openSharedShell(launchIntent: Intent? = intent) {
        val pageData = SlockKuiklyActivity.appShellPageDataFromIntent(launchIntent)
        openSharedShellPageData(pageData, launchIntent)
    }

    private fun openSharedShellPageData(
        pageData: JSONObject,
        launchIntent: Intent? = intent
    ) {
        SlockKuiklyActivity.startAppShell(
            this,
            pageData = pageData,
            clearTask = launchIntent.shouldClearTaskForSharedShell(),
        )
        finish()
    }

    private fun handleEmailVerificationLink(intent: Intent?): Boolean {
        val token = intent.emailVerificationToken() ?: return false
        val hasLiveShellInTask = AndroidShellWindowRegistry.hasShellInTask(taskId)
        val loginTransition = AndroidLoginTransitionRouting.captureAuthentication(this)
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
                    Toast.makeText(this@MainActivity, getString(R.string.email_verified_toast), Toast.LENGTH_SHORT).show()
                    when (emailVerificationNavigation(hasLiveShellInTask, EmailVerificationOutcome.Verified)) {
                        EmailVerificationNavigation.FinishTrampoline -> finish()
                        EmailVerificationNavigation.OpenPostLoginDestination ->
                            openPostLoginDestination(result.session, loginTransition)
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
                        getString(R.string.email_verified_sign_in_toast),
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
            Toast.makeText(this, getString(R.string.provider_unavailable_toast), Toast.LENGTH_SHORT).show()
            return true
        }
        val error = uri.getQueryParameter("error") ?: uri.getQueryParameter("error_description")
        if (!error.isNullOrBlank()) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.google_sign_in_no_code_toast), Toast.LENGTH_SHORT).show()
            return true
        }
        val loginTransition = AndroidLoginTransitionRouting.captureAuthentication(this)
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
                    openPostLoginDestination(result.data, loginTransition)
                is SharedAuthResult.Failure ->
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                SharedAuthResult.Unauthenticated ->
                    Toast.makeText(this@MainActivity, getString(R.string.google_sign_in_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    /**
     * Browser web login callback (`<scheme>://login/callback`). The shared coordinator owns the pending
     * request and the exchange; the login page observes the result. A live shell is simply revealed so
     * its login page keeps its state; otherwise the shell starts and picks the result up.
     */
    private fun handleAppLoginCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!uri.scheme.equals(BuildConfig.OAUTH_CALLBACK_SCHEME, ignoreCase = true) ||
            !uri.host.equals("login", ignoreCase = true)
        ) {
            return false
        }
        val hasLiveShellInTask = AndroidShellWindowRegistry.hasShellInTask(taskId)
        // The login page is not a registered app shell, so a login page waiting for this callback shows up
        // only as "this trampoline is not the task root". Restarting the shell there would destroy the
        // waiting page and race a fresh one against the exchange (build 38: "Unable to finish sign-in").
        val revealsExistingScreen = hasLiveShellInTask || !isTaskRoot
        val accepted = SlockAppLoginBridge.completeCallbackUri(uri.toString())
        setIntent(Intent(this, MainActivity::class.java))
        SlockAppLog.d(
            "SlockAuth",
            "app_login_callback accepted=$accepted liveShell=$hasLiveShellInTask taskRoot=$isTaskRoot"
        )
        if (revealsExistingScreen) {
            finish()
        } else {
            openSharedShell(Intent(this, MainActivity::class.java))
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
            openAfterMobileOAuthError(getString(R.string.oauth_request_expired_toast), AndroidMobileOAuth.ModeLogin)
            return true
        }
        val providerName = pending.provider.socialProviderDisplayName()
        val error = uri.getQueryParameter("error") ?: uri.getQueryParameter("error_description")
        if (!error.isNullOrBlank()) {
            pendingStore.clear()
            // Map the typed conflict codes through the shared mapper before
            // surfacing anything. Previously the raw code was toasted, so a user
            // whose email simply already had a password account saw the literal
            // string "account_conflict" with no idea what to do next. Any other
            // code keeps the existing passthrough.
            openAfterMobileOAuthError(
                mobileOAuthConflictMessageOrNull(providerName, error) ?: error,
                pending.mode,
            )
            return true
        }
        val handoffCode = uri.getQueryParameter("code")
        if (handoffCode.isNullOrBlank()) {
            pendingStore.clear()
            openAfterMobileOAuthError(getString(R.string.provider_no_handoff_code_toast, providerName), pending.mode)
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
                        Toast.makeText(this@MainActivity, getString(R.string.provider_linked_toast, providerName), Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                    is SharedAuthResult.Failure -> {
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                    SharedAuthResult.Unauthenticated -> {
                        Toast.makeText(this@MainActivity, getString(R.string.provider_link_sign_in_again_toast, providerName), Toast.LENGTH_SHORT).show()
                        openSettingsAccountAfterMobileOAuthLink()
                    }
                }
            }
            return true
        }
        val loginTransition = AndroidLoginTransitionRouting.captureAuthentication(this)
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
                    openPostLoginDestination(result.data, loginTransition)
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
                    Toast.makeText(this@MainActivity, getString(R.string.provider_sign_in_failed_toast, providerName), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun openPostLoginDestination(
        session: StoredAuthSession,
        loginTransition: AndroidLoginTransitionRouting.AuthenticationAttempt,
    ) {
        val accepted = AndroidLoginTransitionRouting.afterSuccessfulAuthentication(this, loginTransition) {
            when (postLoginDestination(session.selectedServerId)) {
                PostLoginDestination.AppShell ->
                    SlockKuiklyActivity.startAppShell(
                        this,
                        pageData =
                            postLoginAppShellPageData(
                                owner =
                                    AndroidShellRouteOwner(
                                            environmentId =
                                                AndroidApiServerEnvironmentStore(applicationContext)
                                                    .current()
                                                    .apiBaseUrl,
                                            accountId = session.user.id,
                                            serverId =
                                                session.currentServerId
                                                    ?: session.selectedServerId.orEmpty(),
                                        )
                                        .normalizedOrNull()
                            ),
                        clearTask = true
                    )
                PostLoginDestination.ChooseServer ->
                    SlockKuiklyActivity.startAuthChooseServer(
                        this,
                        clearTask = true
                    )
            }
        }
        if (accepted) finish()
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
            pageData =
                settingsAccountAppShellPageData(
                    owner = currentAndroidShellRouteOwner(this)
                ),
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

internal fun postLoginAppShellPageData(owner: AndroidShellRouteOwner? = null): JSONObject =
    JSONObject()
        .put("slockSuppressRestoreLanding", true)
        .put("slockAuthGateInstanceId", System.nanoTime().toString())
        .let { base -> owner?.let { base.withAndroidShellRouteOwner(it) } ?: base }

internal fun settingsAccountAppShellPageData(owner: AndroidShellRouteOwner? = null): JSONObject =
    JSONObject()
        .put("slockSuppressRestoreLanding", true)
        .put("slockAuthGateInstanceId", System.nanoTime().toString())
        .put("slockInitialTab", "settings")
        .put("slockInitialSettingsDestination", "account")
        .let { base -> owner?.let { base.withAndroidShellRouteOwner(it) } ?: base }

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
    this?.data?.let { uri ->
        admittedUriQuery(
            isHierarchical = uri.isHierarchical,
            routeAccepted = action == Intent.ACTION_VIEW && uri.isSlockAppWebLink(),
        ) {
            shouldOpenUnsupportedSlockWebLinkExternally(
                action = action,
                dataScheme = uri.scheme,
                dataHost = uri.host,
                dataPath = uri.path,
                verifyToken = uri.getQueryParameter("verify"),
            )
        }
    } ?: false

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
    this?.data?.let { uri ->
        admittedUriQuery(
            isHierarchical = uri.isHierarchical,
            routeAccepted = action == Intent.ACTION_VIEW && uri.isSlockAppWebLink() && isRootWebPath(uri.path),
        ) {
            emailVerificationToken(
                action = action,
                dataScheme = uri.scheme,
                dataHost = uri.host,
                dataPath = uri.path,
                verifyToken = uri.getQueryParameter("verify"),
            )
        }
    }

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

/**
 * The browser for a Raft web link. Every Raft build (release, alpha, connected-test, ...) claims the Raft web
 * origin, so with several installed the "default" handler can be another Raft app, which would only bounce
 * the link back (artin, 2026-10-15: tapping web login first opened a Raft app). Any `build.raft.app*`
 * package is skipped, not just this one.
 */
internal fun selectExternalWebHandlerPackage(
    ownPackage: String,
    defaultPackage: String?,
    candidatePackages: List<String>
): String? {
    val externalPackages =
        candidatePackages.filter { it.isNotBlank() && it != ownPackage && !it.isRaftAppPackage() }
    return defaultPackage?.takeIf { it in externalPackages }
        ?: externalPackages.firstOrNull()
}

private fun String.isRaftAppPackage(): Boolean = this == RAFT_APP_PACKAGE || startsWith("$RAFT_APP_PACKAGE.")

private const val RAFT_APP_PACKAGE = "build.raft.app"

/** An explicit browser activity for [uri], so Android App Links cannot route it to a Raft app; null if none. */
internal fun androidExternalBrowserIntent(context: Context, uri: Uri): Intent? {
    val packageManager = context.packageManager
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
            ownPackage = context.packageName,
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
    admittedUriQuery(
        isHierarchical = isHierarchical,
        routeAccepted = scheme == "https" && host in setOf("app.slock.ai", "app.raft.build"),
    ) {
        getQueryParameter("auth_callback") == "social"
    } ?: false

internal fun Uri.isSlockMobileOAuthCallback(): Boolean =
    admittedUriQuery(
        isHierarchical = isHierarchical,
        routeAccepted = AndroidMobileOAuth.isCallbackRoute(scheme = scheme, host = host, path = path),
    ) {
        AndroidMobileOAuth.isCallbackUri(
            scheme = scheme,
            host = host,
            path = path,
            code = getQueryParameter("code"),
            error = getQueryParameter("error") ?: getQueryParameter("error_description")
        )
    } ?: false

internal fun <T> admittedUriQuery(
    isHierarchical: Boolean,
    routeAccepted: Boolean,
    readQuery: () -> T,
): T? =
    if (isHierarchical && routeAccepted) readQuery() else null

private fun String.socialProviderDisplayName(): String =
    when (lowercase(Locale.US)) {
        "github" -> "GitHub"
        "google" -> "Google"
        else -> "Social"
    }

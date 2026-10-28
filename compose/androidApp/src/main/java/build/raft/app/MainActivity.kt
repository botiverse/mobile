package build.raft.app

import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.shared.AndroidShellRouteOwner
import build.raft.app.shared.AndroidShellWindowRegistry
import build.raft.app.shared.AndroidLoginTransitionRouting
import build.raft.app.shared.RaftCmpActivity
import build.raft.app.shared.RaftRouteKeys
import build.raft.app.shared.currentAndroidShellRouteOwner
import build.raft.app.shared.withAndroidShellRouteOwner
import build.raft.app.data.RaftAppLoginBridge
import build.raft.app.RaftMessageLinkIngress
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
        // `adb shell am start -n <package>/build.raft.app.MainActivity --ez RaftHideEnvironmentIndicator true`
        // for store screenshots and recordings on a staging demo server.
        if (intent.getBooleanExtra("RaftHideEnvironmentIndicator", false)) {
            setRaftEnvironmentIndicatorHiddenForCapture(true)
        }
        externalShareAdmissionOwner = AndroidExternalShareProcessOwner.admissionOwner(applicationContext)
        val rawShare = intent.takeIf(Intent::isAndroidExternalShareIntent)
        if (rawShare != null) {
            setIntent(Intent(this, MainActivity::class.java))
            externalShareAdmissionOwner.submit(rawShare)
        }
        val resumedExternalShare = externalShareAdmissionOwner.attach(externalShareResultListener)
        RaftAppLog.d(
            "SlockBoot",
            "main_activity_created " +
                "source=${intent.raftLaunchSource()} " +
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
        RaftAppLog.d(
            "SlockBoot",
            "main_activity_new_intent " +
                "source=${intent.raftLaunchSource()} " +
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
            !handleRaftMessageLink(intent) &&
            !handleUnsupportedRaftWebLink(intent)
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
                RaftAppLog.w("SlockBoot", "external_share_rejected code=${result.code}")
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
     * A Raft message link (App Link on app.raft.build / open.raft.build, or the Developer
     * environment's web host): the shared inbox keeps it (through sign-in) until an app shell with
     * a ready session resolves and opens it through the window navigation coordinator, the entry a
     * notification tap uses. A live shell in this task is only revealed; otherwise the shell is
     * started or brought forward the way a notification tap starts it. Anything else stays on the
     * web ([handleUnsupportedRaftWebLink]).
     */
    private fun handleRaftMessageLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val webUrl = AndroidApiServerEnvironmentStore(applicationContext).current().webUrl
        if (!RaftMessageLinkIngress.accept(uri.toString(), source = "android_view", configuredWebUrl = webUrl)) {
            return false
        }
        // Ids stay out of the retained Activity intent; the inbox owns the link now.
        setIntent(Intent(this, MainActivity::class.java))
        val liveShellInTask = AndroidShellWindowRegistry.hasShellInTask(taskId)
        RaftAppLog.d("SlockBoot", "app_message_link_accepted liveShellInTask=$liveShellInTask")
        if (!liveShellInTask) {
            RaftCmpActivity.startAppShell(applicationContext, pageData = JSONObject(), clearTask = false)
        }
        finish()
        return true
    }

    /**
     * The manifest intentionally receives the whole Raft web origin so typed callbacks can be
     * added without another install. Anything not consumed above must stay on the web instead of
     * silently opening an empty app shell. The browser component is explicit so Android App Links
     * cannot resolve the fallback straight back to this activity.
     */
    private fun handleUnsupportedRaftWebLink(intent: Intent?): Boolean {
        if (!intent.shouldOpenUnsupportedRaftWebLinkExternally()) return false
        val uri = checkNotNull(intent?.data)
        setIntent(Intent(this, MainActivity::class.java))
        val browserIntent = externalBrowserIntent(uri)
        if (browserIntent == null) {
            RaftAppLog.w(
                "SlockBoot",
                "app_web_link_external_fallback result=no_external_handler " +
                    "target=${uri.safeWebLinkTarget()}"
            )
            Toast.makeText(this, getString(R.string.browser_unavailable_toast), Toast.LENGTH_SHORT).show()
        } else {
            RaftAppLog.d(
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
        val pageData = RaftCmpActivity.appShellPageDataFromIntent(launchIntent)
        openSharedShellPageData(pageData, launchIntent)
    }

    private fun openSharedShellPageData(
        pageData: JSONObject,
        launchIntent: Intent? = intent
    ) {
        RaftCmpActivity.startAppShell(
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
        if (!uri.isRaftSocialAuthCallback()) return false
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
        val accepted = RaftAppLoginBridge.completeCallbackUri(uri.toString())
        setIntent(Intent(this, MainActivity::class.java))
        RaftAppLog.d(
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
        if (!uri.isRaftMobileOAuthCallback()) return false
        setIntent(Intent(this, MainActivity::class.java))
        val pendingStore = AndroidMobileOAuthPendingStore(applicationContext)
        val pending = pendingStore.read()
        if (pending == null) {
            pendingStore.clear()
            openAfterMobileOAuthError(getString(R.string.oauth_request_expired_toast))
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
            openAfterMobileOAuthError(mobileOAuthConflictMessageOrNull(providerName, error) ?: error)
            return true
        }
        val handoffCode = uri.getQueryParameter("code")
        if (handoffCode.isNullOrBlank()) {
            pendingStore.clear()
            openAfterMobileOAuthError(getString(R.string.provider_no_handoff_code_toast, providerName))
            return true
        }
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

    private fun openPostLoginDestination(
        session: StoredAuthSession,
        loginTransition: AndroidLoginTransitionRouting.AuthenticationAttempt,
    ) {
        val accepted = AndroidLoginTransitionRouting.afterSuccessfulAuthentication(this, loginTransition) {
            when (postLoginDestination(session.selectedServerId)) {
                PostLoginDestination.AppShell ->
                    RaftCmpActivity.startAppShell(
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
                    RaftCmpActivity.startAuthChooseServer(
                        this,
                        clearTask = true
                    )
            }
        }
        if (accepted) finish()
    }

    private fun openAfterMobileOAuthError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        openSettingsAccountAfterMobileOAuthLink()
    }

    private fun openSettingsAccountAfterMobileOAuthLink() {
        RaftCmpActivity.startAppShell(
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

private fun logSharedAuthDiagnostics(message: String) {
    RaftAppLog.auth("SlockKuikly", "shared_auth $message")
}

internal fun Intent?.shouldClearTaskForSharedShell(): Boolean =
    shouldClearTaskForSharedShell(
        action = this?.action,
        dataScheme = this?.data?.scheme,
        dataHost = this?.data?.host,
        appShellPageData = this.isAppShellPageDataIntent()
    )

internal fun Intent?.raftLaunchSource(): String =
    raftLaunchSource(
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
    !appShellPageData && !isRaftAppWebViewIntent(action, dataScheme, dataHost)

internal fun raftLaunchSource(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    appShellPageData: Boolean = false
): String =
    when {
        appShellPageData -> "app_shell_intent"
        action == null -> "missing"
        isRaftAppWebViewIntent(action, dataScheme, dataHost) -> "app_web_view"
        action == Intent.ACTION_VIEW -> "external_view"
        action == Intent.ACTION_MAIN -> "launcher"
        else -> action.ifBlank { "unknown" }
    }

internal fun Intent?.shouldOpenUnsupportedRaftWebLinkExternally(): Boolean =
    this?.data?.let { uri ->
        admittedUriQuery(
            isHierarchical = uri.isHierarchical,
            routeAccepted = action == Intent.ACTION_VIEW && uri.isRaftAppWebLink(),
        ) {
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = action,
                dataScheme = uri.scheme,
                dataHost = uri.host,
                dataPath = uri.path,
                verifyToken = uri.getQueryParameter("verify"),
            )
        }
    } ?: false

internal fun shouldOpenUnsupportedRaftWebLinkExternally(
    action: String?,
    dataScheme: String?,
    dataHost: String?,
    dataPath: String? = null,
    verifyToken: String? = null
): Boolean =
    action == Intent.ACTION_VIEW &&
        isRaftAppWebLink(dataScheme, dataHost) &&
        emailVerificationToken(action, dataScheme, dataHost, dataPath, verifyToken) == null

internal fun Intent?.emailVerificationToken(): String? =
    this?.data?.let { uri ->
        admittedUriQuery(
            isHierarchical = uri.isHierarchical,
            routeAccepted = action == Intent.ACTION_VIEW && uri.isRaftAppWebLink() && isRootWebPath(uri.path),
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
            isRaftAppWebLink(dataScheme, dataHost) &&
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

/**
 * Whether an `openUrl` is pinned to a browser package first: only when the caller asked for the
 * browser (`browser: true`, HostLinkCapability.openUrlInBrowser) and it is an untyped http(s) URL.
 */
internal fun openUrlUsesBrowserPackage(browser: Boolean, mimeType: String?, scheme: String?): Boolean =
    browser && mimeType == null && scheme?.lowercase() in setOf("http", "https")

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
    this?.getStringExtra(RaftCmpActivity.KEY_PAGE_NAME) == RaftCmpActivity.PAGE_APP_SHELL

private fun isRaftAppWebViewIntent(action: String?, dataScheme: String?, dataHost: String?): Boolean =
    action == Intent.ACTION_VIEW && isRaftAppWebLink(dataScheme, dataHost)

internal fun Uri.isRaftAppWebLink(): Boolean =
    isRaftAppWebLink(scheme, host)

private fun isRaftAppWebLink(scheme: String?, host: String?): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        host?.lowercase(Locale.US) in setOf("app.slock.ai", "app.raft.build")

private fun Intent?.safeDataString(): String =
    this?.data?.buildUpon()?.clearQuery()?.fragment(null)?.build()?.toString().orEmpty()

private fun Uri.safeWebLinkTarget(): String =
    buildUpon().clearQuery().fragment(null).build().toString()

private fun Uri.isRaftSocialAuthCallback(): Boolean =
    admittedUriQuery(
        isHierarchical = isHierarchical,
        routeAccepted = scheme == "https" && host in setOf("app.slock.ai", "app.raft.build"),
    ) {
        getQueryParameter("auth_callback") == "social"
    } ?: false

internal fun Uri.isRaftMobileOAuthCallback(): Boolean =
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

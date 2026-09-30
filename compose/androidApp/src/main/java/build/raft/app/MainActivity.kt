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
import java.util.UUID
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.mobileOAuthConflictMessageOrNull
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedEmailVerificationResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SlockExternalPermalinkContract
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
    private lateinit var externalShareAdmissionOwner: AndroidExternalShareAdmissionOwner<Intent>
    private val externalShareResultListener: (Long, AndroidExternalShareIntakeResult) -> Unit =
        { generation, result -> handleExternalShareAdmissionResult(generation, result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
            !handleMobileOAuthCallback(intent) &&
            !handleSocialAuthCallback(intent) &&
            !handleEmailVerificationLink(intent) &&
            !handleSlockMessagePermalinkLink(intent) &&
            !handleUnsupportedSlockWebLink(intent)
        ) {
            openSharedShell(intent)
        }
    }

    /**
     * task #13 external deep-link: canonical `/s/{serverSlug}/{channel|dm}/{id}`
     * permalinks resolve inside the app instead of bouncing to the browser.
     * Each delivered VIEW click mints a fresh `activationId`; the shared window
     * navigation coordinator dedups on the intent key (which includes it), so
     * OS-level redelivery of the same pageData stays idempotent while a genuine
     * re-click re-opens. Anything that fails parsing falls through to the
     * existing unsupported-web-link fallback.
     */
    private fun handleSlockMessagePermalinkLink(intent: Intent?): Boolean {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return false
        val target = uri.toString().toSlockMessagePermalinkTarget() ?: return false
        val pageData = androidExternalPermalinkPageData(target, uri)
        SlockAppLog.d(
            "SlockBoot",
            "external_permalink_received " +
                "serverSlug=${target.serverSlug} route=${uri.path.orEmpty()} " +
                "activationId=${pageData.optJSONObject(SlockExternalPermalinkContract.PageDataBlockKey)?.optString(SlockExternalPermalinkContract.KeyActivationId).orEmpty()}"
        )
        openSharedShellPageData(pageData)
        return true
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
        openSharedShellPageData(pageData, launchIntent)
    }

    private fun openSharedShellPageData(
        pageData: JSONObject,
        launchIntent: Intent? = intent
    ) {
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
                    Toast.makeText(this@MainActivity, getString(R.string.email_verified_toast), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, getString(R.string.google_sign_in_failed_toast), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, getString(R.string.provider_sign_in_failed_toast, providerName), Toast.LENGTH_SHORT).show()
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

/**
 * Builds the AppShell pageData carrying the external-link identity envelope.
 * The shell scope is this window's own `slockShellInstanceId`, resolved by the
 * shared launch-target contract (single-window Android today, deterministic so
 * Ready/Accepted stay scoped rather than broadcast).
 */
fun androidExternalPermalinkPageData(
    target: SlockMessagePermalinkTarget,
    uri: Uri,
): JSONObject {
    val shellScopeId =
        resolveAndroidShellLaunchTarget(JSONObject()).shellInstanceId
    val envelope =
        JSONObject()
            .put(SlockExternalPermalinkContract.KeyActivationId, UUID.randomUUID().toString())
            .put(SlockExternalPermalinkContract.KeyServerSlug, target.serverSlug)
            .put(SlockExternalPermalinkContract.KeyChannelId, target.channelId.value)
            .put(
                SlockExternalPermalinkContract.KeyRouteKind,
                when (target.routeKind) {
                    build.raft.app.model.ConversationKind.DirectMessage -> "dm"
                    else -> "channel"
                }
            )
            .put(SlockExternalPermalinkContract.KeyShellScopeId, shellScopeId)
            .put(SlockExternalPermalinkContract.KeyReceivedAtMillis, System.currentTimeMillis().toString())
            .put(SlockExternalPermalinkContract.KeyOriginHost, uri.host.orEmpty())
    target.threadParentMessageId?.value?.takeIf(String::isNotBlank)?.let { parent ->
        envelope.put(SlockExternalPermalinkContract.KeyParentMessageId, parent)
    }
    target.messageId.value.takeIf(String::isNotBlank)?.let { message ->
        envelope.put(SlockExternalPermalinkContract.KeyMessageId, message)
    }
    return JSONObject().put(SlockExternalPermalinkContract.PageDataBlockKey, envelope)
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

package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.slock.compose.base.BridgeModule
import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.KuiklySlockAuthTransport
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDataSource
import ai.slock.compose.data.SharedSettingsDiagnosticsEntry
import ai.slock.compose.data.SharedSettingsNetworkProbeSnapshot
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.runCatchingPreservingCancellation
import ai.slock.compose.network.SlockNetworkModule
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.focus.focusProperties
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

const val LOGIN_PAGE = "compose_login"
const val REGISTER_PAGE = "compose_register"
const val FORGOT_PASSWORD_PAGE = "compose_forgot_password"
const val AUTH_DIAGNOSTICS_PAGE = "compose_auth_diagnostics"
private const val AUTH_LOGIN_INPUTS_VISUAL_PAGE = "compose_auth_login_inputs"
private const val AUTH_REGISTER_INPUTS_VISUAL_PAGE = "compose_auth_register_inputs"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
private const val AUTH_SOCIAL_PROVIDERS_KEY = "slockSocialAuthProviders"
private const val AUTH_REGISTER_VISUAL_NAME_KEY = "registerName"
private const val AUTH_REGISTER_VISUAL_EMAIL_KEY = "registerEmail"
private const val AUTH_REGISTER_VISUAL_PASSWORD_KEY = "registerPassword"
private const val AUTH_REGISTER_VISUAL_DEFAULT_NAME = "Product UX Designer"
private const val AUTH_REGISTER_VISUAL_DEFAULT_EMAIL = "new@slock.ai"
private const val AUTH_REGISTER_VISUAL_DEFAULT_PASSWORD = "correct horse battery staple"

private fun Modifier.authPageStaticTextFocus(): Modifier =
    focusProperties { canFocus = false }

private fun Pager.sharedAuthDataSource(bridge: BridgeModule): SharedAuthDataSource {
    val sharedStores = SlockSharedStoreRegistry.get(bridge)
    return SharedAuthDataSource(
        transport =
            KuiklySlockAuthTransport(
                networkModule = acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME)
            ),
        sessionStore = sharedStores.authStore,
        diagnosticsLogger = { message -> bridge.log("shared_auth $message") }
    )
}

@Page(LOGIN_PAGE, supportInLocal = true)
internal class ComposeLoginPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { LoginScreen() }
    }
}

@Page(REGISTER_PAGE, supportInLocal = true)
internal class ComposeRegisterPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RegisterScreen() }
    }
}

@Page(FORGOT_PASSWORD_PAGE, supportInLocal = true)
internal class ComposeForgotPasswordPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ForgotPasswordScreen() }
    }
}

@Page(AUTH_DIAGNOSTICS_PAGE, supportInLocal = true)
internal class ComposeAuthDiagnosticsPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthDiagnosticsScreen() }
    }
}

@Page(AUTH_LOGIN_INPUTS_VISUAL_PAGE, supportInLocal = true)
internal class ComposeAuthLoginInputsVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthLoginInputsVisualScreen() }
    }
}

@Page(AUTH_REGISTER_INPUTS_VISUAL_PAGE, supportInLocal = true)
internal class ComposeAuthRegisterInputsVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthRegisterInputsVisualScreen() }
    }
}

@Composable
private fun LoginScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = remember { pager.sharedAuthDataSource(bridge) }
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    var pendingSocialAuth by remember { mutableStateOf<PendingSocialAuth?>(null) }
    var socialSubmittingProvider by remember { mutableStateOf<AuthProvider?>(null) }
    val socialProviders = remember(pager.pageData.params.toString()) { pager.socialAuthProviders() }

    LaunchedEffect(pendingLogin) {
        val effect = pendingLogin ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.login(effect.email, effect.password) }
                .getOrElse { SharedAuthResult.Failure(ai.slock.compose.data.SharedAuthErrorCode.Unknown, "Sign in could not finish setup. Try again.") }
        pendingLogin = null
        when (result) {
            is SharedAuthResult.Success -> {
                state = state.copy(submitting = false, errorMessage = null)
                pager.openAuthPage(SLOCK_APP_SHELL_PAGE)
            }
            is SharedAuthResult.Failure ->
                state = state.copy(submitting = false, errorMessage = result.message)
            SharedAuthResult.Unauthenticated ->
                state = state.copy(submitting = false, errorMessage = "Sign in failed. Try again.")
        }
    }

    LaunchedEffect(pendingSocialAuth) {
        val effect = pendingSocialAuth ?: return@LaunchedEffect
        val providerId = effect.provider.providerId()
        val response = runCatching { bridge.socialAuth(providerId) }.getOrNull()
        pendingSocialAuth = null
        socialSubmittingProvider = null
        val code = response?.optString("code").orEmpty().toIntOrNull()
        if (code == 0 || response?.optString("status") == "error") {
            state = state.copy(
                submitting = false,
                errorMessage =
                    response?.optString("message").takeUnless { it.isNullOrBlank() }
                        ?: "Google sign-in is unavailable. Try email sign-in."
            )
        } else {
            state = state.copy(submitting = false, errorMessage = null)
        }
    }

    fun submitLogin(emailValue: String = state.email, passwordValue: String = state.password) {
        slockLogDebug(
            "SlockAuth",
            "login_submit_entry surface=auth_page emailLength=${emailValue.length} passwordPresent=${passwordValue.isNotBlank()}"
        )
        if (
            isLoginSubmitInFlight(
                submitting = state.submitting,
                pendingLogin = pendingLogin != null,
                socialSubmitting = socialSubmittingProvider != null
            )
        ) {
            slockLogDebug("SlockAuth", "login_submit_skip surface=auth_page reason=in_flight")
            return
        }
        val update = reducer.reduce(state.copy(email = emailValue, password = passwordValue), LoginFormEvent.Submit)
        slockLogDebug(
            "SlockAuth",
            "login_submit_reduce surface=auth_page emailError=${update.state.emailFormatError != null} passwordError=${update.state.passwordError != null} effect=${update.effect.loginEffectName()}"
        )
        state = update.state
        when (val effect = update.effect) {
            is LoginFormEffect.SubmitLogin -> {
                pendingLogin = PendingLogin(
                    nonce = (pendingLogin?.nonce ?: 0) + 1,
                    email = effect.email,
                    password = effect.password
                )
            }
            LoginFormEffect.FocusEmail -> Unit
            LoginFormEffect.FocusPassword -> Unit
            null -> Unit
        }
    }

    LoginContent(
        email = state.email,
        password = state.password,
        submitting = state.submitting,
        errorMessage = state.errorMessage,
        emailError = state.emailFormatError,
        passwordError = state.passwordError,
        socialProviders = socialProviders,
        socialSubmittingProvider = socialSubmittingProvider,
        onEmailChange = { state = reducer.reduce(state, LoginFormEvent.EmailChanged(it)).state },
        onPasswordChange = { state = reducer.reduce(state, LoginFormEvent.PasswordChanged(it)).state },
        onSubmit = { submitLogin() },
        onSubmitValues = { emailValue, passwordValue -> submitLogin(emailValue, passwordValue) },
        onSocialSignIn = { provider ->
            state = state.copy(submitting = false, errorMessage = null)
            socialSubmittingProvider = provider
            pendingSocialAuth =
                PendingSocialAuth(
                    nonce = (pendingSocialAuth?.nonce ?: 0) + 1,
                    provider = provider
                )
        },
        onForgotPassword = { pager.openAuthPage(FORGOT_PASSWORD_PAGE) },
        onCreateAccount = { pager.openAuthPage(REGISTER_PAGE) },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_DIAGNOSTICS_PAGE) }
    )
}

private data class PendingLogin(
    val nonce: Int,
    val email: String,
    val password: String
)

private data class PendingSocialAuth(
    val nonce: Int,
    val provider: AuthProvider
)

private data class PendingRegister(
    val nonce: Int,
    val name: String,
    val email: String,
    val password: String
)

private data class PendingForgotPassword(
    val nonce: Int,
    val email: String
)

@Composable
private fun RegisterScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = remember { pager.sharedAuthDataSource(bridge) }
    val reducer = remember { CreateAccountFormReducer() }
    var state by remember { mutableStateOf(CreateAccountFormState()) }
    var pendingRegister by remember { mutableStateOf<PendingRegister?>(null) }

    LaunchedEffect(pendingRegister) {
        val effect = pendingRegister ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation {
                authDataSource.register(
                    name = effect.name,
                    email = effect.email,
                    password = effect.password
                )
            }.getOrElse {
                SharedAuthResult.Failure(
                    ai.slock.compose.data.SharedAuthErrorCode.Unknown,
                    "Create account could not finish setup. Try again."
                )
            }
        pendingRegister = null
        when (result) {
            is SharedAuthResult.Success -> {
                state = state.copy(submitting = false, errorMessage = null)
                pager.openAuthPage(SLOCK_APP_SHELL_PAGE)
            }
            is SharedAuthResult.Failure ->
                state = state.copy(submitting = false, errorMessage = result.message)
            SharedAuthResult.Unauthenticated ->
                state = state.copy(submitting = false, errorMessage = "Create account failed. Try again.")
        }
    }

    RegisterContent(
        name = state.name,
        email = state.email,
        password = state.password,
        submitting = state.submitting,
        errorMessage = state.errorMessage,
        nameError = state.nameError,
        emailError = state.emailFormatError,
        passwordError = state.passwordError,
        onNameChange = { state = reducer.reduce(state, CreateAccountFormEvent.NameChanged(it)).state },
        onEmailChange = { state = reducer.reduce(state, CreateAccountFormEvent.EmailChanged(it)).state },
        onPasswordChange = { state = reducer.reduce(state, CreateAccountFormEvent.PasswordChanged(it)).state },
        onSubmit = {
            val update = reducer.reduce(state, CreateAccountFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is CreateAccountFormEffect.SubmitCreateAccount -> {
                    pendingRegister = PendingRegister(
                        nonce = (pendingRegister?.nonce ?: 0) + 1,
                        name = effect.name,
                        email = effect.email,
                        password = effect.password
                    )
                }
                CreateAccountFormEffect.FocusName -> Unit
                CreateAccountFormEffect.FocusEmail -> Unit
                CreateAccountFormEffect.FocusPassword -> Unit
                null -> Unit
            }
        },
        onBackToSignIn = { pager.openAuthPage(LOGIN_PAGE) },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_DIAGNOSTICS_PAGE) }
    )
}

@Composable
private fun ForgotPasswordScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = remember { pager.sharedAuthDataSource(bridge) }
    val reducer = remember { ForgotPasswordFormReducer() }
    var state by remember { mutableStateOf(ForgotPasswordFormState()) }
    var pendingForgotPassword by remember { mutableStateOf<PendingForgotPassword?>(null) }

    LaunchedEffect(pendingForgotPassword) {
        val effect = pendingForgotPassword ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.forgotPassword(effect.email) }
                .getOrElse {
                    SharedAuthResult.Failure(
                        ai.slock.compose.data.SharedAuthErrorCode.Unknown,
                        "Reset password could not finish setup. Try again."
                    )
                }
        pendingForgotPassword = null
        state =
            when (result) {
                is SharedAuthResult.Success ->
                    state.copy(submitting = false, sent = true, errorMessage = null)
                is SharedAuthResult.Failure ->
                    state.copy(submitting = false, sent = false, errorMessage = result.message)
                SharedAuthResult.Unauthenticated ->
                    state.copy(submitting = false, sent = false, errorMessage = "Reset password failed. Try again.")
            }
    }

    ForgotPasswordContent(
        email = state.email,
        sending = state.submitting,
        sent = state.sent,
        errorMessage = state.errorMessage,
        emailError = state.emailFormatError,
        onEmailChange = { state = reducer.reduce(state, ForgotPasswordFormEvent.EmailChanged(it)).state },
        onSubmit = {
            val update = reducer.reduce(state, ForgotPasswordFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is ForgotPasswordFormEffect.SubmitForgotPassword -> {
                    pendingForgotPassword =
                        PendingForgotPassword(
                            nonce = (pendingForgotPassword?.nonce ?: 0) + 1,
                            email = effect.email
                        )
                }
                ForgotPasswordFormEffect.FocusEmail -> Unit
                null -> Unit
            }
        },
        onBackToSignIn = { pager.openAuthPage(LOGIN_PAGE) },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_DIAGNOSTICS_PAGE) }
    )
}

private data class AuthDiagnosticsUiState(
    val loading: Boolean = true,
    val about: SharedSettingsAboutSnapshot? = null,
    val probe: SharedSettingsNetworkProbeSnapshot? = null,
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList(),
    val errorMessage: String? = null
)

@Composable
private fun AuthDiagnosticsScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val settingsDataSource =
        remember {
            SharedSettingsDataSource(
                sessionStore = sharedStores.authStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("compose_auth_diagnostics $message") }
            )
        }
    var refreshNonce by remember { mutableStateOf(0) }
    var copyNonce by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf(AuthDiagnosticsUiState()) }

    LaunchedEffect(refreshNonce) {
        state = state.copy(loading = true, errorMessage = null)
        val aboutResult = runCatchingPreservingCancellation { settingsDataSource.loadAbout() }.getOrElse {
            DataResult.Error(ai.slock.compose.data.ScreenError("settings_about_exception"))
        }
        val diagnosticsResult = runCatchingPreservingCancellation { settingsCapabilities.loadDiagnosticsHost() }.getOrElse {
            DataResult.Error(ai.slock.compose.data.ScreenError("settings_diagnostics_exception"))
        }
        val probeResult = runCatchingPreservingCancellation { settingsCapabilities.loadNetworkProbe() }.getOrElse {
            DataResult.Error(ai.slock.compose.data.ScreenError("settings_network_probe_exception"))
        }
        state =
            AuthDiagnosticsUiState(
                loading = false,
                about = (aboutResult as? DataResult.Content)?.data,
                probe = (probeResult as? DataResult.Content)?.data,
                entries = (diagnosticsResult as? DataResult.Content)?.data?.entries.orEmpty(),
                errorMessage =
                    listOf(
                        aboutResult.authDiagnosticsErrorLabel("about"),
                        diagnosticsResult.authDiagnosticsErrorLabel("diagnostics"),
                        probeResult.authDiagnosticsErrorLabel("probe")
                    ).filterNotNull().joinToString("; ").takeIf { it.isNotBlank() }
            )
    }

    LaunchedEffect(copyNonce) {
        if (copyNonce > 0) {
            settingsCapabilities.bridgeCopyToClipboard(
                buildAuthDiagnosticsExportText(
                    about = state.about,
                    probe = state.probe,
                    entries = state.entries,
                    errorMessage = state.errorMessage
                )
            )
            bridge.toast("Diagnostics copied")
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = "Diagnostics",
            subtitle = "Auth and network",
            iconName = SlockLucideIconName.Activity,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Copy,
                        onClick = { copyNonce += 1 },
                        size = 30.dp,
                        shadowOffset = 2.dp
                    )
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.RefreshCw,
                        onClick = { refreshNonce += 1 },
                        enabled = !state.loading,
                        size = 30.dp,
                        shadowOffset = 2.dp
                    )
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockBrutalActionButton(
                        label = "Copy",
                        leadingIcon = SlockLucideIconName.Copy,
                        onClick = { copyNonce += 1 },
                        backgroundColor = SlockColor.BrandYellow,
                        minWidth = 94.dp
                    )
                    SlockBrutalActionButton(
                        label = if (state.loading) "Loading" else "Refresh",
                        leadingIcon = SlockLucideIconName.RefreshCw,
                        onClick = { refreshNonce += 1 },
                        enabled = !state.loading,
                        minWidth = 104.dp
                    )
                }
            }
            state.errorMessage?.let { error ->
                item {
                    AuthDiagnosticsPanel("Load status") {
                        AuthDiagnosticsValue("Error", error)
                    }
                }
            }
            item {
                AuthDiagnosticsPanel("Network probe") {
                    val probe = state.probe
                    AuthDiagnosticsValue("Status", probe?.statusLabel ?: if (state.loading) "Loading" else "Unavailable")
                    AuthDiagnosticsValue("Base URL", probe?.baseUrl.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("HTTP code", probe?.code?.takeIf { it > 0 }?.toString() ?: "--")
                    AuthDiagnosticsValue("Duration", probe?.durationMillis?.takeIf { it > 0L }?.let { "${it}ms" } ?: "--")
                    probe?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        AuthDiagnosticsValue("Detail", detail)
                    }
                }
            }
            item {
                AuthDiagnosticsPanel("Environment") {
                    val about = state.about
                    AuthDiagnosticsValue("App", about?.appName.orEmpty().ifBlank { "Raft" })
                    AuthDiagnosticsValue("Version", about?.versionLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Build", about?.buildLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Source", about?.sourceLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Package", about?.packageName.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Platform", about?.platformLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Device", about?.deviceLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Session", about?.sessionStatusLabel.orEmpty().ifBlank { "Unknown" })
                    AuthDiagnosticsValue("Server", about?.selectedServerLabel.orEmpty().ifBlank { "Unknown" })
                }
            }
            item {
                AuthDiagnosticsPanel("Recent logs") {
                    if (state.entries.isEmpty()) {
                        AuthDiagnosticsValue("Log", if (state.loading) "Loading" else "No recent entries")
                    } else {
                        state.entries.takeLast(80).forEach { entry ->
                            AuthDiagnosticsLogEntry(entry)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AuthDiagnosticsPanel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun AuthDiagnosticsValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = SlockType.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp)
        )
    }
}

@Composable
private fun AuthDiagnosticsLogEntry(entry: SharedSettingsDiagnosticsEntry) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${entry.timestampLabel} ${entry.tag}",
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = entry.message,
            style = SlockType.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp)
        )
    }
}

private fun <T> DataResult<T>.authDiagnosticsErrorLabel(label: String): String? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Empty -> "$label empty: ${reason.name}"
        is DataResult.Error -> "$label error: ${error.code}"
        is DataResult.NoPermission -> "$label blocked: ${blocker.code}"
    }

internal fun buildAuthDiagnosticsExportText(
    about: SharedSettingsAboutSnapshot?,
    probe: SharedSettingsNetworkProbeSnapshot?,
    entries: List<SharedSettingsDiagnosticsEntry>,
    errorMessage: String? = null
): String =
    redactAuthDiagnosticsText(
        buildString {
            appendLine("Raft auth diagnostics")
            appendLine()
            appendLine("Environment")
            appendDiagnosticsLine("App", about?.appName ?: "Raft")
            appendDiagnosticsLine("Version", about?.versionLabel)
            appendDiagnosticsLine("Build", about?.buildLabel)
            appendDiagnosticsLine("Source", about?.sourceLabel)
            appendDiagnosticsLine("Package", about?.packageName)
            appendDiagnosticsLine("Platform", about?.platformLabel)
            appendDiagnosticsLine("Device", about?.deviceLabel)
            appendDiagnosticsLine("Session", about?.sessionStatusLabel)
            appendDiagnosticsLine("Signed in user", about?.signedInUserLabel)
            appendDiagnosticsLine("Selected server", about?.selectedServerLabel)
            appendLine()
            appendLine("Network probe")
            appendDiagnosticsLine("Base URL", probe?.baseUrl)
            appendDiagnosticsLine("Status", probe?.statusLabel)
            appendDiagnosticsLine("Reachable", probe?.reachable?.toString())
            appendDiagnosticsLine("HTTP code", probe?.code?.takeIf { it > 0 }?.toString())
            appendDiagnosticsLine("Duration", probe?.durationMillis?.takeIf { it > 0L }?.let { "${it}ms" })
            appendDiagnosticsLine("Detail", probe?.detail)
            errorMessage?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Load errors")
                appendDiagnosticsLine("Error", it)
            }
            appendLine()
            appendLine("Recent logs")
            if (entries.isEmpty()) {
                appendLine("No recent entries")
            } else {
                entries.takeLast(120).forEach { entry ->
                    appendLine("${entry.timestampLabel} ${entry.tag}: ${entry.message}")
                }
            }
        }
    )

private fun StringBuilder.appendDiagnosticsLine(label: String, value: String?) {
    append(label)
    append(": ")
    appendLine(value?.takeIf { it.isNotBlank() } ?: "--")
}

internal fun redactAuthDiagnosticsText(value: String): String {
    var redacted = value
    AuthDiagnosticsSensitivePatterns.forEach { pattern ->
        redacted = pattern.replace(redacted, "$1[redacted]")
    }
    redacted =
        AuthDiagnosticsEmailPattern.replace(redacted) { match ->
            "${match.groupValues[1]}[redacted]${match.groupValues[2]}"
        }
    return redacted
}

private val AuthDiagnosticsSensitivePatterns =
    listOf(
        Regex("""(?i)(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;]+"""),
        Regex("""(?i)(\bbearer\s+)[^\s,;]+"""),
        Regex("""(?i)(\baccess[_-]?token\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)(\brefresh[_-]?token\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)(\bpassword\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)(\bcookie\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)(\braw\s+(?:response|body)\s*[:=]\s*).+"""),
        Regex("""\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b""")
    )

private val AuthDiagnosticsEmailPattern =
    Regex("""\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")

@Composable
private fun AuthLoginInputsVisualScreen() {
    LoginContent(
        email = "artin@slock.test",
        password = "password",
        submitting = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onForgotPassword = {},
        onCreateAccount = {}
    )
}

@Composable
private fun AuthRegisterInputsVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    RegisterContent(
        name = params.optString(AUTH_REGISTER_VISUAL_NAME_KEY).ifBlank { AUTH_REGISTER_VISUAL_DEFAULT_NAME },
        email = params.optString(AUTH_REGISTER_VISUAL_EMAIL_KEY).ifBlank { AUTH_REGISTER_VISUAL_DEFAULT_EMAIL },
        password = params.optString(AUTH_REGISTER_VISUAL_PASSWORD_KEY).ifBlank { AUTH_REGISTER_VISUAL_DEFAULT_PASSWORD },
        submitting = false,
        errorMessage = null,
        onNameChange = {},
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onBackToSignIn = {},
        forceNameFocused = false
    )
}

@Composable
internal fun LoginContent(
    email: String,
    password: String,
    submitting: Boolean,
    errorMessage: String?,
    emailError: String? = null,
    passwordError: String? = null,
    socialProviders: Set<AuthProvider> = emptySet(),
    socialSubmittingProvider: AuthProvider? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitValues: (String, String) -> Unit = { _, _ -> onSubmit() },
    onSocialSignIn: (AuthProvider) -> Unit = {},
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    var emailDraft by remember { mutableStateOf(email) }
    var passwordDraft by remember { mutableStateOf(password) }
    val passwordFocusRequester = remember { FocusRequester() }
    LaunchedEffect(email) {
        if (email != emailDraft) {
            emailDraft = email
        }
    }
    LaunchedEffect(password) {
        if (password != passwordDraft) {
            passwordDraft = password
        }
    }
    val formBusy = submitting || socialSubmittingProvider != null
    val canSubmit = canSubmitLoginForm(emailDraft, passwordDraft, formBusy, emailError)
    val submitCurrentDraft = {
        when (val decision = resolveLoginDraftSubmit(emailDraft, passwordDraft, formBusy)) {
            is LoginDraftSubmitDecision.Forward ->
                onSubmitValues(decision.email, decision.password)
            LoginDraftSubmitDecision.IgnoreBusy -> Unit
        }
    }
    AuthScaffold(onDiagnosticsShortcut = onOpenDiagnostics) {
        AuthCenterMark()
        Text(
            text = "Sign In",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                value = emailDraft,
                onValueChange = {
                    emailDraft = it
                    onEmailChange(it)
                },
                label = "Email",
                required = true,
                enabled = !formBusy,
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
            )
            AuthTextField(
                value = passwordDraft,
                onValueChange = {
                    passwordDraft = it
                    onPasswordChange(it)
                },
                label = "Password",
                required = true,
                enabled = !formBusy,
                isError = !passwordError.isNullOrBlank(),
                errorText = passwordError,
                focusRequester = passwordFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitCurrentDraft() })
            )
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) "Signing In..." else "Sign In",
                onClick = submitCurrentDraft,
                tone = if (submitting) AuthButtonTone.Pink else AuthButtonTone.Pink,
                enabled = canSubmit,
                loading = submitting,
                preserveDisabledVisual = true
            )
            if (socialProviders.isNotEmpty()) {
                AuthDivider()
                if (AuthProvider.Google in socialProviders) {
                    AuthActionButton(
                        text = if (socialSubmittingProvider == AuthProvider.Google) "Opening Google" else "Continue with Google",
                        onClick = { onSocialSignIn(AuthProvider.Google) },
                        tone = AuthButtonTone.White,
                        enabled = !formBusy,
                        loading = socialSubmittingProvider == AuthProvider.Google,
                        preserveDisabledVisual = true,
                        leadingContent = { AuthProviderIcon(AuthProvider.Google) }
                    )
                }
                if (AuthProvider.GitHub in socialProviders) {
                    AuthActionButton(
                        text = if (socialSubmittingProvider == AuthProvider.GitHub) "Opening GitHub" else "Continue with GitHub",
                        onClick = { onSocialSignIn(AuthProvider.GitHub) },
                        tone = AuthButtonTone.Black,
                        enabled = !formBusy,
                        loading = socialSubmittingProvider == AuthProvider.GitHub,
                        preserveDisabledVisual = true,
                        leadingContent = { AuthProviderIcon(AuthProvider.GitHub) }
                    )
                }
            }
            AuthTextLink("Forgot Password?") { onForgotPassword() }
            AuthInlineAction("No account?", "Create One") { onCreateAccount() }
        }
    }
}

@Composable
internal fun RegisterContent(
    name: String,
    email: String,
    password: String,
    submitting: Boolean,
    errorMessage: String?,
    nameError: String? = null,
    emailError: String? = null,
    passwordError: String? = null,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    forceNameFocused: Boolean = true,
    onOpenDiagnostics: () -> Unit = {}
) {
    val formEnabled = !submitting
    var termsAccepted by remember { mutableStateOf(false) }
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    AuthScaffold(onDiagnosticsShortcut = onOpenDiagnostics) {
        AuthCenterMark()
        Text(
            text = "Create Account",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                name,
                onNameChange,
                "Name",
                placeholder = "Letters, numbers, hyphens, underscores",
                required = true,
                enabled = formEnabled,
                isError = !nameError.isNullOrBlank(),
                errorText = nameError,
                forceFocused = forceNameFocused,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { emailFocusRequester.requestFocus() })
            )
            AuthTextField(
                email,
                onEmailChange,
                "Email",
                required = true,
                enabled = formEnabled,
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                focusRequester = emailFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
            )
            AuthTextField(
                password,
                onPasswordChange,
                "Password",
                placeholder = "Min 8 characters",
                required = true,
                enabled = formEnabled,
                isError = !passwordError.isNullOrBlank(),
                errorText = passwordError,
                focusRequester = passwordFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
            AuthTermsAgreement(termsAccepted, { termsAccepted = it })
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) "Creating account..." else "Create Account",
                onClick = onSubmit,
                tone = AuthButtonTone.Pink,
                enabled = formEnabled && termsAccepted && name.isNotBlank() && email.isNotBlank() && password.isNotBlank() && emailError == null,
                loading = submitting,
                preserveDisabledVisual = true,
                variant = AuthActionButtonVariant.Large
            )
            AuthInlineAction("Already have an account?", "Sign In") { onBackToSignIn() }
        }
    }
}

@Composable
internal fun ForgotPasswordContent(
    email: String,
    sending: Boolean,
    sent: Boolean,
    errorMessage: String?,
    emailError: String? = null,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    AuthScaffold(onDiagnosticsShortcut = onOpenDiagnostics) {
        AuthCenterMark()
        Text(
            text = "Reset password",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        Text(
            text = "Enter your email and we’ll send you a link to\nreset your password.",
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                value = email,
                onValueChange = onEmailChange,
                label = "Email",
                enabled = !sending && !sent,
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                forceFocused = !sent,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
            AuthError(errorMessage)
            if (sent) {
                AuthSuccessNotice(
                    title = "Reset link sent",
                    detail = "Check ${email.ifBlank { "your email" }} for your secure reset link."
                )
            }
            AuthActionButton(
                text = if (sending) "Sending link..." else "Send reset link",
                onClick = onSubmit,
                tone = AuthButtonTone.Pink,
                enabled = email.isNotBlank() && emailError == null && !sent,
                loading = sending,
                preserveDisabledVisual = true
            )
            AuthTextLink("Back to sign in") { onBackToSignIn() }
        }
    }
}

@Composable
private fun AuthTermsAgreement(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (checked) SlockLucideIcon(SlockLucideIconName.Check, Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "I agree to the ",
                    style = AuthTermsTextStyle,
                    color = AuthTermsTextColor,
                    modifier = Modifier.authPageStaticTextFocus()
                )
                Text("Terms of Service", style = AuthTermsLinkStyle, color = SlockColor.TextBlack)
                Text(
                    " and",
                    style = AuthTermsTextStyle,
                    color = AuthTermsTextColor,
                    modifier = Modifier.authPageStaticTextFocus()
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "acknowledge the ",
                    style = AuthTermsTextStyle,
                    color = AuthTermsTextColor,
                    modifier = Modifier.authPageStaticTextFocus()
                )
                Text("Privacy Policy.", style = AuthTermsLinkStyle, color = SlockColor.TextBlack)
            }
        }
    }
}

private val AuthTermsTextColor = SlockColor.TextBlack.copy(alpha = 0.70f)
private val AuthTermsTextStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = AuthTermsTextColor)
private val AuthTermsLinkStyle = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack, textDecoration = TextDecoration.Underline)

@Composable
private fun LoginContentPreview() {
    LoginContent(
        email = "artin@raft.build",
        password = "password",
        submitting = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onForgotPassword = {},
        onCreateAccount = {}
    )
}

@Composable
private fun RegisterStatesPreview() {
    RegisterContent(
        name = "Very Long Name With 中文 Mixed",
        email = "new@raft.build",
        password = "password",
        submitting = false,
        errorMessage = null,
        onNameChange = {},
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onBackToSignIn = {}
    )
}

@Composable
private fun ForgotPasswordSuccessPreview() {
    ForgotPasswordContent(
        email = "very.long.email.address+team@raft.build",
        sending = false,
        sent = true,
        errorMessage = null,
        onEmailChange = {},
        onSubmit = {},
        onBackToSignIn = {}
    )
}

private fun Pager.openAuthPage(pageName: String) {
    val pageData =
        JSONObject().apply {
            put("pageName", pageName)
            this@openAuthPage.pageData.params.optString(AUTH_SOCIAL_PROVIDERS_KEY)
                .takeIf { it.isNotBlank() }
                ?.let { put(AUTH_SOCIAL_PROVIDERS_KEY, it) }
            if (pageName == SLOCK_APP_SHELL_PAGE) {
                put(AUTH_REPLACE_CURRENT_PAGE, true)
            }
        }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}

private fun Pager.socialAuthProviders(): Set<AuthProvider> =
    pageData.params.optString(AUTH_SOCIAL_PROVIDERS_KEY)
        .split(',')
        .mapNotNull { raw ->
            when (raw.trim().lowercase()) {
                AuthProvider.Google.providerId() -> AuthProvider.Google
                AuthProvider.GitHub.providerId() -> AuthProvider.GitHub
                else -> null
            }
        }
        .toSet()

private fun AuthProvider.providerId(): String =
    when (this) {
        AuthProvider.Google -> "google"
        AuthProvider.GitHub -> "github"
    }

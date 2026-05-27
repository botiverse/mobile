package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.slock.compose.base.Utils
import ai.slock.compose.data.BridgeAuthApi
import ai.slock.compose.data.KuiklyAuthSessionStore
import ai.slock.compose.data.KuiklyRelationalStore
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
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
private const val AUTH_LOGIN_INPUTS_VISUAL_PAGE = "compose_auth_login_inputs"
private const val AUTH_REGISTER_INPUTS_VISUAL_PAGE = "compose_auth_register_inputs"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
private const val AUTH_SOCIAL_PROVIDERS_KEY = "slockSocialAuthProviders"

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
    val authDataSource =
        remember {
            SharedAuthDataSource(
                transport = BridgeAuthApi(bridge),
                sessionStore = KuiklyAuthSessionStore(KuiklyRelationalStore(bridge))
            )
        }
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    var pendingSocialAuth by remember { mutableStateOf<PendingSocialAuth?>(null) }
    var socialSubmittingProvider by remember { mutableStateOf<AuthProvider?>(null) }
    val socialProviders = remember(pager.pageData.params.toString()) { pager.socialAuthProviders() }

    LaunchedEffect(pendingLogin) {
        val effect = pendingLogin ?: return@LaunchedEffect
        val result =
            runCatching { authDataSource.login(effect.email, effect.password) }
                .getOrElse { SharedAuthResult.Failure(ai.slock.compose.data.SharedAuthErrorCode.Network, "Network unavailable. Check your connection.") }
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
        val update = reducer.reduce(state.copy(email = emailValue, password = passwordValue), LoginFormEvent.Submit)
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
            null -> Unit
        }
    }

    LoginContent(
        email = state.email,
        password = state.password,
        submitting = state.submitting,
        errorMessage = state.errorMessage,
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
        onCreateAccount = { pager.openAuthPage(REGISTER_PAGE) }
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

@Composable
private fun RegisterScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val reducer = remember { CreateAccountFormReducer() }
    var state by remember { mutableStateOf(CreateAccountFormState()) }

    RegisterContent(
        name = state.name,
        email = state.email,
        password = state.password,
        submitting = state.submitting,
        errorMessage = state.errorMessage,
        onNameChange = { state = reducer.reduce(state, CreateAccountFormEvent.NameChanged(it)).state },
        onEmailChange = { state = reducer.reduce(state, CreateAccountFormEvent.EmailChanged(it)).state },
        onPasswordChange = { state = reducer.reduce(state, CreateAccountFormEvent.PasswordChanged(it)).state },
        onSubmit = {
            val update = reducer.reduce(state, CreateAccountFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is CreateAccountFormEffect.SubmitCreateAccount -> {
                    Utils.bridgeModule(pager.pagerId).toast("Create account: ${effect.email}")
                    state = state.copy(submitting = false)
                }
                CreateAccountFormEffect.FocusName -> Unit
                null -> Unit
            }
        },
        onBackToSignIn = { pager.openAuthPage(LOGIN_PAGE) }
    )
}

@Composable
private fun ForgotPasswordScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val reducer = remember { ForgotPasswordFormReducer() }
    var state by remember { mutableStateOf(ForgotPasswordFormState()) }

    ForgotPasswordContent(
        email = state.email,
        sending = state.submitting,
        sent = state.sent,
        errorMessage = state.errorMessage,
        onEmailChange = { state = reducer.reduce(state, ForgotPasswordFormEvent.EmailChanged(it)).state },
        onSubmit = {
            val update = reducer.reduce(state, ForgotPasswordFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is ForgotPasswordFormEffect.SubmitForgotPassword -> {
                    Utils.bridgeModule(pager.pagerId).toast("Reset password: ${effect.email}")
                    state = state.copy(submitting = false, sent = true)
                }
                ForgotPasswordFormEffect.FocusEmail -> Unit
                null -> Unit
            }
        },
        onBackToSignIn = { pager.openAuthPage(LOGIN_PAGE) }
    )
}

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
    RegisterContent(
        name = "",
        email = "new@slock.test",
        password = "",
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
    socialProviders: Set<AuthProvider> = emptySet(),
    socialSubmittingProvider: AuthProvider? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitValues: (String, String) -> Unit = { _, _ -> onSubmit() },
    onSocialSignIn: (AuthProvider) -> Unit = {},
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit
) {
    var emailDraft by remember { mutableStateOf(email) }
    var passwordDraft by remember { mutableStateOf(password) }
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
    val canSubmit = canSubmitLoginForm(emailDraft, passwordDraft, formBusy)
    val submitIfReady = {
        if (canSubmit) {
            onSubmitValues(emailDraft, passwordDraft)
        }
    }
    AuthScaffold {
        AuthCenterMark()
        Text(
            text = "Sign in",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions.Default
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitIfReady() })
            )
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) "Signing in..." else "Sign in",
                onClick = submitIfReady,
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
            AuthTextLink("Forgot password?") { onForgotPassword() }
            AuthInlineAction("No account?", "Create one") { onCreateAccount() }
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
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    forceNameFocused: Boolean = true
) {
    val formEnabled = !submitting
    var termsAccepted by remember { mutableStateOf(false) }
    AuthScaffold {
        AuthCenterMark()
        Text(
            text = "Create account",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthFormColumn {
            AuthTextField(name, onNameChange, "Name", placeholder = "Letters, numbers, hyphens, underscores", required = true, enabled = formEnabled, forceFocused = forceNameFocused)
            AuthTextField(email, onEmailChange, "Email", required = true, enabled = formEnabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            AuthTextField(
                password,
                onPasswordChange,
                "Password",
                placeholder = "Min 8 characters",
                required = true,
                enabled = formEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
            AuthTermsAgreement(termsAccepted, { termsAccepted = it })
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) "Creating account..." else "Create account",
                onClick = onSubmit,
                tone = AuthButtonTone.Pink,
                enabled = formEnabled && termsAccepted && name.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                loading = submitting,
                preserveDisabledVisual = true
            )
            AuthInlineAction("Already have an account?", "Sign in") { onBackToSignIn() }
        }
    }
}

@Composable
internal fun ForgotPasswordContent(
    email: String,
    sending: Boolean,
    sent: Boolean,
    errorMessage: String?,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit
) {
    AuthScaffold {
        AuthCenterMark()
        Text(
            text = "Reset password",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = "Enter your email and we’ll send you a link to\nreset your password.",
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            AuthTextField(
                value = email,
                onValueChange = onEmailChange,
                label = "Email",
                enabled = !sending && !sent,
                isError = !errorMessage.isNullOrBlank(),
                errorText = errorMessage,
                forceFocused = !sent,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
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
                enabled = email.isNotBlank() && !sent,
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
            if (checked) SlockLucideIcon("check", Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I agree to the ", style = AuthTermsTextStyle, color = SlockColor.TextMutedGray)
                Text("Terms of Service", style = AuthTermsLinkStyle, color = SlockColor.TextBlack)
                Text(" and", style = AuthTermsTextStyle, color = SlockColor.TextMutedGray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("acknowledge the ", style = AuthTermsTextStyle, color = SlockColor.TextMutedGray)
                Text("Privacy Policy.", style = AuthTermsLinkStyle, color = SlockColor.TextBlack)
            }
        }
    }
}

private val AuthTermsTextStyle = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray)
private val AuthTermsLinkStyle = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack, textDecoration = TextDecoration.Underline)

@Composable
private fun LoginContentPreview() {
    LoginContent(
        email = "artin@slock.ai",
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
        email = "new@slock.ai",
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
        email = "very.long.email.address+team@slock.ai",
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

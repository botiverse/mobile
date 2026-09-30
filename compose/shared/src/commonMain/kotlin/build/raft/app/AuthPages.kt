package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.DataResult
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedAuthRuntimeCapabilities
import build.raft.app.data.SharedAuthRuntimeCapabilityStore
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsEntry
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
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
import com.tencent.kuikly.compose.ui.input.key.Key
import com.tencent.kuikly.compose.ui.input.key.KeyEventType
import com.tencent.kuikly.compose.ui.input.key.onPreviewKeyEvent
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.LinkInteractionListener
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.withLink
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.launch

const val LOGIN_PAGE = "compose_login"
const val REGISTER_PAGE = "compose_register"
const val AUTH_PROFILE_SETUP_PAGE = "compose_auth_profile_setup"
const val FORGOT_PASSWORD_PAGE = "compose_forgot_password"
const val AUTH_LEGAL_ACCEPTANCE_PAGE = "compose_auth_legal_acceptance"
const val AUTH_ABOUT_PAGE = "compose_auth_developer"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
internal const val AUTH_SOCIAL_PROVIDERS_KEY = "slockSocialAuthProviders"
private const val AUTH_MESSAGE_KEY = "slockAuthMessage"
private const val AUTH_LEGAL_PROVIDER_KEY = "slockAuthLegalProvider"
private const val AUTH_LEGAL_PLATFORM_KEY = "slockAuthLegalPlatform"
private const val AUTH_LEGAL_CODE_KEY = "slockAuthLegalCode"
private const val AUTH_LEGAL_CODE_VERIFIER_KEY = "slockAuthLegalCodeVerifier"
private const val AUTH_VISUAL_FIXTURE_KEY = "visualFixture"
private const val AUTH_LOGIN_VISUAL_FIXTURE_SIGNING = "signing"
private const val AUTH_LOGIN_VISUAL_FIXTURE_INVALID = "invalid"
// Mirrors the react screens.auth.login.signing fill interactions
// (packages/visual-testing/shared/sharedCases.json): same email and the same
// 21-char password so both sides mask the same number of dots.
private const val AUTH_LOGIN_VISUAL_SIGNING_EMAIL = "artin@raft.build"
private const val AUTH_LOGIN_VISUAL_SIGNING_PASSWORD = "visual-sign-in-secret"
private const val AUTH_REGISTER_VISUAL_EMAIL_KEY = "registerEmail"
private const val AUTH_REGISTER_VISUAL_PASSWORD_KEY = "registerPassword"
private const val AUTH_REGISTER_VISUAL_DEFAULT_EMAIL = "new@raft.build"
private const val AUTH_REGISTER_VISUAL_DEFAULT_PASSWORD = "correct horse battery staple"
private const val AUTH_PROFILE_SETUP_VISUAL_HANDLE_KEY = "profileSetupHandle"
private const val AUTH_PROFILE_SETUP_VISUAL_DISPLAY_NAME_KEY = "profileSetupDisplayName"
private const val AUTH_PROFILE_SETUP_VISUAL_DEFAULT_HANDLE = "new_designer"
private const val AUTH_PROFILE_SETUP_VISUAL_DEFAULT_DISPLAY_NAME = "New Designer"
private const val AUTH_TERMS_URL = "https://raft.build/terms"
private const val AUTH_PRIVACY_URL = "https://raft.build/privacy"

private val MobileHiddenSocialAuthProviders = emptySet<AuthProvider>()

private fun Modifier.authPageStaticTextFocus(): Modifier =
    focusProperties { canFocus = false }

private fun Set<AuthProvider>.visibleMobileSocialAuthProviders(): Set<AuthProvider> =
    filterNot { provider -> provider in MobileHiddenSocialAuthProviders }.toSet()

@Composable
private fun rememberCurrentEnvironmentAuthDataSource(bridge: BridgeModule): SharedAuthDataSource {
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeSlockAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext()
            )
        }
    return remember(sharedStores, avatarNativeCache) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(avatarNativeCache)
    }
}

private sealed interface AuthErrorPresentation {
    data class Dynamic(val message: String) : AuthErrorPresentation

    data class Static(val message: StringDesc) : AuthErrorPresentation
}

@Composable
private fun AuthErrorPresentation?.localizedMessage(): String? =
    when (this) {
        is AuthErrorPresentation.Dynamic -> message
        is AuthErrorPresentation.Static -> message.localized()
        null -> null
    }

@Composable
internal fun AuthValidationError?.localizedMessage(): String? {
    val description =
        when (this) {
            AuthValidationError.InvalidEmailFormat -> MR.strings.auth_validation_invalid_email.desc()
            AuthValidationError.PasswordRequired -> MR.strings.auth_validation_password_required.desc()
            AuthValidationError.PasswordTooShort ->
                StringDesc.ResourceFormatted(
                    MR.strings.auth_validation_password_min_length,
                    listOf(AUTH_PASSWORD_MIN_LENGTH.toString())
                )
            AuthValidationError.UsernameTooShort ->
                StringDesc.ResourceFormatted(
                    MR.strings.auth_validation_username_min_length,
                    listOf(AUTH_IDENTITY_NAME_MIN_LENGTH.toString())
                )
            AuthValidationError.UsernameTooLong ->
                StringDesc.ResourceFormatted(
                    MR.strings.auth_validation_username_max_length,
                    listOf(AUTH_IDENTITY_NAME_MAX_LENGTH.toString())
                )
            AuthValidationError.UsernameInvalidFormat -> MR.strings.auth_validation_username_format.desc()
            AuthValidationError.UsernameReserved -> MR.strings.auth_validation_username_reserved.desc()
            AuthValidationError.DisplayNameRequired -> MR.strings.auth_validation_display_name_required.desc()
            AuthValidationError.DisplayNameTooLong ->
                StringDesc.ResourceFormatted(
                    MR.strings.auth_validation_display_name_max_length,
                    listOf(AUTH_IDENTITY_DISPLAY_NAME_MAX_LENGTH.toString())
                )
            null -> null
        }
    return description?.localized()
}

@Page(LOGIN_PAGE, supportInLocal = true)
internal class ComposeLoginPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { LoginScreen() }
    }
}

@Page(REGISTER_PAGE, supportInLocal = true)
internal class ComposeRegisterPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RegisterScreen() }
    }
}

@Page(AUTH_PROFILE_SETUP_PAGE, supportInLocal = true)
internal class ComposeAuthProfileSetupPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthProfileSetupScreen() }
    }
}

@Page(FORGOT_PASSWORD_PAGE, supportInLocal = true)
internal class ComposeForgotPasswordPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ForgotPasswordScreen() }
    }
}

@Page(AUTH_LEGAL_ACCEPTANCE_PAGE, supportInLocal = true)
internal class ComposeAuthLegalAcceptancePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthLegalAcceptanceScreen() }
    }
}

@Page(AUTH_ABOUT_PAGE, supportInLocal = true)
internal class ComposeAuthAboutPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthAboutScreen() }
    }
}

@Composable
private fun LoginScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    val paramsSignature = pager.pageData.params.toString()
    // Seed host capabilities synchronously and observe subsequent host updates.
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val authRuntimeCapabilities by sharedStores.authRuntimeCapabilityStore.state.collectAsState()
    if (authRuntimeCapabilities.webLoginOnly) {
        WebLoginScreen(pager, bridge, authDataSource)
        return
    }
    val linkNavigator =
        rememberSlockLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = SlockInAppBrowserRouteScope.Unscoped,
        )
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var emailFieldValue by remember { mutableStateOf(TextFieldValue(state.email)) }
    var passwordFieldValue by remember { mutableStateOf(TextFieldValue(state.password)) }
    var errorPresentation by remember { mutableStateOf<AuthErrorPresentation?>(null) }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    var pendingSocialAuth by remember { mutableStateOf<PendingSocialAuth?>(null) }
    var socialSubmittingProvider by remember { mutableStateOf<AuthProvider?>(null) }
    val socialProviders = remember(authRuntimeCapabilities) {
        authRuntimeCapabilities.socialAuthProviders(
            applePlatformSupported = slockPlatformSupportsAppleSignIn()
        )
    }
    val routeMessage = remember(paramsSignature) {
        pager.pageData.params.optString(AUTH_MESSAGE_KEY).takeIf { it.isNotBlank() }
    }

    LaunchedEffect(pendingLogin) {
        val effect = pendingLogin ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.login(effect.email, effect.password) }
                .getOrNull()
        pendingLogin = null
        if (result == null) {
            state = state.copy(submitting = false, errorMessage = null)
            errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_login_setup.desc())
            return@LaunchedEffect
        }
        when (result) {
            is SharedAuthResult.Success -> {
                when (authDataSource.postAuthSessionReadback(result.data)) {
                    PostAuthSessionReadback.Ready -> {
                        state = state.copy(submitting = false, errorMessage = null)
                        errorPresentation = null
                        val destination = postAuthRouteDestination(result.data)
                        authDataSource.logDiagnostics(
                            "auth_post_success_route page=${destination.pageName} source=login " +
                                "serversCount=${result.data.servers.size} " +
                                "selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                        )
                        pager.openPostAuthRoute(result.data)
                    }
                    PostAuthSessionReadback.Missing ->
                        state = state.copy(
                            submitting = false,
                            errorMessage = null
                        ).also {
                            errorPresentation =
                                AuthErrorPresentation.Static(MR.strings.auth_error_local_session_setup.desc())
                        }
                }
            }
            is SharedAuthResult.Failure -> {
                state = state.copy(submitting = false, errorMessage = null)
                errorPresentation = AuthErrorPresentation.Dynamic(result.message)
            }
            SharedAuthResult.Unauthenticated -> {
                state = state.copy(submitting = false, errorMessage = null)
                errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_login_failed.desc())
            }
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
                errorMessage = null
            )
            errorPresentation =
                response?.optString("message")
                    .takeUnless { it.isNullOrBlank() }
                    ?.let(AuthErrorPresentation::Dynamic)
                    ?: AuthErrorPresentation.Static(
                        StringDesc.ResourceFormatted(
                            MR.strings.auth_error_provider_unavailable,
                            listOf(effect.provider.providerDisplayName())
                        )
                    )
        } else {
            state = state.copy(submitting = false, errorMessage = null)
            errorPresentation = null
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
        errorPresentation = null
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
        email = emailFieldValue,
        password = passwordFieldValue,
        submitting = state.submitting,
        errorMessage = errorPresentation.localizedMessage() ?: state.errorMessage ?: routeMessage,
        emailError = state.emailFormatError.localizedMessage(),
        passwordError = state.passwordError.localizedMessage(),
        socialProviders = socialProviders,
        socialSubmittingProvider = socialSubmittingProvider,
        onEmailChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { emailFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, LoginFormEvent.EmailChanged(it)).state
                },
            )
        },
        onPasswordChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { passwordFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, LoginFormEvent.PasswordChanged(it)).state
                },
            )
        },
        onSubmit = { submitLogin() },
        onSubmitValues = { emailValue, passwordValue -> submitLogin(emailValue, passwordValue) },
        onSocialSignIn = { provider ->
            state = state.copy(submitting = false, errorMessage = null)
            errorPresentation = null
            socialSubmittingProvider = provider
            pendingSocialAuth =
                PendingSocialAuth(
                    nonce = (pendingSocialAuth?.nonce ?: 0) + 1,
                    provider = provider
                )
        },
        onForgotPassword = { pager.openAuthPage(FORGOT_PASSWORD_PAGE) },
        onCreateAccount = { pager.openAuthPage(REGISTER_PAGE) },
        onOpenTerms = { linkNavigator.open(AUTH_TERMS_URL) },
        onOpenPrivacy = { linkNavigator.open(AUTH_PRIVACY_URL) },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_ABOUT_PAGE) }
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
    val email: String,
    val password: String
)

private data class PendingProfileSetup(
    val nonce: Int,
    val handle: String,
    val displayName: String
)

private data class PendingForgotPassword(
    val nonce: Int,
    val email: String
)

/**
 * Applies the native editing snapshot to the synchronous UI owner before projecting plain text to
 * auth reducers or request state. The projection is intentionally one-way while this editor
 * instance is mounted: reducer recomposition must never rebuild selection or IME composition.
 */
internal fun applyAuthTextFieldValue(
    nextValue: TextFieldValue,
    updateOwner: (TextFieldValue) -> Unit,
    projectText: (String) -> Unit,
) {
    updateOwner(nextValue)
    projectText(nextValue.text)
}

private data class PendingMobileOAuthLegalAcceptance(
    val nonce: Int,
    val code: String,
    val codeVerifier: String
)

@Composable
private fun AuthLegalAcceptanceScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val linkNavigator =
        rememberSlockLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = SlockInAppBrowserRouteScope.Unscoped,
        )
    val paramsSignature = pager.pageData.params.toString()
    val providerId = remember(paramsSignature) {
        pager.pageData.params.optString(AUTH_LEGAL_PROVIDER_KEY).ifBlank { AuthProvider.Google.providerId() }
    }
    val platform = remember(paramsSignature) {
        pager.pageData.params.optString(AUTH_LEGAL_PLATFORM_KEY).ifBlank { "android" }
    }
    val code = remember(paramsSignature) { pager.pageData.params.optString(AUTH_LEGAL_CODE_KEY) }
    val codeVerifier = remember(paramsSignature) { pager.pageData.params.optString(AUTH_LEGAL_CODE_VERIFIER_KEY) }
    var accepted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errorPresentation by remember {
        mutableStateOf<AuthErrorPresentation?>(
            if (code.isBlank() || codeVerifier.isBlank()) {
                AuthErrorPresentation.Static(MR.strings.auth_error_request_expired.desc())
            } else {
                null
            }
        )
    }
    var pending by remember { mutableStateOf<PendingMobileOAuthLegalAcceptance?>(null) }
    val providerName = remember(providerId) { providerId.mobileOAuthProviderDisplayName() }
    val providerCancelledMessage =
        remember(providerName) {
            StringDesc.ResourceFormatted(
                MR.strings.auth_provider_cancelled,
                listOf(providerName)
            )
        }.localized()

    LaunchedEffect(pending) {
        val effect = pending ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation {
                authDataSource.completeMobileOAuthLogin(
                    code = effect.code,
                    codeVerifier = effect.codeVerifier,
                    platform = platform,
                    acceptLegalTerms = true
                )
            }.getOrNull()
        pending = null
        if (result == null) {
            submitting = false
            errorPresentation =
                AuthErrorPresentation.Static(
                    StringDesc.ResourceFormatted(
                        MR.strings.auth_error_provider_setup,
                        listOf(providerName)
                    )
                )
            return@LaunchedEffect
        }
        when (result) {
            is SharedAuthResult.Success -> {
                submitting = false
                errorPresentation = null
                val destination = postAuthRouteDestination(result.data)
                authDataSource.logDiagnostics(
                    "auth_post_success_route page=${destination.pageName} source=mobile_oauth_legal " +
                        "serversCount=${result.data.servers.size} " +
                        "selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                pager.openPostAuthRoute(result.data)
            }
            is SharedAuthResult.Failure -> {
                submitting = false
                errorPresentation = AuthErrorPresentation.Dynamic(result.message)
            }
            SharedAuthResult.Unauthenticated -> {
                submitting = false
                errorPresentation =
                    AuthErrorPresentation.Static(
                        StringDesc.ResourceFormatted(
                            MR.strings.auth_error_provider_failed,
                            listOf(providerName)
                        )
                    )
            }
        }
    }

    AuthLegalAcceptanceContent(
        providerName = providerName,
        accepted = accepted,
        submitting = submitting,
        errorMessage = errorPresentation.localizedMessage(),
        canSubmit = code.isNotBlank() && codeVerifier.isNotBlank(),
        onAcceptedChange = { accepted = it },
        onContinue = {
            if (submitting || !accepted || code.isBlank() || codeVerifier.isBlank()) return@AuthLegalAcceptanceContent
            submitting = true
            errorPresentation = null
            pending =
                PendingMobileOAuthLegalAcceptance(
                    nonce = (pending?.nonce ?: 0) + 1,
                    code = code,
                    codeVerifier = codeVerifier
                )
        },
        onBackToSignIn = {
            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
                LOGIN_PAGE,
                loginPageData(
                    replaceCurrent = true,
                    message = providerCancelledMessage
                )
            )
        },
        onOpenTerms = { linkNavigator.open(AUTH_TERMS_URL) },
        onOpenPrivacy = { linkNavigator.open(AUTH_PRIVACY_URL) }
    )
}

@Composable
private fun RegisterScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    val reducer = remember { CreateAccountFormReducer() }
    var state by remember { mutableStateOf(CreateAccountFormState()) }
    var emailFieldValue by remember { mutableStateOf(TextFieldValue(state.email)) }
    var passwordFieldValue by remember { mutableStateOf(TextFieldValue(state.password)) }
    var errorPresentation by remember { mutableStateOf<AuthErrorPresentation?>(null) }
    var pendingRegister by remember { mutableStateOf<PendingRegister?>(null) }

    LaunchedEffect(pendingRegister) {
        val effect = pendingRegister ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation {
                authDataSource.register(
                    email = effect.email,
                    password = effect.password
                )
            }.getOrNull()
        pendingRegister = null
        if (result == null) {
            state = state.copy(submitting = false, errorMessage = null)
            errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_register_setup.desc())
            return@LaunchedEffect
        }
        when (result) {
            is SharedAuthResult.Success -> {
                when (authDataSource.postAuthSessionReadback(result.data)) {
                    PostAuthSessionReadback.Ready -> {
                        state = state.copy(submitting = false, errorMessage = null)
                        errorPresentation = null
                        val destination = postAuthRouteDestination(result.data)
                        authDataSource.logDiagnostics(
                            "auth_post_success_route page=${destination.pageName} source=register " +
                                "serversCount=${result.data.servers.size} " +
                                "selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                        )
                        pager.openPostAuthRoute(result.data)
                    }
                    PostAuthSessionReadback.Missing ->
                        state = state.copy(
                            submitting = false,
                            errorMessage = null
                        ).also {
                            errorPresentation =
                                AuthErrorPresentation.Static(MR.strings.auth_error_local_session_setup.desc())
                        }
                }
            }
            is SharedAuthResult.Failure -> {
                state = state.copy(submitting = false, errorMessage = null)
                errorPresentation = AuthErrorPresentation.Dynamic(result.message)
            }
            SharedAuthResult.Unauthenticated -> {
                state = state.copy(submitting = false, errorMessage = null)
                errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_register_failed.desc())
            }
        }
    }

    RegisterContent(
        email = emailFieldValue,
        password = passwordFieldValue,
        submitting = state.submitting,
        errorMessage = errorPresentation.localizedMessage() ?: state.errorMessage,
        emailError = state.emailFormatError.localizedMessage(),
        passwordError = state.passwordError.localizedMessage(),
        onEmailChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { emailFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, CreateAccountFormEvent.EmailChanged(it)).state
                },
            )
        },
        onPasswordChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { passwordFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, CreateAccountFormEvent.PasswordChanged(it)).state
                },
            )
        },
        onSubmit = {
            errorPresentation = null
            val update = reducer.reduce(state, CreateAccountFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is CreateAccountFormEffect.SubmitCreateAccount -> {
                    pendingRegister = PendingRegister(
                        nonce = (pendingRegister?.nonce ?: 0) + 1,
                        email = effect.email,
                        password = effect.password
                    )
                }
                CreateAccountFormEffect.FocusEmail -> Unit
                CreateAccountFormEffect.FocusPassword -> Unit
                null -> Unit
            }
        },
        onBackToSignIn = { pager.closeAuthSubPage() },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_ABOUT_PAGE) }
    )
}

@Composable
private fun AuthProfileSetupScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    val reducer = remember { IdentitySetupFormReducer() }
    var state by remember { mutableStateOf(IdentitySetupFormState()) }
    var handleFieldValue by remember { mutableStateOf(TextFieldValue(state.handle)) }
    var displayNameFieldValue by remember { mutableStateOf(TextFieldValue(state.displayName)) }
    var loaded by remember { mutableStateOf(false) }
    var errorPresentation by remember { mutableStateOf<AuthErrorPresentation?>(null) }
    var pendingProfileSetup by remember { mutableStateOf<PendingProfileSetup?>(null) }

    LaunchedEffect(Unit) {
        when (val current = authDataSource.currentSession()) {
            is SharedAuthResult.Success -> {
                if (!current.data.user.requiresProfileSetup) {
                    pager.openPostAuthRoute(current.data)
                    return@LaunchedEffect
                }
                val handle = current.data.user.profileSetupSuggestedHandle.orEmpty()
                val displayName = current.data.user.displayName.orEmpty()
                state = state.copy(handle = handle, displayName = displayName)
                handleFieldValue = TextFieldValue(handle)
                displayNameFieldValue = TextFieldValue(displayName)
                loaded = true
            }
            SharedAuthResult.Unauthenticated -> pager.openAuthPage(LOGIN_PAGE)
            is SharedAuthResult.Failure -> {
                loaded = true
                errorPresentation = AuthErrorPresentation.Dynamic(current.message)
            }
        }
    }

    LaunchedEffect(pendingProfileSetup) {
        val effect = pendingProfileSetup ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation {
                authDataSource.completeProfile(effect.handle, effect.displayName)
            }.getOrNull()
        pendingProfileSetup = null
        when (result) {
            is SharedAuthResult.Success -> pager.openPostAuthRoute(result.data)
            is SharedAuthResult.Failure -> {
                state = state.copy(submitting = false)
                errorPresentation = AuthErrorPresentation.Dynamic(result.message)
            }
            SharedAuthResult.Unauthenticated -> pager.openAuthPage(LOGIN_PAGE)
            null -> {
                state = state.copy(submitting = false)
                errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_profile_setup_failed.desc())
            }
        }
    }

    if (!loaded) {
        AuthScaffold { AuthCenterMark() }
        return
    }

    AuthProfileSetupContent(
        handle = handleFieldValue,
        displayName = displayNameFieldValue,
        submitting = state.submitting,
        errorMessage = errorPresentation.localizedMessage() ?: state.errorMessage,
        handleError = state.handleError.localizedMessage(),
        displayNameError = state.displayNameError.localizedMessage(),
        onHandleChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { handleFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, IdentitySetupFormEvent.HandleChanged(it)).state
                },
            )
        },
        onDisplayNameChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { displayNameFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, IdentitySetupFormEvent.DisplayNameChanged(it)).state
                },
            )
        },
        onSubmit = {
            errorPresentation = null
            val update = reducer.reduce(state, IdentitySetupFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is IdentitySetupFormEffect.SubmitIdentity ->
                    pendingProfileSetup = PendingProfileSetup(
                        nonce = (pendingProfileSetup?.nonce ?: 0) + 1,
                        handle = effect.handle,
                        displayName = effect.displayName
                    )
                IdentitySetupFormEffect.FocusHandle,
                IdentitySetupFormEffect.FocusDisplayName,
                null -> Unit
            }
        },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_ABOUT_PAGE) }
    )
}

@Composable
private fun ForgotPasswordScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    val reducer = remember { ForgotPasswordFormReducer() }
    var state by remember { mutableStateOf(ForgotPasswordFormState()) }
    var emailFieldValue by remember { mutableStateOf(TextFieldValue(state.email)) }
    var errorPresentation by remember { mutableStateOf<AuthErrorPresentation?>(null) }
    var pendingForgotPassword by remember { mutableStateOf<PendingForgotPassword?>(null) }

    LaunchedEffect(pendingForgotPassword) {
        val effect = pendingForgotPassword ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.forgotPassword(effect.email) }
                .getOrNull()
        pendingForgotPassword = null
        if (result == null) {
            state = state.copy(submitting = false, sent = false, errorMessage = null)
            errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_password_reset_setup.desc())
            return@LaunchedEffect
        }
        state =
            when (result) {
                is SharedAuthResult.Success ->
                    state.copy(submitting = false, sent = true, errorMessage = null)
                is SharedAuthResult.Failure -> {
                    errorPresentation = AuthErrorPresentation.Dynamic(result.message)
                    state.copy(submitting = false, sent = false, errorMessage = null)
                }
                SharedAuthResult.Unauthenticated -> {
                    errorPresentation = AuthErrorPresentation.Static(MR.strings.auth_error_password_reset_failed.desc())
                    state.copy(submitting = false, sent = false, errorMessage = null)
                }
            }
        if (result is SharedAuthResult.Success) {
            errorPresentation = null
        }
    }

    ForgotPasswordContent(
        email = emailFieldValue,
        sending = state.submitting,
        sent = state.sent,
        errorMessage = errorPresentation.localizedMessage() ?: state.errorMessage,
        emailError = state.emailFormatError.localizedMessage(),
        onEmailChange = { nextValue ->
            applyAuthTextFieldValue(
                nextValue = nextValue,
                updateOwner = { emailFieldValue = it },
                projectText = {
                    errorPresentation = null
                    state = reducer.reduce(state, ForgotPasswordFormEvent.EmailChanged(it)).state
                },
            )
        },
        onSubmit = {
            errorPresentation = null
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
        onBackToSignIn = { pager.closeAuthSubPage() },
        onOpenDiagnostics = { pager.openAuthPage(AUTH_ABOUT_PAGE) }
    )
}

private enum class AuthAboutDestination {
    About,
    ApiEnvironment,
    Diagnostics
}

@Composable
private fun AuthAboutScreen(initialDestination: AuthAboutDestination = AuthAboutDestination.About) {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val linkNavigator =
        rememberSlockLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = SlockInAppBrowserRouteScope.Unscoped,
        )
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeSlockAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext()
            )
        }
    val copiedMessage = stringResource(MR.strings.common_copied)
    val settingsCapabilities =
        remember(bridge, bridgeCallScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    val settingsDataSource =
        remember(sharedStores, settingsCapabilities, avatarNativeCache) {
            SharedSettingsDataSource(
                sessionStore = sharedStores.authStore,
                apiEnvironmentStore = sharedStores.apiEnvironmentStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("compose_auth_developer $message") },
                authRuntimeCapabilityStore = sharedStores.authRuntimeCapabilityStore
            ).withAvatarCacheRuntime(
                runtime = sharedStores.avatarCacheRuntime,
                nativeCache = avatarNativeCache
            )
        }
    var aboutState by remember { mutableStateOf<ScreenLoadState<SharedSettingsAboutSnapshot>>(ScreenLoadState.Loading) }
    var developerState by remember { mutableStateOf<ScreenLoadState<SharedSettingsDeveloperSnapshot>>(ScreenLoadState.Loading) }
    var diagnosticsState by remember { mutableStateOf<ScreenLoadState<build.raft.app.data.SharedSettingsDiagnosticsSnapshot>>(ScreenLoadState.Loading) }
    var actionState by remember { mutableStateOf<SharedSettingsActionState?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var destination by remember { mutableStateOf(initialDestination) }
    var pendingEnvironment by remember { mutableStateOf<SharedSettingsApiEnvironmentRequest?>(null) }

    LaunchedEffect(settingsDataSource, reloadToken) {
        aboutState =
            when (val result = settingsDataSource.loadAbout()) {
                is DataResult.Content -> ScreenLoadState.Content(result.data)
                is DataResult.Empty -> ScreenLoadState.Empty(result.reason)
                is DataResult.Error -> ScreenLoadState.Error(result.error)
                is DataResult.NoPermission -> ScreenLoadState.NoPermission(result.blocker)
            }
        developerState =
            when (val result = settingsDataSource.loadDeveloper()) {
                is DataResult.Content -> ScreenLoadState.Content(result.data)
                is DataResult.Empty -> ScreenLoadState.Empty(result.reason)
                is DataResult.Error -> ScreenLoadState.Error(result.error)
                is DataResult.NoPermission -> ScreenLoadState.NoPermission(result.blocker)
            }
        diagnosticsState =
            when (val result = settingsDataSource.loadDiagnostics()) {
                is DataResult.Content -> ScreenLoadState.Content(result.data)
                is DataResult.Empty -> ScreenLoadState.Empty(result.reason)
                is DataResult.Error -> ScreenLoadState.Error(result.error)
                is DataResult.NoPermission -> ScreenLoadState.NoPermission(result.blocker)
            }
    }

    LaunchedEffect(pendingEnvironment) {
        val request = pendingEnvironment ?: return@LaunchedEffect
        actionState = SharedSettingsActionState(DEVELOPER_ACTION_API_ENVIRONMENT, request.key, inProgress = true)
        developerState =
            when (val result = settingsDataSource.saveApiEnvironment(request)) {
                is DataResult.Content -> ScreenLoadState.Content(result.data)
                is DataResult.Empty -> ScreenLoadState.Empty(result.reason)
                is DataResult.Error -> ScreenLoadState.Error(result.error)
                is DataResult.NoPermission -> ScreenLoadState.NoPermission(result.blocker)
            }
        actionState = actionState?.copy(inProgress = false, errorCode = null)
        pendingEnvironment = null
    }

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title =
                when (destination) {
                    AuthAboutDestination.About -> stringResource(MR.strings.settings_about_title)
                    AuthAboutDestination.ApiEnvironment -> stringResource(MR.strings.settings_developer_api_environment)
                    AuthAboutDestination.Diagnostics -> stringResource(MR.strings.settings_diagnostics_title)
                },
            subtitle =
                when (destination) {
                    AuthAboutDestination.About -> stringResource(MR.strings.settings_about_subtitle)
                    AuthAboutDestination.ApiEnvironment -> stringResource(MR.strings.settings_developer_environment_detail)
                    AuthAboutDestination.Diagnostics -> stringResource(MR.strings.settings_developer_diagnostics_detail)
                },
            iconName =
                when (destination) {
                    AuthAboutDestination.About -> SlockLucideIconName.BadgeInfo
                    AuthAboutDestination.ApiEnvironment -> SlockLucideIconName.Server
                    AuthAboutDestination.Diagnostics -> SlockLucideIconName.ClipboardList
                },
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = {
                        if (destination != AuthAboutDestination.About && initialDestination == AuthAboutDestination.About) {
                            destination = AuthAboutDestination.About
                        } else {
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        val sourceLicensesSignInMessage = stringResource(MR.strings.auth_notice_source_licenses_sign_in)
        val reportSubmittedPrefix = stringResource(MR.strings.auth_notice_report_submitted_prefix)
        val reportFailedDiagnosticsCopiedMessage = stringResource(MR.strings.auth_notice_report_failed_diagnostics_copied)
        when (destination) {
            AuthAboutDestination.About ->
                SharedSettingsAboutContent(
                    state = aboutState,
                    fallback = SharedSettingsAboutSnapshot(sessionStatusLabel = stringResource(MR.strings.settings_about_state_signed_out)),
                    developerState = developerState,
                    includeDeveloperShortcuts = true,
                    updateCheck = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.updateCheck,
                    actionState = actionState,
                    onCopyBuild = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    onCopyValue = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    webUrl = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.webUrl.orEmpty(),
                    onOpenSlock = { url -> linkNavigator.open(url) },
                    onCheckHandsUpdate = {
                        bridgeCallScope.launch {
                            developerState =
                                when (val result = settingsDataSource.checkHandsUpdate()) {
                                    is DataResult.Content -> ScreenLoadState.Content(result.data)
                                    is DataResult.Empty -> ScreenLoadState.Empty(result.reason)
                                    is DataResult.Error -> ScreenLoadState.Error(result.error)
                                    is DataResult.NoPermission -> ScreenLoadState.NoPermission(result.blocker)
                                }
                        }
                    },
                    onOpenSourceLicenses = { bridge.toast(sourceLicensesSignInMessage) },
                    onCopyEnvironmentInfo = {
                        val about = (aboutState as? ScreenLoadState.Content<SharedSettingsAboutSnapshot>)?.data
                        settingsCapabilities.bridgeCopyToClipboard(
                            listOfNotNull(
                                about?.appName,
                                about?.versionLabel,
                                about?.buildLabel,
                                (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.apiBaseUrl
                            ).filter { it.isNotBlank() }.joinToString("\n")
                        )
                    },
                    onOpenApiEnvironment = { destination = AuthAboutDestination.ApiEnvironment },
                    onOpenDiagnostics = { destination = AuthAboutDestination.Diagnostics },
                    onSendFeedback = {
                        bridgeCallScope.launch {
                            settingsCapabilities.sendFeedback()
                        }
                    }
                )
            AuthAboutDestination.ApiEnvironment ->
                SharedSettingsApiEnvironmentContent(
                    state = developerState,
                    actionState = actionState,
                    onSaveApiEnvironment = { request -> pendingEnvironment = request }
                )
            AuthAboutDestination.Diagnostics ->
                SharedSettingsDiagnosticsContent(
                    state = diagnosticsState,
                    actionState = actionState,
                    onCopyLogs = {
                        bridgeCallScope.launch { settingsCapabilities.copyDiagnostics() }
                    },
                    onShareLogs = {
                        bridgeCallScope.launch { settingsCapabilities.shareDiagnostics() }
                    },
                    onSetDiagnosticFeatureFlagOverride = { key, mode ->
                        bridgeCallScope.launch {
                            diagnosticsState =
                                settingsDataSource.setDiagnosticFeatureFlagOverride(key, mode)
                                    .toDiagnosticsScreenLoadState()
                        }
                    },
                    onSetAppleSignInLocalOverride = { enabled ->
                        bridgeCallScope.launch {
                            diagnosticsState =
                                settingsDataSource.setAppleSignInLocalOverride(enabled)
                                    .toDiagnosticsScreenLoadState()
                        }
                    },
                    onCopyValue = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    onSendFeedback = {
                        bridgeCallScope.launch {
                            val about = (aboutState as? ScreenLoadState.Content<SharedSettingsAboutSnapshot>)?.data
                            when (
                                val result = settingsCapabilities.submitHandsFeedback(
                                    message = buildSignedOutAuthDiagnosticsFeedbackMessage(about),
                                    attachments = emptyList()
                                )
                            ) {
                                is DataResult.Content -> bridge.toast(reportSubmittedPrefix + result.data.ticketId)
                                else -> {
                                    settingsCapabilities.copyDiagnostics()
                                    settingsCapabilities.shareDiagnostics()
                                    bridge.toast(reportFailedDiagnosticsCopiedMessage)
                                }
                            }
                        }
                    }
                )
        }
    }
}

private fun DataResult<build.raft.app.data.SharedSettingsDiagnosticsSnapshot>.toDiagnosticsScreenLoadState(): ScreenLoadState<build.raft.app.data.SharedSettingsDiagnosticsSnapshot> =
    when (this) {
        is DataResult.Content -> ScreenLoadState.Content(data)
        is DataResult.Empty -> ScreenLoadState.Empty(reason)
        is DataResult.Error -> ScreenLoadState.Error(error)
        is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
    }

internal fun buildAuthDiagnosticsExportText(
    about: SharedSettingsAboutSnapshot?,
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

internal fun buildSignedOutAuthDiagnosticsFeedbackMessage(
    about: SharedSettingsAboutSnapshot?
): String =
    redactAuthDiagnosticsText(
        buildString {
            appendLine("Signed-out auth diagnostics report")
            appendLine()
            appendLine("Submitted from the signed-out Diagnostics screen.")
            appendDiagnosticsLine("Version", about?.versionLabel)
            appendDiagnosticsLine("Build", about?.buildLabel)
            appendDiagnosticsLine("Source", about?.sourceLabel)
            appendLine("The attached diagnostics archive contains auth critical records when available.")
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
internal fun AuthLoginInputsVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    // visualFixture "signing" renders the in-flight sign-in state: filled
    // fields that stay white/enabled (task #372) plus the submit button as
    // "Signing in…" at the 50%-alpha disabled look, with the google+github
    // social section pinned to match the react case's /auth/providers stub.
    // Any other value keeps the pre-existing default render used by the
    // empty/filled/invalid captures.
    val signing =
        pager.pageData.params.optString(AUTH_VISUAL_FIXTURE_KEY) == AUTH_LOGIN_VISUAL_FIXTURE_SIGNING
    val invalid =
        pager.pageData.params.optString(AUTH_VISUAL_FIXTURE_KEY) == AUTH_LOGIN_VISUAL_FIXTURE_INVALID
    val email =
        when {
            invalid -> "invalid-email"
            signing -> AUTH_LOGIN_VISUAL_SIGNING_EMAIL
            else -> "artin@slock.test"
        }
    val emailError =
        if (invalid) {
            LoginFormReducer()
                .reduce(LoginFormState(email = email, password = "password"), LoginFormEvent.Submit)
                .state
                .emailFormatError
                .localizedMessage()
        } else {
            null
        }
    LoginContent(
        email = TextFieldValue(email),
        password = TextFieldValue(if (signing) AUTH_LOGIN_VISUAL_SIGNING_PASSWORD else "password"),
        submitting = signing,
        errorMessage = null,
        emailError = emailError,
        socialProviders = if (signing) setOf(AuthProvider.Google, AuthProvider.GitHub) else emptySet(),
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onForgotPassword = {},
        onCreateAccount = {}
    )
}

@Composable
internal fun AuthRegisterInputsVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    RegisterContent(
        email = TextFieldValue(params.optString(AUTH_REGISTER_VISUAL_EMAIL_KEY).ifBlank { AUTH_REGISTER_VISUAL_DEFAULT_EMAIL }),
        password = TextFieldValue(params.optString(AUTH_REGISTER_VISUAL_PASSWORD_KEY).ifBlank { AUTH_REGISTER_VISUAL_DEFAULT_PASSWORD }),
        submitting = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onBackToSignIn = {}
    )
}

@Composable
internal fun AuthProfileSetupVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    AuthProfileSetupContent(
        handle = TextFieldValue(
            params.optString(AUTH_PROFILE_SETUP_VISUAL_HANDLE_KEY)
                .ifBlank { AUTH_PROFILE_SETUP_VISUAL_DEFAULT_HANDLE }
        ),
        displayName = TextFieldValue(
            params.optString(AUTH_PROFILE_SETUP_VISUAL_DISPLAY_NAME_KEY)
                .ifBlank { AUTH_PROFILE_SETUP_VISUAL_DEFAULT_DISPLAY_NAME }
        ),
        submitting = false,
        errorMessage = null,
        onHandleChange = {},
        onDisplayNameChange = {},
        onSubmit = {}
    )
}

@Composable
internal fun AuthForgotPasswordSentVisualScreen() {
    ForgotPasswordContent(
        email = TextFieldValue("visual.reset@raft.build"),
        sending = false,
        sent = true,
        errorMessage = null,
        onEmailChange = {},
        onSubmit = {},
        onBackToSignIn = {}
    )
}

@Composable
internal fun LoginContent(
    email: TextFieldValue,
    password: TextFieldValue,
    submitting: Boolean,
    errorMessage: String?,
    emailError: String? = null,
    passwordError: String? = null,
    socialProviders: Set<AuthProvider> = emptySet(),
    socialSubmittingProvider: AuthProvider? = null,
    onEmailChange: (TextFieldValue) -> Unit,
    onPasswordChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onSubmitValues: (String, String) -> Unit = { _, _ -> onSubmit() },
    onSocialSignIn: (AuthProvider) -> Unit = {},
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onOpenTerms: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val visibleSocialProviders = remember(socialProviders) { socialProviders.visibleMobileSocialAuthProviders() }
    val formBusy = submitting || socialSubmittingProvider != null
    val canSubmit = canSubmitLoginForm(email.text, password.text, formBusy, emailError)
    val submitCurrentDraft = {
        when (val decision = resolveLoginDraftSubmit(email.text, password.text, formBusy)) {
            is LoginDraftSubmitDecision.Forward ->
                onSubmitValues(decision.email, decision.password)
            LoginDraftSubmitDecision.IgnoreBusy -> Unit
        }
    }
    AuthScaffold(
        onDiagnosticsShortcut = onOpenDiagnostics,
        contentScrollable = true
    ) {
        AuthCenterMark()
        Text(
            text = stringResource(MR.strings.auth_title_sign_in),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            // React parity (LoginPage.tsx): the inputs are never disabled while a
            // sign-in request is in flight — they stay white/black-bordered and
            // editable; only the submit button dims. Gating them on !formBusy
            // grayed the whole form during signing (task #372). Double-submit
            // stays impossible: the button disables itself and
            // resolveLoginDraftSubmit ignores submits while formBusy.
            AuthTextField(
                value = email,
                onValueChange = onEmailChange,
                // React parity: LoginPage.tsx labels are plain "Email"/"Password"
                // (no pink required asterisk).
                label = stringResource(MR.strings.auth_label_email),
                modifier =
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isShiftPressed) {
                            passwordFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                focusRequester = emailFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
            )
            AuthTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = stringResource(MR.strings.auth_label_password),
                modifier =
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && event.isShiftPressed) {
                            emailFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
                isError = !passwordError.isNullOrBlank(),
                errorText = passwordError,
                focusRequester = passwordFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitCurrentDraft() })
            )
            AuthError(errorMessage)
            // React parity (LoginPage.tsx): Sign In stays full pink + black text
            // even with an empty form (validation happens on submit) and only
            // dims to the 50%-alpha look while busy — gating enabled on
            // canSubmit sent the button into the generic disabled palette
            // (#FFD8E5 + white text = unreadable white-on-white, task #372).
            AuthActionButton(
                text =
                    if (submitting) {
                        stringResource(MR.strings.auth_state_signing_in)
                    } else {
                        stringResource(MR.strings.auth_action_sign_in)
                    },
                onClick = submitCurrentDraft,
                tone = AuthButtonTone.Pink,
                enabled = !formBusy,
                loading = submitting,
                preserveDisabledVisual = true
            )
            if (visibleSocialProviders.isNotEmpty()) {
                AuthDivider()
                // React parity (LoginPage.tsx SocialProviderButton): social buttons
                // are never disabled by an in-flight email/password sign-in — react
                // keeps them full-opacity and clickable (tapping one abandons the
                // pending login; onSocialSignIn resets submitting here too). They
                // only dim during Android's own in-app "Opening …" social flow,
                // a state react never renders because it redirects instead.
                val socialEnabled = socialSubmittingProvider == null
                if (AuthProvider.Google in visibleSocialProviders) {
                    AuthActionButton(
                        text =
                            if (socialSubmittingProvider == AuthProvider.Google) {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_opening_provider,
                                    listOf(AuthProvider.Google.providerDisplayName())
                                ).localized()
                            } else {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_continue_with_provider,
                                    listOf(AuthProvider.Google.providerDisplayName())
                                ).localized()
                            },
                        onClick = { onSocialSignIn(AuthProvider.Google) },
                        tone = AuthButtonTone.White,
                        enabled = socialEnabled,
                        loading = socialSubmittingProvider == AuthProvider.Google,
                        preserveDisabledVisual = true,
                        leadingContent = { AuthProviderIcon(AuthProvider.Google) }
                    )
                }
                if (AuthProvider.GitHub in visibleSocialProviders) {
                    AuthActionButton(
                        text =
                            if (socialSubmittingProvider == AuthProvider.GitHub) {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_opening_provider,
                                    listOf(AuthProvider.GitHub.providerDisplayName())
                                ).localized()
                            } else {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_continue_with_provider,
                                    listOf(AuthProvider.GitHub.providerDisplayName())
                                ).localized()
                            },
                        onClick = { onSocialSignIn(AuthProvider.GitHub) },
                        tone = AuthButtonTone.Black,
                        enabled = socialEnabled,
                        loading = socialSubmittingProvider == AuthProvider.GitHub,
                        preserveDisabledVisual = true,
                        leadingContent = { AuthProviderIcon(AuthProvider.GitHub) }
                    )
                }
                if (AuthProvider.Apple in visibleSocialProviders) {
                    AuthActionButton(
                        text =
                            if (socialSubmittingProvider == AuthProvider.Apple) {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_opening_provider,
                                    listOf(AuthProvider.Apple.providerDisplayName())
                                ).localized()
                            } else {
                                StringDesc.ResourceFormatted(
                                    MR.strings.auth_action_continue_with_provider,
                                    listOf(AuthProvider.Apple.providerDisplayName())
                                ).localized()
                            },
                        onClick = { onSocialSignIn(AuthProvider.Apple) },
                        tone = AuthButtonTone.Black,
                        enabled = socialEnabled,
                        loading = socialSubmittingProvider == AuthProvider.Apple,
                        preserveDisabledVisual = true,
                        leadingContent = { AuthProviderIcon(AuthProvider.Apple) }
                    )
                }
            }
            AuthLegalNotice(
                onOpenTerms = onOpenTerms,
                onOpenPrivacy = onOpenPrivacy
            )
            AuthTextLink(stringResource(MR.strings.auth_link_forgot_password)) { onForgotPassword() }
            AuthInlineAction(
                stringResource(MR.strings.auth_prompt_no_account),
                stringResource(MR.strings.auth_action_create_one)
            ) { onCreateAccount() }
        }
    }
}

@Composable
internal fun RegisterContent(
    email: TextFieldValue,
    password: TextFieldValue,
    submitting: Boolean,
    errorMessage: String?,
    emailError: String? = null,
    passwordError: String? = null,
    onEmailChange: (TextFieldValue) -> Unit,
    onPasswordChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    var termsAccepted by remember { mutableStateOf(false) }
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val canSubmit =
        canSubmitRegisterForm(
            email = email.text,
            password = password.text,
            termsAccepted = termsAccepted,
            formBusy = submitting,
            emailFormatError = emailError,
            passwordError = passwordError
        )
    val submitIfAllowed = {
        if (canSubmit) {
            onSubmit()
        }
    }
    AuthScaffold(
        onDiagnosticsShortcut = onOpenDiagnostics,
        contentScrollable = true
    ) {
        AuthCenterMark()
        Text(
            text = stringResource(MR.strings.auth_title_create_account),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            // React parity (RegisterPage.tsx): the inputs are never disabled while
            // the create-account request is in flight — only the legal checkbox
            // and submit button get disabled={loading} (task #372).
            AuthTextField(
                email,
                onEmailChange,
                stringResource(MR.strings.auth_label_email),
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                focusRequester = emailFocusRequester,
                forceFocused = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
            )
            AuthTextField(
                password,
                onPasswordChange,
                stringResource(MR.strings.auth_label_password),
                placeholder = stringResource(MR.strings.auth_password_min_length),
                isError = !passwordError.isNullOrBlank(),
                errorText = passwordError,
                focusRequester = passwordFocusRequester,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitIfAllowed() })
            )
            AuthTermsAgreement(termsAccepted, { termsAccepted = it })
            AuthError(errorMessage)
            AuthActionButton(
                text =
                    if (submitting) {
                        stringResource(MR.strings.auth_state_creating_account)
                    } else {
                        stringResource(MR.strings.auth_action_create_account)
                    },
                onClick = submitIfAllowed,
                tone = AuthButtonTone.Pink,
                enabled = canSubmit,
                loading = submitting,
                // Disabled renders as the react disabled:opacity-50 look (50%-alpha
                // pink + black) instead of the white-on-white generic palette.
                preserveDisabledVisual = true,
                variant = AuthActionButtonVariant.Large
            )
            AuthInlineAction(
                stringResource(MR.strings.auth_prompt_already_have_account),
                stringResource(MR.strings.auth_action_sign_in)
            ) { onBackToSignIn() }
        }
    }
}

@Composable
internal fun AuthProfileSetupContent(
    handle: TextFieldValue,
    displayName: TextFieldValue,
    submitting: Boolean,
    errorMessage: String?,
    handleError: String? = null,
    displayNameError: String? = null,
    onHandleChange: (TextFieldValue) -> Unit,
    onDisplayNameChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val displayNameFocusRequester = remember { FocusRequester() }
    val canSubmit =
        identityHandleValidationError(handle.text) == null &&
            identityDisplayNameValidationError(displayName.text) == null &&
            handleError == null &&
            displayNameError == null &&
            !submitting
    val submitIfAllowed = { if (canSubmit) onSubmit() }

    AuthScaffold(onDiagnosticsShortcut = onOpenDiagnostics, contentScrollable = true) {
        AuthCenterMark()
        Text(
            text = stringResource(MR.strings.auth_title_profile_setup),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        Text(
            text = stringResource(MR.strings.auth_hint_profile_setup),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                handle,
                onHandleChange,
                stringResource(MR.strings.auth_label_username),
                placeholder = stringResource(MR.strings.auth_username_placeholder),
                isError = !handleError.isNullOrBlank(),
                errorText = handleError,
                forceFocused = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { displayNameFocusRequester.requestFocus() })
            )
            Text(
                text = stringResource(MR.strings.auth_hint_username),
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.authPageStaticTextFocus()
            )
            AuthTextField(
                displayName,
                onDisplayNameChange,
                stringResource(MR.strings.auth_label_display_name),
                placeholder = stringResource(MR.strings.auth_display_name_placeholder),
                isError = !displayNameError.isNullOrBlank(),
                errorText = displayNameError,
                focusRequester = displayNameFocusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitIfAllowed() })
            )
            Text(
                text = stringResource(MR.strings.auth_hint_display_name),
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.authPageStaticTextFocus()
            )
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) {
                    stringResource(MR.strings.auth_state_finishing_profile)
                } else {
                    stringResource(MR.strings.auth_action_continue)
                },
                onClick = submitIfAllowed,
                tone = AuthButtonTone.Pink,
                enabled = canSubmit,
                loading = submitting
            )
        }
    }
}

@Composable
internal fun ForgotPasswordContent(
    email: TextFieldValue,
    sending: Boolean,
    sent: Boolean,
    errorMessage: String?,
    emailError: String? = null,
    onEmailChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    AuthScaffold(
        onDiagnosticsShortcut = onOpenDiagnostics,
        contentScrollable = true
    ) {
        AuthCenterMark()
        Text(
            text = stringResource(MR.strings.auth_title_reset_password),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        Text(
            text = stringResource(MR.strings.auth_hint_reset_password),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                value = email,
                onValueChange = onEmailChange,
                label = stringResource(MR.strings.auth_label_email),
                // React parity (ForgotPasswordPage.tsx): the email input stays
                // enabled while the reset request is in flight (task #372).
                // After `sent`, react swaps to a page without the input at all,
                // so the grayed disabled field remains mobile's stand-in.
                enabled = !sent,
                isError = !emailError.isNullOrBlank(),
                errorText = emailError,
                forceFocused = !sent,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
            AuthError(errorMessage)
            if (sent) {
                val resetEmail = email.text.ifBlank { stringResource(MR.strings.auth_fallback_your_email) }
                AuthSuccessNotice(
                    title = stringResource(MR.strings.auth_check_email_title),
                    detail =
                        StringDesc.ResourceFormatted(
                            MR.strings.auth_notice_password_reset_sent,
                            listOf(resetEmail)
                        ).localized()
                )
            }
            AuthActionButton(
                text =
                    if (sending) {
                        stringResource(MR.strings.auth_state_sending)
                    } else {
                        stringResource(MR.strings.auth_action_send_reset_link)
                    },
                onClick = onSubmit,
                tone = AuthButtonTone.Pink,
                enabled = email.text.isNotBlank() && emailError == null && !sent,
                loading = sending,
                preserveDisabledVisual = true
            )
            AuthTextLink(stringResource(MR.strings.auth_link_back_to_sign_in)) { onBackToSignIn() }
        }
    }
}

@Composable
private fun AuthLegalAcceptanceContent(
    providerName: String,
    accepted: Boolean,
    submitting: Boolean,
    errorMessage: String?,
    canSubmit: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBackToSignIn: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    AuthScaffold(contentScrollable = true) {
        AuthCenterMark()
        Text(
            text =
                StringDesc.ResourceFormatted(
                    MR.strings.auth_title_provider_sign_in,
                    listOf(providerName)
                ).localized(),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        Text(
            text = stringResource(MR.strings.auth_hint_legal_acceptance),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTermsAgreement(accepted, onAcceptedChange)
            AuthLegalNotice(onOpenTerms = onOpenTerms, onOpenPrivacy = onOpenPrivacy)
            AuthError(errorMessage)
            AuthActionButton(
                text =
                    if (submitting) {
                        stringResource(MR.strings.auth_state_continuing)
                    } else {
                        stringResource(MR.strings.auth_action_continue)
                    },
                onClick = onContinue,
                tone = AuthButtonTone.Pink,
                enabled = canSubmit && accepted && !submitting,
                loading = submitting,
                preserveDisabledVisual = true,
                variant = AuthActionButtonVariant.Large
            )
            AuthTextLink(stringResource(MR.strings.auth_link_back_to_sign_in)) { onBackToSignIn() }
        }
    }
}

@Composable
private fun AuthLegalNotice(
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val linkStyles = TextLinkStyles(style = AuthLegalNoticeLinkStyle.toSpanStyle())
    val prefix = stringResource(MR.strings.auth_legal_notice_prefix)
    val terms = stringResource(MR.strings.auth_link_terms_of_service)
    val connector = stringResource(MR.strings.auth_legal_notice_connector)
    val privacy = stringResource(MR.strings.auth_link_privacy_policy)
    val suffix = stringResource(MR.strings.auth_legal_notice_suffix)
    val text =
        buildAnnotatedString {
            append(prefix)
            withLink(
                LinkAnnotation.Clickable(
                    tag = "auth-terms",
                    styles = linkStyles,
                    linkInteractionListener = LinkInteractionListener { onOpenTerms() }
                )
            ) {
                append(terms)
            }
            append(connector)
            withLink(
                LinkAnnotation.Clickable(
                    tag = "auth-privacy",
                    styles = linkStyles,
                    linkInteractionListener = LinkInteractionListener { onOpenPrivacy() }
                )
            ) {
                append(privacy)
            }
            append(suffix)
        }
    Text(
        text = text,
        style = AuthLegalNoticeTextStyle,
        color = AuthLegalNoticeTextColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().authPageStaticTextFocus()
    )
}

@Composable
private fun AuthTermsAgreement(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val agreementText =
        authTermsAgreementText(
            prefix = stringResource(MR.strings.auth_terms_agreement_prefix),
            terms = stringResource(MR.strings.auth_link_terms_of_service),
            connector = stringResource(MR.strings.auth_terms_agreement_connector),
            acknowledgement = stringResource(MR.strings.auth_terms_agreement_acknowledgement),
            privacy = stringResource(MR.strings.auth_link_privacy_policy),
            suffix = stringResource(MR.strings.auth_terms_agreement_suffix),
        )
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
        Text(
            text = agreementText,
            style = AuthTermsTextStyle,
            color = AuthTermsTextColor,
            modifier = Modifier.authPageStaticTextFocus()
        )
    }
}

internal fun authTermsAgreementText(
    prefix: String,
    terms: String,
    connector: String,
    acknowledgement: String,
    privacy: String,
    suffix: String,
): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        withStyle(AuthTermsLinkStyle.toSpanStyle()) { append(terms) }
        append(connector)
        append(acknowledgement)
        withStyle(AuthTermsLinkStyle.toSpanStyle()) { append(privacy) }
        append(suffix)
    }

private val AuthLegalNoticeTextColor = SlockColor.TextBlack.copy(alpha = 0.60f)
private val AuthLegalNoticeTextStyle = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = AuthLegalNoticeTextColor)
private val AuthLegalNoticeLinkStyle = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack, textDecoration = TextDecoration.Underline)
private val AuthTermsTextColor = SlockColor.TextBlack.copy(alpha = 0.70f)
private val AuthTermsTextStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = AuthTermsTextColor)
private val AuthTermsLinkStyle = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack, textDecoration = TextDecoration.Underline)

@Composable
private fun LoginContentPreview() {
    LoginContent(
        email = TextFieldValue("artin@raft.build"),
        password = TextFieldValue("password"),
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
        email = TextFieldValue("new@raft.build"),
        password = TextFieldValue("password"),
        submitting = false,
        errorMessage = null,
        onEmailChange = {},
        onPasswordChange = {},
        onSubmit = {},
        onBackToSignIn = {}
    )
}

@Composable
private fun ForgotPasswordSuccessPreview() {
    ForgotPasswordContent(
        email = TextFieldValue("very.long.email.address+team@raft.build"),
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
        authPageData(
            pageName = pageName,
            replaceCurrent = pageName == SLOCK_APP_SHELL_PAGE
        )
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}

internal fun Pager.openPostAuthRoute(session: StoredAuthSession) {
    val destination = postAuthRouteDestination(session)
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            destination.pageName,
            destination.pageData
        )
}

internal fun Pager.openAuthProfileSetupReplacingCurrentPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        AUTH_PROFILE_SETUP_PAGE,
        authPageData(pageName = AUTH_PROFILE_SETUP_PAGE, replaceCurrent = true)
    )
}

internal fun postAuthRouteDestination(session: StoredAuthSession? = null): AuthRouteDestination =
    if (session != null && (session.user.emailVerified == false || session.user.requiresProfileSetup)) {
        AuthRouteDestination(
            pageName = SLOCK_APP_SHELL_PAGE,
            pageData = appShellPageData(replaceCurrent = true, suppressRestoreLanding = true)
        )
    } else if (!session?.selectedServerId.isNullOrBlank()) {
        AuthRouteDestination(
            pageName = SLOCK_APP_SHELL_PAGE,
            pageData = appShellPageData(replaceCurrent = true, suppressRestoreLanding = true)
        )
    } else {
        AuthRouteDestination(
            pageName = AUTH_CHOOSE_SERVER_PAGE,
            // The chooser is the first mounted shell-owned page after a fresh login. Keep its
            // auth/selection authority on the same default shell scope that AppShell will mount
            // after the user picks a server; presentation remains the signed-out/fresh-auth
            // variant (so this intentionally does not set fromShell).
            pageData =
                authChooseServerPageData(
                    replaceCurrent = true,
                    storeScope = SlockShellStoreScope.Legacy
                )
        )
    }

internal data class AuthRouteDestination(
    val pageName: String,
    val pageData: JSONObject
)

private suspend fun SharedAuthDataSource.postAuthSessionReadback(
    expected: StoredAuthSession
): PostAuthSessionReadback {
    val readback =
        runCatchingPreservingCancellation { currentSession() }
            .getOrElse {
                logDiagnostics("auth_post_success_readback_result result=failure stage=session_readback")
                return PostAuthSessionReadback.Missing
            }
    val ready =
        readback is SharedAuthResult.Success &&
            readback.data.isSamePostAuthSession(expected)
    logDiagnostics(
        "auth_post_success_readback_result result=${if (ready) "success" else "missing"} " +
            "hasSession=${readback is SharedAuthResult.Success}"
    )
    return if (ready) PostAuthSessionReadback.Ready else PostAuthSessionReadback.Missing
}

internal fun StoredAuthSession.isSamePostAuthSession(
    expected: StoredAuthSession
): Boolean =
    user.id == expected.user.id &&
        accessToken == expected.accessToken &&
        refreshToken == expected.refreshToken

private enum class PostAuthSessionReadback {
    Ready,
    Missing
}

internal fun loginPageData(
    replaceCurrent: Boolean = false,
    message: String? = null
): JSONObject =
    authPageData(
        pageName = LOGIN_PAGE,
        replaceCurrent = replaceCurrent,
        message = message
    )

internal fun authPageData(
    pageName: String,
    replaceCurrent: Boolean = false,
    message: String? = null
): JSONObject =
    JSONObject().apply {
        put("pageName", pageName)
        message
            ?.takeIf { it.isNotBlank() }
            ?.let { put(AUTH_MESSAGE_KEY, it) }
        if (replaceCurrent) {
            put(AUTH_REPLACE_CURRENT_PAGE, true)
        }
    }

internal fun JSONObject.authRouteMessage(): String? =
    optString(AUTH_MESSAGE_KEY).takeIf { it.isNotBlank() }

internal fun SharedAuthRuntimeCapabilityStore.seedFromAuthPageData(
    pageData: JSONObject
): SharedAuthRuntimeCapabilities {
    seedWebLoginOnly(if (pageData.has("slockWebLoginOnly")) pageData.optBoolean("slockWebLoginOnly", false) else null)
    return seedSocialProviders(
        if (pageData.has(AUTH_SOCIAL_PROVIDERS_KEY)) {
            pageData.optString(AUTH_SOCIAL_PROVIDERS_KEY)
        } else {
            null
        }
    )
}

@Composable
internal fun rememberAuthRuntimeCapabilities(
    store: SharedAuthRuntimeCapabilityStore,
    pageData: JSONObject
): SharedAuthRuntimeCapabilities {
    val pageDataSignature = pageData.toString()
    // Seed synchronously: AppShell can replace itself with Login from its first composition, so a
    // LaunchedEffect write would race that navigation and briefly recreate the original bug.
    return remember(store, pageDataSignature) {
        store.seedFromAuthPageData(pageData)
    }
}

private fun Pager.closeAuthSubPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

internal fun SharedAuthRuntimeCapabilities.socialAuthProviders(
    applePlatformSupported: Boolean
): Set<AuthProvider> {
    val hostProviders = socialProviderIds
        .mapNotNull { raw ->
            when (raw.trim().lowercase()) {
                AuthProvider.Google.providerId() -> AuthProvider.Google
                AuthProvider.GitHub.providerId() -> AuthProvider.GitHub
                AuthProvider.Apple.providerId() -> AuthProvider.Apple
                else -> null
            }
        }
        .toSet()
    // Apple is always available on iOS, including the first cold-start composition.
    // Neither a missing/stale provider probe nor legacy local overrides control it.
    // Native authorization and server credential validation still run when selected.
    return if (applePlatformSupported) hostProviders + AuthProvider.Apple else hostProviders - AuthProvider.Apple
}

private fun AuthProvider.providerId(): String =
    when (this) {
        AuthProvider.Google -> "google"
        AuthProvider.GitHub -> "github"
        AuthProvider.Apple -> "apple"
    }

private fun AuthProvider.providerDisplayName(): String =
    when (this) {
        AuthProvider.Google -> "Google"
        AuthProvider.GitHub -> "GitHub"
        AuthProvider.Apple -> "Apple"
    }

private fun String.mobileOAuthProviderDisplayName(): String =
    when (trim().lowercase()) {
        AuthProvider.Google.providerId() -> AuthProvider.Google.providerDisplayName()
        AuthProvider.GitHub.providerId() -> AuthProvider.GitHub.providerDisplayName()
        AuthProvider.Apple.providerId() -> AuthProvider.Apple.providerDisplayName()
        else -> "Social"
    }

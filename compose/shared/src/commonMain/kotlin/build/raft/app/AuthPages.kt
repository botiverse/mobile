package build.raft.app

import build.raft.app.resources.*
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
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeRaftAvatarNativeCache
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
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape

const val LOGIN_PAGE = "compose_login"
const val AUTH_PROFILE_SETUP_PAGE = "compose_auth_profile_setup"
const val AUTH_ABOUT_PAGE = "compose_auth_developer"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
internal const val AUTH_SOCIAL_PROVIDERS_KEY = "slockSocialAuthProviders"
private const val AUTH_MESSAGE_KEY = "slockAuthMessage"
internal const val AUTH_DESTINATION_KEY = "slockAuthDestination"
internal const val AUTH_DESTINATION_API_ENVIRONMENT = "apiEnvironment"
private const val AUTH_PROFILE_SETUP_VISUAL_HANDLE_KEY = "profileSetupHandle"
private const val AUTH_PROFILE_SETUP_VISUAL_DISPLAY_NAME_KEY = "profileSetupDisplayName"
private const val AUTH_PROFILE_SETUP_VISUAL_DEFAULT_HANDLE = "new_designer"
private const val AUTH_PROFILE_SETUP_VISUAL_DEFAULT_DISPLAY_NAME = "New Designer"
internal const val AUTH_TERMS_URL = "https://raft.build/terms"
internal const val AUTH_PRIVACY_URL = "https://raft.build/privacy"

private fun Modifier.authPageStaticTextFocus(): Modifier =
    focusProperties { canFocus = false }

@Composable
private fun rememberCurrentEnvironmentAuthDataSource(bridge: BridgeModule): SharedAuthDataSource {
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeRaftAvatarNativeCache(
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
            AuthValidationError.UsernameTooShort ->
                StringDesc.ResourceFormatted(
                    Res.string.auth_validation_username_min_length,
                    listOf(AUTH_IDENTITY_NAME_MIN_LENGTH.toString())
                )
            AuthValidationError.UsernameTooLong ->
                StringDesc.ResourceFormatted(
                    Res.string.auth_validation_username_max_length,
                    listOf(AUTH_IDENTITY_NAME_MAX_LENGTH.toString())
                )
            AuthValidationError.UsernameInvalidFormat -> Res.string.auth_validation_username_format.desc()
            AuthValidationError.UsernameReserved -> Res.string.auth_validation_username_reserved.desc()
            AuthValidationError.DisplayNameRequired -> Res.string.auth_validation_display_name_required.desc()
            AuthValidationError.DisplayNameTooLong ->
                StringDesc.ResourceFormatted(
                    Res.string.auth_validation_display_name_max_length,
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

@Page(AUTH_PROFILE_SETUP_PAGE, supportInLocal = true)
internal class ComposeAuthProfileSetupPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthProfileSetupScreen() }
    }
}

/**
 * Where the auth About page opens. The login pages' "Sign in to a private deployment" link (on the email/social
 * login on Android and iOS) passes [AUTH_DESTINATION_API_ENVIRONMENT] to land on
 * the API environment editor; the hidden diagnostics shortcut passes nothing and lands on About.
 */
internal fun authAboutInitialDestination(destination: String?): AuthAboutDestination =
    if (destination == AUTH_DESTINATION_API_ENVIRONMENT) {
        AuthAboutDestination.ApiEnvironment
    } else {
        AuthAboutDestination.About
    }

@Page(AUTH_ABOUT_PAGE, supportInLocal = true)
internal class ComposeAuthAboutPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        val initial = authAboutInitialDestination(pageData.params.optString(AUTH_DESTINATION_KEY))
        setContent { AuthAboutScreen(initialDestination = initial) }
    }
}

@Composable
private fun LoginScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }
    val authDataSource = rememberCurrentEnvironmentAuthDataSource(bridge)
    // Seed host capabilities synchronously and observe subsequent host updates.
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    // Follows the stored environment so a deployment saved from the sheet below is used at once.
    val apiEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val settingsDataSource = rememberAuthSettingsDataSource(pager, bridge, sharedStores)
    var privateDeploymentVisible by remember { mutableStateOf(false) }
    PrivateDeploymentSheet(
        visible = privateDeploymentVisible,
        environmentStore = sharedStores.apiEnvironmentStore,
        settingsDataSource = settingsDataSource,
        onDismiss = { privateDeploymentVisible = false },
    )
    WebLoginScreen(
        pager,
        bridge,
        authDataSource,
        onOpenPrivateDeployment = { privateDeploymentVisible = true },
        webUrl = apiEnvironment.webUrl,
    )
}
private data class PendingProfileSetup(
    val nonce: Int,
    val handle: String,
    val displayName: String
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
                errorPresentation = AuthErrorPresentation.Static(Res.string.auth_error_profile_setup_failed.desc())
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

/**
 * The settings data source of the signed-out pages (About / API environment editor, the private deployment
 * sheet). Its platform capabilities tell the host about an environment change (realtime disconnect, widgets).
 */
@Composable
internal fun rememberAuthSettingsDataSource(
    pager: Pager,
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    linkNavigator: RaftLinkNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
        ),
): SharedSettingsDataSource {
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext()
            )
        }
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, bridgeCallScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    return remember(sharedStores, settingsCapabilities, avatarNativeCache) {
        authSettingsDataSource(bridge, sharedStores, settingsCapabilities, avatarNativeCache)
    }
}

private fun authSettingsDataSource(
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    avatarNativeCache: BridgeRaftAvatarNativeCache,
): SharedSettingsDataSource =
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

internal enum class AuthAboutDestination {
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
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
        )
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext()
            )
        }
    val copiedMessage = stringResource(Res.string.common_copied)
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
            authSettingsDataSource(bridge, sharedStores, settingsCapabilities, avatarNativeCache)
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

    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        RaftTopPageHeader(
            title =
                when (destination) {
                    AuthAboutDestination.About -> stringResource(Res.string.settings_about_title)
                    AuthAboutDestination.ApiEnvironment -> stringResource(Res.string.settings_developer_api_environment)
                    AuthAboutDestination.Diagnostics -> stringResource(Res.string.settings_diagnostics_title)
                },
            subtitle =
                when (destination) {
                    AuthAboutDestination.About -> stringResource(Res.string.settings_about_subtitle)
                    AuthAboutDestination.ApiEnvironment -> stringResource(Res.string.settings_developer_environment_detail)
                    AuthAboutDestination.Diagnostics -> stringResource(Res.string.settings_developer_diagnostics_detail)
                },
            iconName =
                when (destination) {
                    AuthAboutDestination.About -> RaftLucideIconName.BadgeInfo
                    AuthAboutDestination.ApiEnvironment -> RaftLucideIconName.Server
                    AuthAboutDestination.Diagnostics -> RaftLucideIconName.ClipboardList
                },
            onBack = {
                if (destination != AuthAboutDestination.About && initialDestination == AuthAboutDestination.About) {
                    destination = AuthAboutDestination.About
                } else {
                    pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                }
            }
        )
        val sourceLicensesSignInMessage = stringResource(Res.string.auth_notice_source_licenses_sign_in)
        val reportSubmittedPrefix = stringResource(Res.string.auth_notice_report_submitted_prefix)
        val reportFailedDiagnosticsCopiedMessage = stringResource(Res.string.auth_notice_report_failed_diagnostics_copied)
        // task #177: standalone page outside the app shell; the lists reserve the
        // system navigation bar themselves.
        val aboutBottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg
        when (destination) {
            AuthAboutDestination.About ->
                SharedSettingsAboutContent(
                    bottomContentPadding = aboutBottomContentPadding,
                    state = aboutState,
                    fallback = SharedSettingsAboutSnapshot(sessionStatusLabel = stringResource(Res.string.settings_about_state_signed_out)),
                    developerState = developerState,
                    includeDeveloperShortcuts = true,
                    updateCheck = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.updateCheck,
                    actionState = actionState,
                    onCopyBuild = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    onCopyValue = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    webUrl = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.webUrl.orEmpty(),
                    onOpenRaft = { url -> linkNavigator.open(url) },
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
                    bottomContentPadding = aboutBottomContentPadding,
                    state = developerState,
                    actionState = actionState,
                    onSaveApiEnvironment = { request -> pendingEnvironment = request }
                )
            AuthAboutDestination.Diagnostics ->
                SharedSettingsDiagnosticsContent(
                    bottomContentPadding = aboutBottomContentPadding,
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
            text = stringResource(Res.string.auth_title_profile_setup),
            style = RaftType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            modifier = Modifier.authPageStaticTextFocus()
        )
        Text(
            text = stringResource(Res.string.auth_hint_profile_setup),
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            modifier = Modifier.authPageStaticTextFocus()
        )
        AuthFormColumn {
            AuthTextField(
                handle,
                onHandleChange,
                stringResource(Res.string.auth_label_username),
                placeholder = stringResource(Res.string.auth_username_placeholder),
                isError = !handleError.isNullOrBlank(),
                errorText = handleError,
                forceFocused = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { displayNameFocusRequester.requestFocus() })
            )
            Text(
                text = stringResource(Res.string.auth_hint_username),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                modifier = Modifier.authPageStaticTextFocus()
            )
            AuthTextField(
                displayName,
                onDisplayNameChange,
                stringResource(Res.string.auth_label_display_name),
                placeholder = stringResource(Res.string.auth_display_name_placeholder),
                isError = !displayNameError.isNullOrBlank(),
                errorText = displayNameError,
                focusRequester = displayNameFocusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitIfAllowed() })
            )
            Text(
                text = stringResource(Res.string.auth_hint_display_name),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                modifier = Modifier.authPageStaticTextFocus()
            )
            AuthError(errorMessage)
            AuthActionButton(
                text = if (submitting) {
                    stringResource(Res.string.auth_state_finishing_profile)
                } else {
                    stringResource(Res.string.auth_action_continue)
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
internal fun AuthTermsAgreement(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenTerms: (() -> Unit)? = null,
    onOpenPrivacy: (() -> Unit)? = null,
) {
    val agreementText =
        authTermsAgreementText(
            prefix = stringResource(Res.string.auth_terms_agreement_prefix),
            terms = stringResource(Res.string.auth_link_terms_of_service),
            connector = stringResource(Res.string.auth_terms_agreement_connector),
            acknowledgement = stringResource(Res.string.auth_terms_agreement_acknowledgement),
            privacy = stringResource(Res.string.auth_link_privacy_policy),
            suffix = stringResource(Res.string.auth_terms_agreement_suffix),
            onOpenTerms = onOpenTerms,
            onOpenPrivacy = onOpenPrivacy,
        )
    val hasPolicyLinks = onOpenTerms != null || onOpenPrivacy != null
    val toggleConsent = { onCheckedChange(!checked) }
    Row(
        modifier = Modifier.fillMaxWidth().then(
            if (hasPolicyLinks) Modifier else Modifier.clickable(onClick = toggleConsent)
        ),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .then(if (hasPolicyLinks) Modifier.clickable(onClick = toggleConsent) else Modifier)
                .padding(top = 2.dp)
                .size(18.dp)
                // raft-ui Checkbox: sticker radius, 1.5dp ink edge; checked fills with
                // foreground and an inverse check.
                .background(if (checked) RaftColor.TextBlack else RaftColor.Card, RoundedCornerShape(RaftAppRadius.Sticker))
                .border(RaftAppStroke.Control, if (checked) RaftColor.TextBlack else RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Sticker)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(13.dp), color = raftArgbHex(RaftColor.ForegroundInverse))
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
    onOpenTerms: (() -> Unit)? = null,
    onOpenPrivacy: (() -> Unit)? = null,
): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        if (onOpenTerms == null) {
            withStyle(AuthTermsLinkStyle.toSpanStyle()) { append(terms) }
        } else {
            withLink(LinkAnnotation.Clickable(
                tag = "auth-terms",
                styles = TextLinkStyles(style = AuthTermsLinkStyle.toSpanStyle()),
                linkInteractionListener = LinkInteractionListener { onOpenTerms() },
            )) { append(terms) }
        }
        append(authLegalConnectorText(connector))
        append(acknowledgement)
        if (onOpenPrivacy == null) {
            withStyle(AuthTermsLinkStyle.toSpanStyle()) { append(privacy) }
        } else {
            withLink(LinkAnnotation.Clickable(
                tag = "auth-privacy",
                styles = TextLinkStyles(style = AuthTermsLinkStyle.toSpanStyle()),
                linkInteractionListener = LinkInteractionListener { onOpenPrivacy() },
            )) { append(privacy) }
        }
        append(suffix)
    }

// Resource boundary spaces survive platform resource generation, but must not bind
// the last policy word to the following connector and sentence at layout time.
internal fun authLegalConnectorText(connector: String): String = connector.replace('\u00A0', ' ')

private val AuthTermsTextColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.70f)
private val AuthTermsTextStyle: TextStyle
    get() = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = AuthTermsTextColor)
private val AuthTermsLinkStyle: TextStyle
    get() = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack, textDecoration = TextDecoration.Underline)

internal fun Pager.openAuthPage(pageName: String, destination: String? = null) {
    val pageData =
        authPageData(
            pageName = pageName,
            replaceCurrent = pageName == RAFT_APP_SHELL_PAGE
        ).apply {
            destination?.takeIf { it.isNotBlank() }?.let { put(AUTH_DESTINATION_KEY, it) }
        }
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
            pageName = RAFT_APP_SHELL_PAGE,
            pageData = appShellPageData(replaceCurrent = true, suppressRestoreLanding = true)
        )
    } else if (!session?.selectedServerId.isNullOrBlank()) {
        AuthRouteDestination(
            pageName = RAFT_APP_SHELL_PAGE,
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
                    storeScope = RaftShellStoreScope.Legacy
                )
        )
    }

internal data class AuthRouteDestination(
    val pageName: String,
    val pageData: JSONObject
)

internal fun StoredAuthSession.isSamePostAuthSession(
    expected: StoredAuthSession
): Boolean =
    user.id == expected.user.id &&
        accessToken == expected.accessToken &&
        refreshToken == expected.refreshToken

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


package build.raft.app.settings

import build.raft.designsystem.theme.RaftTheme
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.designsystem.components.RaftEmptyState
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.iconName
import build.raft.app.optString
import build.raft.app.raftReleaseNotesAppSlug
import build.raft.app.raftWebOriginOf
import build.raft.app.raftWebViewUrlForLog
import build.raft.app.rememberRaftWebViewController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.AuthenticatedProductPager
import build.raft.app.AuthenticatedProductRoute
import build.raft.app.LocalRaftLinkNavigator
import build.raft.app.RAFT_SHELL_INSTANCE_ID
import build.raft.app.RAFT_SHELL_SERVER_ID
import build.raft.app.RaftBackHandler
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftWebViewController
import build.raft.app.RaftWebViewNavigationState
import build.raft.app.RememberKmmResourceLocaleSeedFromLocalActivity
import kotlinx.coroutines.CancellationException

const val RELEASE_NOTES_PAGE = "compose_release_notes"

@Page(RELEASE_NOTES_PAGE, supportInLocal = true)
internal class ComposeReleaseNotesPage : AuthenticatedProductPager(AuthenticatedProductRoute.ReleaseNotes) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { ReleaseNotesScreen() }
    }
}

// Opened from the Settings "About" section (task #227). The optional version
// code is local app build identity, not product content. Passing it lets the
// WebView start without rebuilding the standalone Settings store; direct/deep
// opens still fall back to loading About locally when the value is absent.
internal fun Pager.openReleaseNotesPage(
    versionCode: String = "",
    storeScope: build.raft.app.data.RaftShellStoreScope,
) {
    acquireModule<build.raft.app.compat.module.RouterModule>(
        build.raft.app.compat.module.RouterModule.MODULE_NAME
    ).openPage(
        RELEASE_NOTES_PAGE,
        JSONObject().apply {
            put("pageName", RELEASE_NOTES_PAGE)
            put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
            releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
                put("versionCode", it)
            }
        }
    )
}

// Release Notes remains a dedicated pager so the WebView owns a full-height
// viewport and a clean lifecycle. The shared WebView scaffold preserves the
// native first-frame header, measured attach, navigation/back, and retry contract.
@Composable
internal fun ReleaseNotesScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val launchVersionCode =
        remember(pager.pageData.params) {
            releaseNotesVersionCodeFromPageData(pager.pageData.params)
        }
    SettingsWebViewPageScaffold(
        pager = pager,
        surface = SettingsWebViewSurface.ReleaseNotes,
        title = stringResource(Res.string.release_notes_title),
        iconName = RaftLucideIconName.FileText,
    ) { controller, onNavigationStateChange ->
        if (launchVersionCode.isNotBlank()) {
            val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
            LaunchedEffect(launchVersionCode) {
                bridge.log("release_notes_open versionSource=launch renderer=webview")
            }
            ReleaseNotesWebViewBody(
                versionCode = launchVersionCode,
                controller = controller,
                handlesPageBack = false,
                onNavigationStateChange = onNavigationStateChange,
            )
        } else {
            ReleaseNotesScreenBody(
                pager = pager,
                controller = controller,
                onNavigationStateChange = onNavigationStateChange,
            )
        }
    }
}

@Composable
private fun ReleaseNotesScreenBody(
    pager: Pager,
    controller: RaftWebViewController,
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
) {
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-release-notes") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("release_notes_open versionSource=about renderer=webview")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)

    when (val resolution = releaseNotesAboutResolution(settingsState.about)) {
        ReleaseNotesAboutResolution.Loading -> ReleaseNotesLoadingCover()
        is ReleaseNotesAboutResolution.Ready ->
            ReleaseNotesWebViewBody(
                versionCode = resolution.versionCode,
                controller = controller,
                handlesPageBack = false,
                onNavigationStateChange = onNavigationStateChange,
            )
        ReleaseNotesAboutResolution.Failure ->
            ReleaseNotesErrorState {
                settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.About))
            }
    }
}

internal sealed interface ReleaseNotesAboutResolution {
    data object Loading : ReleaseNotesAboutResolution

    data class Ready(val versionCode: String) : ReleaseNotesAboutResolution

    data object Failure : ReleaseNotesAboutResolution
}

internal fun releaseNotesAboutResolution(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>
): ReleaseNotesAboutResolution =
    when (state) {
        ScreenLoadState.Loading -> ReleaseNotesAboutResolution.Loading
        is ScreenLoadState.Content ->
            releaseNotesSanitizeVersionCode(state.data.buildLabel)
                .takeIf { it.isNotBlank() }
                ?.let(ReleaseNotesAboutResolution::Ready)
                ?: ReleaseNotesAboutResolution.Failure
        is ScreenLoadState.Empty,
        is ScreenLoadState.Error,
        is ScreenLoadState.NoPermission -> ReleaseNotesAboutResolution.Failure
    }

internal fun releaseNotesVersionCodeFromPageData(params: JSONObject): String =
    releaseNotesSanitizeVersionCode(params.optString("versionCode"))

internal fun releaseNotesSanitizeVersionCode(value: String): String =
    value.trim().takeIf { versionCode -> versionCode.isNotBlank() && versionCode.all(Char::isDigit) }.orEmpty()

/**
 * The public Hands history page is the release-notes source of truth. Keep the
 * URL contract in shared code so Android's document cache and every platform's
 * WebView receive the same platform/version/locale identity.
 */
internal fun sharedSettingsReleaseNotesUrl(versionCode: String): String {
    val localeTag =
        try {
            val locale = KeyValueLocalePreferenceStore(raftSharedPreferenceStore()).readLocaleSync()
            releaseNotesLocaleTag(locale, androidx.compose.ui.text.intl.Locale.current.toLanguageTag())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AppLocale.Default.kmmResourceLocaleTag
        }
    return sharedSettingsReleaseNotesUrlForLocale(versionCode, localeTag)
}

/**
 * The `lang` the notes page is asked for: the app's own language, or with "follow system" the
 * system's, mapped onto the app's languages. The URL has to say it: Android reads the page through
 * the app's document cache, whose request carries no Accept-Language, so a system-language page
 * fell back to English (artin 11-22, Volta found the cause).
 */
internal fun releaseNotesLocaleTag(appLocale: AppLocale, systemLanguageTag: String?): String? =
    if (appLocale == AppLocale.System) {
        releaseNotesSystemAppLocale(systemLanguageTag).kmmResourceLocaleTag
    } else {
        appLocale.kmmResourceLocaleTag
    }

/** The app language a system language tag (`zh-Hans-CN`, `es-ES`, `fr-FR`) maps to; English otherwise. */
internal fun releaseNotesSystemAppLocale(systemLanguageTag: String?): AppLocale {
    val tag = systemLanguageTag?.trim()?.lowercase()?.replace('_', '-').orEmpty()
    if (tag.isEmpty()) return AppLocale.Default
    val parts = tag.split('-')
    if (parts.first() == "zh") {
        val traditional = parts.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }
        return if (traditional) AppLocale.TraditionalChinese else AppLocale.SimplifiedChinese
    }
    val exact = AppLocale.fromStorageKey(tag)
    if (exact != AppLocale.Default && exact != AppLocale.System) return exact
    return AppLocale.fromStorageKey(parts.first()).takeUnless { it == AppLocale.System } ?: AppLocale.Default
}

internal fun sharedSettingsReleaseNotesUrlForLocale(
    versionCode: String,
    localeTag: String?
): String =
    sharedSettingsReleaseNotesUrlForLocaleWithSlug(
        appSlug = raftReleaseNotesAppSlug(),
        versionCode = versionCode,
        localeTag = localeTag,
    )

internal fun sharedSettingsReleaseNotesUrlForLocaleWithSlug(
    appSlug: String,
    versionCode: String,
    localeTag: String?,
): String {
    val exactAppSlug =
        appSlug
            .trim()
            .takeIf { slug ->
                slug.isNotBlank() &&
                    slug.all { character ->
                        character in 'a'..'z' || character in '0'..'9' || character == '-'
                    }
            }
            ?: return ""
    val exactVersionCode = releaseNotesSanitizeVersionCode(versionCode)
    if (exactVersionCode.isBlank()) return ""
    val baseUrl =
        "https://hands.build/notes/$exactAppSlug?version_code=$exactVersionCode"
    val language =
        localeTag
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::releaseNotesNormalizeLanguageTag)
            ?.let(::releaseNotesQueryEncode)
    return language?.let { "$baseUrl&lang=$it" } ?: baseUrl
}

internal fun releaseNotesNormalizeLanguageTag(value: String?): String =
    value
        ?.trim()
        ?.substringBefore('.')
        ?.substringBefore('@')
        ?.replace('_', '-')
        ?.takeIf {
            it.isNotBlank() &&
                !it.equals("C", ignoreCase = true) &&
                !it.equals("POSIX", ignoreCase = true)
        }
        ?: requireNotNull(AppLocale.Default.kmmResourceLocaleTag)

private fun releaseNotesQueryEncode(value: String): String {
    val bytes = value.encodeToByteArray()
    val encoded = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val number = byte.toInt() and 0xff
        val character = number.toChar()
        if (
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '.' ||
                character == '_' ||
                character == '~'
        ) {
            encoded.append(character)
        } else {
            encoded.append('%')
            encoded.append("0123456789ABCDEF"[number ushr 4])
            encoded.append("0123456789ABCDEF"[number and 0x0f])
        }
    }
    return encoded.toString()
}

@Composable
internal fun ReleaseNotesWebViewBody(
    versionCode: String,
    controller: RaftWebViewController = rememberRaftWebViewController(),
    handlesPageBack: Boolean = true,
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit = {},
) {
    if (versionCode.isBlank()) {
        ReleaseNotesLoadingCover()
        return
    }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val linkNavigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    // Match the pre-native WebView contract: an explicit app language is sent
    // as `lang`; system language is left to the browser's Accept-Language.
    val url = remember(versionCode) { sharedSettingsReleaseNotesUrl(versionCode) }
    var navigationState by
        remember(url) {
            mutableStateOf(
                RaftWebViewNavigationState(
                    url = url,
                    isLoading = true,
                )
            )
        }

    fun closePage() {
        pager.acquireModule<build.raft.app.compat.module.RouterModule>(
            build.raft.app.compat.module.RouterModule.MODULE_NAME
        ).closePage()
    }

    RaftBackHandler(
        enabled = handlesPageBack,
        label = "release_notes_webview",
        blocksBackSwipe = false,
        onBack = {
            performSettingsWebViewBack(
                canGoBack = navigationState.canGoBack,
                goBack = controller::goBack,
                closePage = ::closePage,
            )
        },
    )

    SettingsWebViewContent(
        url = url,
        modifier = Modifier.fillMaxSize(),
        diagnosticsName = "release_notes",
        allowedNavigationSchemes = setOf("http", "https"),
        mainFrameOrigin = raftWebOriginOf(url),
        controller = controller,
        onLoadCommit = {
            bridge.log("release_notes_load_commit renderer=webview versionCode=$versionCode")
        },
        onLoadFailure = { reason ->
            bridge.logWarn(
                "release_notes_load_failure renderer=webview versionCode=$versionCode reason=${reason.take(120)}"
            )
        },
        onNavigationBlocked = { blockedUrl ->
            val target = settingsWebViewBlockedNavigationUrlOrNull(blockedUrl)
            if (target != null) {
                bridge.log("release_notes_navigation_blocked action=open url=${raftWebViewUrlForLog(target)}")
                if (linkNavigator != null) linkNavigator.open(target) else uriHandler.openUri(target)
            } else {
                bridge.log(
                    "release_notes_navigation_blocked action=ignored scheme=${settingsWebViewSchemeForLog(blockedUrl)}"
                )
            }
        },
        onNavigationStateChange = { update ->
            navigationState = update.state
            onNavigationStateChange(update)
        },
    )
}

@Composable
internal fun ReleaseNotesLoadingCover() {
    SettingsWebViewLoadingPlaceholder()
}

@Composable
private fun ReleaseNotesErrorState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas),
        contentAlignment = Alignment.Center,
    ) {
        RaftEmptyState(
            title = stringResource(Res.string.release_notes_error_title),
            description = stringResource(Res.string.release_notes_error_body),
            modifier = Modifier.fillMaxSize(),
            icon = { RaftSlotIcon(RaftLucideIconName.TriangleAlert) },
            actions = {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                    size = RaftButtonSize.Lg,
                )
            },
        )
    }
}

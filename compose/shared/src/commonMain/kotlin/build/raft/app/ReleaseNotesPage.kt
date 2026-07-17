package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.time.TimeSource

const val RELEASE_NOTES_PAGE = "compose_release_notes"

@Page(RELEASE_NOTES_PAGE, supportInLocal = true)
internal class ComposeReleaseNotesPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ReleaseNotesScreen() }
    }
}

// Opened from the Settings "About" section (task #227). The optional version
// code is local app build identity, not product content. Passing it lets the
// WebView start without rebuilding the standalone Settings store; direct/deep
// opens still fall back to loading About locally when the value is absent.
internal fun Pager.openReleaseNotesPage(
    versionCode: String = "",
    storeScope: build.raft.app.data.SlockShellStoreScope,
) {
    acquireModule<com.tencent.kuikly.core.module.RouterModule>(
        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
    ).openPage(
        RELEASE_NOTES_PAGE,
        com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
            put("pageName", RELEASE_NOTES_PAGE)
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
            releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
                put("versionCode", it)
            }
        }
    )
}

// task #227: Release Notes is its own single-page pager (not a destination in the
// Settings child-tab pager) so it gets a full-height native viewport and clean
// lifecycle — avoiding the retained multi-tab pager's sizing/attach issues (the
// bottom white strip and header/webview attach-order flashes). The page owns its
// About data via the standalone settings environment, so version_code is resolved
// race-free instead of depending on the shell having loaded About.
@Composable
private fun ReleaseNotesScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val loadingMessage = stringResource(MR.strings.release_notes_loading)
    val launchVersionCode =
        remember(pager.pageData.params) {
            releaseNotesVersionCodeFromPageData(pager.pageData.params)
        }
    // Render the header on the first frame. The body's first composition is heavy
    // (rememberStandaloneSettingsEnvironment builds the settings stores + the
    // WebView native view attaches), which otherwise delays the header a frame and
    // shows the native white page first (task #227 follow-up). Defer the body to a
    // child composable committed after the first frame so the header paints first.
    var bodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bodyReady = true }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.release_notes_title),
            iconName = SlockLucideIconName.FileText,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockTopPageHeaderAction(
                    icon = SlockLucideIconName.ArrowLeft,
                    onClick = {
                        pager.acquireModule<com.tencent.kuikly.core.module.RouterModule>(
                            com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
                        ).closePage()
                    }
                )
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (bodyReady) {
                if (launchVersionCode.isNotBlank()) {
                    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
                    LaunchedEffect(launchVersionCode) {
                        bridge.log("release_notes_open versionSource=launch")
                    }
                    ReleaseNotesWebViewBody(launchVersionCode)
                } else {
                    ReleaseNotesScreenBody(pager)
                }
            } else {
                ReleaseNotesCenteredNotice(loadingMessage)
            }
        }
    }
}

@Composable
private fun ReleaseNotesScreenBody(pager: Pager) {
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-release-notes") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("release_notes_open versionSource=about")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)

    val versionCode =
        (settingsState.about as? ScreenLoadState.Content)?.data?.buildLabel.orEmpty()
    ReleaseNotesWebViewBody(versionCode)
}

internal fun releaseNotesVersionCodeFromPageData(
    params: com.tencent.kuikly.core.nvi.serialization.json.JSONObject
): String =
    releaseNotesSanitizeVersionCode(params.optString("versionCode"))

internal fun releaseNotesSanitizeVersionCode(value: String): String =
    value.trim().takeIf { versionCode -> versionCode.all(Char::isDigit) }.orEmpty()

@Composable
internal fun ReleaseNotesWebViewBody(versionCode: String) {
    val loadingMessage = stringResource(MR.strings.release_notes_loading)
    if (versionCode.isBlank()) {
        ReleaseNotesCenteredNotice(loadingMessage)
        return
    }
    val errorTitle = stringResource(MR.strings.release_notes_error_title)
    val errorBody = stringResource(MR.strings.release_notes_error_body)
    val retryLabel = stringResource(MR.strings.common_retry)
    val url = remember(versionCode) { sharedSettingsReleaseNotesUrl(versionCode) }
    var reloadKey by remember { mutableStateOf(0) }
    var loadError by remember(reloadKey, url) { mutableStateOf<String?>(null) }
    var loaded by remember(reloadKey, url) { mutableStateOf(false) }
    var bodyFrameReady by remember(reloadKey, url) { mutableStateOf(false) }
    var webViewAttachReady by remember(reloadKey, url) { mutableStateOf(false) }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val loadStartedAt = remember(reloadKey, url) { TimeSource.Monotonic.markNow() }
    LaunchedEffect(bodyFrameReady, reloadKey, url) {
        if (!bodyFrameReady) {
            webViewAttachReady = false
            return@LaunchedEffect
        }
        // A cached document can commit before Kuikly applies the custom native
        // view's final body frame. Wait for the measured body and two render
        // frames so Android never attaches the WebView at the root viewport.
        repeat(RELEASE_NOTES_WEB_VIEW_ATTACH_DELAY_FRAMES) {
            withFrameNanos { }
        }
        webViewAttachReady = true
    }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .onGloballyPositioned { coordinates ->
                    bodyFrameReady =
                        releaseNotesWebViewFrameIsReady(
                            positionY = coordinates.positionInRoot().y,
                            width = coordinates.size.width,
                            height = coordinates.size.height
                        )
                }
    ) {
        // The native WebView applies its own bottom safe-area content inset, so it
        // stops short of the screen bottom; extend the frame past the slot so that
        // inset falls off-screen and the page fills edge-to-edge.
        val webViewHeight = maxHeight + slockBottomSafeAreaPadding()
        if (webViewAttachReady) {
            key(reloadKey, url) {
                SlockWebView(
                    url = url,
                    modifier = Modifier.fillMaxWidth().height(webViewHeight),
                    allowNavigation = true,
                    onLoadSuccess = {
                        loadError = null
                        loaded = true
                        bridge.log(
                            "release_notes_load_success versionCode=$versionCode " +
                                "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    },
                    onLoadFailure = { reason ->
                        loadError = reason
                        bridge.log(
                            "release_notes_load_failure versionCode=$versionCode " +
                                "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds} " +
                                "reason=${reason.take(120)}"
                        )
                    }
                )
            }
        }
        // Cover the network-load gap: until the first successful load, the WebView
        // paints bare white, so show a loading notice over it instead (task #227).
        if (!loaded && loadError == null) {
            ReleaseNotesCenteredNotice(loadingMessage)
        }
        if (loadError != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.SurfaceWhite)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(52.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(26.dp))
                }
                Text(errorTitle, style = SlockType.PageTitle.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                Text(errorBody, style = SlockType.Body.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
                SlockBrutalActionButton(
                    label = retryLabel,
                    onClick = { reloadKey += 1 },
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}

private const val RELEASE_NOTES_WEB_VIEW_ATTACH_DELAY_FRAMES = 2

internal fun releaseNotesWebViewFrameIsReady(
    positionY: Float,
    width: Int,
    height: Int
): Boolean = positionY > 0f && width > 0 && height > 0

@Composable
internal fun ReleaseNotesCenteredNotice(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

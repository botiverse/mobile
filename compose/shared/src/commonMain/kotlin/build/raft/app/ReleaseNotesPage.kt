package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
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
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource

const val RELEASE_NOTES_PAGE = "compose_release_notes"

@Page(RELEASE_NOTES_PAGE, supportInLocal = true)
internal class ComposeReleaseNotesPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ReleaseNotesScreen() }
    }
}

// Opened from the Settings "About" section (task #227). No launch payload: the
// page loads its own About snapshot to resolve version_code, so it never depends
// on the shell having About loaded at tap time.
internal fun Pager.openReleaseNotesPage() {
    acquireModule<com.tencent.kuikly.core.module.RouterModule>(
        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
    ).openPage(
        RELEASE_NOTES_PAGE,
        com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
            put("pageName", RELEASE_NOTES_PAGE)
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
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = {
                        pager.acquireModule<com.tencent.kuikly.core.module.RouterModule>(
                            com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
                        ).closePage()
                    },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (bodyReady) {
                ReleaseNotesScreenBody(pager)
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
        bridge.log("release_notes_open")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)

    val versionCode =
        (settingsState.about as? ScreenLoadState.Content)?.data?.buildLabel.orEmpty()
    ReleaseNotesWebViewBody(versionCode)
}

@Composable
private fun ReleaseNotesWebViewBody(versionCode: String) {
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        // The native WebView applies its own bottom safe-area content inset, so it
        // stops short of the screen bottom; extend the frame past the slot so that
        // inset falls off-screen and the page fills edge-to-edge.
        val webViewHeight = maxHeight + slockBottomSafeAreaPadding()
        key(reloadKey, url) {
            SlockWebView(
                url = url,
                modifier = Modifier.fillMaxWidth().height(webViewHeight),
                allowNavigation = true,
                onLoadSuccess = {
                    loadError = null
                    loaded = true
                },
                onLoadFailure = { reason -> loadError = reason }
            )
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

@Composable
private fun ReleaseNotesCenteredNotice(message: String) {
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

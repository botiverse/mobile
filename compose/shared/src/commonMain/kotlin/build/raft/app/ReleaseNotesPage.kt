package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlin.time.TimeSource

// Opened from Settings > About. Version code is optional local shell configuration; when absent,
// the shared host resolves About through the standalone Settings environment before attaching the
// same production WebView renderer used by every other Settings Web surface.
internal fun Pager.openReleaseNotesPage(
    versionCode: String = "",
    storeScope: build.raft.app.data.SlockShellStoreScope,
) {
    openSettingsWebViewPage(
        surface = SettingsWebViewSurface.ReleaseNotes,
        storeScope = storeScope,
        versionCode = versionCode,
    )
}

@Composable
internal fun ReleaseNotesSettingsWebViewBody(
    pager: Pager,
    launchVersionCode: String,
    controller: SlockWebViewController,
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit,
) {
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    if (launchVersionCode.isNotBlank()) {
        LaunchedEffect(launchVersionCode) {
            bridge.log("release_notes_open versionSource=launch")
        }
        ReleaseNotesWebViewBody(
            pager = pager,
            versionCode = launchVersionCode,
            controller = controller,
            onNavigationStateChange = onNavigationStateChange,
        )
        return
    }

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

    ReleaseNotesWebViewBody(
        pager = pager,
        versionCode = (settingsState.about as? ScreenLoadState.Content)?.data?.buildLabel.orEmpty(),
        controller = controller,
        onNavigationStateChange = onNavigationStateChange,
    )
}

internal fun releaseNotesVersionCodeFromPageData(
    params: com.tencent.kuikly.core.nvi.serialization.json.JSONObject,
): String = releaseNotesSanitizeVersionCode(params.optString(SETTINGS_WEB_VIEW_VERSION_CODE))

internal fun releaseNotesSanitizeVersionCode(value: String): String =
    value.trim().takeIf { versionCode -> versionCode.all(Char::isDigit) }.orEmpty()

@Composable
internal fun ReleaseNotesWebViewBody(versionCode: String) {
    val pager = LocalActivity.current.getPager() as Pager
    ReleaseNotesWebViewBody(
        pager = pager,
        versionCode = versionCode,
        controller = rememberSlockWebViewController(),
        onNavigationStateChange = {},
    )
}

@Composable
internal fun ReleaseNotesWebViewBody(
    pager: Pager,
    versionCode: String,
    controller: SlockWebViewController,
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit,
) {
    if (versionCode.isBlank()) {
        SettingsWebViewLoadingCover()
        return
    }
    val url = remember(versionCode) { sharedSettingsReleaseNotesUrl(versionCode) }
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val loadStartedAt = remember(url) { TimeSource.Monotonic.markNow() }
    SettingsWebViewContent(
        url = url,
        controller = controller,
        onNavigationStateChange = onNavigationStateChange,
        onLoadSuccess = {
            bridge.log(
                "release_notes_load_success versionCode=$versionCode " +
                    "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds}"
            )
        },
        onLoadFailure = { reason ->
            bridge.log(
                "release_notes_load_failure versionCode=$versionCode " +
                    "durationMs=${loadStartedAt.elapsedNow().inWholeMilliseconds} " +
                    "reason=${reason.take(120)}"
            )
        },
    )
}

@Composable
internal fun ReleaseNotesCenteredNotice(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
        )
    }
}

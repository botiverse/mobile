package build.raft.app

import com.tencent.kuikly.core.pager.Pager
import build.raft.app.data.StoredAuthSession

internal fun Pager.openSettingsFeedbackWebViewPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Feedback, storeScope)
}

internal val settingsFeedbackWebViewSpec = StandaloneSettingsWebViewSpec(
    surface = SettingsWebViewSurface.Feedback,
    hostEventsEnabled = true,
    buildWebUrl = { webBaseUrl, _, _ ->
        sharedSettingsHostShellWebUrl(webBaseUrl, "feedback/")
    },
)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalBadge
import build.raft.app.components.SlockBrutalTone
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SlockSharedStoreRegistry
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

internal const val SLOCK_EMBEDDED_CHILD_PAGE_PARAM = "slockEmbeddedChildPage"

private val SlockEnvironmentIndicatorMaxWidth = 144.dp

@Composable
internal fun SlockEnvironmentIndicatorHost(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environmentStore =
        remember(bridge) {
            SlockSharedStoreRegistry.get(bridge).apiEnvironmentStore
        }
    val environment by environmentStore.state.collectAsState()
    val label = slockEnvironmentIndicatorLabel(environment)
    if (label == null) {
        // Production keeps its original layout tree: no indicator and no extra root Box.
        content()
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Intentionally no clickable/pointerInput modifier: the visual label stays pointer-through.
        SlockBrutalBadge(
            label = label,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = slockTopSafeAreaPadding() + 6.dp,
                        end = 6.dp,
                    )
                    .widthIn(max = SlockEnvironmentIndicatorMaxWidth)
                    .zIndex(SlockEnvironmentIndicatorZIndex),
            tone = SlockBrutalTone.Yellow,
        )
    }
}

internal fun slockEnvironmentIndicatorLabel(
    environment: SharedSettingsApiEnvironmentOption,
): String? {
    val productionApiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl.normalizedEnvironmentBaseUrl()
    if (environment.apiBaseUrl.normalizedEnvironmentBaseUrl() == productionApiBaseUrl) return null
    return environment.label.trim().takeIf(String::isNotEmpty)
}

private fun String.normalizedEnvironmentBaseUrl(): String = trim().trimEnd('/')

internal fun slockEnvironmentIndicatorEnabledForPage(params: JSONObject): Boolean =
    !params.optBoolean(SLOCK_EMBEDDED_CHILD_PAGE_PARAM, false)

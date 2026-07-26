package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.data.DataResult
import build.raft.app.data.KuiklyAppUpdateEvents
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.launch

const val APP_UPDATE_PAGE = "compose_app_update"

@Page(APP_UPDATE_PAGE, supportInLocal = true)
internal class ComposeAppUpdatePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AppUpdateScreen() }
    }
}

internal fun Pager.openAppUpdatePage(storeScope: SlockShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        APP_UPDATE_PAGE,
        JSONObject().apply {
            put("pageName", APP_UPDATE_PAGE)
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        }
    )
}

@Composable
private fun AppUpdateScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val appUpdateEvents = remember(sharedStores.eventBus) { KuiklyAppUpdateEvents(sharedStores.eventBus) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SharedSettingsUpdateCheckSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionRunning by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = snapshot == null
            when (val result = environment.settingsCapabilities.checkQuiverUpdateStatus()) {
                is DataResult.Content -> {
                    snapshot = result.data
                    errorCode = result.data.errorCode.takeIf { it.isNotBlank() }
                }
                is DataResult.Empty -> errorCode = "app_update_unavailable"
                is DataResult.Error -> errorCode = result.error.code
                is DataResult.NoPermission -> errorCode = result.blocker.code
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(appUpdateEvents) {
        val listener = appUpdateEvents.addStatusListener { refresh() }
        onDispose { appUpdateEvents.removeStatusListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.home_notification_app_update_title),
            iconName = SlockLucideIconName.CircleArrowUp,
            backgroundColor = SlockColor.BrandYellow,
            leadingContent = {
                SlockTopPageHeaderAction(
                    icon = SlockLucideIconName.ArrowLeft,
                    onClick = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
                )
            }
        )
        AppUpdateSummary(
            snapshot = snapshot,
            loading = loading,
            actionRunning = actionRunning,
            errorCode = errorCode,
            onRetry = ::refresh,
            onSkipVersion = {
                snapshot?.latestVersionCode?.let(::saveSkippedAppUpdateVersionCode)
                appUpdateEvents.notifyStatusChanged()
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            },
            onAction = {
                scope.launch {
                    actionRunning = true
                    errorCode = null
                    when (val result = environment.settingsCapabilities.installQuiverUpdate()) {
                        is DataResult.Content -> snapshot = result.data
                        is DataResult.Empty -> errorCode = "app_update_unavailable"
                        is DataResult.Error -> errorCode = result.error.code
                        is DataResult.NoPermission -> errorCode = result.blocker.code
                    }
                    actionRunning = false
                }
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val changelog = appUpdateInlineChangelog(snapshot)
            val versionCode = snapshot?.latestVersionCode?.takeIf { it > 0L }?.toString().orEmpty()
            if (changelog != null) {
                ReleaseNotesMarkdownBody(changelog)
            } else if (versionCode.isNotBlank()) {
                ReleaseNotesNativeBody(versionCode)
            } else {
                ReleaseNotesLoadingCover()
            }
        }
    }
}

internal fun appUpdateInlineChangelog(snapshot: SharedSettingsUpdateCheckSnapshot?): String? =
    snapshot?.latestChangelog?.takeIf(String::isNotBlank)

@Composable
internal fun AppUpdateSummary(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    loading: Boolean,
    actionRunning: Boolean,
    errorCode: String?,
    onRetry: () -> Unit,
    onSkipVersion: () -> Unit,
    onAction: () -> Unit
) {
    val currentVersion = snapshot?.currentVersionLabel.orEmpty().ifBlank { "-" }
    val latestVersion = snapshot?.latestVersionLabel.orEmpty().ifBlank { "-" }
    val actionLabel =
        appUpdatePrimaryActionLabel(
            snapshot = snapshot,
            actionRunning = actionRunning,
            retryLabel = stringResource(MR.strings.app_update_retry),
            workingLabel = stringResource(MR.strings.app_update_working),
            downloadLabel = stringResource(MR.strings.app_update_download),
            downloadingLabel = stringResource(MR.strings.app_update_downloading),
            installingLabel = stringResource(MR.strings.app_update_installing),
            installLabel = stringResource(MR.strings.app_update_install)
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(SlockLucideIconName.Download, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(MR.strings.app_update_version_summary, currentVersion, latestVersion),
                    style = SlockType.ButtonBold,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (errorCode != null) {
                            stringResource(MR.strings.app_update_unavailable)
                        } else {
                            snapshot?.detail.orEmpty()
                        },
                    style = SlockType.Caption,
                    color = if (errorCode == null) SlockColor.TextMutedGray else SlockColor.AccentRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockBrutalActionButton(
                label = actionLabel,
                onClick = if (errorCode != null && snapshot == null) onRetry else onAction,
                enabled = appUpdatePrimaryActionEnabled(snapshot, loading, actionRunning, errorCode),
                leadingIcon = if (errorCode != null && snapshot == null) SlockLucideIconName.RefreshCw else SlockLucideIconName.Download,
                minWidth = 96.dp,
                backgroundColor = SlockColor.AccentPink
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        if (snapshot?.latestVersionCode?.let { it > 0L } == true) {
            Text(
                text = stringResource(MR.strings.app_update_skip_version),
                modifier = Modifier.align(Alignment.End).clickable(onClick = onSkipVersion).padding(vertical = 4.dp),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun AppUpdateDetailsVisualScreen(chinese: Boolean = false) {
    val snapshot =
        SharedSettingsUpdateCheckSnapshot(
            supported = true,
            statusLabel = "download",
            detail =
                if (chinese) {
                    "更快、更清晰的 Raft 新版本已可安装。"
                } else {
                    "A faster, clearer Raft experience is ready to install."
                },
            actionLabel = "download",
            updateAvailable = true,
            currentVersionLabel = "1.5.0",
            currentVersionCode = 1_050_000L,
            latestVersionLabel = "1.6.0",
            latestVersionCode = 1_060_000L,
            checkedAtLabel = "Just now"
        )
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.home_notification_app_update_title),
            iconName = SlockLucideIconName.CircleArrowUp,
            backgroundColor = SlockColor.BrandYellow,
            leadingContent = {
                SlockTopPageHeaderAction(icon = SlockLucideIconName.ArrowLeft, onClick = {})
            }
        )
        AppUpdateSummary(
            snapshot = snapshot,
            loading = false,
            actionRunning = false,
            errorCode = null,
            onRetry = {},
            onSkipVersion = {},
            onAction = {}
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (chinese) "1.6.0 更新内容" else "WHAT'S NEW IN 1.6.0",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            Text(
                text = if (chinese) "专注于更高效协作的更新" else "A focused update for faster collaboration",
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack
            )
            Text(
                text =
                    if (chinese) {
                        "更新 Raft，获得更清晰的动态状态、更流畅的导航和最新的稳定性改进。"
                    } else {
                        "Update Raft to get clearer activity states, smoother navigation, and the latest reliability improvements."
                    },
                style = SlockType.Body,
                color = SlockColor.TextBlack
            )
            Text(
                text =
                    if (chinese) {
                        "- 快速查看最新动态\n- 更顺畅地切换对话\n- 无需离开 Raft 即可下载并安装更新"
                    } else {
                        "- Review new activity at a glance\n- Move between conversations with fewer interruptions\n- Download and install updates without leaving Raft"
                    },
                style = SlockType.Body,
                color = SlockColor.TextBlack
            )
        }
    }
}

private const val SkippedAppUpdateVersionCodeKey = "appUpdate.skippedVersionCode"

internal fun skippedAppUpdateVersionCode(
    store: SharedPreferenceStore = slockSharedPreferenceStore()
): Long = store.getString(SkippedAppUpdateVersionCodeKey)?.toLongOrNull() ?: 0L

internal fun saveSkippedAppUpdateVersionCode(
    versionCode: Long,
    store: SharedPreferenceStore = slockSharedPreferenceStore()
) {
    if (versionCode > 0L) {
        store.putString(SkippedAppUpdateVersionCodeKey, versionCode.toString())
    }
}

internal fun SharedSettingsUpdateCheckSnapshot.withSkippedAppUpdateVersionApplied(
    skippedVersionCode: Long = skippedAppUpdateVersionCode()
): SharedSettingsUpdateCheckSnapshot =
    if (latestVersionCode > 0L && latestVersionCode == skippedVersionCode) {
        copy(updateAvailable = false)
    } else {
        this
    }

internal fun appUpdatePrimaryActionLabel(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    actionRunning: Boolean,
    retryLabel: String = "Retry",
    workingLabel: String = "Working...",
    downloadLabel: String = "Download",
    downloadingLabel: String = "Downloading",
    installingLabel: String = "Installing",
    installLabel: String = "Install"
): String =
    when {
        actionRunning && snapshot.isDownloadAction() -> downloadingLabel
        actionRunning -> workingLabel
        snapshot == null -> retryLabel
        snapshot.actionLabel.equals("download", ignoreCase = true) -> downloadLabel
        snapshot.actionLabel.equals("downloading", ignoreCase = true) -> downloadingLabel
        snapshot.actionLabel.equals("installing", ignoreCase = true) -> installingLabel
        snapshot.actionLabel.equals("install", ignoreCase = true) -> installLabel
        snapshot.actionLabel.isNotBlank() -> snapshot.actionLabel
        snapshot.statusLabel.equals("install", ignoreCase = true) -> installLabel
        snapshot.statusLabel.equals("downloading", ignoreCase = true) -> downloadingLabel
        snapshot.statusLabel.equals("installing", ignoreCase = true) -> installingLabel
        else -> downloadLabel
    }

private fun SharedSettingsUpdateCheckSnapshot?.isDownloadAction(): Boolean =
    this != null &&
        (
            actionLabel.equals("download", ignoreCase = true) ||
                (actionLabel.isBlank() && statusLabel.equals("download", ignoreCase = true))
        )

internal fun appUpdatePrimaryActionEnabled(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    loading: Boolean,
    actionRunning: Boolean,
    errorCode: String?
): Boolean =
    when {
        loading || actionRunning -> false
        snapshot == null -> errorCode != null
        !snapshot.supported || !snapshot.updateAvailable -> false
        snapshot.statusLabel.equals("downloading", ignoreCase = true) ||
            snapshot.statusLabel.equals("installing", ignoreCase = true) -> false
        else -> true
    }

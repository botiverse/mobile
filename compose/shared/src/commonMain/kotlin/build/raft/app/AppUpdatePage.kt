package build.raft.app

import build.raft.app.resources.*
import build.raft.app.format.RaftFormat
import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAppUpdateEvents
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import build.raft.app.data.SharedUpdateDownloadProgress
import build.raft.app.components.RaftProgressBar
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.foundation.layout.widthIn

const val APP_UPDATE_PAGE = "compose_app_update"

@Page(APP_UPDATE_PAGE, supportInLocal = true)
internal class ComposeAppUpdatePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AppUpdateScreen(raftPageVisibleResumeTick, raftPageVisible) }
    }
}

internal fun Pager.openAppUpdatePage(storeScope: RaftShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        APP_UPDATE_PAGE,
        JSONObject().apply {
            put("pageName", APP_UPDATE_PAGE)
            put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        }
    )
}

@Composable
private fun AppUpdateScreen(pageVisibleResumeTick: Int, pageVisible: Boolean) {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val appUpdateEvents = remember(sharedStores.eventBus) { RaftAppUpdateEvents(sharedStores.eventBus) }
    val scope = rememberCoroutineScope()
    val refreshGate = remember { AppUpdateRefreshGate() }
    val unavailableMessage = stringResource(Res.string.app_update_unavailable)
    val errorStrings = localizedErrorPresentationStrings()
    var snapshot by remember { mutableStateOf<SharedSettingsUpdateCheckSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<SharedUpdateDownloadProgress?>(null) }
    var transfer by remember { mutableStateOf<RaftTransferProgress?>(null) }
    val downloadFailedMessage = stringResource(Res.string.app_update_download_failed)

    fun refresh() {
        scope.launch {
            if (!refreshGate.tryStart()) return@launch
            try {
                loading = true
                do {
                    val result = environment.settingsCapabilities.checkHandsUpdateStatus()
                    val projection = result.toAppUpdateResultProjection(snapshot, unavailableMessage, errorStrings)
                    snapshot = projection.snapshot?.withLocalDownloadTerminal(progress)
                    errorMessage = if (snapshot?.errorCode == "settings_hands_update_download_failed") downloadFailedMessage else projection.errorMessage
                } while (refreshGate.completeAndShouldContinue())
            } finally {
                loading = false
                refreshGate.reset()
            }
        }
    }

    val progressToken = snapshot?.downloadProgressToken.orEmpty()
    LaunchedEffect(progressToken, pageVisible) {
        progress = null
        transfer = null
        if (!pageVisible || progressToken.isBlank()) return@LaunchedEffect
        // The page owns this download's meter: one per attempt token.
        val meter = RaftTransferMeter()
        while (true) {
            val observed = environment.settingsCapabilities.readHandsDownloadProgress(progressToken)
            if (observed == null || observed.token != progressToken) {
                // A transient local query failure must not freeze the old percentage.
                progress = null
                delay(1_000)
                continue
            }
            progress = observed
            transfer = meter.observe(observed)
            if (!observed.active) {
                snapshot = snapshot?.withLocalDownloadTerminal(observed)
                errorMessage = if (observed.phase == "failed") downloadFailedMessage else null
                refresh()
                break
            }
            delay(500)
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(pageVisibleResumeTick) {
        if (appUpdatePageShouldRefreshOnResume(pageVisibleResumeTick)) {
            refresh()
        }
    }
    DisposableEffect(appUpdateEvents) {
        val listener = appUpdateEvents.addStatusListener { refresh() }
        onDispose { appUpdateEvents.removeStatusListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.home_notification_app_update_title),
            iconName = RaftLucideIconName.CircleArrowUp,
            backgroundColor = RaftColor.BrandYellow,
            onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
        )
        AppUpdateSummary(
            snapshot = snapshot,
            progress = progress?.takeIf { it.token == progressToken },
            transfer = transfer.takeIf { progress?.token == progressToken },
            loading = loading && progress?.takeIf { it.token == progressToken }?.active != false,
            actionRunning = actionRunning,
            errorMessage = errorMessage,
            onRetry = ::refresh,
            onSkipVersion = {
                snapshot?.latestVersionCode?.let(::saveSkippedAppUpdateVersionCode)
                appUpdateEvents.notifyStatusChanged()
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            },
            onAction = {
                scope.launch {
                    actionRunning = true
                    errorMessage = null
                    try {
                        val result = environment.settingsCapabilities.installHandsUpdateForToken(snapshot?.downloadProgressToken.orEmpty())
                        val projection = result.toAppUpdateResultProjection(snapshot, unavailableMessage, errorStrings)
                        snapshot = projection.snapshot
                        errorMessage = projection.errorMessage
                    } finally {
                        actionRunning = false
                    }
                }
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val versionCode = snapshot?.latestVersionCode?.takeIf { it > 0L }?.toString().orEmpty()
            if (versionCode.isNotBlank()) {
                ReleaseNotesWebViewBody(versionCode)
            } else {
                ReleaseNotesLoadingCover()
            }
        }
    }
}

internal fun appUpdatePageShouldRefreshOnResume(pageVisibleResumeTick: Int): Boolean =
    pageVisibleResumeTick > 0

internal class AppUpdateRefreshGate {
    private var inFlight = false
    private var refreshPending = false

    fun tryStart(): Boolean {
        if (inFlight) {
            refreshPending = true
            return false
        }
        inFlight = true
        return true
    }

    fun completeAndShouldContinue(): Boolean {
        if (refreshPending) {
            refreshPending = false
            return true
        }
        inFlight = false
        return false
    }

    fun reset() {
        inFlight = false
        refreshPending = false
    }
}

internal fun SharedSettingsUpdateCheckSnapshot.withAppUpdateDisplayFallback(
    previous: SharedSettingsUpdateCheckSnapshot?
): SharedSettingsUpdateCheckSnapshot {
    if (previous == null) return this
    val responseTargetVersionCode =
        latestVersionCode.takeIf { it > 0L }
            ?: transactionTargetVersionCode.takeIf { it > 0L }
    val previousTargetVersionCode =
        previous.latestVersionCode.takeIf { it > 0L }
            ?: previous.transactionTargetVersionCode.takeIf { it > 0L }
    val resolvedVersionCode = responseTargetVersionCode ?: previous.latestVersionCode
    if (
        responseTargetVersionCode != null &&
        previousTargetVersionCode != null &&
        responseTargetVersionCode != previousTargetVersionCode
    ) {
        return this
    }
    return copy(
        latestVersionCode = resolvedVersionCode,
        latestVersionLabel = latestVersionLabel.ifBlank { previous.latestVersionLabel },
        latestChangelog = latestChangelog.ifBlank { previous.latestChangelog },
        downloadUrl = downloadUrl.ifBlank { previous.downloadUrl },
        detail =
            if (downloadSizesResolved || errorCode.isNotBlank()) {
                detail
            } else {
                previous.detail.ifBlank { detail }
            },
        downloadSizesResolved = downloadSizesResolved || previous.downloadSizesResolved,
        fullDownloadSizeBytes =
            if (downloadSizesResolved) fullDownloadSizeBytes else previous.fullDownloadSizeBytes,
        patchDownloadSizeBytes =
            if (downloadSizesResolved) patchDownloadSizeBytes else previous.patchDownloadSizeBytes
    )
}

internal fun DataResult<SharedSettingsUpdateCheckSnapshot>.appUpdateErrorMessage(
    unavailableMessage: String,
    errorStrings: ErrorPresentationStrings
): String? =
    when (this) {
        is DataResult.Content ->
            data.errorCode
                .takeIf { it.isNotBlank() }
                ?.let { code ->
                    data.detail.takeIf { it.isNotBlank() }
                        ?: data.screenError?.userFacingMessage(errorStrings)
                        ?: ScreenError(code).userFacingMessage(errorStrings)
                }
        is DataResult.Empty -> unavailableMessage
        is DataResult.Error -> error.userFacingMessage(errorStrings)
        is DataResult.NoPermission -> blocker.userFacingMessage(errorStrings)
    }

internal data class AppUpdateResultProjection(
    val snapshot: SharedSettingsUpdateCheckSnapshot?,
    val errorMessage: String?
)

internal fun DataResult<SharedSettingsUpdateCheckSnapshot>.toAppUpdateResultProjection(
    previousSnapshot: SharedSettingsUpdateCheckSnapshot?,
    unavailableMessage: String,
    errorStrings: ErrorPresentationStrings
): AppUpdateResultProjection =
    when (this) {
        is DataResult.Content -> {
            val currentErrorMessage = appUpdateErrorMessage(unavailableMessage, errorStrings)
            val resolvedSnapshot = data.withAppUpdateDisplayFallback(previousSnapshot)
            AppUpdateResultProjection(
                snapshot = resolvedSnapshot,
                errorMessage = currentErrorMessage
            )
        }
        else ->
            AppUpdateResultProjection(
                snapshot = previousSnapshot,
                errorMessage = appUpdateErrorMessage(unavailableMessage, errorStrings)
            )
    }

/**
 * The "current → latest" pair shown by the updater. A build of the same version (1.13.0 build 1 →
 * 1.13.0 build 8) used to read "1.13.0 → 1.13.0" and looked like a bug (artin 2026-10-10), so when
 * the version names match and both builds are known and differ, each side gets its build number:
 * "1.13.0 (11300001) → 1.13.0 (11300008)". Different versions keep the plain names.
 */
internal fun appUpdateVersionLabels(
    currentLabel: String,
    currentCode: Long,
    latestLabel: String,
    latestCode: Long,
): Pair<String, String> {
    val sameName = currentLabel.isNotBlank() && currentLabel.trim() == latestLabel.trim()
    if (!sameName || currentCode <= 0L || latestCode <= 0L || currentCode == latestCode) {
        return currentLabel to latestLabel
    }
    return "$currentLabel ($currentCode)" to "$latestLabel ($latestCode)"
}

@Composable
internal fun AppUpdateSummary(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    loading: Boolean,
    actionRunning: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSkipVersion: () -> Unit,
    onAction: () -> Unit,
    progress: SharedUpdateDownloadProgress? = null,
    transfer: RaftTransferProgress? = null
) {
    val versionLabels =
        appUpdateVersionLabels(
            currentLabel = snapshot?.currentVersionLabel.orEmpty(),
            currentCode = snapshot?.currentVersionCode ?: 0L,
            latestLabel = snapshot?.latestVersionLabel.orEmpty(),
            latestCode = snapshot?.latestVersionCode ?: 0L,
        )
    val currentVersion = versionLabels.first.ifBlank { "-" }
    val latestVersion = versionLabels.second.ifBlank { "-" }
    val actionLabel =
        appUpdatePrimaryActionLabel(
            snapshot = snapshot,
            actionRunning = actionRunning,
            retryLabel = stringResource(Res.string.app_update_retry),
            workingLabel = stringResource(Res.string.app_update_working),
            checkingLabel = stringResource(Res.string.app_update_checking),
            preparingLabel = stringResource(Res.string.app_update_preparing),
            downloadLabel = stringResource(Res.string.app_update_download),
            downloadingLabel = stringResource(Res.string.app_update_downloading),
            installingLabel = stringResource(Res.string.app_update_installing),
            installLabel = stringResource(Res.string.app_update_install),
            installedLabel = stringResource(Res.string.app_update_installed)
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(RaftColor.BrandYellow).border(RaftAppStroke.Default, RaftColor.StickerInk),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(RaftLucideIconName.Download, modifier = Modifier.size(20.dp), color = raftArgbHex(RaftColor.StickerInk))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.app_update_version_summary, currentVersion, latestVersion),
                    style = RaftType.ButtonBold,
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (snapshot?.errorCode == "settings_hands_update_download_failed") {
                            stringResource(Res.string.app_update_download_failed)
                        } else if (errorMessage != null) {
                            errorMessage
                        } else {
                            snapshot?.detail.orEmpty()
                        },
                    style = RaftType.Caption,
                    color = if (errorMessage == null) RaftColor.TextMutedGray else RaftColor.AccentRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftButton(
                text = actionLabel,
                onClick = if (errorMessage != null && snapshot == null) onRetry else onAction,
                modifier = Modifier.widthIn(min = 96.dp),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = appUpdatePrimaryActionEnabled(snapshot, loading, actionRunning, errorMessage),
                leadingIcon = { RaftSlotIcon(appUpdatePrimaryActionIcon(snapshot, errorMessage)) }
            )
        }
        if (progress?.active == true) {
            val bytes = RaftFormat.bytes(transfer?.bytesDone ?: progress.downloadedBytes)
            val total = (transfer?.bytesTotal ?: progress.totalBytes)?.let { RaftFormat.bytes(it) }
            val percent = transfer?.percent
            val label = if (total != null) "$bytes / $total" else bytes
            Text(
                text = stringResource(if (progress.phase == "paused") Res.string.app_update_download_paused else Res.string.app_update_downloading),
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
            // Unknown response length has no fabricated percentage/bar fill.
            if (percent != null) {
                RaftProgressBar(value = percent, label = label, showPercent = true)
            } else {
                Text(text = label, style = RaftType.Caption, color = RaftColor.TextMutedGray)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
        if (appUpdateCanSkipVersion(snapshot)) {
            Text(
                text = stringResource(Res.string.app_update_skip_version),
                modifier = Modifier.align(Alignment.End).clickable(onClick = onSkipVersion).padding(vertical = 4.dp),
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
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
                    "增量包 93.0 KB；完整包备用 90.0 MB。"
                } else {
                    "Patch 93.0 KB; full fallback 90.0 MB."
                },
            actionLabel = "download",
            updateAvailable = true,
            currentVersionLabel = "1.5.0",
            currentVersionCode = 1_050_000L,
            latestVersionLabel = "1.6.0",
            latestVersionCode = 1_060_000L,
            downloadSizesResolved = true,
            patchDownloadSizeBytes = 95_256L,
            fullDownloadSizeBytes = 94_411_540L,
            checkedAtLabel = "Just now"
        )
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(RaftColor.SurfaceWhite).padding(top = 48.dp)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.home_notification_app_update_title),
            iconName = RaftLucideIconName.CircleArrowUp,
            backgroundColor = RaftColor.BrandYellow,
            onBack = {}
        )
        AppUpdateSummary(
            snapshot = snapshot,
            loading = false,
            actionRunning = false,
            errorMessage = null,
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
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
            Text(
                text = if (chinese) "专注于更高效协作的更新" else "A focused update for faster collaboration",
                style = RaftType.PageTitle,
                color = RaftColor.TextBlack
            )
            Text(
                text =
                    if (chinese) {
                        "更新 Raft，获得更清晰的动态状态、更流畅的导航和最新的稳定性改进。"
                    } else {
                        "Update Raft to get clearer activity states, smoother navigation, and the latest reliability improvements."
                    },
                style = RaftType.Body,
                color = RaftColor.TextBlack
            )
            Text(
                text =
                    if (chinese) {
                        "- 快速查看最新动态\n- 更顺畅地切换对话\n- 无需离开 Raft 即可下载并安装更新"
                    } else {
                        "- Review new activity at a glance\n- Move between conversations with fewer interruptions\n- Download and install updates without leaving Raft"
                    },
                style = RaftType.Body,
                color = RaftColor.TextBlack
            )
        }
    }
}

private const val SkippedAppUpdateVersionCodeKey = "appUpdate.skippedVersionCode"

internal fun skippedAppUpdateVersionCode(
    store: SharedPreferenceStore = raftSharedPreferenceStore()
): Long = store.getString(SkippedAppUpdateVersionCodeKey)?.toLongOrNull() ?: 0L

internal fun saveSkippedAppUpdateVersionCode(
    versionCode: Long,
    store: SharedPreferenceStore = raftSharedPreferenceStore()
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
    checkingLabel: String = "Checking",
    preparingLabel: String = "Preparing",
    downloadLabel: String = "Download",
    downloadingLabel: String = "Downloading",
    installingLabel: String = "Installing",
    installLabel: String = "Install",
    installedLabel: String = "Installed"
): String =
    when {
        actionRunning && snapshot.isDownloadAction() -> downloadingLabel
        actionRunning -> workingLabel
        snapshot == null -> retryLabel
        snapshot.errorCode == "settings_hands_update_download_failed" -> retryLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Idle -> downloadLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Checking -> checkingLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Patching -> preparingLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Downloading -> downloadingLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall -> installLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.InstallerOpened -> installLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Failed -> downloadLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Stale -> downloadLabel
        snapshot.transactionState == SharedSettingsUpdateTransactionState.Installed -> installedLabel
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
    when (this?.transactionState) {
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale -> true
        SharedSettingsUpdateTransactionState.Unknown ->
            actionLabel.equals("download", ignoreCase = true) ||
                (actionLabel.isBlank() && statusLabel.equals("download", ignoreCase = true))
        else -> false
    }

internal fun appUpdatePrimaryActionEnabled(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    loading: Boolean,
    actionRunning: Boolean,
    errorMessage: String?
): Boolean {
    if (loading || actionRunning) return false
    if (snapshot == null) return errorMessage != null
    if (!snapshot.supported) return false
    return when (snapshot.transactionState) {
        SharedSettingsUpdateTransactionState.Idle -> snapshot.updateAvailable
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale ->
            snapshot.transactionRetryable
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened -> true
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Downloading,
        SharedSettingsUpdateTransactionState.Installed -> false
        SharedSettingsUpdateTransactionState.Unknown ->
            snapshot.updateAvailable &&
                !snapshot.statusLabel.equals("downloading", ignoreCase = true) &&
                !snapshot.statusLabel.equals("installing", ignoreCase = true)
    }
}

internal fun appUpdatePrimaryActionIcon(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    errorMessage: String?
): RaftLucideIconName =
    when {
        snapshot == null && errorMessage != null -> RaftLucideIconName.RefreshCw
        snapshot?.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall ||
            snapshot?.transactionState == SharedSettingsUpdateTransactionState.InstallerOpened ->
            RaftLucideIconName.PackageCheck
        snapshot?.transactionState == SharedSettingsUpdateTransactionState.Installed ->
            RaftLucideIconName.CircleCheckBig
        snapshot?.transactionRetryable == true &&
            (snapshot.transactionState == SharedSettingsUpdateTransactionState.Failed ||
                snapshot.transactionState == SharedSettingsUpdateTransactionState.Stale) ->
            RaftLucideIconName.RefreshCw
        else -> RaftLucideIconName.Download
    }

internal fun appUpdateCanSkipVersion(snapshot: SharedSettingsUpdateCheckSnapshot?): Boolean {
    if (
        snapshot == null ||
        !snapshot.supported ||
        !snapshot.updateAvailable ||
        snapshot.latestVersionCode <= 0L ||
        (snapshot.currentVersionCode > 0L && snapshot.latestVersionCode <= snapshot.currentVersionCode)
    ) {
        return false
    }
    return when (snapshot.transactionState) {
        SharedSettingsUpdateTransactionState.Unknown,
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale -> true
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Downloading,
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened,
        SharedSettingsUpdateTransactionState.Installed -> false
    }
}

/**
 * One poll of the host's update download into the page's meter. "ready" (the APK is complete in
 * Downloads) is the only Done; until then the bar stops at 99.
 */
internal fun RaftTransferMeter.observe(sample: SharedUpdateDownloadProgress): RaftTransferProgress {
    when (sample.phase) {
        "paused" -> {
            bytes(sample.downloadedBytes, sample.totalBytes)
            pause()
        }
        "downloading" -> {
            resume()
            bytes(sample.downloadedBytes, sample.totalBytes)
        }
        "ready" -> done(sample.totalBytes ?: sample.downloadedBytes)
        "failed" -> failed()
    }
    return progress
}

/** Local completion remains actionable even when the subsequent offer refresh fails. */
internal fun SharedSettingsUpdateCheckSnapshot.withLocalDownloadTerminal(
    progress: SharedUpdateDownloadProgress?
): SharedSettingsUpdateCheckSnapshot {
    if (progress == null || progress.active || progress.token != downloadProgressToken) return this
    if (transactionState == SharedSettingsUpdateTransactionState.InstallerOpened || errorCode.startsWith("install_")) return this
    return when (progress.phase) {
        "ready" -> copy(
            transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall,
            statusLabel = "Install", actionLabel = "Install", errorCode = "", detail = ""
        )
        "failed" -> copy(
            transactionState = SharedSettingsUpdateTransactionState.Failed,
            transactionRetryable = true,
            statusLabel = "Failed", actionLabel = "Download",
            errorCode = "settings_hands_update_download_failed", detail = ""
        )
        else -> this
    }
}

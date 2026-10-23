package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.isRefreshing
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val SETTINGS_STORAGE_PAGE = "compose_settings_storage"

@Page(SETTINGS_STORAGE_PAGE, supportInLocal = true)
internal class ComposeSettingsStoragePage : AuthenticatedProductPager(AuthenticatedProductRoute.SettingsStorage) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsStorageScreen() }
    }
}

@Serializable
internal data class RaftSettingsStoragePayload(
    val instanceId: String = "",
    val totalTitle: String = "Storage used",
    val totalSize: String = "0 B",
    val refreshLabel: String = "Refresh",
    val safeClearNote: String = "Clearing cache never removes your account or server data.",
    val feedback: String = "",
    val rows: List<RaftSettingsStorageRow> = emptyList(),
    /** A refresh is running: the refresh button shows its spinner. */
    val refreshing: Boolean = false
)

@Serializable
internal data class RaftSettingsStorageRow(
    val target: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Archive,
    val title: String = "",
    val detail: String = "",
    val size: String = "0 B",
    val clearLabel: String = "",
    val clearEnabled: Boolean = false,
    val clearInProgress: Boolean = false
)

internal val settingsStorageSmokePayload = RaftSettingsStoragePayload(
    instanceId = "settings-storage-smoke",
    totalSize = "22.5 MB",
    rows = listOf(
        RaftSettingsStorageRow(
            target = "database",
            iconName = RaftLucideIconName.Archive,
            title = "Local database",
            detail = "Messages, task metadata, and server snapshots stored on this device.",
            size = "4.6 MB"
        ),
        RaftSettingsStorageRow(
            target = "imageCache",
            iconName = RaftLucideIconName.Image,
            title = "Image cache",
            detail = "Downloaded image previews kept for faster viewing.",
            size = "17.4 MB",
            clearLabel = "Clear image cache",
            clearEnabled = true
        ),
        RaftSettingsStorageRow(
            target = "attachmentDownloads",
            iconName = RaftLucideIconName.Download,
            title = "Attachment staging",
            detail = "Temporary data from active or failed downloads. Completed files remain in Downloads.",
            size = "768 KB",
            clearLabel = "Clear attachment staging",
            clearEnabled = true
        ),
        RaftSettingsStorageRow(
            target = "temporaryAndLogs",
            iconName = RaftLucideIconName.FileText,
            title = "Temporary files and logs",
            detail = "Debug logs and temporary files generated by the app.",
            size = "512 KB",
            clearLabel = "Clear temp files and logs",
            clearEnabled = true
        )
    )
)

@Composable
private fun SettingsStorageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val launchPayload = rememberStoragePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-storage") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("settings_storage_open instanceId=${launchPayload.instanceId}")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Storage))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    val downloadCenter = RaftDownloadCenterProcess.center
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val attachmentOwnerVersion by ProcessAttachmentAuthScopes.version.collectAsState()
    val attachmentOwner = remember(attachmentOwnerVersion, storeScope.shellInstanceId) {
        currentAttachmentOwnerLease(storeScope)
    }
    val ownerKey = attachmentOwner?.principalKey?.value
    val downloadEntries by remember(ownerKey) { downloadCenter.entries(ownerKey) }.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val openResultTerms =
        RaftAttachmentOpenResultTerms(
            downloadStopped = stringResource(MR.strings.file_preview_download_stopped),
            unableToDownloadAttachment = stringResource(MR.strings.file_preview_error_download_attachment),
            noAppCanOpenFile = stringResource(MR.strings.file_preview_error_no_app),
            storageUnavailable = stringResource(MR.strings.file_preview_error_storage_unavailable),
            downloadFailed = stringResource(MR.strings.file_preview_download_failed),
            unableToOpenAttachment = stringResource(MR.strings.file_preview_error_open_attachment)
        )
    fun onDownloadAction(action: RaftDownloadCenterAction) {
        when (action) {
            is RaftDownloadCenterAction.Open -> {
                val owner = attachmentOwner ?: return
                val entry = downloadEntries.firstOrNull { it.id == action.id } ?: return
                coroutineScope.launch {
                    // The committed public copy only: never re-downloads from here.
                    val result =
                        bridge.openAttachment(
                            url = "",
                            attachmentId = entry.attachmentId,
                            fileName = entry.fileName,
                            owner = owner,
                            mimeType = entry.mimeType,
                            requirePublicDownload = true
                        )
                    raftAttachmentOpenResultMessage(result?.optString("status").orEmpty(), openResultTerms)
                        ?.let(bridge::toast)
                }
            }
            is RaftDownloadCenterAction.Pause -> downloadCenter.pause(action.id)
            is RaftDownloadCenterAction.Resume -> downloadCenter.resume(action.id)
            is RaftDownloadCenterAction.Cancel -> downloadCenter.cancel(action.id)
            is RaftDownloadCenterAction.Retry -> downloadCenter.retry(action.id)
            is RaftDownloadCenterAction.Delete -> downloadCenter.delete(action.id)
        }
    }
    // The download dates are relative times ("5 min ago"); this page sits outside the app shell's
    // time context, so it keeps its own live one in the device zone.
    val timeCapabilities = remember {
        RaftTimeCapabilities(
            localOffsetMinutes = ProcessRaftDatetimeFoundationOwner.localOffsetMinutesAt(defaultNowMillis(), 0),
            timeFormat = RaftTimeFormat24Hour
        )
    }
    val inheritedTimeContextProvider = LocalRaftTimeContextProvider.current
    val ownTimeContextProvider =
        rememberLiveRaftTimeContext(capabilities = timeCapabilities, active = inheritedTimeContextProvider == null)
    val timeContextProvider = inheritedTimeContextProvider ?: ownTimeContextProvider
    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContextProvider) {
        SettingsDetailShell(
            title = stringResource(MR.strings.settings_storage_title),
            subtitle = "",
            onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
        ) {
            SettingsStorageContent(
                // task #177: standalone page outside the app shell.
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                payload =
                    settingsState.storage.toStoragePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Storage)
                    ),
                onEvent = { action, target ->
                    when (action) {
                        "refreshStorage" -> settingsViewModel.dispatch(SharedSettingsIntent.RefreshStorage)
                        "clearStorage" ->
                            target
                                ?.takeIf { it.isNotBlank() }
                                ?.let { settingsViewModel.dispatch(SharedSettingsIntent.ClearStorage(it)) }
                        else -> bridge.log("settings_storage_unsupported_action action=$action target=${target.orEmpty()}")
                    }
                },
                downloads = {
                    RaftDownloadCenterSection(
                        entries = downloadEntries,
                        canRetry = downloadCenter::canRetry,
                        onAction = ::onDownloadAction,
                        onClearFinished = { ownerKey?.let(downloadCenter::clearFinished) }
                    )
                }
            )
        }
    }
}

@Composable
private fun rememberStoragePayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): RaftSettingsStoragePayload =
    remember(payload, fallbackPageData, instanceId) {
        parseSettingsStoragePayload(payload, fallbackPageData, instanceId)
    }

/**
 * Settings › Storage as the grouped settings list of the redesigned settings pages (Channel
 * settings): [RaftSection]s on the canvas, each with an inset [RaftListRowGroup].
 *
 * - Downloads (the download centre, [downloads]) first, as before.
 * - Storage used: the total under the section heading, refreshed by a ghost icon button in the
 *   heading (the page also loads on open); one row per category — tile, title, a one-line hint
 *   description, the size at the end and, where it can be cleared, a ghost trash icon button
 *   named by the clear label ("Clear image cache"), as the download rows' Remove. Clearing a
 *   cache cannot remove account or server data, so it acts at once, as before.
 * - The safe-clear note under the group, and the last result as an info [RaftBanner].
 */
@Composable
internal fun SettingsStorageContent(
    payload: RaftSettingsStoragePayload,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onEvent: (String, String?) -> Unit,
    /** Settings › Storage › Downloads (the download centre), above the storage rows. */
    downloads: (@Composable () -> Unit)? = null
) {
    RaftDesignSystemTheme {
        LazyColumn(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            if (downloads != null) {
                item(key = "download-center") { downloads() }
            }
            item(key = "storage-used") {
                SettingsStorageUsedSection(
                    payload = payload,
                    separator = downloads != null,
                    onRefresh = { onEvent("refreshStorage", null) },
                    onClear = { target -> onEvent("clearStorage", target) }
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
        }
    }
}

@Composable
private fun SettingsStorageUsedSection(
    payload: RaftSettingsStoragePayload,
    separator: Boolean,
    onRefresh: () -> Unit,
    onClear: (String) -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftSection(
        title = payload.totalTitle,
        separator = separator,
        action = {
            RaftIconButton(
                onClick = onRefresh,
                tooltip = payload.refreshLabel,
                variant = RaftButtonVariant.Ghost,
                enabled = !payload.refreshing,
                loading = payload.refreshing
            ) { RaftDownloadIcon(RaftLucideIconName.RefreshCw) }
        }
    ) {
        RaftText(
            text = payload.totalSize,
            modifier = Modifier.padding(bottom = 12.dp).raftCopyable(payload.totalSize),
            style = typography.title,
            color = colors.foreground,
            maxLines = 1
        )
        if (payload.rows.isNotEmpty()) {
            // Rows without a clear action keep the button's place, so the sizes line up.
            val actionSlot = payload.rows.any { it.clearLabel.isNotBlank() }
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                payload.rows.forEachIndexed { index, row ->
                    SettingsStorageRow(row = row, divider = index > 0, actionSlot = actionSlot, onClear = { onClear(row.target) })
                }
            }
        }
        RaftText(
            text = payload.safeClearNote,
            modifier = Modifier.padding(top = 12.dp),
            style = typography.footnote,
            color = colors.foregroundHint
        )
        payload.feedback.takeIf { it.isNotBlank() }?.let { feedback ->
            RaftBanner(
                description = feedback,
                status = RaftBannerStatus.Info,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun SettingsStorageRow(row: RaftSettingsStorageRow, divider: Boolean, actionSlot: Boolean, onClear: () -> Unit) {
    RaftListRow(
        title = row.title,
        divider = divider,
        text = RaftListRowText.Member,
        subtitle = row.detail.takeIf { it.isNotBlank() },
        leading = {
            RaftIconTile(size = RaftIconTileSize.Md) {
                RaftDownloadIcon(row.iconName, size = RaftIconTileSize.Md.icon)
            }
        },
        trailing = {
            RaftListRowValue(row.size, maxWidth = 96.dp)
            if (row.clearLabel.isNotBlank()) {
                RaftIconButton(
                    onClick = onClear,
                    tooltip = row.clearLabel,
                    variant = RaftButtonVariant.Ghost,
                    enabled = row.clearEnabled,
                    loading = row.clearInProgress
                ) { RaftDownloadIcon(RaftLucideIconName.Trash2) }
            } else if (actionSlot) {
                Spacer(Modifier.size(RaftStorageRowActionSize))
            }
        }
    )
}

/** The ghost icon button's box (raft-ui `icon-md`). */
private val RaftStorageRowActionSize = 32.dp

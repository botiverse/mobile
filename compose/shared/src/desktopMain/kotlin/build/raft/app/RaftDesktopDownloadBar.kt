package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.downloads.RaftDownloadBarState
import build.raft.app.downloads.RaftDownloadCenterProcess
import build.raft.app.downloads.raftDownloadBarState
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A saved download's bar stays up this long unless the user closes it first. */
private const val RaftDesktopDownloadBarSavedMillis = 8_000L

/**
 * The window's download bar (Compose 版): a download started on a file page keeps running after
 * the page closes (the app-scoped download centre), and this says how it is going wherever the
 * user went: the file and its progress (or "2 downloads"), then "Saved to Downloads" with Show in
 * Finder, or the failure with Retry. Drawn by the desktop host over every page.
 */
@Composable
fun RaftDesktopDownloadBar(modifier: Modifier = Modifier) {
    val center = remember { RaftDownloadCenterProcess.center }
    val since = remember { Clock.System.now().toEpochMilliseconds() }
    val snapshot by center.state.collectAsState()
    var dismissed by remember { mutableStateOf(emptyMap<String, Long>()) }
    val state = raftDownloadBarState(snapshot.entries, since, dismissed) ?: return
    fun dismiss(id: String) {
        val entry = snapshot.entries.firstOrNull { it.id == id } ?: return
        dismissed = dismissed + (id to entry.updatedAtMillis)
    }
    if (state is RaftDownloadBarState.Saved) {
        LaunchedEffect(state) {
            delay(RaftDesktopDownloadBarSavedMillis)
            dismiss(state.entryId)
        }
    }
    val host = remember { raftPlatformSavedFileHost() }
    val scope = rememberCoroutineScope()
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftCard(modifier.padding(RaftSpacing.Md).widthIn(max = 380.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon =
                    when (state) {
                        is RaftDownloadBarState.Downloading -> RaftLucideIconName.Download
                        is RaftDownloadBarState.Saved -> RaftLucideIconName.CircleCheck
                        is RaftDownloadBarState.Failed -> RaftLucideIconName.TriangleAlert
                    }
                RaftDownloadIcon(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RaftSpacing.Xxs)) {
                    val title =
                        when (state) {
                            is RaftDownloadBarState.Downloading ->
                                state.fileName ?: stringResource(Res.string.download_bar_count, state.count)
                            is RaftDownloadBarState.Saved -> state.fileName
                            is RaftDownloadBarState.Failed -> state.fileName
                        }
                    RaftText(title, style = typography.callout, color = colors.foreground, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    val detail =
                        when (state) {
                            is RaftDownloadBarState.Downloading ->
                                stringResource(Res.string.file_preview_download_downloading) +
                                    (state.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
                            is RaftDownloadBarState.Saved ->
                                state.localUri?.let { host?.folderName(it) }
                                    ?.let { stringResource(Res.string.file_preview_saved_to_folder, it) }
                                    ?: stringResource(Res.string.file_preview_private_download_saved)
                            is RaftDownloadBarState.Failed -> stringResource(Res.string.file_preview_download_failed)
                        }
                    RaftText(detail, style = typography.footnote, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (state) {
                    is RaftDownloadBarState.Saved ->
                        if (host != null && state.localUri != null && host.exists(state.localUri)) {
                            RaftButton(
                                text =
                                    stringResource(
                                        if (host.revealsInFinder) {
                                            Res.string.file_preview_action_show_in_finder
                                        } else {
                                            Res.string.file_preview_action_show_in_folder
                                        }
                                    ),
                                onClick = { scope.launch { host.reveal(state.localUri) } },
                                variant = RaftButtonVariant.Ghost,
                            )
                        }
                    is RaftDownloadBarState.Failed ->
                        if (center.canRetry(state.entryId)) {
                            RaftButton(
                                text = stringResource(Res.string.common_retry),
                                onClick = { center.retry(state.entryId) },
                                variant = RaftButtonVariant.Ghost,
                            )
                        }
                    is RaftDownloadBarState.Downloading -> Unit
                }
                val closeId =
                    when (state) {
                        is RaftDownloadBarState.Saved -> state.entryId
                        is RaftDownloadBarState.Failed -> state.entryId
                        // A running download is stopped from its file page or Settings › Storage.
                        is RaftDownloadBarState.Downloading -> null
                    }
                if (closeId != null) {
                    RaftIconButton(
                        onClick = { dismiss(closeId) },
                        tooltip = stringResource(Res.string.common_dismiss),
                        variant = RaftButtonVariant.Ghost,
                    ) {
                        RaftDownloadIcon(RaftLucideIconName.X, size = 16.dp)
                    }
                }
            }
            if (state is RaftDownloadBarState.Downloading) {
                RaftProgressBar(value = state.fraction, modifier = Modifier.fillMaxWidth().padding(top = RaftSpacing.Sm))
            }
        }
    }
}

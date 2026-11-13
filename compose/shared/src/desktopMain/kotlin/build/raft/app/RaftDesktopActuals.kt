package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.SharedAuthDataSource
import java.awt.Desktop
import java.io.File
import java.net.URI
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import java.util.UUID
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun logPixelAvatarDrawCache(message: String) = Unit

internal actual suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource): Boolean = false

internal actual fun raftPlatformTextMotion(): TextMotion? = null

internal actual fun raftPlatformLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: RaftPlatformLogHint,
) {
    if (level == RaftLogLevel.Debug && System.getProperty("raft.desktop.debugLogs") != "true") return
    val stream = if (level == RaftLogLevel.Error || level == RaftLogLevel.Warn) System.err else System.out
    stream.println("${level.wireValue.uppercase()} $tag: $message")
    // The packaged app's stdout goes nowhere: the log file is what a bug report can attach.
    RaftDesktopFileLog.write(level.wireValue, tag, message)
}

internal actual suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean = true

/** Selectable text without a native text view: Compose's own selection. */
@Composable
actual fun RaftNativeSelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier,
    selection: RaftNativeTextSelection?,
) {
    val selectionState = remember { SelectionState() }
    DisposableEffect(selection, selectionState) {
        selection?.reader = { selectionState.selectedTexts.joinToString("\n") { it.text } }
        onDispose { selection?.reader = null }
    }
    SelectionContainer(selectionState, modifier) { BasicText(text, style = style) }
}

// TODO(desktop): no PDF renderer yet; the preview reports the file as unreadable.
@Composable
internal actual fun RaftPdfDocumentView(
    path: String,
    onPageChanged: (currentPage: Int, pageCount: Int) -> Unit,
    onError: (RaftPdfDocumentError) -> Unit,
    modifier: Modifier,
    bottomInset: Dp,
) {
    LaunchedEffect(path) { onError(RaftPdfDocumentError.Corrupt) }
    Box(modifier.fillMaxSize())
}

/**
 * Content this desktop app can't show inline (a video it can't play, web content it can't host):
 * no line on why, just "Open in browser" for that content ([target], a URL or a file). The case is
 * logged ([kind]) so it shows up in diagnostics. With nothing to open, the area stays empty.
 */
@Composable
internal fun RaftDesktopOpenInBrowser(target: String?, kind: String, modifier: Modifier) {
    val uri = remember(target) { raftDesktopBrowseUri(target) }
    LaunchedEffect(kind, uri) {
        raftLogInfo(RAFT_DESKTOP_OPEN_IN_BROWSER_TAG, "desktop_open_in_browser_offered kind=$kind hasTarget=${uri != null}")
    }
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        if (uri != null) {
            RaftButton(
                text = stringResource(Res.string.file_preview_action_open_in_browser),
                onClick = {
                    val opened =
                        runCatching {
                            val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
                            desktop?.browse(uri) != null
                        }.getOrDefault(false)
                    raftLogInfo(RAFT_DESKTOP_OPEN_IN_BROWSER_TAG, "desktop_open_in_browser kind=$kind opened=$opened")
                },
                trailingIcon = { RaftSlotIcon(RaftLucideIconName.ExternalLink) },
            )
        }
    }
}

private const val RAFT_DESKTOP_OPEN_IN_BROWSER_TAG = "RaftDesktop"

/**
 * What "Open in browser" opens for [target]: only an http(s) URL, the desktop openUrl policy's web
 * links. A file, a plain path or any other scheme gets no button (null), never Desktop.browse.
 */
fun raftDesktopBrowseUri(target: String?): URI? =
    runCatching {
        val uri = URI(target?.trim().orEmpty())
        uri.takeIf { (it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)) && !it.host.isNullOrBlank() }
    }.getOrNull()

@Composable
internal actual fun rememberRaftPngFileWriter(): RaftPngFileWriter =
    remember {
        RaftPngFileWriter { image, namePrefix ->
            val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
            val bytes =
                try {
                    skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes
                } finally {
                    skiaImage.close()
                }
            if (bytes == null || bytes.isEmpty()) return@RaftPngFileWriter null
            val file = File(RaftDesktopPaths.snapshotsDir, "$namePrefix${UUID.randomUUID()}.png")
            file.writeBytes(bytes)
            file.toURI().toString()
        }
    }

internal actual val raftWebViewNeedsHostBottomInsets: Boolean = false

internal actual val raftWebViewDesktopModeViaViewportMeta: Boolean = false

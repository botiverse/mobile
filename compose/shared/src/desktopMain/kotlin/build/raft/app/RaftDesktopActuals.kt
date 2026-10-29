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
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.SharedAuthDataSource
import java.io.File
import java.util.UUID
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun logPixelAvatarDrawCache(message: String) = Unit

internal actual suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource) = Unit

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

// TODO(desktop): no video player yet; shows a notice instead of the video.
@Composable
internal actual fun RaftVideoPlayer(
    source: String,
    playControl: VideoPlayControl,
    seekRequestId: Int?,
    seekTimeMs: Int,
    muted: Boolean,
    rate: Float,
    onPlayStateChanged: (PlayState) -> Unit,
    onPlayTimeChanged: (currentMs: Int, totalMs: Int) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier,
) {
    RaftDesktopUnsupportedNotice("Video playback is not available on desktop yet.", modifier)
}

// TODO(desktop): no embedded web view yet (sandboxed app views, feedback).
@Composable
internal actual fun RaftSandboxWebViewHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    RaftDesktopUnsupportedNotice("Web content is not available on desktop yet.", modifier)
}

@Composable
private fun RaftDesktopUnsupportedNotice(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { BasicText(text) }
}

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
            val dir = File(RaftDesktopPaths.cacheDir, "raft_snapshots").apply { mkdirs() }
            val file = File(dir, "$namePrefix${UUID.randomUUID()}.png")
            file.writeBytes(bytes)
            file.toURI().toString()
        }
    }

internal actual val raftWebViewNeedsHostBottomInsets: Boolean = false

package build.raft.app.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/** Web WeChatQrCode `size-48`: the frame's outer size. */
internal val WeChatQrCodeSize: Dp = 192.dp

/** Web `p-1` inside the frame. */
private val WeChatQrCodeInset: Dp = 4.dp

/** Web `encode(content, { border: 2 })`: two modules of quiet zone round the code. */
private const val WeChatQrQuietModules = 2

/** qrose draws each module 10 units wide at its intrinsic size (QrCodePainter.intrinsicSize). */
private const val QrosePainterUnitsPerModule = 10f

/**
 * The QR code WeChat scans (web WeChatQrCode.tsx), from the start's `qrContent`, shown as is; null
 * while it is being made (the same empty frame, so nothing jumps when the code arrives).
 *
 * Paper under ink in both themes (`bg-sticker-paper text-sticker-ink`, a sticker never inverts):
 * dark modules on white in dark mode too, since scanners need the contrast. A 1px hairline frame
 * with the sticker radius, 4dp of padding, then the code with two modules of quiet zone, as web
 * draws it. No logo, square modules and finder patterns (qrose's defaults).
 */
@Composable
internal fun WeChatQrCode(content: String?, label: String, modifier: Modifier = Modifier) {
    val shape = RaftTheme.shapes.sticker
    Box(
        modifier
            .size(WeChatQrCodeSize)
            .clip(shape)
            .background(RaftUiPalette.stickerPaper)
            .border(RaftStroke.Hairline, RaftTheme.colors.lineHairline, shape)
            .padding(RaftStroke.Hairline + WeChatQrCodeInset)
            .testTag(if (content == null) "wechat-qr-placeholder" else "wechat-qr"),
    ) {
        if (content != null) {
            val painter = rememberQrCodePainter(data = content, darkBrush = QrBrush.solid(RaftUiPalette.stickerInk))
            val modules = (painter.intrinsicSize.width / QrosePainterUnitsPerModule).coerceAtLeast(1f)
            val inner = WeChatQrCodeSize - (RaftStroke.Hairline + WeChatQrCodeInset) * 2
            val quiet = inner * (WeChatQrQuietModules / (modules + 2 * WeChatQrQuietModules))
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(quiet).semantics { contentDescription = label },
            )
        }
    }
}

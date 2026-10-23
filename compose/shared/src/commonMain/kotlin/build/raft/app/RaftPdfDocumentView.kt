package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Native PDF viewer over a local file ([path] is an absolute file-system path in the attachment
 * cache): a vertical, continuous column of pages with pinch-zoom and pan.
 *
 * Android: `android.graphics.pdf.PdfRenderer`; only the pages around the viewport are rendered,
 * as bitmaps at the current zoom, inside a bounded cache (see `RaftPdfRenderWindow`).
 * iOS: PDFKit's `PDFView` hosted through `UIKitView`, like `RaftSandboxWebView`.
 *
 * [onPageChanged] reports the zero-based page at the viewport centre and the page count, each time
 * either changes. [onError] reports once when the document cannot be opened. A new [path] opens a
 * fresh document. [bottomInset] is the system bar / home indicator height the viewer is drawn
 * under: the last page can scroll clear of it. Pages never draw outside the viewer's bounds.
 */
@Composable
internal expect fun RaftPdfDocumentView(
    path: String,
    onPageChanged: (currentPage: Int, pageCount: Int) -> Unit,
    onError: (RaftPdfDocumentError) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
)

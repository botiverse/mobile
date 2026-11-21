@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.UIKitView
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView
import platform.PDFKit.PDFViewPageChangedNotification
import platform.PDFKit.kPDFDisplayDirectionVertical
import platform.PDFKit.kPDFDisplaySinglePageContinuous
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.darwin.NSObjectProtocol

/**
 * PDFKit's `PDFView` hosted through [UIKitView], the same interop the HTML preview uses for its
 * WKWebView ([RaftSandboxWebViewHost]). PDFView owns rendering, tiling, memory, pinch-zoom and
 * scrolling; this host only opens the document, reports the page under the viewport, and maps an
 * unopenable document (nil, locked, no pages) to [RaftPdfDocumentError].
 */
@Composable
internal actual fun RaftPdfDocumentView(
    path: String,
    onPageChanged: (currentPage: Int, pageCount: Int) -> Unit,
    onError: (RaftPdfDocumentError) -> Unit,
    modifier: Modifier,
    bottomInset: Dp,
) {
    val pageCallback by rememberUpdatedState(onPageChanged)
    val errorCallback by rememberUpdatedState(onError)
    key(path) {
        val host = remember { RaftIosPdfViewHost { current, count -> pageCallback(current, count) } }
        LaunchedEffect(host, path) {
            when (val outcome = withContext(Dispatchers.IO) { raftIosOpenPdfDocument(path) }) {
                is RaftIosPdfOpenResult.Opened -> host.attach(outcome.document)
                is RaftIosPdfOpenResult.Failed -> errorCallback(outcome.error)
            }
        }
        UIKitView(
            factory = { host.view },
            // The native view stops above the home indicator, so the last page scrolls clear of it.
            modifier = modifier.padding(bottom = bottomInset),
            update = {},
            onRelease = { host.dispose() },
        )
    }
}

private sealed interface RaftIosPdfOpenResult {
    class Opened(val document: PDFDocument) : RaftIosPdfOpenResult

    class Failed(val error: RaftPdfDocumentError) : RaftIosPdfOpenResult
}

/**
 * Validates the PDF header and constructs [PDFDocument] off the main thread so opening a large
 * local PDF does not block UIKit / Compose frames.
 */
private fun raftIosOpenPdfDocument(path: String): RaftIosPdfOpenResult {
    // Only a file that looks like a PDF reaches PDFKit; a parse failure still maps to Corrupt.
    if (!raftIosFileHasPdfHeader(path)) {
        return RaftIosPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
    }
    val document =
        runCatching { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }.getOrNull()
            ?: return RaftIosPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
    return when {
        document.isLocked -> RaftIosPdfOpenResult.Failed(RaftPdfDocumentError.PasswordProtected)
        document.pageCount == 0uL -> RaftIosPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
        else -> RaftIosPdfOpenResult.Opened(document)
    }
}

private class RaftIosPdfViewHost(
    private val onPage: (Int, Int) -> Unit,
) {
    private var document: PDFDocument? = null
    private var disposed: Boolean = false

    val view: PDFView =
        PDFView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).also { pdfView ->
            pdfView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            pdfView.displayMode = kPDFDisplaySinglePageContinuous
            pdfView.displayDirection = kPDFDisplayDirectionVertical
            pdfView.displaysPageBreaks = true
            pdfView.autoScales = true
            // Pages scrolled past the top must not paint over the page header.
            pdfView.clipsToBounds = true
        }

    private var observer: NSObjectProtocol? =
        NSNotificationCenter.defaultCenter.addObserverForName(
            PDFViewPageChangedNotification,
            view,
            NSOperationQueue.mainQueue,
        ) { _ -> reportPage() }
    private var lastReported: Pair<Int, Int>? = null

    fun attach(loadedDocument: PDFDocument) {
        if (disposed) return
        document = loadedDocument
        view.document = loadedDocument
        reportPage()
    }

    fun reportPage() {
        if (disposed) return
        val doc = document ?: return
        val count = doc.pageCount.toInt()
        if (count <= 0) return
        val current = view.currentPage?.let { doc.indexForPage(it).toInt() } ?: 0
        val next = current.coerceIn(0, (count - 1).coerceAtLeast(0)) to count
        if (next == lastReported) return
        lastReported = next
        onPage(next.first, next.second)
    }

    fun dispose() {
        disposed = true
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        document = null
        view.document = null
    }
}

/** Reads only the first KiB of the file; false when it cannot be read. */
private fun raftIosFileHasPdfHeader(path: String): Boolean {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return false
    val data =
        try {
            handle.readDataOfLength(1024uL)
        } finally {
            handle.closeFile()
        }
    val length = minOf(data.length.toInt(), 1024)
    if (length <= 0) return false
    val head = data.bytes?.reinterpret<ByteVar>()?.readBytes(length) ?: return false
    return raftPdfHasHeader(head)
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
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
) {
    val pageCallback by rememberUpdatedState(onPageChanged)
    val errorCallback by rememberUpdatedState(onError)
    key(path) {
        val host = remember { RaftIosPdfViewHost(path) { current, count -> pageCallback(current, count) } }
        LaunchedEffect(host) {
            val error = host.openError
            if (error != null) errorCallback(error) else host.reportPage()
        }
        UIKitView(
            factory = { host.view },
            modifier = modifier,
            update = {},
            onRelease = { host.dispose() },
        )
    }
}

private class RaftIosPdfViewHost(
    path: String,
    private val onPage: (Int, Int) -> Unit,
) {
    // Only a file that looks like a PDF reaches PDFKit; a parse failure still maps to Corrupt.
    private val document: PDFDocument? =
        if (raftIosFileHasPdfHeader(path)) {
            runCatching { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }.getOrNull()
        } else {
            null
        }

    /** Null when the document opened with at least one page. */
    val openError: RaftPdfDocumentError? =
        when {
            document == null -> RaftPdfDocumentError.Corrupt
            document.isLocked -> RaftPdfDocumentError.PasswordProtected
            document.pageCount == 0uL -> RaftPdfDocumentError.Corrupt
            else -> null
        }

    val view: PDFView =
        PDFView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).also { pdfView ->
            pdfView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            pdfView.displayMode = kPDFDisplaySinglePageContinuous
            pdfView.displayDirection = kPDFDisplayDirectionVertical
            pdfView.displaysPageBreaks = true
            pdfView.autoScales = true
            if (openError == null) pdfView.document = document
        }

    private var observer: NSObjectProtocol? =
        NSNotificationCenter.defaultCenter.addObserverForName(
            PDFViewPageChangedNotification,
            view,
            NSOperationQueue.mainQueue,
        ) { _ -> reportPage() }
    private var lastReported: Pair<Int, Int>? = null

    fun reportPage() {
        val doc = document ?: return
        if (openError != null) return
        val count = doc.pageCount.toInt()
        val current = view.currentPage?.let { doc.indexForPage(it).toInt() } ?: 0
        val next = current.coerceIn(0, (count - 1).coerceAtLeast(0)) to count
        if (next == lastReported) return
        lastReported = next
        onPage(next.first, next.second)
    }

    fun dispose() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        view.document = null
    }
}

/** Reads only the first KiB of the file; false when it cannot be read. */
private fun raftIosFileHasPdfHeader(path: String): Boolean {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return false
    val data = handle.readDataOfLength(1024uL)
    handle.closeFile()
    val length = minOf(data.length.toInt(), 1024)
    if (length <= 0) return false
    val head = data.bytes?.reinterpret<ByteVar>()?.readBytes(length) ?: return false
    return raftPdfHasHeader(head)
}

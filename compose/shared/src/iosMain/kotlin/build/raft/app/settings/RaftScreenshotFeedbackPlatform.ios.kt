@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import build.raft.app.data.PlatformFile
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.*
import platform.darwin.NSObjectProtocol

private const val IOS_SCREENSHOT_FEEDBACK_DIR_NAME = "screenshot-feedback"
private const val IOS_SCREENSHOT_FEEDBACK_MAX_EDGE_PX = 1600.0
private const val IOS_SCREENSHOT_FEEDBACK_JPEG_QUALITY = 0.85

internal actual fun raftDeleteScreenshotFeedbackTempFile(fileRef: String) {
    val trimmed = fileRef.trim()
    if (trimmed.isEmpty()) return
    val url =
        if (trimmed.startsWith("/")) {
            NSURL.fileURLWithPath(trimmed)
        } else {
            NSURL.URLWithString(trimmed)
        } ?: return
    val path = url.path ?: return
    if (!path.contains("/$IOS_SCREENSHOT_FEEDBACK_DIR_NAME/")) return
    runCatching {
        NSFileManager.defaultManager.removeItemAtURL(url, null)
    }
}

@Composable
internal actual fun RaftPlatformScreenshotFeedbackObserverEffect(
    enabled: Boolean,
    pageContextProvider: () -> RaftScreenshotFeedbackPageContext,
) {
    if (!enabled) return
    val viewController = LocalUIViewController.current
    val latestPageContextProvider by rememberUpdatedState(pageContextProvider)
    val scope = rememberCoroutineScope()

    DisposableEffect(enabled, viewController) {
        val center = NSNotificationCenter.defaultCenter
        var screenshotObserver: NSObjectProtocol? = null

        fun registerScreenshotObserver() {
            if (screenshotObserver != null) return
            screenshotObserver =
                center.addObserverForName(
                    name = UIApplicationUserDidTakeScreenshotNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                ) { _ ->
                    if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                        return@addObserverForName
                    }
                    if (!RaftScreenshotFeedbackRuntime.isCurrentlyEligible(buildAlphaEnabled = false)) {
                        return@addObserverForName
                    }
                    val window = resolveActiveIosWindow(viewController.view.window)
                    val capturedImage = window?.let(::captureIosWindowSnapshotOnMain)
                    val pageContext = latestPageContextProvider()
                    scope.launch {
                        val screenshotFile =
                            if (capturedImage != null) {
                                withContext(Dispatchers.Default) {
                                    encodeAndStoreIosScreenshotJpeg(capturedImage)
                                }
                            } else {
                                null
                            }
                        RaftScreenshotFeedbackRuntime.onScreenshotCaptured(
                            screenshot = screenshotFile,
                            pageContext = pageContext,
                            buildAlphaEnabled = false,
                        )
                    }
                }
        }

        fun unregisterScreenshotObserver() {
            screenshotObserver?.let { center.removeObserver(it) }
            screenshotObserver = null
        }

        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) {
            registerScreenshotObserver()
        }
        val activeObserver =
            center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                registerScreenshotObserver()
            }
        val backgroundObserver =
            center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                unregisterScreenshotObserver()
            }

        onDispose {
            unregisterScreenshotObserver()
            center.removeObserver(activeObserver)
            center.removeObserver(backgroundObserver)
        }
    }
}

@Suppress("DEPRECATION")
private fun resolveActiveIosWindow(controllerWindow: UIWindow?): UIWindow? =
    controllerWindow ?: UIApplication.sharedApplication.keyWindow

private fun captureIosWindowSnapshotOnMain(window: UIWindow): UIImage? =
    runCatching {
        val (width, height) = window.bounds.useContents { size.width to size.height }
        if (width <= 0.0 || height <= 0.0) return null
        val longestPt = maxOf(width, height)
        val screenScale = UIScreen.mainScreen.scale.takeIf { it > 0.0 } ?: 1.0
        val cappedScale = minOf(screenScale, IOS_SCREENSHOT_FEEDBACK_MAX_EDGE_PX / longestPt).coerceAtLeast(1.0)
        val format =
            UIGraphicsImageRendererFormat().apply {
                scale = cappedScale
                opaque = true
            }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(width, height), format = format)
        val drawRect = CGRectMake(0.0, 0.0, width, height)
        renderer.imageWithActions { _ ->
            window.drawViewHierarchyInRect(drawRect, afterScreenUpdates = false)
        }
    }.getOrNull()

private fun encodeAndStoreIosScreenshotJpeg(image: UIImage): PlatformFile? =
    runCatching {
        val jpegData = UIImageJPEGRepresentation(image, IOS_SCREENSHOT_FEEDBACK_JPEG_QUALITY) ?: return null
        val sizeBytes = jpegData.length.toLong()
        if (sizeBytes <= 0L) return null
        val dir = NSTemporaryDirectory().trimEnd('/') + "/" + IOS_SCREENSHOT_FEEDBACK_DIR_NAME
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val uuid = NSUUID().UUIDString
        val path = "$dir/$uuid.jpg"
        if (!jpegData.writeToFile(path, atomically = true)) return null
        val fileUrl = NSURL.fileURLWithPath(path).absoluteString ?: return null
        PlatformFile(
            id = "screenshot-$uuid",
            fileName = "screenshot-${uuid.take(8).lowercase()}.jpg",
            mimeType = "image/jpeg",
            sizeBytes = sizeBytes,
            previewUri = fileUrl,
            fileRef = fileUrl,
            source = "screenshot_feedback",
        )
    }.getOrNull()

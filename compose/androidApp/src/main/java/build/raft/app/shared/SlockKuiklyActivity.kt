package build.raft.app.shared

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Base64InputStream
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.Log
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import build.raft.app.R
import build.raft.app.MainActivity
import build.raft.app.BuildConfig
import build.raft.app.androidExternalBrowserIntent
import build.raft.app.data.SlockDesktopShortcutContract
import build.raft.app.FeedbackDiagnosticsBinding
import build.raft.app.FeedbackDiagnosticsFailureReason
import build.raft.app.FeedbackDiagnosticsLifecycle
import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.SlockHostStrings
import build.raft.app.releaseSlockShellStoreScope
import build.raft.app.selectExternalWebHandlerPackage
import build.raft.app.core.data.installAndroidAppScopePlatformFileResolver
import build.raft.app.core.data.auth.GoogleSocialAuthGateway
import build.raft.app.core.data.auth.GoogleSocialAuthResult
import build.raft.app.core.data.auth.completeGoogleSocialAuth
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidApiServerEnvironment
import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingRequest
import build.raft.app.core.data.auth.AndroidMobileOAuthPendingStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.installAndroidAppScopeSharedKeyValueStores
import build.raft.app.core.data.diagnostics.SlockAndroidLocalDiagnostics
import build.raft.app.core.data.diagnostics.SlockAuthCriticalJournal
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.core.data.diagnostics.SlockPerformanceDiagnostics
import build.raft.app.core.data.storage.StorageClearTarget
import build.raft.app.core.data.storage.StorageManagementRepository
import build.raft.app.core.data.storage.StorageManagementSnapshot
import build.raft.app.core.data.storage.SLOCK_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME
import build.raft.app.widget.SlockActivityWidgetProvider
import build.raft.app.widget.SlockAgentStatusWidgetProvider
import build.raft.app.data.SlockInProcessNotifyModule
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.SLOCK_APP_UPDATE_EVENT
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.model.MemberKind
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.data.SlockAndroidSqlDatabaseContext
import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import build.raft.app.data.parseSlockAvatarMountedRenderModel
import build.raft.app.network.SharedBinaryDownloader
import build.raft.app.network.SharedDownloadedBytes
import build.raft.app.network.AttachmentDownloadObserver
import build.raft.app.network.AttachmentDownloadProgress
import build.raft.app.network.AttachmentDownloadResult
import build.raft.app.network.AttachmentDownloadCoordinator
import build.raft.app.network.binaryDownloadUrlDiagnostics
import build.raft.app.network.updateSlockNetworkKmmRecoveryEnvironment
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMutationResult
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideSnapshot
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.network.sync.ProcessReadStateV1DiagnosticOverride
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideMode
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideSnapshot
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRFontAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRUncaughtExceptionHandlerAdapter
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.performace.KRMonitorType
import com.tencent.kuikly.core.render.android.performace.KRPerformanceData
import com.tencent.kuikly.core.render.android.performace.launch.KRLaunchData
import com.tencent.kuikly.core.render.android.expand.component.KRTextAreaView
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView
import com.caverock.androidsvg.SVG
import build.hands.update.HandsDeviceId
import build.hands.update.HandsFeedback
import build.hands.update.HandsUpdateEventListener
import build.hands.update.UpdateChecker
import build.hands.update.internal.HandsClient
import build.hands.update.models.UpdateCheckResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val THREAD_CAMERA_CAPTURE_CACHE_DIR_NAME = "slock-camera-captures"
internal const val THREAD_ATTACHMENT_IMAGE_MAX_EDGE_PX = 2048
internal const val THREAD_ATTACHMENT_IMAGE_JPEG_QUALITY = 82
internal const val THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR = "attachment_image_compression_failed"
private val THREAD_ATTACHMENT_IMAGE_EXTENSIONS =
        setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp")
private val THREAD_ATTACHMENT_VIDEO_EXTENSIONS =
        setOf("3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")

private class ThreadAttachmentImageCompressionException(
        message: String,
        cause: Throwable? = null,
) : IOException(message, cause)

private class ThreadImageCompressionCancelledException : IOException("cancelled")

private const val THREAD_IMAGE_COMPRESSION_CACHE_DIR_NAME = SLOCK_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME
private const val THREAD_IMAGE_COMPRESSION_SOURCE = "composer_image_compressor"
private const val THREAD_IMAGE_COMPRESSION_MAX_REQUEST_ID_LENGTH = 128
private const val THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH = 256

private fun threadImageCompressionFailurePayload(): Map<String, Any> =
        mapOf(
                "code" to 400,
                "errorCode" to THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR,
                "errorBodyText" to "Unable to compress image attachment"
        )

private fun normalizedThreadImageMimeType(value: String): String =
        value.substringBefore(';').trim().lowercase(Locale.US)

private fun threadImageCompressionFileName(fileName: String): String {
    val leaf = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
    val stem = leaf.substringBeforeLast('.', leaf).ifBlank { "image" }
    val safeStem =
            stem.map { character ->
                if (
                        character.isLetterOrDigit() ||
                                character == '.' ||
                                character == '-' ||
                                character == '_' ||
                                character == ' '
                ) {
                    character
                } else {
                    '_'
                }
            }.joinToString("").trim().take(THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH)
    return "${safeStem.ifBlank { "image" }}.jpg"
}

private fun threadImageCompressionPathInside(root: File, candidate: File): Boolean {
    val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
    return candidate.canonicalPath.startsWith(rootPath)
}

private fun parseThreadImageCompressionSource(
        fileRef: String,
        roots: List<File>
): File? {
    val raw = fileRef.trim()
    if (raw.isBlank() || raw.any(Char::isISOControl)) return null
    val path =
            if (raw.startsWith("/")) {
                raw
            } else {
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
                if (
                        !uri.scheme.equals("file", ignoreCase = true) ||
                                !uri.host.isNullOrBlank() ||
                                uri.query != null ||
                                uri.fragment != null
                ) {
                    return null
                }
                uri.path ?: return null
            }
    if (path.isBlank() || path.indexOf('\u0000') >= 0) return null
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (!candidate.isFile || candidate.length() <= 0L) return null
    return candidate.takeIf { file -> roots.any { root -> threadImageCompressionPathInside(root, file) } }
}

private fun isThreadImageCompressionInput(
        fileName: String,
        mimeType: String,
        sourceFile: File
): Boolean {
    val normalizedMime = normalizedThreadImageMimeType(mimeType)
    if (!normalizedMime.startsWith("image/") || !sourceFile.isFile || sourceFile.length() <= 0L) {
        return false
    }
    val extension =
            fileName.substringAfterLast('.', "").trim().lowercase(Locale.US)
    if (extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false
    // The provider-supplied display name is not authoritative.  A mismatched tuple can carry an
    // image MIME/name while the admitted cache path is actually a movie; reject the source path
    // extension before BitmapFactory gets a chance to interpret it as an image.
    val sourceExtension = sourceFile.extension.trim().lowercase(Locale.US)
    if (sourceExtension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false
    if (extension.isNotBlank() && extension !in THREAD_ATTACHMENT_IMAGE_EXTENSIONS) {
        // A renderer snapshot may carry a synthetic/unknown extension while still declaring a
        // valid image MIME.  Known non-image extensions are rejected above; unknown ones are
        // left to BitmapFactory's decode gate.
        return true
    }
    return true
}

private fun compressThreadImageFile(
        sourceFile: File,
        outputFile: File,
        isCancelled: () -> Boolean
) {
    fun checkCancelled() {
        if (isCancelled()) throw ThreadImageCompressionCancelledException()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw ThreadAttachmentImageCompressionException("Unable to decode image attachment")
    }
    checkCancelled()
    val orientation =
            runCatching {
                ExifInterface(sourceFile.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    var decoded: Bitmap? = null
    var oriented: Bitmap? = null
    var resized: Bitmap? = null
    val partFile = File(outputFile.path + ".part")
    var committed = false
    try {
        // A cancellation may race the worker before it opens the output.  The part suffix keeps
        // that race from ever exposing a partially encoded JPEG at the final file name.
        runCatching { partFile.delete() }
        val sampleSize =
                imageCompressionSampleSizeForThreadImage(bounds.outWidth, bounds.outHeight)
        decoded =
                BitmapFactory.decodeFile(
                        sourceFile.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize }
                ) ?: throw ThreadAttachmentImageCompressionException("Unable to decode image attachment")
        checkCancelled()
        oriented = decoded
        val decodedBitmap = requireNotNull(decoded)
        val orientationMatrix = threadImageOrientationMatrix(orientation)
        if (!orientationMatrix.isIdentity) {
            oriented = Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    0,
                    decodedBitmap.width,
                    decodedBitmap.height,
                    orientationMatrix,
                    true
            )
        }
        checkCancelled()
        val orientedBitmap = requireNotNull(oriented)
        val longestEdge = max(orientedBitmap.width, orientedBitmap.height)
        val scale = THREAD_ATTACHMENT_IMAGE_MAX_EDGE_PX.toFloat() /
                longestEdge.coerceAtLeast(1).toFloat()
        resized =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                            orientedBitmap,
                            (orientedBitmap.width * scale).roundToInt().coerceAtLeast(1),
                            (orientedBitmap.height * scale).roundToInt().coerceAtLeast(1),
                            true
                    )
                } else {
                    orientedBitmap
                }
        checkCancelled()
        val resizedBitmap = requireNotNull(resized)
        val parent = outputFile.parentFile
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw ThreadAttachmentImageCompressionException("Attachment cache unavailable")
        }
        FileOutputStream(partFile).use { stream ->
            if (!resizedBitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            THREAD_ATTACHMENT_IMAGE_JPEG_QUALITY,
                            stream
                    )
            ) {
                throw ThreadAttachmentImageCompressionException("Unable to encode image attachment")
            }
        }
        checkCancelled()
        if (!partFile.isFile || partFile.length() <= 0L) {
            throw ThreadAttachmentImageCompressionException("Unable to encode image attachment")
        }
        checkCancelled()
        if (!partFile.renameTo(outputFile)) {
            throw ThreadAttachmentImageCompressionException("Unable to commit image attachment")
        }
        committed = true
        checkCancelled()
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw ThreadAttachmentImageCompressionException("Unable to encode image attachment")
        }
    } catch (throwable: Throwable) {
        if (throwable is ThreadImageCompressionCancelledException ||
                        throwable is ThreadAttachmentImageCompressionException
        ) {
            throw throwable
        }
        throw ThreadAttachmentImageCompressionException(
                throwable.message ?: "Unable to compress image attachment",
                throwable
        )
    } finally {
        if (!committed || isCancelled()) {
            runCatching { partFile.delete() }
            runCatching { outputFile.delete() }
        }
        if (resized !== oriented) resized?.recycleIfNeededForThreadImage()
        if (oriented !== decoded) oriented?.recycleIfNeededForThreadImage()
        decoded?.recycleIfNeededForThreadImage()
    }
}

private fun imageCompressionSampleSizeForThreadImage(sourceWidth: Int, sourceHeight: Int): Int {
    var sampleSize = 1
    val sampledEdge = max(sourceWidth, sourceHeight).toLong()
    while (
            sampleSize <= Int.MAX_VALUE / 2 &&
                    sampledEdge / (sampleSize.toLong() * 2L) >= THREAD_ATTACHMENT_IMAGE_MAX_EDGE_PX
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun threadImageOrientationMatrix(orientation: Int): Matrix =
        Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }

private fun Bitmap.recycleIfNeededForThreadImage() {
    runCatching {
        if (!isRecycled) recycle()
    }
}

internal fun formatMergedSettingsDiagnosticsExportText(
        about: Map<String, Any>,
        recentLogs: String,
        hasFallbackDiagnosticsLogs: Boolean
): String =
        buildString {
            appendLine("Raft diagnostics")
            appendLine()
            appendLine("Environment")
            appendDiagnosticsExportLine("App", about.diagnosticsStringValue("appName").ifBlank { "Raft" })
            appendDiagnosticsExportLine("Version", about.diagnosticsStringValue("versionLabel"))
            appendDiagnosticsExportLine("Version code", about.diagnosticsStringValue("versionCodeLabel"))
            appendDiagnosticsExportLine("Build", about.diagnosticsStringValue("buildLabel"))
            appendDiagnosticsExportLine("Source", about.diagnosticsStringValue("sourceLabel"))
            appendDiagnosticsExportLine("Package", about.diagnosticsStringValue("packageName"))
            appendDiagnosticsExportLine("Platform", about.diagnosticsStringValue("platformLabel"))
            appendDiagnosticsExportLine("SDK", about.diagnosticsStringValue("sdkLabel"))
            appendDiagnosticsExportLine("Device", about.diagnosticsStringValue("deviceLabel"))
            appendDiagnosticsExportLine("Model", about.diagnosticsStringValue("modelLabel"))
            appendDiagnosticsExportLine("Install time", about.diagnosticsStringValue("installTimeLabel"))
            appendDiagnosticsExportLine("Screen", about.diagnosticsStringValue("screenLabel"))
            appendDiagnosticsExportLine("Density", about.diagnosticsStringValue("densityLabel"))
            appendDiagnosticsExportLine("Font scale", about.diagnosticsStringValue("fontScaleLabel"))
            appendDiagnosticsExportLine("Session", about.diagnosticsStringValue("sessionStatusLabel"))
            appendDiagnosticsExportLine("Signed in user", about.diagnosticsStringValue("signedInUserLabel"))
            appendDiagnosticsExportLine("Selected server", about.diagnosticsStringValue("selectedServerLabel"))
            appendDiagnosticsExportLine("Socket", about.diagnosticsStringValue("socketStatusLabel"))
            appendLine()
            appendLine("App diagnostics")
            appendDiagnosticsExportLine("TDLog", "disabled")
            appendDiagnosticsExportLine(
                    "App JSONL log",
                    if (hasFallbackDiagnosticsLogs) {
                        "enabled"
                    } else {
                        "pending"
                    }
            )
            appendDiagnosticsExportLine("Attachment", "App-owned diagnostics JSONL files are included in this zip when available.")
            appendLine()
            appendLine("Recent logs")
            appendLine()
            appendLine(recentLogs.trim().ifBlank { "No local diagnostics log files are currently available." })
        }

internal fun settingsDiagnosticsEnvironmentSection(exportText: String): String {
    val environmentStart = exportText.indexOf("Environment")
    if (environmentStart < 0) return ""
    val sectionStart = environmentStart + "Environment".length
    val diagnosticsStart = exportText.indexOf("\nApp diagnostics", sectionStart)
    val sectionEnd = if (diagnosticsStart >= 0) diagnosticsStart else exportText.length
    return exportText.substring(sectionStart, sectionEnd).trim()
}

internal fun settingsFeedbackShareEnvironmentSection(exportText: String): String {
    val allowedLabels =
        setOf(
            "App",
            "Version",
            "Version code",
            "Build",
            "Source",
            "Platform",
            "SDK",
            "Device",
            "Model"
        )
    return settingsDiagnosticsEnvironmentSection(exportText)
        .lineSequence()
        .filter { line -> line.substringBefore(":").trim() in allowedLabels }
        .joinToString("\n")
}

internal fun handsFeedbackAttachmentNames(attachmentNames: List<String>): List<String> =
    attachmentNames
        .asSequence()
        .map { name ->
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\r\\n]+"), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
        .toList()

internal fun handsFeedbackAttachmentBlock(attachmentNames: List<String>): String? {
    val normalizedNames = handsFeedbackAttachmentNames(attachmentNames)
    if (normalizedNames.isEmpty()) return null
    return (listOf("attachments:") + normalizedNames).joinToString("\n")
}

internal fun formatHandsFeedbackShareText(
    ticketId: String,
    description: String,
    environment: String,
    utcTime: String,
    localTime: String,
    feedbackAppSlug: String,
    feedbackChannel: String,
    updateRoute: HandsUpdateRoute,
    deviceId: String,
    attachmentNames: List<String>
): String =
    buildString {
        append("Hands feedback ").append(ticketId.take(8))
        appendLine()
        append("Ticket: ").append(ticketId)
        appendLine()
        handsFeedbackAttachmentBlock(attachmentNames)?.let { block -> appendLine(block) }
        append("Submitted: ").append(utcTime).append(" (local ").append(localTime).append(")")
        appendLine()
        append("Hands feedback route: slug=").append(feedbackAppSlug)
            .append(", channel=").append(feedbackChannel)
        appendLine()
        append("SlockHandsUpdate request: mode=").append(updateRoute.mode)
            .append(", slug=").append(updateRoute.appSlug)
            .append(", channel=").append(updateRoute.channel)
            .append(", device_id=").append(deviceId.ifBlank { "--" })
        appendLine()
        appendLine()
        appendLine("Environment:")
        appendLine(environment.ifBlank { "--" })
        appendLine()
        appendLine()
        append("Feedback:")
        appendLine()
        append(description)
    }

internal fun formatStorageSizeLabel(
    bytes: Long,
    languageTag: String?,
    systemLocale: Locale = Locale.getDefault()
): String {
    if (bytes <= 0L) return "0 B"
    val locale =
        languageTag
            ?.takeIf { it.isNotBlank() }
            ?.let(Locale::forLanguageTag)
            ?.takeIf { it.language.isNotBlank() }
            ?: systemLocale
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val number =
        if (index == 0) {
            NumberFormat
                .getIntegerInstance(locale)
                .apply { isGroupingUsed = false }
                .format(value.toLong())
        } else {
            NumberFormat.getNumberInstance(locale).apply {
                isGroupingUsed = false
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(value)
        }
    return "$number ${units[index]}"
}

private fun Map<String, Any>.diagnosticsStringValue(key: String): String =
        this[key]?.toString().orEmpty()

private fun StringBuilder.appendDiagnosticsExportLine(label: String, value: String?) {
    append(label)
    append(": ")
    appendLine(value?.takeIf { it.isNotBlank() } ?: "--")
}

internal fun threadShareChromeHeaderHeight(width: Int): Int =
        (width * 0.12f).roundToInt().coerceIn(72, 96)

internal fun threadShareChromeCaptionTextSize(width: Int): Float =
        (width * 0.034f).coerceIn(18f, 26f)

internal fun threadShareChromeOutputHeight(width: Int, contentHeight: Int): Int =
        contentHeight.coerceAtLeast(1) + threadShareChromeHeaderHeight(width)

internal data class SlockKuiklyLoadReadiness(
    val succeeded: Boolean,
    val completedAtMillis: Long
)

internal data class SlockKuiklyRenderReadinessSnapshot(
    val createdAtMillis: Long,
    val resumedAtMillis: Long?,
    val topPageLoad: SlockKuiklyLoadReadiness?,
    val childPageLoad: SlockKuiklyLoadReadiness?,
    val hasWindowFocus: Boolean,
    val contentWidth: Int,
    val contentHeight: Int
)

private data class PendingAndroidAttachmentSaveAs(
    val operationId: String,
    val source: AndroidAttachmentContentUriSource,
    val owner: AndroidAttachmentOwnerLease,
    val attachmentId: String,
    val callback: KuiklyRenderCallback?,
    val restoredFromActivityResultState: Boolean = false
)

private fun PendingAndroidAttachmentSaveAs.toSavedState(): Bundle =
        Bundle().apply {
            putString("operationId", operationId)
            putString("sourceKind", source.kind.name)
            putString("sourceUri", source.contentUri)
            putString("mimeType", source.mimeType)
            putString("displayName", source.displayName)
            putLong("sizeBytes", source.sizeBytes)
            putString("shellInstanceId", owner.shellInstanceId)
            putString("scopeId", owner.scopeId)
            putLong("authEpoch", owner.authEpoch)
            putString("principalKey", owner.principalKey)
            putLong("generation", owner.generation)
            putString("attachmentId", attachmentId)
        }

private fun Bundle.toPendingAndroidAttachmentSaveAs(): PendingAndroidAttachmentSaveAs? =
        runCatching {
            val sourceKind = AndroidAttachmentOpenSourceKind.valueOf(checkNotNull(getString("sourceKind")))
            PendingAndroidAttachmentSaveAs(
                    operationId = checkNotNull(getString("operationId")),
                    source =
                            AndroidAttachmentContentUriSource(
                                    kind = sourceKind,
                                    contentUri = checkNotNull(getString("sourceUri")),
                                    mimeType = checkNotNull(getString("mimeType")),
                                    displayName = checkNotNull(getString("displayName")),
                                    sizeBytes = getLong("sizeBytes"),
                                    durableCache = sourceKind == AndroidAttachmentOpenSourceKind.PublicDownload
                            ),
                    owner =
                            AndroidAttachmentOwnerLease(
                                    shellInstanceId = checkNotNull(getString("shellInstanceId")),
                                    scopeId = checkNotNull(getString("scopeId")),
                                    authEpoch = getLong("authEpoch"),
                                    principalKey = checkNotNull(getString("principalKey")),
                                    generation = getLong("generation")
                            ),
                    attachmentId = checkNotNull(getString("attachmentId")),
                    callback = null,
                    restoredFromActivityResultState = true
            )
        }.getOrNull()

internal fun restoredThreadCameraCaptureFile(cacheDir: File, savedPath: String?): File? {
    val cameraDir = File(cacheDir, THREAD_CAMERA_CAPTURE_CACHE_DIR_NAME).canonicalFile
    val candidate = savedPath?.takeIf(String::isNotBlank)?.let(::File)?.canonicalFile ?: return null
    return candidate.takeIf { file ->
        file.parentFile == cameraDir &&
            file.name.startsWith("camera-") &&
            file.name.endsWith(".jpg") &&
            file.isFile
    }
}

internal fun discardRestoredThreadCameraCaptureResult(file: File): Boolean =
    !file.exists() || file.delete()

private fun PendingAndroidAttachmentSaveAs.ownerOperationIsCurrent(): Boolean =
        restoredFromActivityResultState || ProcessAndroidAttachmentOwners.admit(owner)

open class SlockKuiklyActivity :
    ComponentActivity(),
    KuiklyRenderViewBaseDelegatorDelegate,
    SlockKuiklyChildPageLoadListener,
    SlockSandboxWebViewFileChooserHost {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private val adaptivePaneHostDelegate =
        lazy {
            AndroidAdaptivePaneHost(
                activity = this,
                sendEvent = { event, data ->
                    kuiklyRenderViewDelegator.sendEvent(event, data)
                },
            )
        }
    private val adaptivePaneHost by adaptivePaneHostDelegate
    private val renderReadinessCreatedAtMillis = SystemClock.elapsedRealtime()
    @Volatile private var renderReadinessResumedAtMillis: Long? = null
    @Volatile private var renderReadinessTopPageLoad: SlockKuiklyLoadReadiness? = null
    private val renderReadinessChildLoads = ConcurrentHashMap<String, SlockKuiklyLoadReadiness>()
    private var pageSpec: SlockKuiklyPageSpec? = null
    // Instance-owned: onNewIntent may replace the Intent before this instance is destroyed.
    private var loginTransitionToken: String? = null
    private var homeColdStartSplashGate: HomeColdStartSplashGate? = null
    private var homeColdStartSplashTimeoutLogged = false
    private var homeColdStartUsesNativeHold = false
    private var homeColdStartHoldView: View? = null
    private val homeColdStartTimeoutRunnable = Runnable { handleHomeColdStartTimeout() }
    private val homeColdStartFallbackTimeoutRunnable =
        Runnable { handleHomeColdStartFallbackTimeout() }
    private var homeColdStartFirstDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var kuiklyHostAttached = false
    private var inputFontLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingThreadAttachmentUpload: PendingThreadAttachmentUpload? = null
    private var pendingThreadCameraCapture: PendingThreadCameraCapture? = null
    private var pendingAttachmentSaveAs: PendingAndroidAttachmentSaveAs? = null
    private var androidShellLaunchTarget: AndroidShellLaunchTarget? = null
    private val realtimeVisibilityOwnerId: String by lazy {
        androidShellLaunchTarget?.shellInstanceId
            ?: pageSpec?.pageData?.shellInstanceId?.trim()?.takeIf(String::isNotBlank)
            ?: "legacy-app-shell"
    }
    private val googleSocialAuthGateway = GoogleSocialAuthGateway()
    private val kuiklySystemBackCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleKuiklySystemBack()
                }
            }
    private val threadImageAttachmentPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
            registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                handleThreadAttachmentPickerResult(uris)
            }
    private val threadFileAttachmentPickerLauncher: ActivityResultLauncher<Array<String>> =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                handleThreadAttachmentPickerResult(uris, persistable = true)
            }
    private val threadCameraAttachmentLauncher: ActivityResultLauncher<Uri> =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
                handleThreadCameraCaptureResult(captured)
            }
    private val attachmentSaveAsLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleAttachmentSaveAsResult(result.resultCode, result.data?.data)
            }
    private var pendingWebViewFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val webViewFileChooserLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val callback = pendingWebViewFileChooserCallback
                pendingWebViewFileChooserCallback = null
                callback?.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }

    override fun launchSandboxWebViewFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean {
        if (pendingWebViewFileChooserCallback != null) {
            callback.onReceiveValue(null)
            return false
        }
        return runCatching {
            pendingWebViewFileChooserCallback = callback
            webViewFileChooserLauncher.launch(params.createIntent())
        }.onFailure {
            pendingWebViewFileChooserCallback = null
            callback.onReceiveValue(null)
        }.isSuccess
    }
    private val screenshotFeedbackEventGate =
            ScreenshotFeedbackEventGate(SCREENSHOT_FEEDBACK_MINIMUM_INTERVAL_MILLIS)
    private val screenshotFeedbackListeningGate = ScreenshotFeedbackListeningGate()
    private val screenshotFeedbackHideRunnable = Runnable { hideScreenshotFeedbackAction() }
    private var screenCaptureObserver: ScreenCaptureObserver? = null
    private var screenshotFeedbackActionView: View? = null
    private var screenshotFeedbackSubmissionActive = false
    private val pageName: String
        get() = resolvedPageName(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareHomeColdStartSplashIfNeeded()
        super.onCreate(savedInstanceState)
        if (!AndroidLoginTransitionRouting.observeHost(this)) {
            finish()
            return
        }
        if (pageName == PAGE_LOGIN) {
            loginTransitionToken = AndroidLoginTransitionRouting.bindLogin(
                this,
                intent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN),
            )
            if (loginTransitionToken == null) {
                finish()
                return
            }
            intent.putExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN, loginTransitionToken)
        }
        requestHighestSupportedDisplayMode()
        schedulePickedThreadAttachmentCacheCleanup()
        installHuaweiAndroid12InsetsAnimationWorkaroundIfNeeded()
        pendingAttachmentSaveAs =
                savedInstanceState
                    ?.getBundle(ATTACHMENT_SAVE_AS_STATE_KEY)
                    ?.toPendingAndroidAttachmentSaveAs()
        pendingThreadCameraCapture = savedInstanceState
            ?.getString(THREAD_CAMERA_CAPTURE_STATE_KEY)
            ?.let { restoredThreadCameraCaptureFile(applicationContext.cacheDir, it) }
            ?.let { file ->
                PendingThreadCameraCapture(
                    uri = FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.fileprovider",
                        file,
                    ),
                    file = file,
                    restoredAfterActivityRecreation = true,
                )
            }
        onBackPressedDispatcher.addCallback(this, kuiklySystemBackCallback)
        setContentView(R.layout.activity_slock_kuikly)
        prepareScreenshotFeedbackObserver()
        registerAndroidShellWindow(intent)
        setupImmersiveMode()
        setupImeInsetsRelayout()
        suppressVisualKeyboard()
        installHomeColdStartNativeHoldIfNeeded()
        if (homeColdStartUsesNativeHold) {
            attachKuiklyHostAfterHoldFirstDraw()
        } else {
            attachKuiklyHost()
        }
    }

    /**
     * High-refresh-rate support: adaptive-refresh devices (120Hz panels) cap
     * third-party windows at 60Hz unless the window explicitly requests a
     * display mode. Pick the highest-refresh [Display.Mode] that keeps the
     * current resolution so SurfaceFlinger is allowed to run this window at
     * 90/120Hz. Kuikly's `KRVsyncModule` follows `Choreographer`, which then
     * ticks at the unlocked rate. No-op below API 23 or when the panel exposes
     * a single mode.
     */
    private fun requestHighestSupportedDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val currentDisplay =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION") windowManager.defaultDisplay
                }
                        ?: return
        val currentMode = currentDisplay.mode ?: return
        val current =
                DisplayModeSpec(
                        modeId = currentMode.modeId,
                        physicalWidth = currentMode.physicalWidth,
                        physicalHeight = currentMode.physicalHeight,
                        refreshRate = currentMode.refreshRate,
                )
        val supported =
                currentDisplay.supportedModes?.map {
                    DisplayModeSpec(
                            modeId = it.modeId,
                            physicalWidth = it.physicalWidth,
                            physicalHeight = it.physicalHeight,
                            refreshRate = it.refreshRate,
                    )
                }
                        ?: return
        val bestMode = selectHighestRefreshSameResolutionMode(current, supported) ?: return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = bestMode.modeId
        window.attributes = attrs
    }

    private fun installHuaweiAndroid12InsetsAnimationWorkaroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (
            !shouldUseMainThreadInsetsAnimationRunner(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
            )
        ) {
            return
        }

        // Keep this callback installed through ViewRoot teardown. On Android 12 its presence makes
        // InsetsController select the main-thread runner instead of InsetsAnimationThread, whose
        // asynchronous cancellation can leave a queued Surface transaction behind destroySurface.
        window.decorView.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>,
                ): WindowInsets = insets
            }
        )
        SlockAppLog.w(
            "SlockWindowInsets",
            "main_thread_insets_animation_runner_enabled " +
                "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT}",
        )
    }

    private fun attachKuiklyHostAfterHoldFirstDraw() {
        val content = findViewById<View>(android.R.id.content)
        var drawObserved = false
        val listener =
            ViewTreeObserver.OnDrawListener {
                if (drawObserved) return@OnDrawListener
                drawObserved = true
                content.post {
                    removeHomeColdStartFirstDrawListener(content)
                    attachKuiklyHost()
                }
            }
        homeColdStartFirstDrawListener = listener
        content.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun attachKuiklyHost() {
        if (kuiklyHostAttached || isFinishing || isDestroyed) return
        SlockKuiklyAdapters.init(applicationContext)
        val container = findViewById<ViewGroup>(R.id.slock_kuikly_container)
        installSlockInputFontBridge(container)
        val spec = createPageSpec()
        pageSpec = spec
        SlockPerformanceDiagnostics.install(window)
        SlockPerformanceDiagnostics.updateRoute(container, spec.pageName)
        SlockPerformanceDiagnostics.updateOperation(container, "kuikly_attach")
        kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, spec.pageData.toKuiklyMap())
        kuiklyHostAttached = true
        adaptivePaneHost.start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            kuiklyRenderViewDelegator.onResume()
            reportRealtimeVisibility(AndroidHostVisibility.Visible, "deferred_host_attach")
        }
        SlockAndroidLocalDiagnostics.reportAppFullLaunch()
    }

    private fun removeHomeColdStartFirstDrawListener(
        content: View = findViewById(android.R.id.content)
    ) {
        val listener = homeColdStartFirstDrawListener ?: return
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnDrawListener(listener)
        }
        homeColdStartFirstDrawListener = null
    }

    private fun prepareHomeColdStartSplashIfNeeded() {
        val resolvedPageName = resolvedPageName(intent)
        val pageDataText = resolvedPageData(intent).text
        if (!shouldInstallHomeColdStartSplash(resolvedPageName, pageDataText)) return

        setTheme(R.style.Theme_Slock_AppShellStarting)
        val gate =
            HomeColdStartSplashGate(
                enabled = true,
                startedAtMillis = SystemClock.elapsedRealtime()
            )
        homeColdStartSplashGate = gate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            homeColdStartUsesNativeHold = true
            SlockAppLog.d(
                "SlockBoot",
                "home_content_splash_installed page=$resolvedPageName mode=native_hold"
            )
            return
        }
        installSplashScreen().setKeepOnScreenCondition {
            shouldKeepHomeColdStartOnScreen(gate)
        }
        SlockAppLog.d(
            "SlockBoot",
            "home_content_splash_installed page=$resolvedPageName mode=compat_splash"
        )
    }

    private fun installHomeColdStartNativeHoldIfNeeded() {
        if (!homeColdStartUsesNativeHold) return
        val gate = homeColdStartSplashGate ?: return
        val content = findViewById<ViewGroup>(android.R.id.content)
        val holdView = layoutInflater.inflate(R.layout.slock_app_shell_splash_hold, content, false)
        content.addView(holdView)
        homeColdStartHoldView = holdView
        SlockAppLog.d("SlockBoot", "home_content_splash_hold_view_installed")
        scheduleHomeColdStartTimeout(gate)
    }

    private fun shouldKeepHomeColdStartOnScreen(gate: HomeColdStartSplashGate): Boolean {
        val keepOnScreen = gate.shouldKeepOnScreen(SystemClock.elapsedRealtime())
        if (!keepOnScreen && gate.timedOut() && !homeColdStartSplashTimeoutLogged) {
            homeColdStartSplashTimeoutLogged = true
            SlockAppLog.w(
                "SlockBoot",
                "home_content_splash_timeout timeoutMs=$HOME_COLD_START_SPLASH_TIMEOUT_MILLIS " +
                    "action=brand_fallback"
            )
        }
        return keepOnScreen
    }

    private fun scheduleHomeColdStartTimeout(gate: HomeColdStartSplashGate) {
        val delayMillis = gate.millisUntilTimeout(SystemClock.elapsedRealtime())
        if (delayMillis <= 0L) {
            handleHomeColdStartTimeout()
        } else {
            window.decorView.postDelayed(homeColdStartTimeoutRunnable, delayMillis)
        }
    }

    private fun handleHomeColdStartTimeout() {
        val gate = homeColdStartSplashGate ?: return
        if (shouldKeepHomeColdStartOnScreen(gate)) {
            scheduleHomeColdStartTimeout(gate)
            return
        }
        val fallbackDelayMillis = gate.millisUntilFallbackTimeout(SystemClock.elapsedRealtime())
        if (fallbackDelayMillis <= 0L) {
            handleHomeColdStartFallbackTimeout()
        } else {
            window.decorView.postDelayed(homeColdStartFallbackTimeoutRunnable, fallbackDelayMillis)
            SlockAppLog.d(
                "SlockBoot",
                "home_content_splash_fallback timeoutMs=$HOME_COLD_START_SPLASH_FALLBACK_TIMEOUT_MILLIS"
            )
        }
    }

    private fun handleHomeColdStartFallbackTimeout() {
        removeHomeColdStartNativeHold(reason = "fallback_timeout")
    }

    private fun removeHomeColdStartNativeHold(reason: String) {
        window.decorView.removeCallbacks(homeColdStartTimeoutRunnable)
        window.decorView.removeCallbacks(homeColdStartFallbackTimeoutRunnable)
        val holdView = homeColdStartHoldView ?: return
        (holdView.parent as? ViewGroup)?.removeView(holdView)
        homeColdStartHoldView = null
        SlockAppLog.d("SlockBoot", "home_content_splash_hold_view_removed reason=$reason")
    }

    override fun onSlockKuiklyChildPageLoadComplete(
        pageName: String,
        pageDataText: String,
        isSucceed: Boolean
    ) {
        renderReadinessChildLoads[pageName] =
            SlockKuiklyLoadReadiness(
                succeeded = isSucceed,
                completedAtMillis = SystemClock.elapsedRealtime()
            )
        if (!isHomeAppShellChildPageData(pageDataText)) return
        if (!isSucceed) {
            SlockAppLog.w(
                "SlockBoot",
                "home_content_splash_child_load_failed page=$pageName " +
                    "action=keep_brand_fallback"
            )
            return
        }
        if (homeColdStartSplashGate?.release() != true) return
        removeHomeColdStartNativeHold(reason = "child_page_load")
        SlockAppLog.d(
            "SlockBoot",
            "home_content_splash_released source=child_page_load success=$isSucceed page=$pageName"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AndroidLoginTransitionRouting.retireHost(this)
        if (pageName == PAGE_LOGIN && resolvedPageName(intent) != PAGE_LOGIN) {
            AndroidLoginTransitionRouting.onDestroyed(this, loginTransitionToken, changingConfigurations = false)
            loginTransitionToken = null
        }
        androidShellLaunchTarget?.let { AndroidShellWindowRegistry.unregister(it.shellInstanceId) }
        setIntent(intent)
        registerAndroidShellWindow(intent)
        SlockAppLog.record(
                "SlockKuiklyActivity",
                "host_new_intent page=${resolvedPageName(intent)} currentPage=${pageSpec?.pageName.orEmpty()}"
        )
        recreate()
    }

    override fun onDestroy() {
        AndroidLoginTransitionRouting.onDestroyed(
            this, loginTransitionToken, isChangingConfigurations,
        )
        screenshotFeedbackListeningGate.stop()
        screenCaptureObserver?.stop()
        screenCaptureObserver = null
        hideScreenshotFeedbackAction()
        removeHomeColdStartFirstDrawListener()
        removeHomeColdStartNativeHold(reason = "destroy")
        removeSlockInputFontBridge()
        if (adaptivePaneHostDelegate.isInitialized()) {
            adaptivePaneHost.stop()
        }
        SlockPerformanceDiagnostics.uninstall(window)
        if (shouldReleaseAndroidShellWindow(isFinishing, isChangingConfigurations)) {
            androidShellLaunchTarget?.let { target ->
                AndroidShellWindowRegistry.unregister(target.shellInstanceId)
                target.serverId?.takeIf(String::isNotBlank)?.let { serverId ->
                    AndroidAppScopeRealtimeOwner.releaseRealtimeOwner(
                        serverId = ServerId(serverId),
                        shellInstanceId = target.shellInstanceId,
                        explicitClose = true
                    )
                }
                ProcessAndroidAttachmentOwners.retireShell(target.shellInstanceId)
                releaseSlockShellStoreScope(target.shellInstanceId, target.serverId)
            }
        }
        super.onDestroy()
        if (kuiklyHostAttached) {
            kuiklyRenderViewDelegator.onDetach()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAttachmentSaveAs?.let { pending ->
            outState.putBundle(ATTACHMENT_SAVE_AS_STATE_KEY, pending.toSavedState())
        }
        pendingThreadCameraCapture?.let { pending ->
            outState.putString(THREAD_CAMERA_CAPTURE_STATE_KEY, pending.file.canonicalPath)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        screenCaptureObserver?.start(screenshotFeedbackListeningGate.start())
    }

    override fun onStop() {
        screenshotFeedbackListeningGate.stop()
        screenCaptureObserver?.stop()
        hideScreenshotFeedbackAction()
        super.onStop()
    }

    private fun prepareScreenshotFeedbackObserver() {
        val runtimeEnabled = resources.getBoolean(R.bool.slock_screenshot_feedback_runtime_enabled)
        if (
            !shouldEnableScreenshotFeedback(
                buildEnabled = BuildConfig.SCREENSHOT_FEEDBACK_ENABLED,
                runtimeEnabled = runtimeEnabled,
                sdkInt = Build.VERSION.SDK_INT,
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureObserver =
                    Android14ScreenCaptureObserver(
                        activity = this,
                        executor = ContextCompat.getMainExecutor(this),
                        onScreenCaptured = ::handleSystemScreenCaptured,
                    )
        }
    }

    private fun handleSystemScreenCaptured(generation: Long) {
        if (!screenshotFeedbackListeningGate.accepts(generation)) return
        if (screenshotFeedbackSubmissionActive || isFinishing || isDestroyed) return
        if (!screenshotFeedbackEventGate.accept(SystemClock.elapsedRealtime())) return
        SlockAppLog.record(
            "SlockScreenshotFeedback",
            "screenshot_detected route=$pageName contentCaptured=false",
        )
        showScreenshotFeedbackAction()
    }

    private fun showScreenshotFeedbackAction() {
        val root = findViewById<android.widget.FrameLayout?>(R.id.slock_kuikly_root) ?: return
        val actionView =
                screenshotFeedbackActionView
                    ?: android.widget.TextView(this).apply {
                        text = getString(R.string.screenshot_feedback_action)
                        contentDescription =
                                getString(R.string.screenshot_feedback_content_description)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.BLACK)
                        minWidth = screenshotFeedbackDp(112)
                        setPadding(
                            screenshotFeedbackDp(18),
                            0,
                            screenshotFeedbackDp(18),
                            0,
                        )
                        background =
                                android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = screenshotFeedbackDp(6).toFloat()
                                    setColor(ContextCompat.getColor(context, R.color.slock_accent_pink))
                                    setStroke(screenshotFeedbackDp(2), Color.BLACK)
                                }
                        elevation = screenshotFeedbackDp(6).toFloat()
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { openScreenshotFeedbackPrompt() }
                    }.also { view ->
                        screenshotFeedbackActionView = view
                        root.addView(
                            view,
                            android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                screenshotFeedbackDp(48),
                            ).apply {
                                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                                marginEnd = screenshotFeedbackDp(16)
                            },
                        )
                    }
        actionView.visibility = View.VISIBLE
        actionView.removeCallbacks(screenshotFeedbackHideRunnable)
        actionView.postDelayed(
            screenshotFeedbackHideRunnable,
            SCREENSHOT_FEEDBACK_VISIBLE_MILLIS,
        )
    }

    private fun hideScreenshotFeedbackAction() {
        screenshotFeedbackActionView?.let { view ->
            view.removeCallbacks(screenshotFeedbackHideRunnable)
            view.visibility = View.GONE
        }
    }

    private fun openScreenshotFeedbackPrompt() {
        if (screenshotFeedbackSubmissionActive) return
        screenshotFeedbackSubmissionActive = true
        hideScreenshotFeedbackAction()
        SlockAppLog.record(
            "SlockScreenshotFeedback",
            "feedback_action_opened route=$pageName screenshotAttached=false",
        )
        lifecycleScope.launch {
            try {
                val description = promptScreenshotFeedbackDescription() ?: return@launch
                SlockKuiklyBridgeModule(applicationContext).submitNativeFeedback(
                    JSONObject().put("message", description)
                )
            } finally {
                screenshotFeedbackSubmissionActive = false
            }
        }
    }

    private suspend fun promptScreenshotFeedbackDescription(): String? =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val input = EditText(this@SlockKuiklyActivity).apply {
                        hint = SlockHostStrings.feedbackPromptPlaceholder()
                        minLines = 3
                        setPadding(
                            screenshotFeedbackDp(16),
                            screenshotFeedbackDp(12),
                            screenshotFeedbackDp(16),
                            screenshotFeedbackDp(12),
                        )
                    }
                    val dialog =
                            AlertDialog.Builder(this@SlockKuiklyActivity)
                                .setTitle(SlockHostStrings.feedbackPromptTitle())
                                .setMessage(SlockHostStrings.feedbackPromptMessage())
                                .setView(input)
                                .setPositiveButton(SlockHostStrings.feedbackSend()) { _, _ ->
                                    val text = input.text?.toString()?.trim().orEmpty()
                                    if (continuation.isActive) {
                                        continuation.resume(text.ifBlank { null })
                                    }
                                }
                                .setNegativeButton(SlockHostStrings.feedbackCancel()) { _, _ ->
                                    if (continuation.isActive) continuation.resume(null)
                                }
                                .setOnCancelListener {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                                .create()
                    continuation.invokeOnCancellation { runCatching { dialog.dismiss() } }
                    dialog.show()
                }
            }

    private fun screenshotFeedbackDp(value: Int): Int =
            (value * resources.displayMetrics.density).roundToInt()

    private fun registerAndroidShellWindow(intent: Intent?) {
        if (resolvedPageName(intent) != PAGE_APP_SHELL) return
        val target = androidShellLaunchTargetFromPageData(appShellPageDataFromIntent(intent)) ?: return
        androidShellLaunchTarget = target
        AndroidShellWindowRegistry.register(target, taskId = taskId)
        SlockAppLog.record(
            "SlockKuiklyActivity",
            "shell_window_registered shellInstanceId=${target.shellInstanceId} " +
                "serverId=${target.serverId.orEmpty()} document=${target.documentShell}",
        )
    }

    override fun onPause() {
        if (kuiklyHostAttached) {
            reportRealtimeVisibility(AndroidHostVisibility.Hidden, "activity_on_pause")
        }
        super.onPause()
        if (kuiklyHostAttached) {
            kuiklyRenderViewDelegator.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        renderReadinessResumedAtMillis = SystemClock.elapsedRealtime()
        suppressVisualKeyboard()
        applySlockInputTypeface()
        if (kuiklyHostAttached) {
            kuiklyRenderViewDelegator.onResume()
            reportRealtimeVisibility(AndroidHostVisibility.Visible, "activity_on_resume")
        }

        AndroidRealtimeKeepAliveController.start(
            context = applicationContext,
            source = "activity_on_resume"
        )
        SlockActivityWidgetProvider.refreshAllWidgets(applicationContext)
        SlockAgentStatusWidgetProvider.refreshAllWidgets(applicationContext)
        SlockKuiklyBridgeModule.resumePendingHandsAutoInstall(applicationContext)
    }

    private fun reportRealtimeVisibility(
        visibility: AndroidHostVisibility,
        source: String,
    ) {
        AndroidAppScopeRealtimeOwner.onHostVisibilityChanged(
            visibility = visibility,
            shellInstanceId = realtimeVisibilityOwnerId,
            pageName = pageName,
            source = source
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            suppressVisualKeyboard()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleKuiklySystemBack()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleSlockTextFieldEnterKey(event)) {
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (kuiklyRenderViewDelegator.onBackPressed()) {
                return true
            }
        }
        return try {
            super.dispatchKeyEvent(event)
        } catch (throwable: IllegalStateException) {
            if (!throwable.isAndroidTextFocusSearchCrash()) {
                throw throwable
            }
            Log.w(
                    "SlockKuiklyActivity",
                    "suppressed_text_focus_search_crash keyCode=${event.keyCode} action=${event.action}",
                    throwable
            )
            true
        }
    }

    private fun IllegalStateException.isAndroidTextFocusSearchCrash(): Boolean =
            message == "focus search returned a view that wasn't able to take focus!"

    private fun handleSlockTextFieldEnterKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ENTER) return false
        val textField = currentFocus as? EditText ?: return false
        if (textField.imeOptions and EditorInfo.IME_MASK_ACTION != EditorInfo.IME_ACTION_SEND) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && event.isShiftPressed && textField.maxLines != 1) {
                val insertAt = textField.selectionStart.coerceIn(0, textField.text.length)
                textField.editableText.insert(insertAt, "\n")
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!event.isShiftPressed) {
                textField.onEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            return true
        }
        return false
    }

    private fun handleKuiklySystemBack() {
        if (kuiklyRenderViewDelegator.onBackPressed()) return
        if (moveAppShellTaskToBackIfNeeded()) return
        kuiklySystemBackCallback.isEnabled = false
        try {
            onBackPressedDispatcher.onBackPressed()
        } finally {
            kuiklySystemBackCallback.isEnabled = true
        }
    }

    internal fun closeKuiklyHostPage() {
        if (moveAppShellTaskToBackIfNeeded()) return
        finish()
    }

    private fun moveAppShellTaskToBackIfNeeded(): Boolean {
        val action = slockKuiklyHostCloseAction(pageSpec?.pageName ?: pageName)
        if (action != SlockKuiklyHostCloseAction.MoveTaskToBack) return false
        Log.i("SlockKuiklyActivity", "app_shell_close_to_background")
        moveTaskToBack(true)
        return true
    }

    // Controlled host error surface (task #230, kuikly-host-alignment-audit
    // row 81): fatal render failures log diagnostics and show a recoverable
    // native fallback instead of leaving a dead view or leaking a raw stack.
    private var hostErrorSurfaceShown = false

    override fun onUnhandledException(
        throwable: Throwable,
        errorReason: ErrorReason,
        executeMode: KuiklyRenderCoreExecuteModeBase
    ) {
        super.onUnhandledException(throwable, errorReason, executeMode)
        // Keep the full local stack for the visual-fixture crash investigation. The previous
        // diagnostics path retained only a sanitized summary, which made the deterministic
        // wrapped+nested CALL_KOTLIN failure impossible to localize from logcat.
        android.util.Log.e(
            "SlockKuiklyCrash",
            "unhandled page=${pageSpec?.pageName ?: pageName} reason=${errorReason.name} mode=${executeMode.mode}",
            throwable
        )
        // Visual capture intentionally collects only a narrow tag allow-list. Mirror the
        // throwable there so the wrapped CALL_KOTLIN cause survives the capture receipt.
        android.util.Log.e(
            "VisualCapture",
            "kuikly_unhandled page=${pageSpec?.pageName ?: pageName} reason=${errorReason.name} mode=${executeMode.mode}",
            throwable
        )
        SlockAndroidLocalDiagnostics.reportCaughtException(
            name = "KuiklyUnhandledException",
            throwable = throwable,
            extraData =
                mapOf(
                    "source" to "host_delegate",
                    "reason" to errorReason.name,
                    "executeMode" to executeMode.mode.toString()
                )
        )
        runOnUiThread { showHostErrorSurface(errorReason.name) }
    }

    override fun onPageLoadComplete(
        isSucceed: Boolean,
        errorReason: ErrorReason?,
        executeMode: KuiklyRenderCoreExecuteModeBase
    ) {
        super.onPageLoadComplete(isSucceed, errorReason, executeMode)
        renderReadinessTopPageLoad =
            SlockKuiklyLoadReadiness(
                succeeded = isSucceed,
                completedAtMillis = SystemClock.elapsedRealtime()
            )
        if (isSucceed) {
            // task #482: insets dispatched between onAttach (pageData copy) and
            // the shared layer's first observer registration would be lost and
            // dedupe-suppressed on the next equal dispatch. Force one replay at
            // page load completion so the mounted pager converges.
            if (Build.VERSION.SDK_INT >= 30) {
                window?.decorView?.rootWindowInsets?.let { insets ->
                    lastPublishedInsetsKey = null
                    publishWindowInsets(insets)
                }
            }
            return
        }
        SlockAppLog.e(
            "SlockKuiklyHost",
            "page_load_failed reason=${errorReason?.name ?: "unknown"} mode=${executeMode.mode}"
        )
        runOnUiThread { showHostErrorSurface(errorReason?.name ?: "PAGE_LOAD_FAILED") }
    }

    private fun showHostErrorSurface(reasonCode: String) {
        if (hostErrorSurfaceShown || isFinishing || isDestroyed) return
        val container = findViewById<ViewGroup?>(R.id.slock_kuikly_container) ?: return
        hostErrorSurfaceShown = true
        SlockAndroidLocalDiagnostics.recordLog(
            "error",
            "SlockKuikly",
            "visual_host_error_surface reason=$reasonCode page=${pageSpec?.pageName ?: pageName}"
        )
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val surface =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(dp(32), dp(32), dp(32), dp(32))
                isClickable = true
            }
        surface.addView(
            android.widget.TextView(this).apply {
                text = "Something went wrong"
                textSize = 20f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        surface.addView(
            android.widget.TextView(this).apply {
                text = "This page hit a rendering problem. A report was recorded. ($reasonCode)"
                textSize = 14f
                setTextColor(0xFF737373.toInt())
                setPadding(0, dp(8), 0, dp(24))
                gravity = android.view.Gravity.CENTER
            }
        )
        surface.addView(
            android.widget.Button(this).apply {
                text = "Reload"
                setOnClickListener { recreate() }
            }
        )
        if (!isTaskRoot) {
            surface.addView(
                android.widget.Button(this).apply {
                    text = "Close"
                    setOnClickListener { finish() }
                }
            )
        }
        container.addView(
            surface,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    internal fun renderReadinessSnapshot(childPageName: String): SlockKuiklyRenderReadinessSnapshot =
        SlockKuiklyRenderReadinessSnapshot(
            createdAtMillis = renderReadinessCreatedAtMillis,
            resumedAtMillis = renderReadinessResumedAtMillis,
            topPageLoad = renderReadinessTopPageLoad,
            childPageLoad = renderReadinessChildLoads[childPageName],
            hasWindowFocus = window.decorView.hasWindowFocus(),
            contentWidth = window.decorView.width,
            contentHeight = window.decorView.height
        )

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(applicationContext)
        }
        // task #354: replace the built-in KRNotifyModule (external module
        // registration is last-wins) so notify events travel in-process via
        // SlockKuiklyEventHub instead of a Binder-limited system broadcast.
        kuiklyRenderExport.moduleExport(SlockInProcessNotifyModule.MODULE_NAME) {
            SlockInProcessNotifyModule()
        }
    }

    override fun performanceMonitorTypes(): List<KRMonitorType> =
            SlockKuiklyPerformanceDiagnostics.monitorTypes

    override fun onGetLaunchData(data: KRLaunchData) {
        SlockKuiklyPerformanceDiagnostics.recordLaunch("activity", pageName, data)
    }

    override fun onGetPerformanceData(data: KRPerformanceData) {
        SlockKuiklyPerformanceDiagnostics.recordPerformance("activity", pageName, data)
    }

    override fun useHostDisplayMetrics(): Boolean =
        shouldUseGlobalHostDisplayMetrics(androidShellLaunchTarget)

    private fun installSlockInputFontBridge(container: ViewGroup) {
        updateDefaultShellDisplayMetrics(container)
        val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    container.applySlockInputTypeface()
                    updateDefaultShellDisplayMetrics(container)
                }
        inputFontLayoutListener = listener
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        container.post {
            container.applySlockInputTypeface()
            updateDefaultShellDisplayMetrics(container)
        }
    }

    private fun updateDefaultShellDisplayMetrics(container: ViewGroup) {
        if (!shouldUseGlobalHostDisplayMetrics(androidShellLaunchTarget)) return
        SlockKuiklyFontAdapter.updateHostDisplayMetrics(container)
    }

    private fun removeSlockInputFontBridge() {
        val container = findViewById<ViewGroup?>(R.id.slock_kuikly_container) ?: return
        val listener = inputFontLayoutListener ?: return
        if (container.viewTreeObserver.isAlive) {
            container.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        inputFontLayoutListener = null
    }

    private fun applySlockInputTypeface() {
        findViewById<ViewGroup?>(R.id.slock_kuikly_container)?.applySlockInputTypeface()
    }

    private fun View.applySlockInputTypeface() {
        if (this is EditText) {
            SlockKuiklyFontAdapter.inputTypeface(context)?.let { typeface = it }
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).applySlockInputTypeface()
            }
        }
    }

    internal fun requestAttachmentSaveAs(
            dispatch: AndroidAttachmentOpenDispatch.SaveAs,
            callback: KuiklyRenderCallback?
    ) {
        runOnUiThread {
            if (!ProcessAndroidAttachmentOwners.admit(dispatch.owner)) {
                callback?.invoke(AndroidAttachmentOpenResult.cancelled().toBridgePayload())
                return@runOnUiThread
            }
            if (pendingAttachmentSaveAs != null) {
                callback?.invoke(AndroidAttachmentOpenResult.failed().toBridgePayload())
                return@runOnUiThread
            }
            val pending =
                    PendingAndroidAttachmentSaveAs(
                            operationId = UUID.randomUUID().toString(),
                            source = dispatch.source,
                            owner = dispatch.owner,
                            attachmentId = dispatch.attachmentId,
                            callback = callback
                    )
            pendingAttachmentSaveAs = pending
            launchAttachmentSaveAsPicker(pending)
        }
    }

    private fun launchAttachmentSaveAsPicker(pending: PendingAndroidAttachmentSaveAs) {
        if (pendingAttachmentSaveAs?.operationId != pending.operationId) return
        if (!pending.ownerOperationIsCurrent()) {
            completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.cancelled())
            return
        }
        runCatching {
            attachmentSaveAsLauncher.launch(androidAttachmentCreateDocumentIntent(pending.source))
        }.onFailure { throwable ->
            if (throwable is ActivityNotFoundException) {
                showAttachmentSaveAsUnavailableDialog(pending)
            } else {
                SlockAppLog.record(
                        "SlockAttachmentOpen",
                        "attachmentId=${pending.attachmentId.take(8)} phase=save_as_picker status=failed type=${throwable::class.java.simpleName}"
                )
                completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.storageFailed())
            }
        }
    }

    private fun showAttachmentSaveAsUnavailableDialog(pending: PendingAndroidAttachmentSaveAs) {
        if (pendingAttachmentSaveAs?.operationId != pending.operationId || isFinishing || isDestroyed) {
            completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.saveCancelled())
            return
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.attachment_save_as_unavailable_title)
                .setMessage(R.string.attachment_save_as_unavailable_message)
                .setPositiveButton(R.string.attachment_save_as_retry) { _, _ ->
                    launchAttachmentSaveAsPicker(pending)
                }
                .setNegativeButton(R.string.attachment_save_as_cancel) { _, _ ->
                    completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.saveCancelled())
                }
                .setOnCancelListener {
                    completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.saveCancelled())
                }
                .show()
    }

    private fun handleAttachmentSaveAsResult(resultCode: Int, destination: Uri?) {
        val pending = pendingAttachmentSaveAs ?: return
        if (resultCode != Activity.RESULT_OK || destination == null) {
            completeAttachmentSaveAs(pending.operationId, AndroidAttachmentOpenResult.saveCancelled())
            return
        }
        SlockKuiklyThreadAdapter.execute("attachment-save-as") {
            val result =
                    AndroidAttachmentSaveAsCoordinator(
                            ownerIsCurrent = {
                                // A restored Activity Result is itself the one-time continuation of
                                // an owner-admitted picker launch. No new attachment action is admitted.
                                pending.ownerOperationIsCurrent()
                            },
                            sourceIsReadable = {
                                runCatching {
                                    contentResolver
                                        .openInputStream(Uri.parse(pending.source.contentUri))
                                        ?.use { true }
                                        ?: false
                                }.getOrDefault(false)
                            },
                            copyToDestination = { selectedDestination: Uri ->
                                copyAndroidAttachmentToDocument(
                                        resolver = contentResolver,
                                        source = pending.source,
                                        destination = selectedDestination
                                )
                            },
                            destinationMatchesSource = { selectedDestination: Uri ->
                                androidAttachmentDocumentMatchesSource(
                                        resolver = contentResolver,
                                        source = pending.source,
                                        destination = selectedDestination
                                )
                            },
                            deleteFailedDestination = { selectedDestination: Uri ->
                                contentResolver.delete(selectedDestination, null, null) > 0
                            },
                            onFailure = { throwable ->
                                SlockAppLog.record(
                                        "SlockAttachmentOpen",
                                        "attachmentId=${pending.attachmentId.take(8)} phase=save_as_copy status=failed type=${throwable::class.java.simpleName}"
                                )
                            }
                    ).save(pending.source, destination)
            if (result.status == AndroidAttachmentOpenStatus.Opened) {
                SlockAppLog.record(
                        "SlockAttachmentOpen",
                        "attachmentId=${pending.attachmentId.take(8)} status=saved_as sizeBytes=${pending.source.sizeBytes} mimeType=${pending.source.mimeType}"
                )
            }
            completeAttachmentSaveAs(pending.operationId, result)
        }
    }

    private fun completeAttachmentSaveAs(
            operationId: String,
            result: AndroidAttachmentOpenResult
    ) {
        runOnUiThread {
            val pending = pendingAttachmentSaveAs
                    ?.takeIf { it.operationId == operationId }
                    ?: return@runOnUiThread
            pendingAttachmentSaveAs = null
            val callback = pending.callback
            if (callback != null) {
                callback.invoke(result.toBridgePayload())
            } else {
                when (result.status) {
                    AndroidAttachmentOpenStatus.Opened ->
                            Toast.makeText(
                                    this,
                                    getString(R.string.attachment_save_as_saved_toast),
                                    Toast.LENGTH_SHORT
                            ).show()
                    AndroidAttachmentOpenStatus.SaveCancelled,
                    AndroidAttachmentOpenStatus.Cancelled -> Unit
                    else ->
                            Toast.makeText(
                                    this,
                                    getString(R.string.attachment_save_as_failed_toast),
                                    Toast.LENGTH_SHORT
                            ).show()
                }
            }
        }
    }

    internal fun requestThreadAttachmentUpload(
            channelId: String,
            imageOnly: Boolean,
            capture: Boolean,
            compressImages: Boolean = false,
            callback: KuiklyRenderCallback?
    ) {
        if (pendingThreadAttachmentUpload != null) {
            callback?.invoke(
                    mapOf(
                            "code" to 409,
                            "bodyText" to "",
                            "errorCode" to "attachment_picker_busy",
                            "errorBodyText" to "Attachment picker already open"
                    )
            )
            return
        }
        pendingThreadAttachmentUpload =
                PendingThreadAttachmentUpload(
                        channelId = channelId,
                        imageOnly = imageOnly,
                        compressImages = compressImages,
                        callback = callback
                )
        runCatching {
                    if (capture) {
                        val cameraCapture = createPendingThreadCameraCapture()
                        pendingThreadCameraCapture = cameraCapture
                        threadCameraAttachmentLauncher.launch(cameraCapture.uri)
                    } else if (imageOnly) {
                        threadImageAttachmentPickerLauncher.launch(threadMediaAttachmentPickerRequest())
                    } else {
                        threadFileAttachmentPickerLauncher.launch(arrayOf("*/*"))
                    }
                }
                .onFailure { throwable ->
                    pendingThreadCameraCapture?.file?.let { runCatching { it.delete() } }
                    pendingThreadCameraCapture = null
                    pendingThreadAttachmentUpload = null
                    callback?.invoke(
                            mapOf(
                                    "code" to 0,
                                    "bodyText" to "",
                                    "errorCode" to "attachment_picker_unavailable",
                                    "errorBodyText" to (throwable.message ?: "Attachment picker unavailable")
                            )
                    )
                }
    }

    private fun createPendingThreadCameraCapture(): PendingThreadCameraCapture {
        val directory = File(applicationContext.cacheDir, THREAD_CAMERA_CAPTURE_CACHE_DIR_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Camera capture cache unavailable")
        }
        directory.listFiles()
                ?.filter { it.isFile }
                ?.forEach { file -> runCatching { file.delete() } }
        val file = File(directory, "camera-${System.currentTimeMillis()}-${System.nanoTime()}.jpg")
        if (!file.createNewFile()) {
            throw IOException("Camera capture file unavailable")
        }
        val uri =
                FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.fileprovider",
                        file
                )
        return PendingThreadCameraCapture(uri = uri, file = file)
    }

    private fun handleThreadCameraCaptureResult(captured: Boolean) {
        val cameraCapture = pendingThreadCameraCapture ?: return
        pendingThreadCameraCapture = null
        if (cameraCapture.restoredAfterActivityRecreation) {
            val cleaned = runCatching {
                discardRestoredThreadCameraCaptureResult(cameraCapture.file)
            }.getOrDefault(false)
            SlockAppLog.w(
                "SlockCameraCapture",
                "camera_result_discarded_after_activity_recreation captured=$captured cleaned=$cleaned",
            )
            return
        }
        if (!captured || !cameraCapture.file.isFile || cameraCapture.file.length() <= 0L) {
            runCatching { cameraCapture.file.delete() }
            val pending = pendingThreadAttachmentUpload ?: return
            pendingThreadAttachmentUpload = null
            pending.callback?.invoke(mapOf("code" to 204, "bodyText" to ""))
            return
        }
        handleThreadAttachmentPickerResult(
                uris = listOf(cameraCapture.uri),
                source = "platform_camera",
                sourceFilesToDelete = listOf(cameraCapture.file)
        )
    }

    internal fun requestGoogleNativeSocialAuth(callback: KuiklyRenderCallback?): Boolean {
        if (!googleSocialAuthGateway.isNativeConfigured()) return false

        val loginTransition = AndroidLoginTransitionRouting.captureAuthentication(this)
        lifecycleScope.launch {
            val nativeResult = googleSocialAuthGateway.signIn(this@SlockKuiklyActivity)
            when (nativeResult) {
                is GoogleSocialAuthResult.IdToken -> {
                    val result =
                            withContext(Dispatchers.IO) {
                                SharedAuthDataSource(
                                        api = AndroidAuthApiFactory.createAuthApi(applicationContext),
                                        sessionStore =
                                                KeyValueAuthSessionStore(
                                                        androidMMKVAuthKeyValueStore(applicationContext)
                                                ),
                                        diagnosticsLogger = ::logSharedAuthDiagnostics
                                ).completeGoogleSocialAuth(nativeResult)
                            }
                    when (result) {
                        is SharedAuthResult.Success -> {
                            val accepted = AndroidLoginTransitionRouting.afterSuccessfulAuthentication(
                                this@SlockKuiklyActivity, loginTransition,
                            ) {
                                SlockKuiklyActivity.startAppShell(
                                        this@SlockKuiklyActivity,
                                        clearTask = true
                                )
                            }
                            if (accepted) {
                                callback?.invoke(mapOf("code" to 200, "status" to "success"))
                                finish()
                            }
                        }
                        is SharedAuthResult.Failure -> {
                            callback?.invoke(
                                    mapOf(
                                            "code" to 0,
                                            "status" to "error",
                                            "message" to result.message
                                    )
                            )
                        }
                        SharedAuthResult.Unauthenticated -> {
                            callback?.invoke(
                                    mapOf(
                                            "code" to 0,
                                            "status" to "error",
                                            "message" to "Google sign-in failed. Try again."
                                    )
                            )
                        }
                    }
                }
                GoogleSocialAuthResult.Cancelled -> {
                    callback?.invoke(
                            mapOf(
                                    "code" to 0,
                                    "status" to "cancelled",
                                    "message" to "Google sign-in canceled."
                            )
                    )
                }
                is GoogleSocialAuthResult.Failure -> {
                    callback?.invoke(
                            mapOf(
                                    "code" to 0,
                                    "status" to "error",
                                    "message" to nativeResult.message
                            )
                    )
                }
            }
        }
        return true
    }

    private fun handleThreadAttachmentPickerResult(
            uris: List<Uri>,
            persistable: Boolean = false,
            source: String = "platform_picker",
            sourceFilesToDelete: List<File> = emptyList()
    ) {
        val pending = pendingThreadAttachmentUpload ?: return
        pendingThreadAttachmentUpload = null
        SlockAppLog.record(
                "SlockKuiklyAttachment",
                "picker_result uriCount=${uris.size}"
        )
        if (uris.isEmpty()) {
            pending.callback?.invoke(mapOf("code" to 204, "bodyText" to ""))
            return
        }
        if (persistable) {
            persistThreadAttachmentUriPermissions(uris)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result =
                    try {
                        readThreadPlatformPickedFiles(
                                uris = uris,
                                source = source,
                                imageOnly = pending.imageOnly,
                                compressImages = pending.compressImages
                        )
                    } finally {
                        sourceFilesToDelete.forEach { file -> runCatching { file.delete() } }
                    }
            withContext(Dispatchers.Main) {
                SlockAppLog.record(
                        "SlockKuiklyAttachment",
                        "picker_callback_deliver code=${result["code"]} thread=${Thread.currentThread().name}"
                )
                pending.callback?.invoke(result)
            }
        }
    }

    private fun persistThreadAttachmentUriPermissions(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                        contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    .onFailure { throwable ->
                        SlockAppLog.record(
                                "SlockKuiklyAttachment",
                                "picker_persist_permission_failed uriScheme=${uri.scheme.orEmpty()} error=${throwable.javaClass.simpleName}"
                        )
                    }
        }
    }

    private fun readThreadPlatformPickedFiles(
            uris: List<Uri>,
            source: String = "platform_picker",
            imageOnly: Boolean = false,
            compressImages: Boolean = false,
    ): Map<String, Any> {
        cleanupPickedThreadAttachmentCache()
        val files = mutableListOf<PickedThreadAttachment>()
        val errorsJson = JSONArray()
        uris.forEachIndexed { index, uri ->
            runCatching {
                        pickedThreadAttachmentRef(
                                uri,
                                imageOnly = imageOnly,
                                compressImages = compressImages
                        )
                    }
                    .onSuccess { file ->
                        files += file
                    }
                    .onFailure { throwable ->
                        val errorCode =
                                if (throwable is ThreadAttachmentImageCompressionException) {
                                    THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
                                } else {
                                    "attachment_read_failed"
                                }
                        errorsJson.put(
                                JSONObject()
                                        .put("index", index)
                                        .put("errorCode", errorCode)
                                        .put("message", throwable.message ?: "Attachment read failed")
                        )
                        SlockAppLog.record(
                                "SlockKuiklyAttachment",
                                "picker_file_read_failed index=$index uriScheme=${uri.scheme.orEmpty()} error=${throwable.javaClass.simpleName}"
                        )
                    }
        }
        if (files.isEmpty()) {
            val compressionFailed =
                    (0 until errorsJson.length()).any { index ->
                        errorsJson.optJSONObject(index)?.optString("errorCode") ==
                                THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
                    }
            return mapOf(
                    "code" to 400,
                    "bodyText" to "",
                    "errorCode" to if (compressionFailed) {
                        THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
                    } else {
                        "attachment_read_failed"
                    },
                    "errorBodyText" to if (compressionFailed) {
                        "Unable to compress image attachment"
                    } else {
                        "No readable attachments"
                    }
            )
        }
        val filesJson = JSONArray()
        files.forEachIndexed { index, file ->
            filesJson.put(
                    JSONObject()
                            .put("id", "picked-${System.nanoTime()}-$index")
                            .put("fileName", file.fileName)
                            .put("mimeType", file.mimeType)
                            .put("sizeBytes", file.sizeBytes)
                            .put("previewUri", file.previewUri)
                            .put("fileRef", file.fileRef)
                            .put("source", source)
            )
        }
        SlockAppLog.record(
                "SlockKuiklyAttachment",
                "picker_files_ready fileCount=${files.size} bytes=${files.sumOf { it.sizeBytes ?: 0L }}"
        )
        val skippedCount = errorsJson.length()
        val hasCompressionFailure =
                (0 until errorsJson.length()).any { index ->
                    errorsJson.optJSONObject(index)?.optString("errorCode") ==
                            THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
                }
        val body =
                JSONObject().put("files", filesJson).apply {
                    if (skippedCount > 0) {
                        put("errors", errorsJson)
                        put("skippedCount", skippedCount)
                    }
                }
        return buildMap {
            put("code", if (skippedCount > 0) 206 else 200)
            put("bodyText", body.toString())
            put("files", filesJson)
            if (skippedCount > 0) {
                put("errors", errorsJson)
                put("skippedCount", skippedCount)
                put(
                        "errorCode",
                        if (hasCompressionFailure) {
                            THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
                        } else {
                            "attachment_read_partial"
                        }
                )
                put("errorBodyText", "$skippedCount attachment(s) could not be read")
            }
        }
    }

    private fun pickedThreadAttachmentRef(
            uri: Uri,
            imageOnly: Boolean = false,
            compressImages: Boolean = false,
    ): PickedThreadAttachment {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val shouldCompress = compressImages && isImageAttachment(uri, fileName, mimeType, imageOnly)
        val cachedFile =
                if (shouldCompress) {
                    cacheCompressedPickedImage(uri, fileName, mimeType)
                } else {
                    cachePickedThreadAttachment(uri, fileName, mimeType)
                }
        val fileRef = Uri.fromFile(cachedFile).toString()
        val resolvedMimeType =
                if (shouldCompress) {
                    "image/jpeg"
                } else {
                    mimeType
                }
        val sizeBytes = cachedFile.length().takeIf { it > 0L } ?: queryPickedFileSize(uri)
        return PickedThreadAttachment(
                fileRef = fileRef,
                fileName = if (resolvedMimeType == "image/jpeg") {
                    compressedPickedImageFileName(fileName)
                } else {
                    fileName
                },
                mimeType = resolvedMimeType,
                previewUri = fileRef,
                sizeBytes = sizeBytes
        )
    }

    private fun isImageAttachment(
            uri: Uri,
            fileName: String,
            mimeType: String,
            imageOnly: Boolean
    ): Boolean {
        // A provider can report image/jpeg while its display name or URI path still identifies a
        // movie (for example, clip.mp4 + image/jpeg).  Extension/path evidence is authoritative
        // for the picker admission and must be rejected before trusting the MIME type; otherwise
        // generic attachFile would route the movie through the JPEG compressor.
        val videoPathCandidates = sequenceOf(
                fileName,
                uri.path.orEmpty(),
                uri.lastPathSegment.orEmpty()
        )
        if (videoPathCandidates.any { candidate ->
                    val extension = candidate
                            .substringBefore('?')
                            .substringBefore('#')
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                            .substringAfterLast('.', "")
                            .lowercase(Locale.US)
                    extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS
                }
        ) {
            return false
        }
        val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        if (normalizedMimeType.startsWith("video/")) return false
        if (normalizedMimeType.startsWith("image/")) return true
        val extension = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast('.', "")
                .lowercase(Locale.US)
        // PickVisualMedia.ImageAndVideo can return a video even though the shared
        // action was requested with imageOnly=true.  Check video extensions before
        // the image-only/octet-stream fallback so an unavailable provider MIME type
        // never sends an MP4/MOV through the image decoder.
        if (extension in THREAD_ATTACHMENT_IMAGE_EXTENSIONS) return true
        return imageOnly && normalizedMimeType == "application/octet-stream"
    }

    private fun compressedPickedImageFileName(fileName: String): String {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        val stem = leaf.substringBeforeLast('.', leaf).ifBlank { "image" }
        return "$stem.jpg"
    }

    private fun cacheCompressedPickedImage(uri: Uri, fileName: String, mimeType: String): File {
        val sourceFile = cachePickedThreadAttachment(uri, fileName, mimeType)
        val outputFile =
                File(
                        pickedThreadAttachmentCacheDir(),
                        safePickedThreadAttachmentFileName(
                                compressedPickedImageFileName(fileName),
                                "image/jpeg"
                        )
                )
        return try {
            compressPickedImageFile(sourceFile, outputFile)
            outputFile
        } catch (throwable: Throwable) {
            runCatching { outputFile.delete() }
            if (throwable is ThreadAttachmentImageCompressionException) {
                throw throwable
            }
            throw ThreadAttachmentImageCompressionException(
                    throwable.message ?: "Unable to compress image attachment",
                    throwable
            )
        } finally {
            runCatching { sourceFile.delete() }
        }
    }

    private fun compressPickedImageFile(sourceFile: File, outputFile: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ThreadAttachmentImageCompressionException("Unable to decode image attachment")
        }
        val orientation =
                runCatching {
                    ExifInterface(sourceFile.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val sampleSize = imageCompressionSampleSize(bounds.outWidth, bounds.outHeight)
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var resized: Bitmap? = null
        try {
            decoded =
                    BitmapFactory.decodeFile(
                            sourceFile.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    ) ?: throw ThreadAttachmentImageCompressionException("Unable to decode image attachment")
            oriented = decoded
            val decodedBitmap = requireNotNull(decoded)
            val orientationMatrix = imageOrientationMatrix(orientation)
            if (!orientationMatrix.isIdentity) {
                oriented = Bitmap.createBitmap(
                        decodedBitmap,
                        0,
                        0,
                        decodedBitmap.width,
                        decodedBitmap.height,
                        orientationMatrix,
                        true
                )
            }
            val orientedBitmap = requireNotNull(oriented)
            val longestEdge = max(orientedBitmap.width, orientedBitmap.height)
            val scale =
                    THREAD_ATTACHMENT_IMAGE_MAX_EDGE_PX.toFloat() /
                            longestEdge.coerceAtLeast(1).toFloat()
            resized =
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                                orientedBitmap,
                                (orientedBitmap.width * scale).roundToInt().coerceAtLeast(1),
                                (orientedBitmap.height * scale).roundToInt().coerceAtLeast(1),
                                true
                        )
                    } else {
                        orientedBitmap
                    }
            val resizedBitmap = requireNotNull(resized)
            if (!outputFile.parentFile?.exists().orFalse() &&
                    outputFile.parentFile?.mkdirs() != true) {
                throw ThreadAttachmentImageCompressionException("Attachment cache unavailable")
            }
            FileOutputStream(outputFile).use { stream ->
                if (!resizedBitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                THREAD_ATTACHMENT_IMAGE_JPEG_QUALITY,
                                stream
                        )
                ) {
                    throw ThreadAttachmentImageCompressionException("Unable to encode image attachment")
                }
            }
            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw ThreadAttachmentImageCompressionException("Unable to encode image attachment")
            }
        } catch (throwable: Throwable) {
            if (throwable is ThreadAttachmentImageCompressionException) throw throwable
            throw ThreadAttachmentImageCompressionException(
                    throwable.message ?: "Unable to compress image attachment",
                    throwable
            )
        } finally {
            // Every decoder/transform allocation is owned by this method.  Keep
            // nullable references until the end so exceptions from createBitmap,
            // createScaledBitmap, or the encoder cannot leak a native bitmap.
            if (resized !== oriented) resized?.recycleIfNeeded()
            if (oriented !== decoded) oriented?.recycleIfNeeded()
            decoded?.recycleIfNeeded()
        }
    }

    private fun imageCompressionSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        var sampleSize = 1
        val sampledEdge = max(sourceWidth, sourceHeight).toLong()
        while (
                sampleSize <= Int.MAX_VALUE / 2 &&
                        sampledEdge / (sampleSize.toLong() * 2L) >= THREAD_ATTACHMENT_IMAGE_MAX_EDGE_PX
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun imageOrientationMatrix(orientation: Int): Matrix =
            Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        postRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        postRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
                }
            }

    private fun Boolean?.orFalse(): Boolean = this == true

    private fun Bitmap.recycleIfNeeded() {
        runCatching {
            if (!isRecycled) recycle()
        }
    }

    private fun cachePickedThreadAttachment(uri: Uri, fileName: String, mimeType: String): File {
        val cacheDir = pickedThreadAttachmentCacheDir()
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw IOException("Attachment cache unavailable")
        }
        val targetFile =
                File(
                        cacheDir,
                        safePickedThreadAttachmentFileName(fileName, mimeType)
                )
        return try {
            val bytesCopied =
                    contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to read attachment")
            if (bytesCopied <= 0L || !targetFile.isFile || targetFile.length() <= 0L) {
                throw IOException("Unable to read attachment")
            }
            SlockAppLog.record(
                    "SlockKuiklyAttachment",
                    "picker_cached_file uriScheme=${uri.scheme.orEmpty()} fileName=${fileName.take(80)} bytes=${targetFile.length()}"
            )
            targetFile
        } catch (throwable: Throwable) {
            runCatching { targetFile.delete() }
            throw throwable
        }
    }

    private fun pickedThreadAttachmentCacheDir(): File =
            File(applicationContext.cacheDir, PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME)

    private fun cleanupPickedThreadAttachmentCache(cacheDir: File = pickedThreadAttachmentCacheDir()) {
        cleanupPickedThreadAttachmentCache(
                cacheDir = cacheDir,
                nowMillis = System.currentTimeMillis(),
                maxAgeMillis = PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS,
                maxFiles = PICKED_THREAD_ATTACHMENT_CACHE_MAX_FILES
        )
    }

    private fun schedulePickedThreadAttachmentCacheCleanup() {
        if (!ProcessPickedThreadAttachmentCacheCleanup.claim()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result =
                    cleanupPickedThreadAttachmentCache(
                            cacheDir = pickedThreadAttachmentCacheDir(),
                            nowMillis = System.currentTimeMillis(),
                            maxAgeMillis = PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS,
                            maxFiles = PICKED_THREAD_ATTACHMENT_CACHE_MAX_FILES
                    )
            SlockAppLog.record(
                    "SlockKuiklyAttachment",
                    "picker_cache_startup_cleanup stale=${result.deletedStaleCount} " +
                            "overflow=${result.deletedOverflowCount} retained=${result.retainedCount}"
            )
        }
    }

    private fun queryPickedFileName(uri: Uri): String? =
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)
                        } else {
                            null
                        }
                    }

    private fun queryPickedFileSize(uri: Uri): Long? =
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getLong(0).takeIf { it > 0L }
                        } else {
                            null
                        }
                    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        kuiklyRenderExport.renderViewExport(
            KRTextFieldView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockKuiklyTextFieldView(context, window.attributes?.softInputMode) }
        )
        kuiklyRenderExport.renderViewExport(
            KRTextAreaView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockKuiklyTextAreaView(context, window.attributes?.softInputMode) }
        )
        kuiklyRenderExport.renderViewExport(
            SlockLucideIconRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
        kuiklyRenderExport.renderViewExport(
            SlockPixelAvatarRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockPixelAvatarRenderView(context) }
        )
        kuiklyRenderExport.renderViewExport(
            SlockKuiklyPageRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockKuiklyPageRenderView(context, this) }
        )
        kuiklyRenderExport.renderViewExport(
            SlockNativeTiltedBadgeChromeRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockNativeTiltedBadgeChromeRenderView(context) }
        )
        kuiklyRenderExport.renderViewExport(
            SlockSandboxWebViewRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockSandboxWebViewRenderView(context) }
        )
    }

    private fun createPageSpec(): SlockKuiklyPageSpec {
        val pageData =
                resolvedPageData(intent)
                        .withAndroidRuntimeWindowInsets()
        return SlockKuiklyPageSpec(
                pageName = pageName,
                pageData = pageData
        )
    }

    internal fun currentPageDataSnapshotForTest(): JSONObject =
            JSONObject(pageSpec?.pageData?.text.orEmpty())

    // task #482: safe-area snapshots are scoped per host window so a second
    // Activity or display can never pollute this window's inset state. The id
    // travels in pageData so embedded child pagers inherit their owner's
    // snapshot (children keep the same window).
    private val hostWindowId: String by lazy {
        "win:${System.identityHashCode(this)}"
    }

    private fun SlockKuiklyPageData.withAndroidRuntimeWindowInsets(): SlockKuiklyPageData =
            withAndroidRuntimeWindowInsets(
                    statusBarHeightPx = androidStatusBarHeightPx(),
                    navigationBarHeightPx = androidNavigationBarHeightPx(),
                    statusBarHidden = !androidStatusBarVisible(),
            )

    // Explicit-values overload used by the live inset publisher so the stored
    // spec always carries the authoritative numbers from the dispatch that just
    // happened — decorView.rootWindowInsets can lag behind the callback.
    private fun SlockKuiklyPageData.withAndroidRuntimeWindowInsets(
            statusBarHeightPx: Int,
            navigationBarHeightPx: Int,
            statusBarHidden: Boolean,
    ): SlockKuiklyPageData =
            SlockKuiklyPageData.fromJson(
                    JSONObject(text).apply {
                        put(KEY_HOST_EXCLUDES_NAVIGATION_BAR, androidHostExcludesNavigationBar())
                        put(KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA, true)
                        put(KEY_HOST_WINDOW_ID, hostWindowId)
                        val statusBarVisible = !statusBarHidden
                        put(KEY_STATUS_BAR_HIDDEN, statusBarHidden)
                        // task #482: the renderer's reported top inset can lag or arrive in
                        // the wrong unit on large-screen/desktop-mode hosts (it resolved to
                        // ~22dp on a 48px status bar), which left the nav rail divider and
                        // page headers drawing under the system bar. Feed the real
                        // WindowInsets pixel value through the same "statusBarHeight"
                        // channel the shared resolver already merges with the native
                        // report (the live per-window snapshot, once present, is
                        // authoritative and may be smaller or zero).
                        if (statusBarVisible && statusBarHeightPx > 0) {
                            put("statusBarHeight", statusBarHeightPx.toString())
                            put("safeAreaTop", statusBarHeightPx.toString())
                        }
                        // Same source for the bottom edge: gesture-nav taskbars are not
                        // always included in the renderer's reported bottom inset, which
                        // left the DM composer under the system bar on large screens.
                        // Write zero explicitly too: a taskbar that hides or shrinks must
                        // clear the reserved padding, not keep a stale positive value.
                        if (navigationBarHeightPx > 0) {
                            put("navigationBarHeight", navigationBarHeightPx.toString())
                            put("safeAreaBottom", navigationBarHeightPx.toString())
                        } else {
                            remove("navigationBarHeight")
                            remove("safeAreaBottom")
                        }
                    }
            )

    private fun androidStatusBarHeightPx(): Int {
        val decorView = window?.decorView ?: return 0
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = decorView.rootWindowInsets ?: return 0
            return insets.getInsets(WindowInsets.Type.statusBars()).top
        }
        @Suppress("DEPRECATION")
        return decorView.rootWindowInsets?.systemWindowInsetTop ?: 0
    }

    // Navigation bar and the gesture-mode taskbar can be reported under different
    // inset types; take the max so either presentation clears the composer.
    private fun androidNavigationBarHeightPx(): Int {
        val decorView = window?.decorView ?: return 0
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = decorView.rootWindowInsets ?: return 0
            val navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val gestures =
                    insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            return maxOf(navigation, gestures)
        }
        @Suppress("DEPRECATION")
        return decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
    }

    private fun androidHostExcludesNavigationBar(): Boolean =
            androidHostExcludesNavigationBarForMode(androidNavigationMode())

    // When the status bar is hidden (immersive / FLAG_FULLSCREEN / legacy
    // systemUiVisibility), pageData.statusBarHeight still reports the bar's
    // nominal height and the shared layer would reserve a full strip of dead
    // space at the top of every page. Flag it so the shared side can fall back
    // to a small fixed padding instead. Undetectable => visible => flag off.
    private fun androidStatusBarVisible(): Boolean {
        val decorView = window?.decorView ?: return true
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = decorView.rootWindowInsets
            if (insets != null) {
                return insets.isVisible(WindowInsets.Type.statusBars())
            }
        }
        if (window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) {
            return false
        }
        @Suppress("DEPRECATION")
        return decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
    }

    private fun androidNavigationMode(): Int? {
        val fromSettings =
                runCatching {
                            Settings.Secure.getInt(contentResolver, NAVIGATION_MODE_SETTING)
                        }
                        .getOrNull()
        if (fromSettings != null) return fromSettings
        val resourceId = resources.getIdentifier(NAVIGATION_MODE_RESOURCE, "integer", "android")
        if (resourceId == 0) return null
        return runCatching { resources.getInteger(resourceId) }.getOrNull()
    }

    // Ported from the upstream Kuikly demo host (KuiklyRenderActivity
    // setupImmersiveMode, kuikly-host-alignment-audit rows 43/78): proper
    // edge-to-edge via setDecorFitsSystemWindows(false) on API 30+ instead of
    // deprecated layout flags alone, plus cutout, bar-contrast, and light
    // status/navigation appearance handling. Slock stays light-on-white, so
    // both bars keep the light appearance.
    private fun setupImmersiveMode() {
        val window = window ?: return
        applyDecorFitsSystemWindows(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor =
                if (Build.VERSION.SDK_INT >= 26) Color.TRANSPARENT else 0x66000000.toInt()
        if (Build.VERSION.SDK_INT >= 28) {
            val cutoutMode =
                    if (Build.VERSION.SDK_INT >= 30) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            val attrs = window.attributes
            if (attrs.layoutInDisplayCutoutMode != cutoutMode) {
                attrs.layoutInDisplayCutoutMode = cutoutMode
                window.attributes = attrs
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        applyLightSystemBarsAppearance(window)
    }

    private fun applyDecorFitsSystemWindows(window: Window) {
        if (Build.VERSION.SDK_INT < 35) {
            val flags =
                    if (Build.VERSION.SDK_INT >= 30) {
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    } else {
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    }
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or flags
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    // task #232: with setDecorFitsSystemWindows(false) (edge-to-edge, task
    // #230) the window no longer resizes when the IME toggles — adjustResize
    // is inert in that model — so no layout pass follows the keyboard and
    // Kuikly's Android11PlusKeyboardWatcher (global-layout driven, reads
    // rootWindowInsets ime−nav) never fires: the composer stayed behind the
    // keyboard. Insets are still dispatched, so force the layout pass the
    // watcher is waiting for whenever they change. API 30+ only: below that
    // decorFits stays true and the legacy popup watcher still sees resizes.
    private fun setupImeInsetsRelayout() {
        if (Build.VERSION.SDK_INT < 30) return
        val content = findViewById<View>(android.R.id.content) ?: return
        content.setOnApplyWindowInsetsListener { view, insets ->
            view.requestLayout()
            // task #482: pageData is a one-shot snapshot taken before the first
            // inset dispatch, so it can carry zero/stale bar heights. Publish
            // every real dispatch to the shared layer, which merges it with the
            // renderer-reported value (max wins) and re-pads affected surfaces.
            publishWindowInsets(insets)
            insets
        }
    }

    private var lastPublishedInsetsKey: String? = null

    private fun publishWindowInsets(insets: WindowInsets) {
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        val bottom =
                maxOf(
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
                )
        val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
        val key = "$top:$bottom:$hidden"
        if (key == lastPublishedInsetsKey) return
        lastPublishedInsetsKey = key
        // Keep the stored pageData spec in sync with the latest authoritative
        // values so pages/children attached later already carry this snapshot —
        // a pager event alone would be missed by observers registered after it
        // fired (and equal future events would be deduped away).
        pageSpec = pageSpec?.let { spec ->
            spec.copy(
                    pageData =
                            spec.pageData.withAndroidRuntimeWindowInsets(
                                    statusBarHeightPx = top,
                                    navigationBarHeightPx = bottom,
                                    statusBarHidden = hidden,
                            )
            )
        }
        kuiklyRenderViewDelegator.sendEvent(
                EVENT_WINDOW_INSETS_CHANGED,
                mapOf(
                        "statusBarHeightPx" to top.toString(),
                        "navigationBarHeightPx" to bottom.toString(),
                        "statusBarHidden" to hidden,
                        KEY_HOST_WINDOW_ID to hostWindowId,
                ),
        )
    }

    private fun applyLightSystemBarsAppearance(window: Window) {
        window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    internal fun updatePerformanceScene(action: String, sceneName: String) {
        val cleanScene = sceneName.takeIf { it.isNotBlank() } ?: return
        val container = findViewById<ViewGroup?>(R.id.slock_kuikly_container) ?: return
        when (action) {
            "enter" -> {
                SlockPerformanceDiagnostics.updateRoute(container, cleanScene)
                SlockPerformanceDiagnostics.updateOperation(container, "scene_enter")
                SlockAndroidLocalDiagnostics.enterScene(cleanScene)
            }
            "exit" -> {
                SlockPerformanceDiagnostics.updateOperation(container, "scene_exit")
                SlockAndroidLocalDiagnostics.exitScene(cleanScene)
            }
            else ->
                    SlockAppLog.w(
                            "SlockPerfScene",
                            "unknown_action action=$action scene=$cleanScene"
                    )
        }
    }

    private fun suppressVisualKeyboard() {
        if (!isVisualTestingPage()) return
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        hideVisualKeyboardNow()
        window.decorView.post { hideVisualKeyboardNow() }
    }

    private fun hideVisualKeyboardNow() {
        currentFocus?.clearFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.ime())
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }

    private fun isVisualTestingPage(): Boolean =
            intent.getBooleanExtra(KEY_SUPPRESS_SOFT_KEYBOARD, false) ||
                    (pageName.startsWith("compose_") && pageName.endsWith("_visual"))

    internal fun requestRealtimeConnect(
        serverId: String,
        accessToken: String,
        shellInstanceId: String = "",
        lastSeq: Long,
        forceReconnect: Boolean = false,
        witness: RealtimeConnectionWitnessToken,
    ) {
        if (
            serverId.isBlank() ||
            accessToken.isBlank() ||
            witness.scopeLeaseId.isBlank() ||
            witness.principalId.isBlank() ||
            witness.authGeneration <= 0L ||
            witness.connectionFamilyId.isBlank()
        ) {
            return
        }
        AndroidAppScopeRealtimeOwner.connectFromSharedRequest(
            context = applicationContext,
            serverId = ServerId(serverId),
            accessToken = accessToken,
            shellInstanceId = shellInstanceId,
            requestedLastSeq = lastSeq,
            forceReconnect = forceReconnect,
            witness = witness,
        )
    }

    internal fun requestRealtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Boolean,
        disconnectReason: String,
        witness: RealtimeConnectionWitnessToken,
    ) {
        AndroidAppScopeRealtimeOwner.reconcileFromSharedRequest(
            context = applicationContext,
            serverIds = serverIds.map(String::trim).filter(String::isNotEmpty).toSet(),
            accessToken = accessToken,
            environmentId = environmentId,
            socketUrl = socketUrl,
            forceReconnect = forceReconnect,
            disconnectReason = disconnectReason,
            witness = witness,
        )
    }

    internal fun requestRealtimeDisconnect(
        reason: String,
        serverId: String = "",
        shellInstanceId: String = "",
        explicitClose: Boolean = false
    ) {
        AndroidRealtimeKeepAliveController.stop(applicationContext, reason)
        AndroidAppScopeRealtimeOwner.disconnectForSessionEnd(
            reason = reason,
            serverId = serverId.takeIf { it.isNotBlank() }?.let(::ServerId),
            shellInstanceId = shellInstanceId,
            explicitClose = explicitClose
        )
    }

    internal fun requestRealtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    ) {
        val targetServerId = serverId.trim().takeIf(String::isNotEmpty) ?: return
        AndroidAppScopeRealtimeOwner.advanceResumeCursorFromSharedRequest(
            serverId = ServerId(targetServerId),
            appliedSeq = appliedSeq,
            witness = witness,
        )
    }

    companion object {
        private const val ATTACHMENT_SAVE_AS_STATE_KEY = "slockAttachmentSaveAsState"
        private const val THREAD_CAMERA_CAPTURE_STATE_KEY = "slockThreadCameraCaptureState"
        const val PAGE_LOGIN = SlockKuiklyPages.LOGIN
        const val PAGE_REGISTER = SlockKuiklyPages.REGISTER
        const val PAGE_FORGOT_PASSWORD = SlockKuiklyPages.FORGOT_PASSWORD
        const val PAGE_AUTH_LEGAL_ACCEPTANCE = SlockKuiklyPages.AUTH_LEGAL_ACCEPTANCE
        const val PAGE_AUTH_CHOOSE_SERVER = SlockKuiklyPages.AUTH_CHOOSE_SERVER
        const val PAGE_APP_SHELL = SlockKuiklyPages.APP_SHELL
        const val PAGE_THREAD = SlockKuiklyPages.THREAD
        const val PAGE_HOME = SlockKuiklyPages.HOME
        const val PAGE_MEMBERS = SlockKuiklyPages.MEMBERS
        const val PAGE_SETTINGS_HOME = SlockKuiklyPages.SETTINGS_HOME
        const val PAGE_SETTINGS_ACCOUNT = SlockKuiklyPages.SETTINGS_ACCOUNT
        const val PAGE_SETTINGS_STORAGE = SlockKuiklyPages.SETTINGS_STORAGE
        const val PAGE_SETTINGS_BILLING = SlockKuiklyPages.SETTINGS_BILLING
        const val PAGE_ICON_GALLERY = SlockKuiklyPages.ICON_GALLERY
        const val PAGE_ROUTER = SlockKuiklyPages.ROUTER

        internal const val KEY_PAGE_NAME = "pageName"
        internal const val KEY_PAGE_DATA = "pageData"
        const val KEY_SUPPRESS_SOFT_KEYBOARD = "slockSuppressSoftKeyboard"

        internal const val KEY_APP_SHELL_NOTIFICATION_SERVER_ID = "slockNotificationServerId"
        internal const val KEY_APP_SHELL_NOTIFICATION_THREAD_ID = "slockNotificationThreadId"
        internal const val KEY_APP_SHELL_NOTIFICATION_CHANNEL_ID = "slockNotificationChannelId"
        internal const val KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID = "slockNotificationMessageId"
        internal const val KEY_APP_SHELL_NOTIFICATION_PARENT_CHANNEL_ID = "slockNotificationParentChannelId"
        internal const val KEY_APP_SHELL_NOTIFICATION_PARENT_MESSAGE_ID = "slockNotificationParentMessageId"
        internal const val KEY_APP_SHELL_NOTIFICATION_KIND = "slockNotificationKind"
        internal const val KEY_APP_SHELL_NOTIFICATION_CATEGORY = "slockNotificationCategory"
        private const val KEY_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
        private const val KEY_VERIFY_KUIKLY_THREAD = "slockKuiklyVerifyThread"
        private const val KEY_VERIFY_KUIKLY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val KEY_HOST_EXCLUDES_NAVIGATION_BAR = "slockHostExcludesNavigationBar"
        private const val KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA =
                "slockKeyboardInsetExcludesBottomSafeArea"
        private const val KEY_STATUS_BAR_HIDDEN = "slockStatusBarHidden"
        private const val KEY_HOST_WINDOW_ID = "slockWindowId"
        // "statusBarHeight" / "safeAreaTop" / "navigationBarHeight" / "safeAreaBottom"
        // are written as string px values by withAndroidRuntimeWindowInsets(); they map
        // to JSONObject params.safeAreaPx keys in shared/SlockSafeArea.kt.
        private const val EVENT_WINDOW_INSETS_CHANGED = "slockWindowInsetsChanged"
        private const val NAVIGATION_MODE_SETTING = "navigation_mode"
        private const val NAVIGATION_MODE_RESOURCE = "config_navBarInteractionMode"
        private const val NAVIGATION_MODE_GESTURAL = 2
        private const val THREAD_SURFACE_CHANNEL = "channel"
        private const val THREAD_SURFACE_DIRECT_MESSAGE = "dm"
        private const val THREAD_SURFACE_REPLY_THREAD = "thread"
        private const val NOTIFICATION_CATEGORY_DIRECT_MESSAGE = "direct_message"
        private const val ACTION_OPEN_DIAGNOSTICS = "build.raft.app.diagnostics.OPEN"
        internal const val KEY_APP_SHELL_INITIAL_TAB = "slockInitialTab"
        private const val KEY_APP_SHELL_INITIAL_SETTINGS_DESTINATION = "slockInitialSettingsDestination"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_ID = "slockDiagnosticsDeepLinkId"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS = "slockDiagnosticsDeepLinkAutoCloseMs"
        private const val PICKED_THREAD_ATTACHMENT_CACHE_MAX_FILES = 40
        private const val PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME = SLOCK_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME

        fun start(
            context: Context,
            pageName: String,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false,
            documentShellId: String? = null,
            hostActivityClass: Class<out Activity>? = null,
            loginTransitionToken: String? = null,
        ) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val hostPageData = pageData.withAndroidHostPageData(pageName)
            val starter =
                    Intent(context, hostActivityClass ?: slockKuiklyHostActivityClass(pageName))
                            .putExtra(KEY_PAGE_NAME, pageName)
                            .putExtra(KEY_PAGE_DATA, SlockKuiklyPageData.fromJson(hostPageData).text)
            loginTransitionToken?.let {
                starter.putExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN, it)
            }
            if (clearTask) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else if (!documentShellId.isNullOrBlank()) {
                starter.data = Uri.parse(documentShellId)
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
            } else if (context !is Activity) {
                starter.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(starter)
        }

        fun startAppShell(
            context: Context,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false,
        ) {
            val multiShellHostEnabled =
                runCatching {
                    KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore())
                        .readMultiShellHostEnabledSync()
                }.getOrDefault(false)
            val target =
                androidShellLaunchTargetFromPageData(pageData, multiShellHostEnabled)
                    ?: resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled)
            val resolvedPageData = pageData.withAndroidShellLaunchTarget(target)
            val taskLaunch = target.taskLaunchPlan(clearTask)
            start(
                context = context,
                pageName = PAGE_APP_SHELL,
                pageData = resolvedPageData,
                clearTask = taskLaunch.clearTask,
                documentShellId = taskLaunch.documentShellId,
                hostActivityClass = slockAppShellHostActivityClass(target)
            )
        }

        fun startAuthChooseServer(
            context: Context,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false
        ) {
            start(context, PAGE_AUTH_CHOOSE_SERVER, pageData, clearTask)
        }

        fun startAuthLegalAcceptance(
            context: Context,
            pageData: JSONObject,
            clearTask: Boolean = false
        ) {
            start(context, PAGE_AUTH_LEGAL_ACCEPTANCE, pageData, clearTask)
        }

        internal fun appShellPageDataFromIntent(intent: Intent?): JSONObject =
                if (resolvedPageName(intent) == PAGE_APP_SHELL) {
                    JSONObject(resolvedPageData(intent).text)
                } else {
                    JSONObject()
                }

        private fun resolvedPageName(intent: Intent?): String =
                if (intent.isDiagnosticsDeepLinkIntent()) {
                    intent?.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_APP_SHELL
                } else {
                    intent?.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_APP_SHELL
                }

        private fun resolvedPageData(intent: Intent?): SlockKuiklyPageData {
            val rawPageData =
                    if (intent.isDiagnosticsDeepLinkIntent() && intent?.getStringExtra(KEY_PAGE_NAME).isNullOrBlank()) {
                        diagnosticsDeepLinkPageData(intent)
                    } else {
                        JSONObject(SlockKuiklyPageData.fromText(intent?.getStringExtra(KEY_PAGE_DATA)).text)
                    }
            if (intent?.hasExtra(SlockDesktopShortcutContract.KeyTargetId) == true) {
                intent.getStringExtra(SlockDesktopShortcutContract.KeyServerId)?.let {
                    rawPageData.put(SlockDesktopShortcutContract.KeyServerId, it)
                }
                intent.getStringExtra(SlockDesktopShortcutContract.KeyTargetId)?.let {
                    rawPageData.put(SlockDesktopShortcutContract.KeyTargetId, it)
                }
                intent.getStringExtra(SlockDesktopShortcutContract.KeyTargetKind)?.let {
                    rawPageData.put(SlockDesktopShortcutContract.KeyTargetKind, it)
                }
                intent.getStringExtra(SlockDesktopShortcutContract.KeyLaunchId)?.let {
                    rawPageData.put(SlockDesktopShortcutContract.KeyLaunchId, it)
                }
            }
            return SlockKuiklyPageData.fromJson(rawPageData)
        }

        private fun Intent?.isDiagnosticsDeepLinkIntent(): Boolean =
                this?.action == ACTION_OPEN_DIAGNOSTICS

        private fun diagnosticsDeepLinkPageData(intent: Intent?): JSONObject {
            // t6-a overhead A/B (debug diagnostics only): apply the sink
            // suppression flag BEFORE any Markdown composition and emit a
            // content-free receipt so each run's arm is verifiable.
            if (intent?.hasExtra("suppress_md_node_sink") == true) {
                val suppressed = intent.getBooleanExtra("suppress_md_node_sink", false)
                com.tencent.kuiklybase.markdown.compose.SlockMarkdownNodeTrace
                    .suppressedForOverheadTest = suppressed
                SlockAppLog.record(
                    "SlockDiagnostics",
                    "md_node_sink_receipt mdNodeSink=${if (suppressed) "suppressed" else "enabled"}"
                )
            }
            val pageData =
                    JSONObject()
                            .put(KEY_PAGE_NAME, PAGE_APP_SHELL)
                            .put(KEY_APP_SHELL_INITIAL_TAB, "settings")
                            .put(KEY_APP_SHELL_INITIAL_SETTINGS_DESTINATION, "diagnostics")
                            .put(KEY_DIAGNOSTICS_DEEP_LINK_ID, "android-diagnostics-${System.currentTimeMillis()}")
                            .put(
                                    KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS,
                                    intent?.getLongExtra("auto_close_ms", 0L)?.coerceAtLeast(0L) ?: 0L
                            )
            SlockAppLog.record(
                    "SlockDiagnostics",
                    "diagnostics_deeplink_received " +
                            "autoCloseMs=${pageData.optLong(KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS, 0L)}"
            )
            return pageData
        }

        internal fun shouldReplaceCurrentPage(pageData: JSONObject): Boolean {
            return pageData.optBoolean(KEY_REPLACE_CURRENT_PAGE, false)
        }

        internal fun buildUnreadNotificationAppShellPageData(request: JSONObject): JSONObject? {
            val threadId = request.optTrimmedString("threadId") ?: return null
            val messageId = request.optTrimmedString("messageId") ?: return null
            val appShellPageData =
                    JSONObject()
                            .put("instanceId", "notification-app-shell-$threadId-$messageId")
                            .put(KEY_APP_SHELL_NOTIFICATION_THREAD_ID, threadId)
                            .put(KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, messageId)
            request.optTrimmedString("serverId")?.let { serverId ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_SERVER_ID, serverId)
            }
            request.optTrimmedString("channelId")?.let { channelId ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_CHANNEL_ID, channelId)
            }
            request.optTrimmedString("parentChannelId")?.let { parentChannelId ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_PARENT_CHANNEL_ID, parentChannelId)
            }
            request.optTrimmedString("parentMessageId")?.let { parentMessageId ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_PARENT_MESSAGE_ID, parentMessageId)
            }
            request.optTrimmedString("kind")?.let { kind ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_KIND, kind)
            }
            request.optTrimmedString("category")?.let { category ->
                appShellPageData.put(KEY_APP_SHELL_NOTIFICATION_CATEGORY, category)
            }
            return appShellPageData.withAndroidHostPageData(PAGE_APP_SHELL)
        }

        internal fun buildUnreadNotificationThreadPageData(request: JSONObject): JSONObject? {
            val threadId = request.optTrimmedString("threadId") ?: return null
            val messageId = request.optTrimmedString("messageId") ?: return null
            val parentMessageId = request.optTrimmedString("parentMessageId")
            val parentChannelId =
                    request.optTrimmedString("parentChannelId")
                            ?: request.optTrimmedString("channelId").takeIf { parentMessageId != null }
            val surfaceKind =
                    when {
                        parentChannelId != null && parentMessageId != null -> THREAD_SURFACE_REPLY_THREAD
                        request.optString("category") == NOTIFICATION_CATEGORY_DIRECT_MESSAGE -> THREAD_SURFACE_DIRECT_MESSAGE
                        else -> THREAD_SURFACE_CHANNEL
                    }
            val instanceId = "notification-thread-$threadId-$messageId"
            val launchPayload =
                    JSONObject()
                            .put("instanceId", instanceId)
                            .put("surfaceKind", surfaceKind)
                            .put("sourceThreadId", threadId)
                            .put("streamChannelId", threadId)
                            .put("focusedMessageId", messageId)
                            .put("selectedTab", "chat")
                            .put("navigationLabel", "Back")
            if (surfaceKind != THREAD_SURFACE_CHANNEL) {
                launchPayload.put("threadId", threadId)
            }
            if (surfaceKind == THREAD_SURFACE_REPLY_THREAD) {
                launchPayload
                        .put("parentChannelId", parentChannelId)
                        .put("parentMessageId", parentMessageId)
                        .put("originParentMessageId", parentMessageId)
            }
            return JSONObject()
                    .put("payload", launchPayload.toString())
                    .put("instanceId", instanceId)
                    .withAndroidHostPageData(PAGE_THREAD)
        }

        private fun JSONObject.withAndroidHostPageData(pageName: String): JSONObject =
                JSONObject(toString()).apply {
                    put(KEY_PAGE_NAME, pageName)
                    putAndroidTimeContextIfMissing()
                    putKuiklyVerificationFlagsIfMissing()
                    if (pageName == PAGE_LOGIN || pageName == PAGE_APP_SHELL) {
                        put("slockSocialAuthProviders", "google,github")
                        // Browser web login is available; the shared gate decides whether it replaces this page.
                        put("slockWebLoginSupported", true)
                    }
                }

        private fun JSONObject.putAndroidTimeContextIfMissing() {
            val nowMillis = System.currentTimeMillis()
            val offsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60_000
            if (!has("localOffsetMinutes")) {
                put("localOffsetMinutes", offsetMinutes)
            }
            if (!has("todayEpochDay")) {
                put("todayEpochDay", Math.floorDiv(nowMillis + offsetMinutes.toLong() * 60_000L, 86_400_000L))
            }
            if (!has("nowEpochMillis")) {
                put("nowEpochMillis", nowMillis)
            }
        }

        private fun JSONObject.putKuiklyVerificationFlagsIfMissing() {
            if (!has(KEY_VERIFY_KUIKLY_THREAD)) {
                put(KEY_VERIFY_KUIKLY_THREAD, BuildConfig.DEBUG)
            }
            if (!has(KEY_VERIFY_KUIKLY_REACTIVE_OBSERVER)) {
                put(KEY_VERIFY_KUIKLY_REACTIVE_OBSERVER, BuildConfig.DEBUG)
            }
        }

        private fun JSONObject.optTrimmedString(key: String): String? =
                optString(key).trim().takeIf { it.isNotBlank() }

        @Suppress("UNUSED_PARAMETER")
        internal fun androidHostExcludesNavigationBarForMode(navigationMode: Int?): Boolean {
            // task #232: setupImmersiveMode renders edge-to-edge (API 30+
            // setDecorFitsSystemWindows(false); older APIs use LAYOUT_HIDE_NAVIGATION),
            // so the host no longer lays Kuikly out above the navigation bar in any
            // navigation mode. Shared code must keep applying the bottom safe-area inset.
            return false
        }
    }
}

internal object SlockKuiklyAdapters {
    private var initialized = false

    fun init(context: Context) {
        installAndroidAppScopeSharedKeyValueStores(context)
        installAndroidAppScopePlatformFileResolver(context)
        installAndroidAppScopeEventBus(context)
        installAndroidThreadComposerTemporaryFileRetirement(context)
        SlockAndroidSqlDatabaseContext.init(context)
        SlockAndroidAvatarCacheProcess.startAsync(context.applicationContext)
        SharedBinaryDownloader.setDiagnosticsLogger { tag, message ->
            // Transport failures must reach release-build feedback logs: avatar/image
            // downloads otherwise fail silently in production (Hands 39eec0a3/d208b2f2).
            if ("status=failed" in message || "status=canceled" in message) {
                SlockAppLog.w(tag, message)
            } else {
                SlockAppLog.record(tag, message)
            }
        }
        if (initialized) {
            return
        }
        initialized = true
        with(KuiklyRenderAdapterManager) {
            krImageAdapter = SlockKuiklyImageAdapter(context.applicationContext)
            krLogAdapter = SlockKuiklyLogAdapter
            krUncaughtExceptionHandlerAdapter = SlockKuiklyExceptionAdapter
            SlockKuiklyFontAdapter.init(context.applicationContext)
            krFontAdapter = SlockKuiklyFontAdapter
            krRouterAdapter = SlockKuiklyRouterAdapter
            krThreadAdapter = SlockKuiklyThreadAdapter
            krVideoViewAdapter = SlockKRVideoViewAdapter
        }
    }
}

private data class PendingThreadAttachmentUpload(
        val channelId: String,
        val imageOnly: Boolean,
        val compressImages: Boolean,
        val callback: KuiklyRenderCallback?
)

private data class PendingThreadCameraCapture(
        val uri: Uri,
        val file: File,
        val restoredAfterActivityRecreation: Boolean = false,
)

private data class PickedThreadAttachment(
        val fileRef: String,
        val fileName: String,
        val mimeType: String,
        val previewUri: String,
        val sizeBytes: Long?
)

private object ProcessPickedThreadAttachmentCacheCleanup {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

internal data class PickedThreadAttachmentCacheCleanupResult(
        val deletedStaleCount: Int,
        val deletedOverflowCount: Int,
        val retainedCount: Int
)

internal fun cleanupPickedThreadAttachmentCache(
        cacheDir: File,
        nowMillis: Long,
        maxAgeMillis: Long,
        maxFiles: Int,
        deleteFile: (File) -> Boolean = File::delete
): PickedThreadAttachmentCacheCleanupResult {
    val files = cacheDir.listFiles()?.filter(File::isFile).orEmpty()
    val staleFiles = files.filter { file -> nowMillis - file.lastModified() > maxAgeMillis.coerceAtLeast(0L) }
    val deletedStaleCount = staleFiles.count { file -> runCatching { deleteFile(file) }.getOrDefault(false) }
    val retainedByAge = files.filter { file -> file.exists() && file !in staleFiles }.sortedByDescending(File::lastModified)
    val overflowFiles = retainedByAge.drop(maxFiles.coerceAtLeast(0))
    val deletedOverflowCount = overflowFiles.count { file -> runCatching { deleteFile(file) }.getOrDefault(false) }
    return PickedThreadAttachmentCacheCleanupResult(
            deletedStaleCount = deletedStaleCount,
            deletedOverflowCount = deletedOverflowCount,
            retainedCount = cacheDir.listFiles()?.count(File::isFile) ?: 0
    )
}

internal fun safePickedThreadAttachmentFileName(
        fileName: String,
        mimeType: String,
        nowMillis: Long = System.currentTimeMillis(),
        nanoTime: Long = System.nanoTime(),
        extensionForMimeType: (String) -> String? = { type ->
            MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        }
): String {
    val baseName =
            fileName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { "attachment" }
                    .map { character ->
                        if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_' || character == ' ') {
                            character
                        } else {
                            '_'
                        }
                    }
                    .joinToString("")
                    .trim()
                    .take(120)
                    .ifBlank { "attachment" }
    val withExtension =
            if (baseName.contains('.')) {
                baseName
            } else {
                val extension = extensionForMimeType(mimeType)
                if (extension.isNullOrBlank()) baseName else "$baseName.$extension"
            }
    return "picked-$nowMillis-$nanoTime-$withExtension"
}

internal fun threadMediaAttachmentPickerRequest(): PickVisualMediaRequest =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)

private object SlockKuiklyRouterAdapter : IKRRouterAdapter {
    override fun openPage(context: Context, pageName: String, pageData: JSONObject) {
        val activity = context as? Activity
        val replaceCurrent = SlockKuiklyActivity.shouldReplaceCurrentPage(pageData)
        if (activity != null && pageName == SlockKuiklyPages.LOGIN && replaceCurrent) {
            AndroidLoginTransitionRouting.openReplacement(activity, pageData)
            return
        }
        val startDestination = {
            if (pageName == SlockKuiklyPages.APP_SHELL) {
                SlockKuiklyActivity.startAppShell(context, pageData)
            } else {
                SlockKuiklyActivity.start(context, pageName, pageData)
            }
        }
        if (activity != null && AndroidLoginTransitionRouting.isPostAuthReplacement(
                activity.intent?.getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME), pageName, replaceCurrent,
            )) {
            if (!AndroidLoginTransitionRouting.afterSuccessfulAuthentication(
                    activity, launchDestination = startDestination,
                )) return
        } else {
            startDestination()
        }
        if (replaceCurrent) {
            activity?.finish()
        }
    }

    override fun closePage(context: Context) {
        val activity = context as? Activity ?: return
        if (activity is SlockKuiklyActivity) {
            activity.closeKuiklyHostPage()
        } else {
            activity.finish()
        }
    }
}

internal enum class SlockKuiklyHostCloseAction {
    Finish,
    MoveTaskToBack
}

internal fun slockKuiklyHostCloseAction(pageName: String?): SlockKuiklyHostCloseAction =
        if (pageName == SlockKuiklyPages.APP_SHELL) {
            SlockKuiklyHostCloseAction.MoveTaskToBack
        } else {
            SlockKuiklyHostCloseAction.Finish
        }

private object SlockKuiklyLogAdapter : IKRLogAdapter {
    override val asyncLogEnable: Boolean = true

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun e(tag: String, msg: String) {
        // Error-level render logs must reach persistent diagnostics, not just
        // Logcat: the Kuikly fork's bridge marshal reports dropped fields via
        // KuiklyRenderLog.e (task #476), and that evidence has to survive into
        // the exported diagnostics ring. SlockAppLog.e writes Logcat itself.
        SlockAppLog.e(tag, msg)
    }
}

private object SlockKuiklyExceptionAdapter : IKRUncaughtExceptionHandlerAdapter {
    override fun uncaughtException(throwable: Throwable) {
        Log.e("SlockKuikly", "Kuikly render error", throwable)
        SlockAndroidLocalDiagnostics.reportCaughtException(
            name = "KuiklyRenderException",
            throwable = throwable,
            extraData =
                mapOf(
                    "source" to "kuikly_render",
                    "thread" to Thread.currentThread().name
                )
        )
    }
}

internal object SlockKuiklyFontAdapter : IKRFontAdapter {
    private const val DESIGN_WIDTH_DP = 393f

    private data class SlockFontSpec(
            val fontRes: Int,
            val weight: Int,
            val useResourceTypeface: Boolean = false,
    )

    private var context: Context? = null
    private var inputTypeface: Typeface? = null
    @Volatile
    private var hostDisplayMetrics: DisplayMetrics? = null
    private val typefaceCache = mutableMapOf<Int, Typeface>()

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun inputTypeface(context: Context): Typeface? {
        inputTypeface?.let { return it }
        return resolveTypeface("space_grotesk_regular", context)
                ?.also { inputTypeface = it }
    }

    internal fun useResourceTypefaceFor(fontFamily: String?): Boolean {
        val fontName = fontFamily?.substringBefore(",")?.trim().orEmpty()
        // Resource-backed (single TTF via ResourcesCompat) rather than CustomFallbackBuilder:
        // CustomFallbackBuilder's per-glyph resolver is what turns U+0031 into a sans-serif glyph
        // on affected hosts, so every mono face stays on the single-resource path.
        return fontName in setOf(
                "SpaceMono-Regular",
                "space_mono_regular",
                "SpaceMono-Bold",
                "space_mono_bold",
                "GeistMono-Regular",
                "geist_mono_regular",
                "GeistMono-Bold",
                "geist_mono_bold"
        )
    }

    fun updateHostDisplayMetrics(container: View) {
        val context = container.context ?: return
        val widthPx = when {
            container.width > 0 -> container.width
            context.resources.displayMetrics.widthPixels > 0 -> context.resources.displayMetrics.widthPixels
            else -> return
        }
        val heightPx = when {
            container.height > 0 -> container.height
            context.resources.displayMetrics.heightPixels > 0 -> context.resources.displayMetrics.heightPixels
            else -> widthPx
        }
        val sourceMetrics = context.resources.displayMetrics
        val density = hostDisplayDensity(
            widthPx = widthPx,
            heightPx = heightPx,
            sourceDensity = sourceMetrics.density
        ) ?: return
        hostDisplayMetrics = DisplayMetrics().apply {
            setTo(sourceMetrics)
            this.widthPixels = widthPx
            this.heightPixels = heightPx
            this.density = density
            this.scaledDensity = density
            this.densityDpi = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }
    }

    internal fun hostDisplayDensity(
            widthPx: Int,
            heightPx: Int,
            sourceDensity: Float
    ): Float? {
        val designEdgePx = minOf(widthPx, heightPx).takeIf { it > 0 } ?: return null
        val designDensity = (designEdgePx / DESIGN_WIDTH_DP).takeIf { it.isFinite() && it > 0f } ?: return null
        val hostDensity = sourceDensity.takeIf { it.isFinite() && it > 0f }
        return hostDensity?.let { minOf(designDensity, it) } ?: designDensity
    }

    fun resolveTypeface(fontFamily: String?, context: Context): Typeface? {
        val fontName = fontFamily?.substringBefore(",")?.trim().orEmpty()
        // Task #179: the Settings>Appearance font option can select the host face. That has to be
        // an explicit branch — the `else -> null` fallthrough below lands on inputTypeface(),
        // which is Space Grotesk, so an unknown name would silently look like a bundled font.
        if (fontName == "system" || fontName == "sans-serif") {
            return Typeface.SANS_SERIF
        }
        val fontSpec = when (fontName) {
            "SpaceGrotesk-Regular", "space_grotesk_regular" -> SlockFontSpec(R.font.space_grotesk_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "SpaceGrotesk-Medium", "space_grotesk_medium" -> SlockFontSpec(R.font.space_grotesk_medium, FontStyle.FONT_WEIGHT_MEDIUM)
            "SpaceGrotesk-SemiBold", "space_grotesk_semibold" -> SlockFontSpec(R.font.space_grotesk_semibold, FontStyle.FONT_WEIGHT_SEMI_BOLD)
            "SpaceGrotesk-Bold", "space_grotesk_bold" -> SlockFontSpec(R.font.space_grotesk_bold, FontStyle.FONT_WEIGHT_BOLD)
            "SpaceGrotesk-Light", "space_grotesk_light" -> SlockFontSpec(R.font.space_grotesk_light, FontStyle.FONT_WEIGHT_LIGHT)
            "SpaceGrotesk-Variable", "space_grotesk_variable" -> SlockFontSpec(R.font.space_grotesk_variable, FontStyle.FONT_WEIGHT_NORMAL)
            "Space Grotesk", "space_grotesk" -> SlockFontSpec(R.font.space_grotesk, FontStyle.FONT_WEIGHT_NORMAL)

            // Task #179: Hanken Grotesk (web `brutal` default, the mobile default option).
            "HankenGrotesk-Light", "hanken_grotesk_light" -> SlockFontSpec(R.font.hanken_grotesk_light, FontStyle.FONT_WEIGHT_LIGHT)
            "HankenGrotesk-Regular", "hanken_grotesk_regular" -> SlockFontSpec(R.font.hanken_grotesk_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "HankenGrotesk-Medium", "hanken_grotesk_medium" -> SlockFontSpec(R.font.hanken_grotesk_medium, FontStyle.FONT_WEIGHT_MEDIUM)
            "HankenGrotesk-SemiBold", "hanken_grotesk_semibold" -> SlockFontSpec(R.font.hanken_grotesk_semibold, FontStyle.FONT_WEIGHT_SEMI_BOLD)
            "HankenGrotesk-Bold", "hanken_grotesk_bold" -> SlockFontSpec(R.font.hanken_grotesk_bold, FontStyle.FONT_WEIGHT_BOLD)

            // Task #179: Geist — web `elegant` sans (body role of the "Geist + Inter" option).
            "Geist-Light", "geist_light" -> SlockFontSpec(R.font.geist_light, FontStyle.FONT_WEIGHT_LIGHT)
            "Geist-Regular", "geist_regular" -> SlockFontSpec(R.font.geist_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "Geist-Medium", "geist_medium" -> SlockFontSpec(R.font.geist_medium, FontStyle.FONT_WEIGHT_MEDIUM)
            "Geist-SemiBold", "geist_semibold" -> SlockFontSpec(R.font.geist_semibold, FontStyle.FONT_WEIGHT_SEMI_BOLD)
            "Geist-Bold", "geist_bold" -> SlockFontSpec(R.font.geist_bold, FontStyle.FONT_WEIGHT_BOLD)

            // Task #179: Inter — web `elegant` heading face (heading role of the same option).
            "Inter-Light", "inter_light" -> SlockFontSpec(R.font.inter_light, FontStyle.FONT_WEIGHT_LIGHT)
            "Inter-Regular", "inter_regular" -> SlockFontSpec(R.font.inter_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "Inter-Medium", "inter_medium" -> SlockFontSpec(R.font.inter_medium, FontStyle.FONT_WEIGHT_MEDIUM)
            "Inter-SemiBold", "inter_semibold" -> SlockFontSpec(R.font.inter_semibold, FontStyle.FONT_WEIGHT_SEMI_BOLD)
            "Inter-Bold", "inter_bold" -> SlockFontSpec(R.font.inter_bold, FontStyle.FONT_WEIGHT_BOLD)

            // Task #179: Geist Mono — web's mono in both themes (mono role of the Hanken and
            // Geist+Inter options). Only Regular/Bold are bundled, matching Space Mono.
            "GeistMono-Regular", "geist_mono_regular" -> SlockFontSpec(
                    R.font.geist_mono_regular,
                    FontStyle.FONT_WEIGHT_NORMAL,
                    useResourceTypeface = useResourceTypefaceFor(fontName)
            )
            "GeistMono-Bold", "geist_mono_bold" -> SlockFontSpec(
                    R.font.geist_mono_bold,
                    FontStyle.FONT_WEIGHT_BOLD,
                    useResourceTypeface = useResourceTypefaceFor(fontName)
            )

            // Keep Space Mono as one resource-backed face. CustomFallbackBuilder's per-glyph
            // resolver is the path that turns U+0031 into a sans-serif glyph on affected hosts.
            "SpaceMono-Regular", "space_mono_regular" -> SlockFontSpec(
                    R.font.space_mono_regular,
                    FontStyle.FONT_WEIGHT_NORMAL,
                    useResourceTypeface = useResourceTypefaceFor(fontName)
            )
            "SpaceMono-Bold", "space_mono_bold" -> SlockFontSpec(
                    R.font.space_mono_bold,
                    FontStyle.FONT_WEIGHT_BOLD,
                    useResourceTypeface = useResourceTypefaceFor(fontName)
            )
            else -> null
        }
        return if (fontSpec != null) {
            cachedTypeface(context.applicationContext, fontSpec)
        } else {
            inputTypeface(context)
        }
    }

    private fun cachedTypeface(context: Context, fontSpec: SlockFontSpec): Typeface? =
            synchronized(typefaceCache) {
                typefaceCache[fontSpec.fontRes]?.let { return@synchronized it }
                val typeface = buildTypeface(context, fontSpec) ?: return@synchronized null
                typefaceCache[fontSpec.fontRes] = typeface
                typeface
            }

    private fun buildTypeface(context: Context, fontSpec: SlockFontSpec): Typeface? {
        if (fontSpec.useResourceTypeface || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Space Mono must remain one resource-backed face: CustomFallbackBuilder's per-glyph
            // resolver can select a sans-serif glyph for U+0031 on affected Android hosts.
            return ResourcesCompat.getFont(context, fontSpec.fontRes)
        }
        return try {
            val font = Font.Builder(context.resources, fontSpec.fontRes)
                    .setWeight(fontSpec.weight)
                    .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                    .build()
            val family = FontFamily.Builder(font).build()
            Typeface.CustomFallbackBuilder(family)
                    .setStyle(FontStyle(fontSpec.weight, FontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif")
                    .build()
        } catch (_: Exception) {
            ResourcesCompat.getFont(context, fontSpec.fontRes)
        }
    }

    override fun getTypeface(fontFamily: String, result: (Typeface?) -> Unit) {
        val currentContext = context
        result(currentContext?.let { resolveTypeface(fontFamily, it) })
    }

    override fun scaleFontSize(fontSize: Float): Float = fontSize

    override fun getDisplayMetrics(useHostDisplayMetrics: Boolean?): DisplayMetrics? {
        if (useHostDisplayMetrics != true) {
            return null
        }
        return hostDisplayMetrics ?: context?.resources?.displayMetrics
    }
}

internal fun isSlockRenderableLocalImageSource(source: String): Boolean =
        source.startsWith("file://", ignoreCase = true) ||
                source.startsWith("content://", ignoreCase = true)

internal sealed interface SlockKuiklyImageRoute {
    val diagnosticsToken: String

    data class ManagedAvatar(
            val descriptor: SlockAvatarMountedRenderDescriptor
    ) : SlockKuiklyImageRoute {
        override val diagnosticsToken: String = "managed_avatar"
    }

    data object Svg : SlockKuiklyImageRoute { override val diagnosticsToken = "svg" }
    data object Base64 : SlockKuiklyImageRoute { override val diagnosticsToken = "base64" }
    data object Assets : SlockKuiklyImageRoute { override val diagnosticsToken = "assets" }
    data object Local : SlockKuiklyImageRoute { override val diagnosticsToken = "local" }
    data object Network : SlockKuiklyImageRoute { override val diagnosticsToken = "network" }
    data object Unsupported : SlockKuiklyImageRoute { override val diagnosticsToken = "unsupported" }
}

internal fun resolveSlockKuiklyImageRoute(
        source: String,
        isBase64: Boolean,
        isAssets: Boolean,
        isLocal: Boolean,
        isWebUrl: Boolean
): SlockKuiklyImageRoute =
        parseSlockAvatarMountedRenderModel(source)?.let { descriptor ->
            SlockKuiklyImageRoute.ManagedAvatar(descriptor)
        }
                ?: when {
                    source.isSlockSvgImageSource() -> SlockKuiklyImageRoute.Svg
                    isBase64 -> SlockKuiklyImageRoute.Base64
                    isAssets -> SlockKuiklyImageRoute.Assets
                    isLocal -> SlockKuiklyImageRoute.Local
                    isWebUrl -> SlockKuiklyImageRoute.Network
                    else -> SlockKuiklyImageRoute.Unsupported
                }

internal fun isSlockAvatarLegacyTerminalBridgeAction(action: String): Boolean =
        action == "retireOwner" || action == "retireSubject"

internal inline fun <T : AutoCloseable> withSlockAvatarRenderDeliveryLease(
        lease: T,
        deliver: (T) -> Unit
) {
    // Terminal drain must cover the observable adapter delivery, not only decode/registration.
    try {
        deliver(lease)
    } finally {
        lease.close()
    }
}

private fun String.isSlockSvgImageSource(): Boolean {
    val raw = trimStart()
    val normalized = raw.lowercase(Locale.US)
    val path = normalized.substringBefore("?").substringBefore("#")
    return raw.startsWith("<svg", ignoreCase = true) ||
            normalized.startsWith("data:image/svg+xml") ||
            path.endsWith(".svg")
}

internal class SlockKuiklyImageAdapter(
        private val context: Context
) : IKRImageAdapter {
    private companion object {
        private const val NETWORK_BITMAP_CACHE_BYTES = 12 * 1024 * 1024
        private const val MAX_SINGLE_NETWORK_BITMAP_BYTES = 2 * 1024 * 1024
        private const val LOCAL_IMAGE_HEADER_SNIFF_BYTES = 512
        private const val MOUNTED_AVATAR_BITMAP_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_SINGLE_MOUNTED_AVATAR_BITMAP_BYTES = 1024 * 1024

        private val networkBitmapCache =
                object : LruCache<SlockNetworkImageRequestKey, Bitmap>(NETWORK_BITMAP_CACHE_BYTES) {
                    override fun sizeOf(key: SlockNetworkImageRequestKey, value: Bitmap): Int {
                        return value.allocationByteCount.coerceAtLeast(value.byteCount)
                    }
                }
        private val mountedAvatarBitmapCache =
                object : LruCache<SlockMountedAvatarRequestKey, Bitmap>(MOUNTED_AVATAR_BITMAP_CACHE_BYTES) {
                    override fun sizeOf(key: SlockMountedAvatarRequestKey, value: Bitmap): Int {
                        return value.allocationByteCount.coerceAtLeast(value.byteCount)
                    }
                }
        private val networkImageFlights =
                SlockImageRequestSingleFlight<SlockNetworkImageRequestKey, Bitmap>()
        private val mountedAvatarImageFlights =
                SlockImageRequestSingleFlight<SlockMountedAvatarRequestKey, Bitmap>()
        private val networkByteFlights =
                SlockImageRequestSingleFlight<String, SharedDownloadedBytes>()
        private val networkDownloadScope =
                CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun fetchDrawable(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        val route =
                resolveSlockKuiklyImageRoute(
                        source = imageLoadOption.src,
                        isBase64 = imageLoadOption.isBase64(),
                        isAssets = imageLoadOption.isAssets(),
                        isLocal = imageLoadOption.isLocalUri(),
                        isWebUrl = imageLoadOption.isWebUrl()
                )
        SlockAppLog.record(
                "SlockImageLoad",
                "source=${route.diagnosticsToken} status=request requestWidth=${imageLoadOption.requestWidth} " +
                        "requestHeight=${imageLoadOption.requestHeight} " +
                        if (route is SlockKuiklyImageRoute.ManagedAvatar) {
                            "managedModelHash=${imageLoadOption.src.hashCode().toString(16)}"
                        } else {
                            binaryDownloadUrlDiagnostics(imageLoadOption.src)
                        }
        )
        when (route) {
            is SlockKuiklyImageRoute.ManagedAvatar ->
                loadMountedAvatar(route.descriptor, imageLoadOption, callback)
            SlockKuiklyImageRoute.Svg -> loadSvg(imageLoadOption, callback)
            SlockKuiklyImageRoute.Base64 -> loadFromBase64(imageLoadOption, callback)
            SlockKuiklyImageRoute.Assets -> loadFromAssets(imageLoadOption, callback)
            SlockKuiklyImageRoute.Local -> loadFromLocalUri(imageLoadOption, callback)
            SlockKuiklyImageRoute.Network -> loadFromNetwork(imageLoadOption, callback)
            SlockKuiklyImageRoute.Unsupported -> {
                SlockAppLog.record(
                        "SlockImageLoad",
                        "source=unsupported status=failed requestWidth=${imageLoadOption.requestWidth} " +
                                "requestHeight=${imageLoadOption.requestHeight} ${binaryDownloadUrlDiagnostics(imageLoadOption.src)}"
                )
                callback(null)
            }
        }
    }

    private fun loadMountedAvatar(
            descriptor: SlockAvatarMountedRenderDescriptor,
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        val requestKey = imageLoadOption.mountedAvatarRequestKey(descriptor)
        cachedMountedAvatarBitmap(requestKey)?.let { bitmap ->
            SlockAppLog.record(
                    "SlockImageLoad",
                    "source=managed_avatar status=cache_hit bitmapWidth=${bitmap.width} " +
                            "bitmapHeight=${bitmap.height} " +
                            "requestWidth=${imageLoadOption.requestWidth} " +
                            "requestHeight=${imageLoadOption.requestHeight} " +
                            "managedModelHash=${imageLoadOption.src.hashCode().toString(16)}"
            )
            callback(bitmap.toBitmapDrawable())
            return
        }
        val subscription =
                mountedAvatarImageFlights.load(
                        key = requestKey,
                        start = { complete ->
                            SlockKuiklyThreadAdapter.executeOnSubThread {
                                val renderRead = SlockAndroidAvatarCacheProcess.currentOrNull()?.beginRender(descriptor)
                                if (renderRead == null) {
                                    complete(Result.failure(IllegalStateException("Avatar render read failed")))
                                    return@executeOnSubThread
                                }
                                withSlockAvatarRenderDeliveryLease(renderRead) { leasedRead ->
                                    val result =
                                            runCatching {
                                                cachedMountedAvatarBitmap(requestKey)?.let { return@runCatching it }
                                                val decoded =
                                                        bitmapFromMountedAvatarBytes(
                                                                bytes = leasedRead.bytes,
                                                                imageLoadOption = imageLoadOption,
                                                                contentType = leasedRead.contentType
                                                        ) ?: throw IllegalStateException("Failed to decode avatar")
                                                val registered =
                                                        leasedRead.registerDecoded(
                                                                width = decoded.width.coerceAtLeast(0),
                                                                height = decoded.height.coerceAtLeast(0),
                                                                scaleIdentity =
                                                                        "${imageLoadOption.requestWidth}x${imageLoadOption.requestHeight}:" +
                                                                                "${imageLoadOption.needResize}:${imageLoadOption.scaleType.name}"
                                                        )
                                                if (!registered) throw IllegalStateException("Failed to register decoded avatar")
                                                cacheMountedAvatarBitmap(requestKey, decoded)
                                                decoded
                                            }
                                    result.exceptionOrNull()?.let { throwable ->
                                        recordImageLoadFailure("managed_avatar", imageLoadOption, throwable)
                                    }
                                    complete(result)
                                }
                            }
                        },
                        callback = { result -> callback(result.getOrNull()?.toBitmapDrawable()) }
                )
        SlockAppLog.record(
                "SlockImageLoad",
                "source=managed_avatar status=${subscription.disposition.diagnosticsToken()} " +
                        "requestKeyHash=${requestKey.hashCode().toString(16)} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        "managedModelHash=${imageLoadOption.src.hashCode().toString(16)}"
        )
    }

    private fun bitmapFromMountedAvatarBytes(
            bytes: ByteArray,
            imageLoadOption: HRImageLoadOption,
            contentType: String?
    ): Bitmap? {
        return if (bytes.looksLikeSvg(contentType)) {
            (svgBitmapDrawable(bytes.toString(StandardCharsets.UTF_8), imageLoadOption) as? BitmapDrawable)?.bitmap
        } else {
            bitmapFromBytes(bytes, imageLoadOption)
        }
    }

    private fun cachedMountedAvatarBitmap(requestKey: SlockMountedAvatarRequestKey): Bitmap? =
            synchronized(mountedAvatarBitmapCache) {
                mountedAvatarBitmapCache.get(requestKey)?.takeUnless { it.isRecycled }
            }

    private fun cacheMountedAvatarBitmap(requestKey: SlockMountedAvatarRequestKey, bitmap: Bitmap) {
        val bitmapBytes = bitmap.allocationByteCount.coerceAtLeast(bitmap.byteCount)
        if (bitmapBytes > MAX_SINGLE_MOUNTED_AVATAR_BITMAP_BYTES) {
            return
        }
        synchronized(mountedAvatarBitmapCache) {
            mountedAvatarBitmapCache.put(requestKey, bitmap)
        }
    }

    private fun HRImageLoadOption.mountedAvatarRequestKey(
            descriptor: SlockAvatarMountedRenderDescriptor
    ): SlockMountedAvatarRequestKey =
            SlockMountedAvatarRequestKey.create(
                    descriptor = descriptor,
                    requestWidth = requestWidth,
                    requestHeight = requestHeight,
                    needResize = needResize,
                    scaleType = scaleType.name
            )

    override fun getDrawableWidth(
            kuiklyRenderViewContext: KuiklyRenderViewContext,
            drawable: Drawable
    ): Float = drawable.intrinsicWidth.toFloat()

    override fun getDrawableHeight(
            kuiklyRenderViewContext: KuiklyRenderViewContext,
            drawable: Drawable
    ): Float = drawable.intrinsicHeight.toFloat()

    private fun loadFromBase64(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        SlockKuiklyThreadAdapter.executeOnSubThread {
            runCatching {
                        val bytes = imageLoadOption.decodeBase64Payload()
                        bitmapDrawableFromBytes(bytes, imageLoadOption)
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure {
                        callback(null)
                    }
        }
    }

    private fun loadFromAssets(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        SlockKuiklyThreadAdapter.executeOnSubThread {
            runCatching {
                        val assetPath = imageLoadOption.src.substring(HRImageLoadOption.SCHEME_ASSETS.length)
                        val bytes = context.assets.open(assetPath).use { input -> input.readBytes() }
                        drawableFromBytes(bytes, imageLoadOption, contentType = null)
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure { throwable ->
                        // Bundled asset paths carry no user data, so log the path itself:
                        // a missing/broken packaged asset is otherwise invisible (task #335
                        // reaction atlas debugging on artin's device hit exactly this).
                        SlockAppLog.w(
                                "SlockImageLoad",
                                "source=assets status=failed type=${throwable::class.java.simpleName} " +
                                        "path=${imageLoadOption.src.removePrefix(HRImageLoadOption.SCHEME_ASSETS)} " +
                                        "message=${throwable.message}"
                        )
                        callback(null)
                    }
        }
    }

    private fun loadFromLocalUri(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        SlockKuiklyThreadAdapter.executeOnSubThread {
            runCatching {
                        val src = imageLoadOption.src
                        val uri = Uri.parse(src)
                        val filePath = uri.path?.takeIf { imageLoadOption.isFile() && it.isNotBlank() }
                        val openInput: () -> InputStream? = {
                            if (filePath != null) {
                                FileInputStream(filePath)
                            } else {
                                context.contentResolver.openInputStream(uri)
                            }
                        }
                        val contentType =
                                if (imageLoadOption.isContentUri()) {
                                    context.contentResolver.getType(uri)
                                } else {
                                    null
                                }
                        // Never read a local raster image into memory as a whole: a 60 MB picked
                        // PNG would need the file bytes plus the full-size bitmap and OOM the
                        // preview.  Sniff a short header for SVG, otherwise decode from the
                        // stream with bounds-first sampling.
                        val header =
                                openInput()?.use { input -> input.readLocalImageHeader() }
                                        ?: return@runCatching null
                        if (header.looksLikeSvg(contentType)) {
                            val bytes = openInput()?.use { input -> input.readBytes() } ?: return@runCatching null
                            return@runCatching svgBitmapDrawable(bytes.toString(StandardCharsets.UTF_8), imageLoadOption)
                        }
                        slockDecodeSampledBitmap(openInput, imageLoadOption.targetDecodeEdgePx())?.toBitmapDrawable()
                                ?: throw IllegalStateException("Image decode failed")
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure { throwable ->
                        recordImageLoadFailure("local", imageLoadOption, throwable)
                        callback(null)
                    }
        }
    }

    private fun loadFromNetwork(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        val requestKey = imageLoadOption.networkRequestKey()
        cachedNetworkBitmap(requestKey)?.let { bitmap ->
            SlockAppLog.record(
                    "SlockImageLoad",
                    "source=network status=cache_hit bitmapWidth=${bitmap.width} " +
                            "bitmapHeight=${bitmap.height} " +
                            "requestWidth=${imageLoadOption.requestWidth} " +
                            "requestHeight=${imageLoadOption.requestHeight} " +
                            binaryDownloadUrlDiagnostics(imageLoadOption.src)
            )
            callback(bitmap.toBitmapDrawable())
            return
        }
        val subscription =
                networkImageFlights.load(
                        key = requestKey,
                        start = { complete ->
                            fetchNetworkBytes(imageLoadOption.src) { byteResult ->
                                byteResult.onFailure { throwable ->
                                    recordImageLoadFailure("network", imageLoadOption, throwable)
                                    complete(Result.failure(throwable))
                                    return@fetchNetworkBytes
                                }
                                val response = byteResult.getOrThrow()
                                SlockKuiklyThreadAdapter.executeOnSubThread {
                                    val result =
                                            runCatching {
                                                cachedNetworkBitmap(requestKey)?.let { return@runCatching it }
                                                recordNetworkImageBytes(imageLoadOption, response.bytes.size, response.contentType)
                                                bitmapFromNetworkBytes(response.bytes, imageLoadOption, response.contentType)
                                                        ?: throw IllegalStateException("Image decode failed")
                                            }
                                    result.exceptionOrNull()?.let { throwable ->
                                        recordImageLoadFailure("network", imageLoadOption, throwable)
                                    }
                                    complete(result)
                                }
                            }
                        },
                        callback = { result -> callback(result.getOrNull()?.toBitmapDrawable()) }
                )
        SlockAppLog.record(
                "SlockImageLoad",
                "source=network status=${subscription.disposition.diagnosticsToken()} " +
                        "requestKeyHash=${requestKey.hashCode().toString(16)} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
    }

    private fun fetchNetworkBytes(
            src: String,
            onComplete: (Result<SharedDownloadedBytes>) -> Unit
    ) {
        val urlIdentity = src.slockNetworkImageUrlIdentity()
        networkByteFlights.load(
                key = urlIdentity,
                start = { complete ->
                    networkDownloadScope.launch {
                        val fetchResult =
                                runCatching {
                                    SharedBinaryDownloader.fetchBytes(src)
                                }
                        complete(fetchResult)
                    }
                },
                callback = onComplete
        )
    }

    private fun cachedNetworkBitmap(requestKey: SlockNetworkImageRequestKey): Bitmap? =
            synchronized(networkBitmapCache) {
                networkBitmapCache.get(requestKey)?.takeUnless { it.isRecycled }
            }

    private fun cacheNetworkBitmap(requestKey: SlockNetworkImageRequestKey, bitmap: Bitmap) {
        val bitmapBytes = bitmap.allocationByteCount.coerceAtLeast(bitmap.byteCount)
        if (bitmapBytes > MAX_SINGLE_NETWORK_BITMAP_BYTES) {
            return
        }
        synchronized(networkBitmapCache) {
            networkBitmapCache.put(requestKey, bitmap)
        }
    }

    private fun bitmapFromNetworkBytes(
            bytes: ByteArray,
            imageLoadOption: HRImageLoadOption,
            contentType: String?
    ): Bitmap? {
        val bitmap =
                if (bytes.looksLikeSvg(contentType)) {
                    (svgBitmapDrawable(bytes.toString(StandardCharsets.UTF_8), imageLoadOption) as? BitmapDrawable)?.bitmap
                } else {
                    bitmapFromBytes(bytes, imageLoadOption)
                }
                        ?: run {
                            recordNetworkImageDecodeFailure(imageLoadOption, bytes.size, contentType)
                            return null
                        }
        cacheNetworkBitmap(imageLoadOption.networkRequestKey(), bitmap)
        SlockAppLog.record(
                "SlockImageLoad",
                "source=network status=decoded bytes=${bytes.size} contentType=${contentType ?: "unknown"} " +
                        "bitmapWidth=${bitmap.width} bitmapHeight=${bitmap.height} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
        return bitmap
    }

    private fun HRImageLoadOption.networkRequestKey(): SlockNetworkImageRequestKey =
            SlockNetworkImageRequestKey.create(
                    url = src,
                    requestWidth = requestWidth,
                    requestHeight = requestHeight,
                    needResize = needResize,
                    scaleType = scaleType.name
            )

    private fun SlockImageFlightDisposition.diagnosticsToken(): String =
            when (this) {
                SlockImageFlightDisposition.Started -> "flight_start"
                SlockImageFlightDisposition.Joined -> "flight_join"
            }

    private fun loadSvg(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        SlockKuiklyThreadAdapter.executeOnSubThread {
            runCatching {
                        val svgText = imageLoadOption.readSvgText()
                        svgBitmapDrawable(svgText, imageLoadOption)
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure {
                        callback(null)
                    }
        }
    }

    private fun drawableFromBytes(
            bytes: ByteArray,
            imageLoadOption: HRImageLoadOption,
            contentType: String?
    ): Drawable? {
        return if (bytes.looksLikeSvg(contentType)) {
            svgBitmapDrawable(bytes.toString(StandardCharsets.UTF_8), imageLoadOption)
        } else {
            bitmapDrawableFromBytes(bytes, imageLoadOption)
        }
    }

    private fun bitmapDrawableFromBytes(bytes: ByteArray, imageLoadOption: HRImageLoadOption): Drawable? {
        return bitmapFromBytes(bytes, imageLoadOption)?.toBitmapDrawable()
    }

    private fun InputStream.readLocalImageHeader(): ByteArray {
        val buffer = ByteArray(LOCAL_IMAGE_HEADER_SNIFF_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    private fun bitmapFromBytes(bytes: ByteArray, imageLoadOption: HRImageLoadOption): Bitmap? {
        val boundsOptions =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val decodeOptions =
                BitmapFactory.Options().apply {
                    inSampleSize = imageLoadOption.bitmapDecodeSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
                }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun HRImageLoadOption.bitmapDecodeSampleSize(sourceWidth: Int, sourceHeight: Int): Int =
            slockBitmapDecodeSampleSize(sourceWidth, sourceHeight, targetDecodeEdgePx())

    private fun HRImageLoadOption.targetDecodeEdgePx(): Int {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val requestedEdgePx =
                max(requestWidth, requestHeight)
                        .takeIf { it > 0 }
                        ?.let { (it * density).roundToInt() * 2 }
        val screenEdgePx =
                max(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        return (requestedEdgePx ?: screenEdgePx)
                .coerceAtLeast(256)
                .coerceAtMost(SLOCK_MAX_BITMAP_DECODE_EDGE_PX)
    }

    private fun Bitmap.toBitmapDrawable(): BitmapDrawable =
            BitmapDrawable(context.resources, this)

    private fun recordNetworkImageBytes(
            imageLoadOption: HRImageLoadOption,
            byteCount: Int,
            contentType: String?
    ) {
        SlockAppLog.record(
                "SlockImageLoad",
                "source=network status=bytes bytes=$byteCount contentType=${contentType ?: "unknown"} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
    }

    private fun recordNetworkImageDecodeFailure(
            imageLoadOption: HRImageLoadOption,
            byteCount: Int,
            contentType: String?
    ) {
        SlockAppLog.w(
                "SlockImageLoad",
                "source=network status=decode_failed bytes=$byteCount contentType=${contentType ?: "unknown"} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
    }

    private fun recordImageLoadFailure(sourceKind: String, imageLoadOption: HRImageLoadOption, throwable: Throwable) {
        SlockAppLog.w(
                "SlockImageLoad",
                "source=$sourceKind status=failed type=${throwable::class.java.simpleName} " +
                        "message=${throwable.safeImageLoadMessage()} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
    }

    private fun Throwable.safeImageLoadMessage(): String =
            (message ?: "none")
                    .replace(Regex("""https?://[^\s)]+""", RegexOption.IGNORE_CASE), "[url]")
                    .replace(
                            Regex(
                                    """(?i)\b(access[_-]?token|refresh[_-]?token|signature|password|authorization|bearer)\s*[:=]\s*[^\s,;)&]+"""
                            )
                    ) { matchResult ->
                        "${matchResult.groupValues[1]}=[redacted]"
                    }
                    .replace(Regex("\\s+"), "_")
                    .filter { char ->
                        char.isLetterOrDigit() ||
                                char == '_' ||
                                char == '-' ||
                                char == '.' ||
                                char == ':' ||
                                char == '/' ||
                                char == '=' ||
                                char == '[' ||
                                char == ']'
                    }
                    .take(160)
                    .ifBlank { "blank" }

    private fun svgBitmapDrawable(svgText: String, imageLoadOption: HRImageLoadOption): Drawable {
        val preparedSvg = imageLoadOption.svgColorOverride()
                ?.let { color -> svgText.applySvgColor(color) }
                ?: svgText
        val svg = SVG.getFromInputStream(ByteArrayInputStream(preparedSvg.toByteArray(StandardCharsets.UTF_8)))
        val width = imageLoadOption.requestWidth.takeIf { it > 0 }?.toSvgPixels()
                ?: svg.documentWidth.takeIf { it > 0f }?.toInt()
                ?: defaultSvgSizePx()
        val height = imageLoadOption.requestHeight.takeIf { it > 0 }?.toSvgPixels()
                ?: svg.documentHeight.takeIf { it > 0f }?.toInt()
                ?: width
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val picture = svg.renderToPicture(width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        picture.draw(Canvas(bitmap))
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun defaultSvgSizePx(): Int = (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)

    private fun Int.toSvgPixels(): Int {
        return (this * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(defaultSvgSizePx())
    }

    private fun HRImageLoadOption.readSvgText(): String {
        val raw = src.trim()
        if (raw.startsWith("<svg", ignoreCase = true)) {
            return raw
        }
        if (raw.lowercase(Locale.US).startsWith("data:image/svg+xml")) {
            return decodeSvgDataUri(raw)
        }
        if (isBase64()) {
            return decodeBase64Payload().toString(StandardCharsets.UTF_8)
        }
        if (isAssets()) {
            val assetPath = src.substring(HRImageLoadOption.SCHEME_ASSETS.length).substringBefore("?")
            return context.assets.open(assetPath).use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
        }
        if (isWebUrl()) {
            return runBlocking {
                SharedBinaryDownloader.fetchBytes(src).bytes.toString(StandardCharsets.UTF_8)
            }
        }
        return raw
    }

    private fun HRImageLoadOption.decodeBase64Payload(): ByteArray {
        return Base64.decode(src.substringAfter(","), Base64.DEFAULT)
    }

    private fun HRImageLoadOption.isSvgSource(): Boolean {
        return src.isSlockSvgImageSource()
    }

    private fun HRImageLoadOption.isContentUri(): Boolean {
        return src.startsWith("content://", ignoreCase = true)
    }

    private fun HRImageLoadOption.isLocalUri(): Boolean {
        return isSlockRenderableLocalImageSource(src)
    }

    private fun ByteArray.looksLikeSvg(contentType: String?): Boolean {
        if (contentType?.contains("svg", ignoreCase = true) == true) {
            return true
        }
        return toString(StandardCharsets.UTF_8).trimStart().startsWith("<svg", ignoreCase = true)
    }

    private fun decodeSvgDataUri(src: String): String {
        val payload = src.substringAfter(",", missingDelimiterValue = "")
        return if (src.substringBefore(",").contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT).toString(StandardCharsets.UTF_8)
        } else {
            URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
        }
    }

    private fun HRImageLoadOption.svgColorOverride(): String? {
        val metadata = src.substringBefore(",", missingDelimiterValue = "")
        val query = src.substringAfter("?", missingDelimiterValue = "")
        return listOf(metadata, query)
                .asSequence()
                .flatMap { it.split("&", ";").asSequence() }
                .mapNotNull { part ->
                    val key = part.substringBefore("=", missingDelimiterValue = "")
                    if (key in setOf("color", "tint", "stroke", "fill")) {
                        part.substringAfter("=", missingDelimiterValue = "").takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
                .firstOrNull()
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }

    private fun String.applySvgColor(color: String): String {
        return replace("stroke=\"currentColor\"", "stroke=\"$color\"")
                .replace("stroke='currentColor'", "stroke='$color'")
                .replace("fill=\"currentColor\"", "fill=\"$color\"")
                .replace("fill='currentColor'", "fill='$color'")
    }
}

internal fun slockAndroidBridgeUrlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

internal fun slockAndroidBridgeUrlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

internal class SlockKuiklyBridgeModule(
        private val appContext: Context,
) : KuiklyRenderBaseModule() {
    private val threadImageCompressionOperations = ConcurrentHashMap<String, ThreadImageCompressionOperation>()
    private val threadImageCompressionMainHandler = Handler(Looper.getMainLooper())
    private val feedbackDiagnosticsLifecycle = FeedbackDiagnosticsLifecycle()
    private val feedbackDiagnosticsUploads = ConcurrentHashMap<String, FeedbackDiagnosticsUpload>()
    private val feedbackDiagnosticsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
    }

    private data class FeedbackDiagnosticsUpload(
        val requestId: String,
        val binding: FeedbackDiagnosticsBinding,
        val uploader: HandsDiagnosticsPreupload,
        @Volatile var job: Job? = null,
        @Volatile var authMonitorJob: Job? = null,
    )

    /**
     * Native callbacks can already be queued on the main handler when the renderer tears down
     * this module.  Keep a separate lifecycle fence for those preflight failures (which do not
     * have an operation entry to cancel) as well as for worker completions.
     */
    private val threadImageCompressionDestroyed = AtomicBoolean(false)

    internal suspend fun submitNativeFeedback(request: JSONObject): Map<String, Any> =
            submitFeedbackToHands(request)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
            "openUrl" -> openUrl(params)
            "openAttachment" -> {
                openAttachment(params, callback)
                null
            }
            "saveAttachment" -> {
                saveAttachment(params, callback)
                null
            }
            "saveImageToPhotos" -> {
                saveImageToPhotos(params, callback)
                null
            }
            "cancelAttachmentExport" -> {
                cancelAttachmentExport(params)
                null
            }
            "attachmentCacheStatus" -> {
                SlockKuiklyThreadAdapter.execute("attachment-public-status") {
                    callback?.invoke(attachmentCacheStatus(params))
                }
                null
            }
            "deleteAttachmentCache" -> {
                SlockKuiklyThreadAdapter.execute("attachment-public-delete") {
                    callback?.invoke(deleteAttachmentCache(params))
                }
                null
            }
            "retireFilePreviewTemporaryFiles" -> {
                // BridgeModule uses a synchronous native call here.  The shared ownership lock
                // remains held until this method returns, therefore quarantine (rename) must be
                // complete before returning; only tombstone deletion may be deferred.
                retireFilePreviewTemporaryFiles(params)
            }
            "attachmentDownloadControl" -> {
                callback?.invoke(attachmentDownloadControl(params))
                null
            }
            "attachmentDownloadOwnerChanged" -> {
                attachmentDownloadOwnerChanged(params)
                null
            }
            "closePage" -> {
                cancelFeedbackDiagnosticsUploads()
                val hostActivity = activity
                if (hostActivity is SlockKuiklyActivity) {
                    hostActivity.closeKuiklyHostPage()
                } else {
                    hostActivity?.finish()
                }
                null
            }
            // iOS owns the native right-edge recognizer; other hosts keep the shared history
            // reducer inert and deliberately treat this host-admission update as a no-op.
            "conversationForwardGesture" -> null
            "log" -> {
                val payload = JSONObject(params ?: "{}")
                val content = payload.optString("content")
                val level = SlockBridgeLogLevel.fromWire(payload.optString("level"))
                if (content.startsWith("shared_realtime_auth_refresh_")) {
                    SlockAndroidLocalDiagnostics.recordAuthLog(level.name.lowercase(), "SlockKuikly", content)
                } else {
                    SlockAndroidLocalDiagnostics.recordLog(level.name.lowercase(), "SlockKuikly", content)
                }
                null
            }
            "copyToPasteboard" -> {
                copyToPasteboard(params)
                null
            }
            "exportThreadShareImage" -> {
                exportThreadShareImage(params, callback)
                null
            }
            "probeThreadViewportPixels" -> {
                probeThreadViewportPixels(params, callback)
                null
            }
            "saveThreadShareImage" -> {
                saveThreadShareImage(params, callback)
                null
            }
            "reportRealTime", "reportRealtime" -> {
                reportRealtime(params)
                null
            }
            "performanceScene" -> {
                performanceScene(params)
                null
            }
            "performanceMarker" -> {
                performanceMarker(params)
                null
            }
            "kuiklyPerformanceSnapshot" -> {
                kuiklyPerformanceSnapshot(params)
                null
            }
            "settingsCapability" -> {
                settingsCapability(params, callback)
                null
            }
            "avatarCacheCapability" -> {
                avatarCacheCapability(params, callback)
                null
            }
            "threadAttachmentUpload" -> {
                val request = JSONObject(params ?: "{}")
                (activity as? SlockKuiklyActivity)?.requestThreadAttachmentUpload(
                        channelId = request.optString("channelId"),
                        imageOnly = request.optBoolean("imageOnly", false),
                        capture = request.optBoolean("capture", false),
                        compressImages = request.optBoolean("compressImages", false),
                        callback = callback
                ) ?: callback?.invoke(
                        mapOf(
                                "code" to 0,
                                "bodyText" to "",
                                "errorCode" to "attachment_picker_unavailable",
                                "errorBodyText" to "Attachment picker unavailable"
                        )
                )
                null
            }
            "compressThreadImage" -> {
                compressThreadImage(params, callback)
                null
            }
            "cancelCompressThreadImage" -> {
                cancelCompressThreadImage(params)
                null
            }
            "socialAuth" -> {
                socialAuth(params, callback)
                null
            }
            "urlEncode" -> {
                val payload = JSONObject(params ?: "{}")
                slockAndroidBridgeUrlEncode(payload.optString("string"))
            }
            "urlDecode" -> {
                val payload = JSONObject(params ?: "{}")
                slockAndroidBridgeUrlDecode(payload.optString("string"))
            }
            "currentTimestamp" -> System.currentTimeMillis().toString()
            "syncDesktopShortcuts" -> {
                syncDesktopShortcuts(params)
                null
            }
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
        }
    }

    override fun onDestroy() {
        cancelFeedbackDiagnosticsUploads()
        threadImageCompressionDestroyed.set(true)
        threadImageCompressionOperations.values.toList().forEach { operation ->
            cancelThreadImageCompression(operation)
            // A delivered operation may already have crossed the callback boundary and therefore
            // refuse the normal ACTIVE/PENDING cancellation CAS.  Destruction still owns every
            // generated cache object, including that terminal operation's .part sibling.
            runCatching { operation.removeOutputs() }
        }
        threadImageCompressionOperations.clear()
        super.onDestroy()
    }

    /**
     * Page close and renderer teardown invalidate every in-flight diagnostics request before the
     * WebView can be reused.  The uploader call is cancelled first, then the shared ledger is
     * invalidated so a queued progress/done callback cannot create a stale reference.
     */
    private fun cancelFeedbackDiagnosticsUploads() {
        feedbackDiagnosticsUploads.values.toList().forEach { operation ->
            feedbackDiagnosticsLifecycle.cancel(operation.requestId, operation.binding)
            operation.uploader.cancel()
            operation.job?.cancel()
            operation.authMonitorJob?.cancel()
        }
        feedbackDiagnosticsUploads.clear()
    }

    private fun compressThreadImage(params: String?, callback: KuiklyRenderCallback?) {
        if (callback == null || threadImageCompressionDestroyed.get()) return
        val request = runCatching { JSONObject(params ?: "{}") }.getOrNull()
        val requestId = request?.optString("requestId")?.trim().orEmpty()
        val fileName = request?.optString("fileName")?.trim().orEmpty()
        val mimeType = normalizedThreadImageMimeType(request?.optString("mimeType").orEmpty())
        val fileRef = request?.optString("fileRef")?.trim().orEmpty()
        // Keep the bridge dispatch path free of filesystem probes.  In particular, canonicalPath,
        // isFile and length can block on a provider-backed cache; perform all source admission on
        // the worker after the request has been reserved in the operation map.
        val validRequest =
                request != null &&
                        requestId.isNotBlank() &&
                        requestId.length <= THREAD_IMAGE_COMPRESSION_MAX_REQUEST_ID_LENGTH &&
                        requestId.none(Char::isISOControl) &&
                        fileName.isNotBlank() &&
                        fileName.length <= THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH &&
                        fileName.none { it == '/' || it == '\\' || it.isISOControl() } &&
                        mimeType.isNotBlank() &&
                        mimeType.startsWith("image/")
        if (!validRequest) {
            deliverThreadImageCompressionFailure(callback)
            return
        }
        val outputRoot = File(appContext.cacheDir, THREAD_IMAGE_COMPRESSION_CACHE_DIR_NAME)
        val outputFile =
                File(outputRoot, "composer-image-${UUID.randomUUID()}.jpg")
        val operation = ThreadImageCompressionOperation(requestId, callback, outputFile)
        if (threadImageCompressionOperations.putIfAbsent(requestId, operation) != null) {
            deliverThreadImageCompressionFailure(callback)
            return
        }
        SlockKuiklyThreadAdapter.execute("thread-image-compress") {
            var successful = false
            try {
                if (operation.isCancelled()) throw ThreadImageCompressionCancelledException()
                val sourceRoots = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir)
                val sourceFile = parseThreadImageCompressionSource(fileRef, sourceRoots)
                if (sourceFile == null || !isThreadImageCompressionInput(fileName, mimeType, sourceFile)) {
                    throw ThreadAttachmentImageCompressionException("Unable to decode image attachment")
                }
                val canonicalRoot = outputRoot.canonicalFile
                if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
                    throw ThreadAttachmentImageCompressionException("Attachment cache unavailable")
                }
                if (!canonicalRoot.isDirectory || !threadImageCompressionPathInside(canonicalRoot, outputFile)) {
                    throw ThreadAttachmentImageCompressionException("Attachment cache unavailable")
                }
                compressThreadImageFile(sourceFile, outputFile, operation::isCancelled)
                if (operation.isCancelled()) throw ThreadImageCompressionCancelledException()
                val outputRef = Uri.fromFile(outputFile).toString()
                val file =
                        JSONObject()
                                .put("fileRef", outputRef)
                                .put("fileName", threadImageCompressionFileName(fileName))
                                .put("mimeType", "image/jpeg")
                                .put("previewUri", outputRef)
                                .put("sizeBytes", outputFile.length())
                                .put("source", THREAD_IMAGE_COMPRESSION_SOURCE)
                val bodyText = JSONObject().put("files", JSONArray().put(file)).toString()
                successful = true
                finishThreadImageCompression(operation, mapOf("code" to 200, "bodyText" to bodyText))
            } catch (_: ThreadImageCompressionCancelledException) {
                cancelThreadImageCompression(operation)
            } catch (_: Throwable) {
                finishThreadImageCompression(operation, threadImageCompressionFailurePayload())
            } finally {
                if (!successful || operation.isCancelled()) {
                    runCatching { operation.removeOutputs() }
                }
                if (operation.isCancelled()) {
                    threadImageCompressionOperations.remove(requestId, operation)
                }
            }
        }
    }

    private fun cancelCompressThreadImage(params: String?) {
        val requestId = runCatching { JSONObject(params ?: "{}").optString("requestId") }
                .getOrNull()
                ?.trim()
                .orEmpty()
        if (requestId.isBlank()) return
        threadImageCompressionOperations[requestId]?.let(::cancelThreadImageCompression)
    }

    private fun cancelThreadImageCompression(operation: ThreadImageCompressionOperation) {
        val cancelled = synchronized(operation) {
            operation.state.compareAndSet(
                    ThreadImageCompressionOperation.ACTIVE,
                    ThreadImageCompressionOperation.CANCELLED
            ) ||
                    operation.state.compareAndSet(
                            ThreadImageCompressionOperation.PENDING,
                            ThreadImageCompressionOperation.CANCELLED
                    )
        }
        if (cancelled) {
            runCatching { operation.removeOutputs() }
            threadImageCompressionOperations.remove(operation.requestId, operation)
        }
    }

    private fun finishThreadImageCompression(
            operation: ThreadImageCompressionOperation,
            payload: Map<String, Any>
    ) {
        if (threadImageCompressionDestroyed.get() ||
                        threadImageCompressionOperations[operation.requestId] !== operation
        ) {
            cancelThreadImageCompression(operation)
            runCatching { operation.removeOutputs() }
            return
        }
        val pending = synchronized(operation) {
            operation.state.compareAndSet(
                    ThreadImageCompressionOperation.ACTIVE,
                    ThreadImageCompressionOperation.PENDING
            )
        }
        if (!pending) {
            runCatching { operation.outputFile.delete() }
            return
        }
        if (!threadImageCompressionMainHandler.post {
            var callbackFailed = false
            var removeOutput = false
            synchronized(operation) {
                if (threadImageCompressionDestroyed.get() ||
                                threadImageCompressionOperations[operation.requestId] !== operation ||
                                !operation.state.compareAndSet(
                                ThreadImageCompressionOperation.PENDING,
                                ThreadImageCompressionOperation.DELIVERED
                        )
                ) {
                    removeOutput = true
                } else if (threadImageCompressionDestroyed.get()) {
                    operation.state.compareAndSet(
                            ThreadImageCompressionOperation.DELIVERED,
                            ThreadImageCompressionOperation.CANCELLED
                    )
                    removeOutput = true
                } else {
                    // Hold the operation monitor across the final lifecycle check and callback.
                    // cancel/onDestroy use the same monitor, so they cannot force-cancel between
                    // this check and callback invocation and leave an unowned JPEG behind.
                    try {
                        operation.callback.invoke(payload)
                    } catch (_: Throwable) {
                        callbackFailed = true
                        operation.state.compareAndSet(
                                ThreadImageCompressionOperation.DELIVERED,
                                ThreadImageCompressionOperation.CANCELLED
                        )
                        removeOutput = true
                    }
                }
            }
            if (removeOutput || callbackFailed) {
                runCatching { operation.removeOutputs() }
            }
            threadImageCompressionOperations.remove(operation.requestId, operation)
        }) {
            cancelThreadImageCompression(operation)
        }
    }

    private fun deliverThreadImageCompressionFailure(callback: KuiklyRenderCallback) {
        if (threadImageCompressionDestroyed.get()) return
        if (!threadImageCompressionMainHandler.post {
            if (!threadImageCompressionDestroyed.get()) {
                runCatching { callback.invoke(threadImageCompressionFailurePayload()) }
            }
        }) return
    }

    private data class ThreadImageCompressionOperation(
            val requestId: String,
            val callback: KuiklyRenderCallback,
            val outputFile: File,
            val state: AtomicInteger = AtomicInteger(ACTIVE)
    ) {
        val partFile: File
            get() = File(outputFile.path + ".part")

        fun removeOutputs() {
            runCatching { partFile.delete() }
            runCatching { outputFile.delete() }
        }

        fun isCancelled(): Boolean = state.get() == CANCELLED

        companion object {
            const val ACTIVE = 0
            const val PENDING = 1
            const val DELIVERED = 2
            const val CANCELLED = 3
        }
    }

    private fun reportRealtime(params: String?) {
        val request = JSONObject(params ?: "{}")
        when (request.optString("action")) {
            "reconcile" -> {
                val serverIds = buildSet {
                    val values = request.optJSONArray("serverIds")
                    if (values != null) {
                        for (index in 0 until values.length()) {
                            values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                        }
                    }
                }
                (activity as? SlockKuiklyActivity)?.requestRealtimeReconcile(
                    serverIds = serverIds,
                    accessToken = request.optString("accessToken"),
                    environmentId = request.optString("environmentId"),
                    socketUrl = request.optString("socketUrl"),
                    forceReconnect = request.optBoolean("forceReconnect", false),
                    disconnectReason =
                        request.optString("disconnectReason")
                            .ifBlank { GeneratedRealtimeContract.DisconnectReason.AuthInvalid },
                    witness =
                        RealtimeConnectionWitnessToken(
                            scopeLeaseId = request.optString("authorityScopeLeaseId"),
                            principalId = request.optString("authorityPrincipalId"),
                            authGeneration = request.optLong("authorityAuthGeneration", 0L),
                            connectionFamilyId = request.optString("authorityConnectionFamilyId"),
                        ),
                )
            }
            "connect" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeConnect(
                            serverId = request.optString("serverId"),
                            accessToken = request.optString("accessToken"),
                            shellInstanceId = request.optString("shellInstanceId"),
                            lastSeq = request.optLong("lastSeq", 0L),
                            forceReconnect = request.optBoolean("forceReconnect", false),
                            witness =
                                RealtimeConnectionWitnessToken(
                                    scopeLeaseId = request.optString("authorityScopeLeaseId"),
                                    principalId = request.optString("authorityPrincipalId"),
                                    authGeneration = request.optLong("authorityAuthGeneration", 0L),
                                    connectionFamilyId = request.optString("authorityConnectionFamilyId"),
                                )
                    )
            "cursorAdvance" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeCursorAdvance(
                            serverId = request.optString("serverId"),
                            appliedSeq = request.optLong("appliedSeq", 0L),
                            witness =
                                RealtimeConnectionWitnessToken(
                                    scopeLeaseId = request.optString("authorityScopeLeaseId"),
                                    principalId = request.optString("authorityPrincipalId"),
                                    authGeneration = request.optLong("authorityAuthGeneration", 0L),
                                    connectionFamilyId = request.optString("authorityConnectionFamilyId"),
                                )
                    )
            "disconnect" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeDisconnect(
                            reason = request.optString("reason").ifBlank { "shared_disconnect" },
                            serverId = request.optString("serverId"),
                            shellInstanceId = request.optString("shellInstanceId"),
                            explicitClose = request.optBoolean("explicitClose", false)
                    )
        }
    }

    private fun performanceScene(params: String?) {
        val request = JSONObject(params ?: "{}")
        val sceneName = request.optString("sceneName").trim()
        if (sceneName.isBlank()) return
        (activity as? SlockKuiklyActivity)?.updatePerformanceScene(
                action = request.optString("action").ifBlank { "enter" },
                sceneName = sceneName
        )
    }

    private fun performanceMarker(params: String?) {
        val request = JSONObject(params ?: "{}")
        val routeName = request.optString("routeName").trim()
        val phase = request.optString("phase").trim()
        if (routeName.isBlank() && phase.isBlank()) return
        val container =
                (activity as? SlockKuiklyActivity)
                        ?.findViewById<ViewGroup?>(R.id.slock_kuikly_container)
                        ?: return
        val durationMs =
                if (request.has("durationMs")) {
                    request.optLong("durationMs").takeIf { it >= 0L }
                } else {
                    null
                }
        val metadata = request.optJSONObject("metadata")
        SlockPerformanceDiagnostics.recordMarker(
                view = container,
                routeName = routeName,
                phase = phase,
                event = request.optString("event").trim(),
                sceneId = request.optString("sceneId").trim(),
                durationMs = durationMs,
                metadata = metadata.toStableLogText(),
                performanceMetadata = metadata.toStableStringMap()
        )
    }

    private fun kuiklyPerformanceSnapshot(params: String?) {
        SlockAndroidLocalDiagnostics.reportKuiklySnapshot(JSONObject(params ?: "{}"))
    }

    private fun toast(params: String?): Any? {
        val content = JSONObject(params ?: "{}").optString("content")
        if (content.isNotBlank()) {
            Toast.makeText(appContext, content, Toast.LENGTH_SHORT).show()
        }
        return null
    }

    private fun syncDesktopShortcuts(params: String?) {
        val request = runCatching { JSONObject(params ?: "{}") }.getOrNull() ?: return
        val rawShortcuts = request.optString("shortcuts")
        val shortcuts = SlockDesktopShortcutContract.deserializeList(rawShortcuts)
        SlockKuiklyThreadAdapter.execute("sync-desktop-shortcuts") {
            try {
                if (shortcuts.isEmpty()) {
                    ShortcutManagerCompat.removeAllDynamicShortcuts(appContext)
                    SlockAppLog.d("SlockDesktopShortcut", "dynamic_shortcuts_cleared")
                    return@execute
                }
                val shortcutInfos = shortcuts.take(4).mapNotNull { item ->
                    val targetId = item.targetId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val serverId = item.serverId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = item.title.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null

                    val launchId = "${serverId}_${targetId}_${System.currentTimeMillis()}"
                    val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
                        val pageData = JSONObject().apply {
                            put(SlockDesktopShortcutContract.KeyServerId, serverId)
                            put(SlockDesktopShortcutContract.KeyTargetId, targetId)
                            put(SlockDesktopShortcutContract.KeyTargetKind, item.targetKind)
                            put(SlockDesktopShortcutContract.KeyLaunchId, launchId)
                        }
                        putExtra(SlockKuiklyActivity.KEY_PAGE_DATA, pageData.toString())
                        putExtra(SlockDesktopShortcutContract.KeyServerId, serverId)
                        putExtra(SlockDesktopShortcutContract.KeyTargetId, targetId)
                        putExtra(SlockDesktopShortcutContract.KeyTargetKind, item.targetKind)
                        putExtra(SlockDesktopShortcutContract.KeyLaunchId, launchId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }

                    ShortcutInfoCompat.Builder(appContext, item.id)
                        .setShortLabel(title)
                        .setLongLabel(title)
                        .setIcon(IconCompat.createWithResource(appContext, R.mipmap.ic_launcher))
                        .setIntent(launchIntent)
                        .build()
                }
                ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcutInfos)
                SlockAppLog.d("SlockDesktopShortcut", "dynamic_shortcuts_updated count=${shortcutInfos.size}")
            } catch (e: Throwable) {
                SlockAppLog.w("SlockDesktopShortcut", "dynamic_shortcuts_update_failed error=${e.message}")
            }
        }
    }

    private fun copyToPasteboard(params: String?) {
        val content = JSONObject(params ?: "{}").optString("content")
        if (content.isBlank()) return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Raft", content))
    }

    private fun exportThreadShareImage(params: String?, callback: KuiklyRenderCallback?) {
        val hostActivity = activity as? SlockKuiklyActivity
        if (hostActivity == null) {
            callback?.invoke(mapOf("status" to "failed", "message" to "Image export unavailable"))
            return
        }
        val request = JSONObject(params ?: "{}")
        hostActivity.runOnUiThread {
            captureThreadShareImage(hostActivity, request) { result -> callback?.invoke(result) }
        }
    }

    private fun probeThreadViewportPixels(params: String?, callback: KuiklyRenderCallback?) {
        val hostActivity = activity as? SlockKuiklyActivity
        if (hostActivity == null) {
            callback?.invoke(mapOf("status" to "failed", "message" to "Thread pixel probe unavailable"))
            return
        }
        val request =
                runCatching { JSONObject(params ?: "{}") }
                        .getOrElse {
                            callback?.invoke(mapOf("status" to "failed", "message" to "Invalid pixel probe request"))
                            return
                        }
        val generation = request.optLong("generation")
        val callbackDelivered = AtomicBoolean(false)
        fun deliver(payload: Map<String, Any?>) {
            if (callbackDelivered.compareAndSet(false, true)) {
                callback?.invoke(payload + ("generation" to generation))
            }
        }
        runCatching {
            hostActivity.runOnUiThread {
            val container =
                    hostActivity.findViewById<ViewGroup?>(R.id.slock_kuikly_container)
                            ?: run {
                                deliver(mapOf("status" to "failed", "message" to "Thread view unavailable"))
                                return@runOnUiThread
                            }
            if (container.width <= 0 || container.height <= 0) {
                deliver(mapOf("status" to "failed", "message" to "Thread view is not ready"))
                return@runOnUiThread
            }
            val left = request.optDouble("x", 0.0).roundToInt().coerceIn(0, container.width - 1)
            val top = request.optDouble("y", 0.0).roundToInt().coerceIn(0, container.height - 1)
            val width =
                    request.optDouble("width", 0.0).roundToInt()
                            .coerceAtLeast(1)
                            .coerceAtMost(container.width - left)
            val height =
                    request.optDouble("height", 0.0).roundToInt()
                            .coerceAtLeast(1)
                            .coerceAtMost(container.height - top)
            val bitmap =
                    runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                            .getOrElse {
                                deliver(mapOf("status" to "failed", "message" to "Unable to allocate pixel probe"))
                                return@runOnUiThread
                            }
            val handler = Handler(Looper.getMainLooper())
            val nativeCompleted = AtomicBoolean(false)
            fun recycleProbeBitmap() {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            val timeout =
                    Runnable {
                        if (nativeCompleted.compareAndSet(false, true)) {
                            deliver(mapOf("status" to "timeout", "message" to "PixelCopy callback timed out"))
                        }
                    }
            handler.postDelayed(timeout, 2_000L)
            fun finish(payload: Map<String, Any?>) {
                if (!nativeCompleted.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                recycleProbeBitmap()
                deliver(payload)
            }
            val complete: (Int) -> Unit = complete@{ pixelCopyResult ->
                if (nativeCompleted.get()) {
                    // A timeout already returned an infra receipt. PixelCopy is
                    // now finished, so its bitmap can be released without
                    // analyzing or publishing stale pixels.
                    recycleProbeBitmap()
                    return@complete
                }
                if (pixelCopyResult != PixelCopy.SUCCESS) {
                    finish(
                            mapOf(
                                    "status" to "failed",
                                    "pixelCopyResult" to pixelCopyResult
                            )
                    )
                } else {
                    val analyzed =
                            runCatching {
                                analyzeThreadViewportPixels(
                                        bitmap = bitmap,
                                        container = container,
                                        generation = generation,
                                        pixelCopyResult = pixelCopyResult
                                )
                            }.getOrElse {
                                mapOf("status" to "failed", "message" to "Unable to analyze pixel probe")
                            }
                    finish(analyzed)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val locationInWindow = IntArray(2)
                container.getLocationInWindow(locationInWindow)
                val sourceRect =
                        Rect(
                                locationInWindow[0] + left,
                                locationInWindow[1] + top,
                                locationInWindow[0] + left + width,
                                locationInWindow[1] + top + height
                        )
                container.postOnAnimation {
                    container.postOnAnimation secondFrame@{
                        if (nativeCompleted.get()) {
                            recycleProbeBitmap()
                            return@secondFrame
                        }
                        if (hostActivity.isFinishing || hostActivity.isDestroyed) {
                            finish(mapOf("status" to "failed", "message" to "Thread view is not ready"))
                            return@secondFrame
                        }
                        runCatching {
                            PixelCopy.request(
                                    hostActivity.window,
                                    sourceRect,
                                    bitmap,
                                    complete,
                                    handler
                            )
                        }.onFailure {
                            finish(mapOf("status" to "failed", "message" to "PixelCopy request failed"))
                        }
                    }
                }
            } else {
                runCatching {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.translate(-left.toFloat(), -top.toFloat())
                    container.draw(canvas)
                }.onSuccess {
                    complete(PixelCopy.SUCCESS)
                }.onFailure {
                    finish(mapOf("status" to "failed", "message" to "Unable to draw pixel probe"))
                }
            }
            }
        }.onFailure {
            deliver(mapOf("status" to "failed", "message" to "Unable to schedule pixel probe"))
        }
    }

    private fun analyzeThreadViewportPixels(
        bitmap: Bitmap,
        container: ViewGroup,
        generation: Long,
        pixelCopyResult: Int
    ): Map<String, Any> {
        var sampleCount = 0
        var nonWhiteCount = 0
        var darkCount = 0
        val sampleStep = 4
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                sampleCount += 1
                if (red < 248 || green < 248 || blue < 248) nonWhiteCount += 1
                if (red <= 64 && green <= 64 && blue <= 64) darkCount += 1
                x += sampleStep
            }
            y += sampleStep
        }
        var descendants = 0
        var visibleDescendants = 0
        var attachedDescendants = 0
        var layoutRequestedDescendants = 0
        val pending = ArrayDeque<View>()
        for (index in 0 until container.childCount) pending.add(container.getChildAt(index))
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            descendants += 1
            if (view.visibility == View.VISIBLE && view.alpha > 0f) visibleDescendants += 1
            if (view.isAttachedToWindow) attachedDescendants += 1
            if (view.isLayoutRequested) layoutRequestedDescendants += 1
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
            }
        }
        val whiteCount = (sampleCount - nonWhiteCount).coerceAtLeast(0)
        return mapOf(
                "status" to "ok",
                "generation" to generation,
                "pixelCopyResult" to pixelCopyResult,
                "sampleCount" to sampleCount,
                "nonWhiteCount" to nonWhiteCount,
                "darkCount" to darkCount,
                "whitePermille" to if (sampleCount > 0) whiteCount * 1000 / sampleCount else 1000,
                "descendants" to descendants,
                "visibleDescendants" to visibleDescendants,
                "attachedDescendants" to attachedDescendants,
                "layoutRequestedDescendants" to layoutRequestedDescendants,
                "rootChild" to
                        container.getChildAt(0)?.javaClass?.name.orEmpty()
        )
    }

    private fun captureThreadShareImage(
        hostActivity: SlockKuiklyActivity,
        request: JSONObject,
        onResult: (Map<String, Any>) -> Unit
    ) {
        val container =
                hostActivity.findViewById<ViewGroup?>(R.id.slock_kuikly_container)
                        ?: run {
                            onResult(mapOf("status" to "failed", "message" to "Thread view unavailable"))
                            return
                        }
        if (container.width <= 0 || container.height <= 0) {
            onResult(mapOf("status" to "failed", "message" to "Thread view is not ready"))
            return
        }
        val fragmentPaths =
                request.optString("fragments")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toList()
        if (fragmentPaths.isNotEmpty()) {
            onResult(stitchThreadShareFragments(fragmentPaths))
            return
        }
        val requestedX = request.optDouble("x", 0.0).roundToInt()
        val requestedY = request.optDouble("y", 0.0).roundToInt()
        val requestedWidth = request.optDouble("width", 0.0).roundToInt()
        val requestedHeight = request.optDouble("height", 0.0).roundToInt()
        val left = requestedX.coerceIn(0, container.width - 1)
        val top = requestedY.coerceIn(0, container.height - 1)
        val width =
                if (requestedWidth > 0) {
                    requestedWidth.coerceAtMost(container.width - left)
                } else {
                    container.width - left
                }.coerceAtLeast(1)
        val height =
                if (requestedHeight > 0) {
                    requestedHeight.coerceAtMost(container.height - top)
                } else {
                    container.height - top
                }.coerceAtLeast(1)
        val bitmap =
                runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                        .getOrElse {
                            onResult(mapOf("status" to "failed", "message" to "Unable to capture image"))
                            return
                        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationInWindow = IntArray(2)
            container.getLocationInWindow(locationInWindow)
            val sourceRect =
                    Rect(
                            locationInWindow[0] + left,
                            locationInWindow[1] + top,
                            locationInWindow[0] + left + width,
                            locationInWindow[1] + top + height
                    )
            // Kuikly can publish the new LazyColumn placement before the
            // corresponding Surface pixels are presented. PixelCopy issued in
            // that window captures the preceding row even though
            // onGloballyPositioned already reports the selected row's bounds.
            // Cross two Android presentation frames at the platform boundary;
            // a coroutine delay in common code does not establish this Surface
            // ordering guarantee.
            container.postOnAnimation {
                container.postOnAnimation {
                    if (hostActivity.isFinishing || hostActivity.isDestroyed) {
                        bitmap.recycle()
                        onResult(mapOf("status" to "failed", "message" to "Thread view is not ready"))
                        return@postOnAnimation
                    }
                    runCatching {
                        PixelCopy.request(
                                hostActivity.window,
                                sourceRect,
                                bitmap,
                                { copyResult ->
                                    if (copyResult == PixelCopy.SUCCESS) {
                                        onResult(saveThreadShareBitmap(bitmap, frame = request.optBoolean("frame", true)))
                                    } else {
                                        bitmap.recycle()
                                        onResult(
                                                mapOf(
                                                        "status" to "failed",
                                                        "message" to "Unable to capture image",
                                                        "pixelCopyResult" to copyResult
                                                )
                                        )
                                    }
                                },
                                Handler(Looper.getMainLooper())
                        )
                    }.onFailure {
                        bitmap.recycle()
                        onResult(mapOf("status" to "failed", "message" to "Unable to capture image"))
                    }
                }
            }
            return
        }
        runCatching {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(-left.toFloat(), -top.toFloat())
            container.draw(canvas)
        }.onSuccess {
            onResult(saveThreadShareBitmap(bitmap, frame = request.optBoolean("frame", true)))
        }.onFailure {
            bitmap.recycle()
            onResult(mapOf("status" to "failed", "message" to "Unable to capture image"))
        }
    }

    private fun stitchThreadShareFragments(fragmentPaths: List<String>): Map<String, Any> {
        val fragments = mutableListOf<Bitmap>()
        fragmentPaths.forEach { path ->
            val fragment = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (fragment == null) {
                fragments.forEach { it.recycle() }
                return mapOf("status" to "failed", "message" to "Unable to capture image")
            }
            fragments += fragment
        }
        if (fragments.isEmpty()) {
            return mapOf("status" to "failed", "message" to "Unable to capture image")
        }
        val width = fragments.maxOf { it.width }.coerceAtLeast(1)
        val height = fragments.sumOf { it.height }.coerceAtLeast(1)
        val stitched =
                runCatching {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                        val canvas = Canvas(output)
                        canvas.drawColor(Color.WHITE)
                        var top = 0f
                        fragments.forEach { fragment ->
                            val left = ((width - fragment.width) / 2f).coerceAtLeast(0f)
                            canvas.drawBitmap(fragment, left, top, null)
                            top += fragment.height
                        }
                    }
                }
                        .getOrElse {
                    fragments.forEach { it.recycle() }
                    return mapOf("status" to "failed", "message" to "Unable to capture image")
                }
        fragments.forEach { it.recycle() }
        return saveThreadShareBitmap(stitched, frame = true)
    }

    private fun saveThreadShareBitmap(bitmap: Bitmap, frame: Boolean): Map<String, Any> {
        val exportBitmap =
                if (frame) {
                    runCatching { bitmap.withThreadShareChrome() }
                        .getOrElse {
                            bitmap.recycle()
                            return mapOf("status" to "failed", "message" to "Unable to frame image")
                        }
                } else {
                    bitmap
                }
        if (frame) {
            bitmap.recycle()
        }
        val exportDir = threadShareImageCacheDir()
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            exportBitmap.recycle()
            return mapOf("status" to "failed", "message" to "Storage unavailable")
        }
        if (frame) {
            cleanupThreadShareImageCache(exportDir)
        }
        val file = File(exportDir, "raft-message-share-${System.currentTimeMillis()}-${System.nanoTime()}.png")
        val saved =
                runCatching {
                    file.outputStream().use { output ->
                        exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                }.getOrDefault(false)
        val exportWidth = exportBitmap.width
        val exportHeight = exportBitmap.height
        exportBitmap.recycle()
        if (!saved || !file.exists() || file.length() <= 0L) {
            return mapOf("status" to "failed", "message" to "Unable to write image")
        }
        val uri =
                runCatching {
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                }.getOrElse {
                    return mapOf("status" to "failed", "message" to "Unable to expose image")
                }
        return mapOf(
                "status" to "ok",
                "uri" to uri.toString(),
                "path" to file.absolutePath,
                "fileName" to file.name,
                "mimeType" to "image/png",
                "width" to exportWidth,
                "height" to exportHeight
        )
    }

    private fun Bitmap.withThreadShareChrome(): Bitmap {
        val headerHeight = threadShareChromeHeaderHeight(width)
        val horizontalInset = (width * 0.048f).roundToInt().coerceIn(26, 38).toFloat()
        val framed =
                Bitmap.createBitmap(width, threadShareChromeOutputHeight(width, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(framed)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.BLACK
        }
        val captionTypeface =
                SlockKuiklyFontAdapter.resolveTypeface("space_grotesk_semibold", appContext)
                        ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val titleTypeface =
                SlockKuiklyFontAdapter.resolveTypeface("space_grotesk_bold", appContext)
                        ?: captionTypeface
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 24)
            textSize = threadShareChromeCaptionTextSize(width)
            typeface = captionTypeface
            letterSpacing = 0.045f
        }
        canvas.drawRect(0f, 0f, width.toFloat(), framed.height.toFloat(), fill)
        fill.color = Color.rgb(255, 216, 77)
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), fill)
        val logo =
                loadThreadShareLogoBitmap(
                        maxWidth = (width * 0.36f).roundToInt().coerceIn(190, 300),
                        maxHeight = (headerHeight - 34).coerceAtLeast(48)
                )
        if (logo != null) {
            val logoTop = ((headerHeight - logo.height) / 2f).coerceAtLeast(0f)
            canvas.drawBitmap(logo, horizontalInset, logoTop, null)
            logo.recycle()
        } else {
            val fallbackTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = (width * 0.07f).coerceIn(36f, 48f)
                typeface = titleTypeface
            }
            canvas.drawText("RAFT", horizontalInset, headerHeight.centeredTextBaseline(fallbackTitle), fallbackTitle)
        }
        val site = "raft.build"
        canvas.drawText(
                site,
                width - caption.measureText(site) - horizontalInset,
                headerHeight.centeredTextBaseline(caption),
                caption
        )
        canvas.drawBitmap(this, 0f, headerHeight.toFloat(), null)
        canvas.drawLine(0f, headerHeight.toFloat(), width.toFloat(), headerHeight.toFloat(), stroke)
        canvas.drawRect(1.5f, 1.5f, width - 1.5f, framed.height - 1.5f, stroke)
        return framed
    }

    private fun loadThreadShareLogoBitmap(maxWidth: Int, maxHeight: Int): Bitmap? =
            runCatching {
                appContext.assets.open("lucide/raft-logo.svg").use { input ->
                    val svg = SVG.getFromInputStream(input)
                    val aspectRatio =
                            svg.documentAspectRatio
                                    .takeIf { it > 0f }
                                    ?: (svg.documentWidth / svg.documentHeight).takeIf { it > 0f }
                                    ?: 4.05f
                    var logoWidth = maxWidth.coerceAtLeast(1)
                    val logoHeight =
                            (logoWidth / aspectRatio)
                                    .roundToInt()
                                    .coerceAtLeast(1)
                    if (logoHeight > maxHeight) {
                        logoWidth = (maxHeight * aspectRatio).roundToInt().coerceAtLeast(1)
                    }
                    val finalLogoHeight =
                            (logoWidth / aspectRatio)
                                    .roundToInt()
                                    .coerceAtLeast(1)
                    svg.setDocumentWidth(logoWidth.toFloat())
                    svg.setDocumentHeight(finalLogoHeight.toFloat())
                    val picture = svg.renderToPicture(logoWidth, finalLogoHeight)
                    Bitmap.createBitmap(logoWidth, finalLogoHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                        picture.draw(Canvas(bitmap))
                    }
                }
            }.getOrNull()

    private fun Int.centeredTextBaseline(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return this / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    private fun saveThreadShareImage(params: String?, callback: KuiklyRenderCallback?) {
        SlockKuiklyThreadAdapter.execute("thread-share-image-save") {
            val request = JSONObject(params ?: "{}")
            callback?.invoke(saveThreadShareImageFile(request))
        }
    }

    private fun saveThreadShareImageFile(request: JSONObject): Map<String, Any> {
        val source = File(request.optString("path"))
        if (!source.exists() || source.length() <= 0L) {
            return mapOf("status" to "failed", "message" to "Image file unavailable")
        }
        val displayName = "raft-message-share-${System.currentTimeMillis()}.png"
        val resolver = appContext.contentResolver
        val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Raft")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
        val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return mapOf("status" to "failed", "message" to "Unable to save image")
        val copied =
                runCatching {
                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(source).use { input -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return mapOf("status" to "failed", "message" to "Unable to save image")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return mapOf(
                "status" to "ok",
                "uri" to uri.toString(),
                "message" to "Image saved to Pictures/Raft"
        )
    }

    private fun threadShareImageCacheDir(): File = File(appContext.cacheDir, THREAD_SHARE_IMAGE_CACHE_DIR_NAME)

    private fun cleanupThreadShareImageCache(cacheDir: File) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()
                ?.filter { file -> now - file.lastModified() > THREAD_SHARE_IMAGE_CACHE_MAX_AGE_MS }
                ?.forEach { file -> runCatching { file.delete() } }
    }

    /**
     * Legacy bridge support is intentionally terminal-only. Mounted production transport and
     * inspection use SlockAvatarMountedNativeProviderRegistry directly; reviving the old
     * inspect/await/finalize actions here would create a second downloader/byte authority.
     */
    private fun avatarCacheCapability(params: String?, callback: KuiklyRenderCallback?) {
        val request = runCatching { JSONObject(params ?: "{}") }.getOrNull()
        val action = request?.optString("action").orEmpty()
        val controller = SlockAndroidAvatarCacheProcess.currentOrNull()
        if (request?.optString("protocol") != "slock_avatar_cache_v1" ||
                controller == null ||
                !isSlockAvatarLegacyTerminalBridgeAction(action)
        ) {
            callback?.invoke(mapOf("status" to "unsupported"))
            return
        }
        val nativePartitionKey = request.optString("nativePartitionKey")
        val nativeSubjectKey = request.optString("nativeSubjectKey")
        if (!nativePartitionKey.matches(SlockAndroidAvatarOpaqueComponentPattern) ||
                (action == "retireSubject" &&
                        !nativeSubjectKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        ) {
            callback?.invoke(mapOf("status" to "error", "code" to "avatar_cache_key_invalid"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result =
                    if (action == "retireOwner") {
                        controller.retireOwner(nativePartitionKey)
                    } else {
                        controller.retireLegacySubject(nativePartitionKey, nativeSubjectKey)
                    }
            val managedBytes = controller.managedBytes()
            val response =
                    if (result == build.raft.app.data.SlockAvatarNativeOperationResult.Accepted &&
                            managedBytes != null
                    ) {
                        mapOf(
                                "status" to "ok",
                                "managedSet" to "typed_avatar_only",
                                "managedIndex" to "opaque_partition_subject_bytes_v1",
                                "budgetScope" to "avatar_only",
                                "indexedRetirement" to true,
                                "managedBytes" to managedBytes.toString()
                        )
                    } else {
                        mapOf(
                                "status" to "error",
                                "code" to
                                        when (result) {
                                            build.raft.app.data.SlockAvatarNativeOperationResult.Stale ->
                                                "avatar_cache_stale"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.AdmissionClosed ->
                                                "avatar_cache_admission_closed"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.ExpectedIdentityMismatch ->
                                                "avatar_cache_identity_mismatch"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.BusyCleanup ->
                                                "avatar_cache_cleanup_pending"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.StorageFailure ->
                                                "avatar_cache_storage_failure"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.Unsupported ->
                                                "avatar_cache_unsupported"
                                            build.raft.app.data.SlockAvatarNativeOperationResult.Accepted ->
                                                "avatar_cache_managed_bytes_unavailable"
                                        }
                        )
                    }
            callback?.invoke(response)
        }
    }

    private fun settingsCapability(params: String?, callback: KuiklyRenderCallback?) {
        val request = JSONObject(params ?: "{}")
        val action = request.optString("action")
        if (!BuildConfig.HANDS_APK_UPDATE_ENABLED &&
            action in setOf("handsDownloadProgress", "handsUpdateCheck", "handsUpdateStatus", "handsUpdateInstall")
        ) {
            callback?.invoke(handsApkUpdateDisabledResponse())
            return
        }
        if (action == "handsDownloadProgress") {
            val token = request.optString("token")
            val tracked = handsUpdateDownloads.values.firstOrNull { it.downloadId.toString() == token }
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val progress = withContext(Dispatchers.IO) {
                    runCatching { tracked?.progressPayload() }.getOrNull()
                }
                callback?.invoke(progress ?: mapOf("status" to "unavailable"))
            }
            return
        }
        if (action == "handsUpdateCheck" || action == "handsUpdateStatus" || action == "handsUpdateInstall") {
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result =
                        runCatching {
                                    val install = action != "handsUpdateStatus"
                                    val tracked = handsUpdateDownloads.values.firstOrNull {
                                        it.downloadId.toString() == request.optString("token")
                                    }
                                    if (action == "handsUpdateInstall" && tracked != null && !tracked.failed &&
                                        tracked.status() == DownloadManager.STATUS_SUCCESSFUL) {
                                        // A completed local APK must remain installable while Hands is offline.
                                        val local = tracked.toInstallResult(appContext, source = "progress_button")
                                        loadSettingsDeveloper() + mapOf(
                                            "updateCheckSupported" to true,
                                            "updateAvailable" to true,
                                            "currentVersionLabel" to BuildConfig.VERSION_NAME,
                                            "currentVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                                            "latestVersionCode" to tracked.versionCode,
                                            "updateTransactionState" to if (local.errorCode == null) "installer_opened" else "ready_to_install",
                                            "updateCheckStatusLabel" to local.statusLabel,
                                            "updateCheckActionLabel" to local.actionLabel,
                                            "updateCheckDetail" to local.detail,
                                            "updateCheckErrorCode" to local.errorCode.orEmpty(),
                                            "updateDownloadProgressToken" to tracked.downloadId.toString()
                                        )
                                    } else {
                                        checkHandsUpdate(install = install)
                                    }
                                }
                                .getOrElse { throwable ->
                                    SlockAppLog.record(
                                            "SlockHandsUpdate",
                                            "check_failed action=$action base=${BuildConfig.HANDS_BASE_URL} " +
                                                    "error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                                    )
                                    mapOf(
                                            "status" to "error",
                                            "code" to "settings_hands_update_check_failed",
                                            "message" to (throwable.message ?: "Hands update check failed")
                                    )
                                }
                callback?.invoke(result)
            }
            return
        }
        if (action == "feedbackDiagnosticsStart") {
            startFeedbackDiagnosticsUpload(request, callback)
            return
        }
        if (action == "feedbackDiagnosticsCancel") {
            cancelFeedbackDiagnosticsUpload(request, callback)
            return
        }
        if (action == "sendFeedback" || action == "submitFeedback") {
            SlockAppLog.record(
                    "SlockDiagnostics",
                    "settings_capability_feedback_action action=$action hasMessage=${request.optString("message").isNotBlank()} " +
                            "fileCount=${request.optJSONArray("files")?.length() ?: 0}"
            )
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result = submitFeedbackToHands(request.takeIf { action == "submitFeedback" })
                SlockAppLog.record(
                        "SlockDiagnostics",
                        "settings_capability_feedback_result action=$action status=${result["status"]} code=${result["code"] ?: "-"}"
                )
                callback?.invoke(result)
            }
            return
        }
        if (action == "promoteFeedbackDraftAttachments" || action == "discardFeedbackDraftAttachments") {
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                        if (action == "promoteFeedbackDraftAttachments") {
                                            promoteFeedbackDraftAttachments(request)
                                        } else {
                                            discardFeedbackDraftAttachments(request)
                                        }
                                    }
                                    .getOrElse { throwable ->
                                        mapOf(
                                                "status" to "error",
                                                "code" to feedbackAttachmentPrepareErrorCode(throwable),
                                                "message" to (throwable.message
                                                        ?: "Feedback draft attachment storage failed")
                                        )
                                    }
                        }
                callback?.invoke(result)
            }
            return
        }
        if (action == "prefetchReleaseNotes") {
            val url = request.optString("url")
            if (!SlockReleaseNotesDocumentCache.isReleaseNotesUrl(url)) {
                callback?.invoke(mapOf("status" to "unsupported", "code" to "release_notes_prefetch_url_invalid"))
                return
            }
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.IO)
            scope.launch(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                val cached = SlockReleaseNotesDocumentCache(appContext).prefetch(url)
                SlockAppLog.record(
                        "SlockReleaseNotes",
                        "prefetch_result cached=$cached durationMs=${System.currentTimeMillis() - startedAt} " +
                                binaryDownloadUrlDiagnostics(url)
                )
            }
            callback?.invoke(mapOf("status" to "started"))
            return
        }
        val result =
                runCatching {
                    when (request.optString("action")) {
                        "loadStorage" -> loadSettingsStorage()
                        "clearStorage" -> clearSettingsStorage(request.optString("target"))
                        "loadAbout" -> loadSettingsAbout()
                        "loadOpenSourceLicenses" -> loadOpenSourceLicenses()
                        "loadDeveloper" -> loadSettingsDeveloper()
                        "setApiEnvironment" -> setApiEnvironment(request)
                        "apiEnvironmentChanged" -> onApiEnvironmentChanged(request)
                        "loadDiagnostics" -> loadSettingsDiagnostics()
                        "setDiagnosticFeatureFlagOverride" -> setDiagnosticFeatureFlagOverride(request)
                        "copyDiagnostics" -> {
                            copyText("Raft diagnostics", buildMergedSettingsDiagnosticsExportText())
                            SlockAppLog.record("SlockDiagnostics", "shared_copy_diagnostics")
                            mapOf("status" to "ok")
                        }
                        "shareDiagnostics" -> {
                            shareText(
                                    subject = "Raft diagnostics",
                                    text = buildMergedSettingsDiagnosticsExportText()
                            )
                            SlockAppLog.record("SlockDiagnostics", "shared_share_diagnostics")
                            mapOf("status" to "ok")
                        }
                        "clearDiagnostics" -> {
                            SlockAndroidLocalDiagnostics.clearLocalDiagnosticsFiles(appContext)
                            SlockAppLog.record("SlockDiagnostics", "shared_logs_cleared")
                            mapOf("status" to "ok")
                        }
                        "prepareDiagnosticsFeedback" -> {
                            // Raft-channel upload flow retired: feedback goes to
                            // Hands via sendFeedback. A non-draft response skips
                            // the channel picker in the shared settings UI.
                            mapOf("status" to "unsupported")
                        }
                        "getNotificationPermissionStatus" -> getNotificationPermissionStatusCapability()
                        "requestNotificationPermission" -> requestNotificationPermissionCapability()
                        "openNotificationSettings" -> {
                            openNotificationSettingsCapability()
                            mapOf("status" to "ok")
                        }
                        "sendTestNotification" -> sendTestNotificationCapability()
                        "postUnreadNotification" -> postUnreadNotificationCapability(request)
                        "mobileOAuthLink" -> return startMobileOAuthLink(request, callback)
                        "appLoginPrepare" -> prepareAppLoginCapability()
                        "appLoginOpen" -> openAppLoginCapability(request)
                        else -> mapOf("status" to "error", "code" to "settings_capability_unknown")
                    }
                }.getOrElse { throwable ->
                    mapOf(
                            "status" to "error",
                            "code" to "settings_capability_failed",
                            "message" to (throwable.message ?: "Settings capability failed")
                    )
                }
        callback?.invoke(result)
    }

    /** Browser web login (the HarmonyOS flow): PKCE for the shared coordinator, which keeps the verifier. */
    private fun prepareAppLoginCapability(): Map<String, Any> {
        val pkce = AndroidMobileOAuth.createPkce()
        return mapOf(
                "status" to "prepared",
                "generation" to UUID.randomUUID().toString(),
                "codeVerifier" to pkce.codeVerifier,
                "codeChallenge" to pkce.codeChallenge,
                "returnUri" to AndroidMobileOAuth.AppLoginReturnUri
        )
    }

    private fun openAppLoginCapability(request: JSONObject): Map<String, Any> {
        val authorizationUrl = request.optString("authorizationUrl").trim()
        if (!authorizationUrl.startsWith("https://")) {
            return mapOf("status" to "error", "code" to "settings_mobile_oauth_url_invalid")
        }
        val uri = Uri.parse(authorizationUrl)
        // The web login page is on the Raft web origin, which every installed Raft build claims as an App Link;
        // open a browser explicitly. Without one there is no fallback: a plain VIEW would land in a Raft app.
        val intent =
                androidExternalBrowserIntent(appContext, uri)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: return mapOf("status" to "error", "code" to "settings_mobile_oauth_browser_unavailable")
        return runCatching { appContext.startActivity(intent) }
                .fold(
                        onSuccess = { mapOf("status" to "started") },
                        onFailure = { mapOf("status" to "error", "code" to "settings_mobile_oauth_browser_failed") }
                )
    }

    private fun startMobileOAuthLink(request: JSONObject, callback: KuiklyRenderCallback?) {
        val provider = request.optString("providerId").trim().lowercase(Locale.US)
        if (!AndroidMobileOAuth.isSupportedProvider(provider)) {
            callback?.invoke(
                    mapOf(
                            "status" to "error",
                            "code" to "settings_mobile_oauth_link_provider_unsupported",
                            "message" to "This account provider is not available on Android yet"
                    )
            )
            return
        }
        val lifecycleOwner = activity as? SlockKuiklyActivity ?: appContext as? SlockKuiklyActivity
        if (lifecycleOwner == null) {
            callback?.invoke(
                    mapOf(
                            "status" to "error",
                            "code" to "settings_mobile_oauth_link_host_unavailable",
                            "message" to "Unable to open ${provider.socialProviderDisplayName()} account linking"
                    )
            )
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            val pkce = AndroidMobileOAuth.createPkce()
            val authDataSource =
                    SharedAuthDataSource(
                            api = AndroidAuthApiFactory.createAuthApi(appContext),
                            sessionStore =
                                    KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)),
                            diagnosticsLogger = ::logSharedAuthDiagnostics
                    )
            val startResult =
                    withContext(Dispatchers.IO) {
                        authDataSource.startMobileOAuthLink(
                                provider = provider,
                                returnUri = AndroidMobileOAuth.ReturnUri,
                                codeChallenge = pkce.codeChallenge
                        )
                    }
            when (startResult) {
                is SharedAuthResult.Success -> {
                    AndroidMobileOAuthPendingStore(appContext).save(
                            AndroidMobileOAuthPendingRequest(
                                    provider = provider,
                                    requestId = startResult.data.requestId,
                                    codeVerifier = pkce.codeVerifier,
                                    mode = AndroidMobileOAuth.ModeLink
                            )
                    )
                    val intent =
                            Intent(Intent.ACTION_VIEW, Uri.parse(startResult.data.authorizationUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                    runCatching {
                                appContext.startActivity(intent)
                            }
                            .onSuccess {
                                callback?.invoke(mapOf("status" to "started", "code" to 202))
                            }
                            .onFailure { throwable ->
                                AndroidMobileOAuthPendingStore(appContext).clear()
                                val message =
                                        if (throwable is ActivityNotFoundException) {
                                            "No browser can open ${provider.socialProviderDisplayName()} account linking"
                                        } else {
                                            "Unable to open ${provider.socialProviderDisplayName()} account linking"
                                        }
                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                callback?.invoke(
                                        mapOf(
                                                "status" to "error",
                                                "code" to "settings_mobile_oauth_link_open_failed",
                                                "message" to message
                                        )
                                )
                            }
                }
                is SharedAuthResult.Failure -> {
                    callback?.invoke(
                            mapOf(
                                    "status" to "error",
                                    "code" to "settings_mobile_oauth_link_start_failed",
                                    "message" to startResult.message
                            )
                    )
                }
                SharedAuthResult.Unauthenticated -> {
                    callback?.invoke(
                            mapOf(
                                    "status" to "error",
                                    "code" to "settings_mobile_oauth_link_auth_required",
                                    "message" to "Sign in again to link ${provider.socialProviderDisplayName()}."
                            )
                    )
                }
            }
        }
    }

    private fun loadSettingsStorage(
            snapshot: StorageManagementSnapshot = StorageManagementRepository(appContext).snapshot(),
            feedback: String? = null
    ): Map<String, Any> =
            mapOf(
                    "status" to "ok",
                    "totalSizeLabel" to snapshot.totalBytes.formatStorageSizeLabel(),
                    "feedback" to feedback.orEmpty(),
                    // Serialized like entriesJson (#484): rows cross the render
                    // bridge as a primitive string, independent of container
                    // marshal support. OHOS/iOS hosts still send structured
                    // "rows"; the shared parser accepts both.
                    "rowsJson" to
                            JSONArray()
                                    .put(
                                            storageRowJson(
                                                    target = "database",
                                                    title = "Local database",
                                                    detail = "Messages, task metadata, and server snapshots stored on this device.",
                                                    sizeLabel = snapshot.localDatabaseBytes.formatStorageSizeLabel(),
                                                    clearEnabled = false
                                            )
                                    )
                                    .put(
                                            storageRowJson(
                                                    target = "imageCache",
                                                    title = "Image cache",
                                                    detail = "Downloaded image previews kept for faster viewing.",
                                                    sizeLabel = snapshot.imageCacheBytes.formatStorageSizeLabel(),
                                                    clearEnabled = true
                                            )
                                    )
                                    .put(
                                            storageRowJson(
                                                    target = "attachmentDownloads",
                                                    title = "Attachment staging",
                                                    detail = "Temporary data from active or failed downloads. Completed files remain in Downloads.",
                                                    sizeLabel = snapshot.attachmentDownloadBytes.formatStorageSizeLabel(),
                                                    clearEnabled = true
                                            )
                                    )
                                    .put(
                                            storageRowJson(
                                                    target = "temporaryAndLogs",
                                                    title = "Temporary files and logs",
                                                    detail = "Debug logs and temporary files generated by the app.",
                                                    sizeLabel = snapshot.temporaryAndLogBytes.formatStorageSizeLabel(),
                                                    clearEnabled = true
                                            )
                                    )
                                    .toString()
            )

    private fun clearSettingsStorage(target: String): Map<String, Any> {
        val clearTarget =
                when (target) {
                    "imageCache" -> StorageClearTarget.ImageCache
                    "attachmentDownloads" -> StorageClearTarget.AttachmentDownloads
                    "temporaryAndLogs" -> StorageClearTarget.TemporaryAndLogs
                    else -> null
                } ?: return mapOf("status" to "error", "code" to "settings_storage_clear_unsupported")
        val repository = StorageManagementRepository(appContext)
        val snapshot =
                if (clearTarget == StorageClearTarget.AttachmentDownloads) {
                    repository.clearAttachmentStaging(::clearDirectAttachmentStaging)
                            ?: return mapOf(
                                    "status" to "error",
                                    "code" to "settings_storage_clear_failed"
                            )
                } else {
                    repository.clear(clearTarget)
                }
        val finalSnapshot =
                if (clearTarget == StorageClearTarget.TemporaryAndLogs) {
                    SlockAndroidLocalDiagnostics.clearLocalDiagnosticsFiles(appContext)
                    // Diagnostics logs are measured in this row but cleared here, after
                    // the repository snapshot; measure again so the row is not stale.
                    repository.snapshot()
                } else {
                    snapshot
                }
        val feedback =
                when (clearTarget) {
                    StorageClearTarget.ImageCache -> "Image cache cleared."
                    StorageClearTarget.AttachmentDownloads -> "Attachment staging cleared. Completed Downloads were not removed."
                    StorageClearTarget.TemporaryAndLogs -> "Temporary files and diagnostics logs cleared."
                }
        SlockAppLog.record("SlockStorage", "shared_clear target=$target")
        return loadSettingsStorage(snapshot = finalSnapshot, feedback = feedback)
    }

    private fun storageRowJson(
            target: String,
            title: String,
            detail: String,
            sizeLabel: String,
            clearEnabled: Boolean
    ): JSONObject =
            JSONObject()
                    .put("target", target)
                    .put("title", title)
                    .put("detail", detail)
                    .put("sizeLabel", sizeLabel)
                    .put("clearEnabled", clearEnabled)

    private fun Long.formatStorageSizeLabel(): String =
            formatStorageSizeLabel(
                    bytes = this,
                    languageTag = SlockHostStrings.appLocaleLanguageTag()
            )

    private fun requestNotificationPermissionCapability(): Map<String, Any> {
        if (hasNotificationPermission()) {
            AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(appContext)
            return if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
                mapOf("status" to "ok", "code" to "permission_granted")
            } else {
                openNotificationSettingsCapability()
                mapOf(
                        "status" to "pending",
                        "code" to "notifications_disabled",
                        "message" to "System notification settings opened."
                )
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (requestPostNotificationsPermission()) {
                mapOf("status" to "pending", "code" to "permission_requested")
            } else {
                mapOf("status" to "error", "code" to "permission_denied")
            }
        } else {
            mapOf("status" to "error", "code" to "permission_denied")
        }
    }

    // The shared Settings > Notifications banner polls this on every load.
    // Codes must stay within the set systemNotificationPermissionSnapshot maps
    // (permission_authorized / permission_not_determined / permission_denied)
    // or the shared side falls back to a permanent "needs permission" banner.
    private fun getNotificationPermissionStatusCapability(): Map<String, Any> {
        val runtimeGranted = hasNotificationPermission()
        val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val code =
                when {
                    runtimeGranted && notificationsEnabled -> "permission_authorized"
                    !runtimeGranted -> "permission_not_determined"
                    else -> "permission_denied"
                }
        val message =
                when (code) {
                    "permission_authorized" -> "System notifications are enabled for Raft."
                    "permission_not_determined" -> "Notification permission has not been requested yet."
                    else -> "System notifications are disabled for Raft in system settings."
                }
        return mapOf("status" to "ok", "code" to code, "message" to message)
    }

    private fun sendTestNotificationCapability(): Map<String, Any> {
        if (!hasNotificationPermission()) {
            return requestNotificationPermissionCapability()
        }
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        if (AndroidUnreadNotificationPoster.isNotificationChannelBlocked(appContext, AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID)) {
            return mapOf("status" to "error", "code" to "channel_blocked")
        }
        val notificationId = ((System.currentTimeMillis() % Int.MAX_VALUE).toInt()).coerceAtLeast(1)
        val contentIntent =
                appContext.packageManager
                        .getLaunchIntentForPackage(appContext.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        ?.let { intent ->
                            PendingIntent.getActivity(
                                    appContext,
                                    notificationId,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        }
        return runCatching {
                    NotificationManagerCompat.from(appContext)
                            .notify(
                                    notificationId,
                                    // The HIGH-importance mention channel is the one that
                                    // heads-up pops on real devices; a test that lands
                                    // silently in the shade reads as "nothing happened".
                                    // It also makes channel_blocked feedback meaningful for
                                    // the channel users actually care about.
                                    NotificationCompat.Builder(appContext, AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID)
                                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                                            .setContentTitle(appContext.getString(R.string.settings_notification_test_title))
                                            .setContentText(appContext.getString(R.string.settings_notification_test_text))
                                            .setStyle(
                                                    NotificationCompat.BigTextStyle()
                                                            .bigText(appContext.getString(R.string.settings_notification_test_text))
                                            )
                                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                            .setWhen(System.currentTimeMillis())
                                            .setShowWhen(true)
                                            .setContentIntent(contentIntent)
                                            .setAutoCancel(true)
                                            .build()
                            )
                    mapOf("status" to "ok", "code" to "posted", "notificationId" to notificationId)
                }
                .getOrElse { error ->
                    mapOf(
                            "status" to "error",
                            "code" to "post_failed",
                            "message" to (error.message ?: error.javaClass.simpleName)
                    )
                }
    }

    private fun postUnreadNotificationCapability(request: JSONObject): Map<String, Any> =
            AndroidUnreadNotificationPoster.postUnreadNotification(appContext, request)

    private fun hasNotificationPermission(): Boolean =
            AndroidUnreadNotificationPoster.hasNotificationPermission(appContext)

    private fun requestPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val hostActivity = activity ?: appContext as? Activity ?: return false
        hostActivity.runOnUiThread {
            hostActivity.requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS_CODE
            )
        }
        SlockAppLog.record("SlockSettingsNotification", "permission_request_dialog_opened")
        return true
    }

    private fun openNotificationSettingsCapability() {
        val notificationSettingsIntent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    null
                }
        runCatching {
                    if (notificationSettingsIntent != null) {
                        appContext.startActivity(notificationSettingsIntent)
                    } else {
                        throw ActivityNotFoundException("Notification settings unavailable")
                    }
                }
                .onFailure {
                    appContext.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", appContext.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                    )
                }
    }

    private fun loadSettingsAbout(): Map<String, Any> {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val displayMetrics = appContext.resources.displayMetrics
        val configuration = appContext.resources.configuration
        val versionCode = packageInfo.versionCodeCompat()
        val installTimeLabel =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(packageInfo.firstInstallTime))
        val session =
                runBlocking {
                    KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).readSession()
                }
        val selectedServer =
                session?.currentServerId?.let { serverId -> session.servers.firstOrNull { it.id == serverId } }
                        ?: session?.servers?.firstOrNull()
        return mapOf(
                "status" to "ok",
                "appName" to "Raft",
                "versionLabel" to (packageInfo.versionName ?: "unknown"),
                "versionCodeLabel" to versionCode,
                "buildLabel" to versionCode,
                "sourceLabel" to BuildConfig.GIT_COMMIT.ifBlank { "unknown" },
                "packageName" to appContext.packageName,
                "signedInUserLabel" to (session?.user?.bestDisplayName ?: "Signed out"),
                "selectedServerLabel" to (selectedServer?.name ?: "No selected server"),
                "sessionStatusLabel" to if (session != null) "Signed in" else "Signed out",
                "socketStatusLabel" to "Host active",
                "notificationStatusLabel" to "Managed by system settings",
                "deviceLabel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "modelLabel" to Build.MODEL,
                "platformLabel" to "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
                "sdkLabel" to Build.VERSION.SDK_INT.toString(),
                "installTimeLabel" to installTimeLabel,
                "screenLabel" to "${displayMetrics.widthPixels}x${displayMetrics.heightPixels} px",
                "densityLabel" to "${displayMetrics.density} (${displayMetrics.densityDpi} dpi)",
                "fontScaleLabel" to configuration.fontScale.toString(),
                "baseUrl" to AndroidAuthApiFactory.currentBaseUrl(appContext),
                "buildTime" to BuildConfig.BUILD_TIME,
                "gitCommit" to BuildConfig.GIT_COMMIT
        )
    }

    private fun loadOpenSourceLicenses(): Map<String, Any> {
        val payloadText =
                runCatching {
                            appContext.assets.open("oss_licenses.json").bufferedReader().use { reader ->
                                reader.readText()
                            }
                        }
                        .getOrElse { throwable ->
                            SlockAppLog.record(
                                    "SlockSettings",
                                    "open_source_licenses_load_failed error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                            )
                            return mapOf(
                                    "status" to "error",
                                    "code" to "settings_open_source_licenses_asset_missing"
                            )
                        }
        val payload =
                runCatching { JSONObject(payloadText) }
                        .getOrElse { throwable ->
                            SlockAppLog.record(
                                    "SlockSettings",
                                    "open_source_licenses_parse_failed error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                            )
                            return mapOf(
                                    "status" to "error",
                                    "code" to "settings_open_source_licenses_asset_invalid"
                            )
                        }
        val sourceItems = payload.optJSONArray("items") ?: JSONArray()
        return mapOf(
                "status" to "ok",
                "schemaVersion" to payload.optInt("schemaVersion", 1),
                "variant" to payload.optString("variant"),
                "sourceConfiguration" to payload.optString("sourceConfiguration"),
                "itemCount" to sourceItems.length(),
                "payloadJson" to payloadText
        )
    }

    private fun loadSettingsDeveloper(
            feedback: String? = null,
            updateRoute: HandsUpdateRoute = handsUpdateRouteForRuntime()
    ): Map<String, Any> {
        val current = AndroidApiServerEnvironmentStore(appContext).current()
        val options =
                JSONArray().also { array ->
                    AndroidApiServerEnvironmentStore.predefined.forEach { environment ->
                        array.put(
                                JSONObject()
                                        .put("key", environment.key)
                                        .put("label", environment.label)
                                        .put("apiBaseUrl", environment.apiBaseUrl)
                                        .put("socketBaseUrl", environment.socketBaseUrl)
                                        .put("webUrl", environment.webUrl)
                        )
                    }
                }
        return mapOf(
                "status" to "ok",
                "environmentKey" to current.key,
                "environmentLabel" to current.label,
                "apiBaseUrl" to current.apiBaseUrl,
                "socketBaseUrl" to current.socketBaseUrl,
                "webUrl" to current.webUrl,
                "updateCheckSupported" to BuildConfig.HANDS_APK_UPDATE_ENABLED,
                "updateCheckStatusLabel" to
                        if (BuildConfig.HANDS_APK_UPDATE_ENABLED) "Ready" else "Disabled",
                "updateCheckDetail" to
                        if (BuildConfig.HANDS_APK_UPDATE_ENABLED) {
                            "Check Hands channel ${updateRoute.channel} for a newer Android APK."
                        } else {
                            "Google Play builds update through Google Play and do not install APKs in-app."
                        },
                // Serialized like entriesJson (#484): options cross the render
                // bridge as a primitive string, independent of container
                // marshal support. OHOS/iOS hosts still send structured
                // "options"; the shared parser accepts both.
                "optionsJson" to options.toString(),
                "feedback" to feedback.orEmpty()
        )
    }

    private suspend fun checkHandsUpdate(install: Boolean = true): Map<String, Any> {
        val arch = Build.SUPPORTED_ABIS.firstOrNull { it.isNotBlank() }
        val updateRoute = handsUpdateRouteForRuntime()
        val deviceId = HandsDeviceId.get(appContext)
        val languageTag =
                handsUpdateLanguageTag(
                        appLanguageTag = SlockHostStrings.appLocaleLanguageTag(),
                        systemLanguageTag = Locale.getDefault().toLanguageTag()
                )
        if (updateRoute.deltaEnabled) {
            return checkHandsDeltaUpdate(
                    install = install,
                    arch = arch,
                    updateRoute = updateRoute,
                    deviceId = deviceId,
                    languageTag = languageTag
            )
        }
        val response =
                HandsClient(BuildConfig.HANDS_BASE_URL)
                        .checkForUpdate(
                                slug = updateRoute.appSlug,
                                channel = updateRoute.channel,
                                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                                productType = "android-apk",
                                platform = "android",
                                arch = arch,
                                filetype = "apk",
                                deviceId = deviceId,
                                languageTag = languageTag
                        )
        val updateAvailable = response.requireUpdate() != null
        val displaySizes =
                response.handsUpdateDisplaySizes(deltaEnabled = false)
        SlockAppLog.record(
                "SlockHandsUpdate",
                "check status=ok mode=${updateRoute.mode} " +
                        "action=${if (install) "install" else "status"} " +
                        "slug=${updateRoute.appSlug} channel=${updateRoute.channel} device_id=$deviceId " +
                        "language=${languageTag} " +
                        "current=${BuildConfig.VERSION_CODE} " +
                        "latest=${response.latest?.version_code ?: response.latest_version_code ?: 0} available=$updateAvailable"
        )
        val installResult =
                when {
                    install && updateAvailable -> installHandsUpdate(response, arch)
                    updateAvailable -> currentHandsDownloadResult(response, arch)
                    else -> HandsInstallResult()
                }
        val updateStatusLabel = installResult.statusLabel.ifBlank { response.handsStatusLabel() }
        val updateDetail = installResult.detail.ifBlank { response.handsDetail(displaySizes) }
        val feedback =
                when {
                    updateAvailable -> "Update available: ${response.latest?.version?.ifBlank { null } ?: "version code ${response.latest?.version_code ?: 0L}"}."
                    else -> "No Hands update available on ${updateRoute.channel}."
                }
        return loadSettingsDeveloper(feedback = feedback, updateRoute = updateRoute) +
                mapOf(
                        "updateCheckSupported" to true,
                        "updateCheckStatusLabel" to updateStatusLabel,
                        "updateCheckDetail" to updateDetail,
                        "updateCheckActionLabel" to installResult.actionLabel,
                        "updateAvailable" to updateAvailable,
                        "currentVersionLabel" to (BuildConfig.VERSION_NAME.ifBlank { BuildConfig.VERSION_CODE.toString() }),
                        "currentVersionCode" to BuildConfig.VERSION_CODE.toLong(),
                        "latestVersionLabel" to response.latest?.version.orEmpty(),
                        "latestVersionCode" to (response.latest?.version_code ?: response.latest_version_code ?: 0L),
                        "latestChangelog" to response.latest?.changelog.orEmpty(),
                        "updateDownloadUrl" to response.asset?.download_url.orEmpty(),
                        "updateDownloadProgressToken" to (handsUpdateDownloads[response.handsDownloadKey(arch)]?.downloadId?.toString().orEmpty()),
                        "updateDownloadSizesResolved" to true,
                        "updateFullDownloadSizeBytes" to (displaySizes.fullSizeBytes ?: 0L),
                        "updatePatchDownloadSizeBytes" to (displaySizes.patchSizeBytes ?: 0L),
                        "updateCheckedAtLabel" to System.currentTimeMillis().toString(),
                        "updateCheckErrorCode" to installResult.errorCode.orEmpty()
                )
    }

    private suspend fun checkHandsDeltaUpdate(
            install: Boolean,
            arch: String?,
            updateRoute: HandsUpdateRoute,
            deviceId: String,
            languageTag: String
    ): Map<String, Any> {
        val checker =
                UpdateChecker(
                        context = appContext,
                        baseUrl = BuildConfig.HANDS_BASE_URL,
                        appSlug = updateRoute.appSlug,
                        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        channel = updateRoute.channel,
                        productType = "android-apk",
                        platform = "android",
                        arch = arch,
                        deviceId = deviceId,
                        deltaApplyEnabled = true,
                        languageTag = languageTag,
                        eventListener =
                                HandsUpdateEventListener { event ->
                                    SlockAppLog.record(
                                            "SlockHandsUpdate",
                                            handsUpdateEventLogLine(
                                                    name = event.name,
                                                    stateWire = event.state.wireValue,
                                                    targetVersionCode = event.targetVersionCode,
                                                    errorCode = event.errorCode,
                                                    requestedLanguageTag = event.requestedLanguageTag,
                                                    resolvedLanguageTag = event.resolvedLanguageTag
                                            )
                                    )
                                    if (handsUpdateEventShouldRefresh(event.name)) {
                                        emitHandsUpdateStatusEvent()
                                    }
                                }
                )
        val status =
                if (install) {
                    alphaHandsUpdateCallOwner.runIfEligible(eligible = true) {
                        checker.install(languageTag)
                    } ?: checker.status()
                } else {
                    checker.status()
                }
        val currentVersionCode = status.installedVersionCode ?: BuildConfig.VERSION_CODE.toLong()
        val offerResult =
                runCatching {
                    HandsClient(BuildConfig.HANDS_BASE_URL)
                            .checkForUpdate(
                                    slug = updateRoute.appSlug,
                                    channel = updateRoute.channel,
                                    currentVersionCode = currentVersionCode,
                                    productType = "android-apk",
                                    platform = "android",
                                    arch = arch,
                                    filetype = "apk",
                                    deviceId = deviceId,
                                    languageTag = languageTag
                            )
                }
        val response = offerResult.getOrNull()
        offerResult.exceptionOrNull()?.let { error ->
            SlockAppLog.record(
                    "SlockHandsUpdate",
                    "offer_check_failed action=${if (install) "install" else "status"} " +
                            "state=${status.state.wireValue} error=${error.javaClass.simpleName}"
            )
        }
        val offerAvailable = response?.requireUpdate() != null
        val offerTargetVersionCode =
                response?.requireUpdate()?.first?.version_code
                        ?: response?.latest?.version_code
                        ?: response?.latest_version_code
        val decision =
                handsUpdateBridgeDecision(
                        sdkStateWire = status.state.wireValue,
                        sdkTarget =
                                HandsUpdateTargetIdentity(
                                        versionCode = status.targetVersionCode,
                                        buildId = status.targetBuildId,
                                        assetSha256 = status.assetSha256
                                ),
                        sdkInstalledVersionCode = status.installedVersionCode,
                        sdkRetryable = status.retryable,
                        offerChecked = offerResult.isSuccess,
                        offerAvailable = offerAvailable,
                        offerTarget =
                                HandsUpdateTargetIdentity(
                                        versionCode = offerTargetVersionCode,
                                        buildId = response?.latest?.build_id,
                                        assetSha256 = response?.asset?.sha256 ?: response?.patch?.target_sha256
                                )
                )
        val errorCode =
                when {
                    decision.targetDrifted && offerAvailable -> "target_changed"
                    decision.targetDrifted -> "offer_withdrawn"
                    else -> status.errorCode
                }
        val installResult = decision.toHandsInstallResult(errorCode)
        val latestVersionCode =
                response?.latest?.version_code
                        ?: response?.latest_version_code
                        ?: decision.targetVersionCode
                        ?: 0L
        val displaySizes =
                response?.handsUpdateDisplaySizes(deltaEnabled = updateRoute.deltaEnabled)
                        ?: HandsUpdateDisplaySizes()
        val updateDetail =
                response?.handsDetail(displaySizes)
                        ?: SlockHostStrings.appUpdateDetail(
                                updateAvailable = decision.updateAvailable,
                                sizeLabel = null
                        )
        val feedback =
                if (decision.updateAvailable) {
                    "Update available: ${response?.latest?.version?.ifBlank { null } ?: "version code $latestVersionCode"}."
                } else {
                    "No Hands update available on ${updateRoute.channel}."
                }
        SlockAppLog.record(
                "SlockHandsUpdate",
                "check status=ok mode=${updateRoute.mode} action=${if (install) "install" else "status"} " +
                        "slug=${updateRoute.appSlug} channel=${updateRoute.channel} device_id=$deviceId " +
                        "language=$languageTag state=${decision.state.wireValue} " +
                        "current=$currentVersionCode latest=$latestVersionCode available=${decision.updateAvailable}"
        )
        return loadSettingsDeveloper(feedback = feedback, updateRoute = updateRoute) +
                mapOf(
                        "updateCheckSupported" to true,
                        "updateTransactionState" to decision.state.wireValue,
                        "updateTransactionTargetVersionCode" to (decision.targetVersionCode ?: 0L),
                        "updateTransactionRetryable" to decision.retryable,
                        "updateCheckStatusLabel" to installResult.statusLabel,
                        "updateCheckDetail" to updateDetail,
                        "updateCheckActionLabel" to installResult.actionLabel,
                        "updateAvailable" to decision.updateAvailable,
                        "currentVersionLabel" to
                                if (currentVersionCode == BuildConfig.VERSION_CODE.toLong()) {
                                    BuildConfig.VERSION_NAME.ifBlank { currentVersionCode.toString() }
                                } else {
                                    currentVersionCode.toString()
                                },
                        "currentVersionCode" to currentVersionCode,
                        "latestVersionLabel" to response?.latest?.version.orEmpty(),
                        "latestVersionCode" to latestVersionCode,
                        "latestChangelog" to response?.latest?.changelog.orEmpty(),
                        "updateDownloadUrl" to response?.asset?.download_url.orEmpty(),
                        "updateDownloadSizesResolved" to offerResult.isSuccess,
                        "updateFullDownloadSizeBytes" to (displaySizes.fullSizeBytes ?: 0L),
                        "updatePatchDownloadSizeBytes" to (displaySizes.patchSizeBytes ?: 0L),
                        "updateCheckedAtLabel" to System.currentTimeMillis().toString(),
                        "updateCheckErrorCode" to errorCode.orEmpty()
                )
    }

    private fun HandsUpdateBridgeDecision.toHandsInstallResult(errorCode: String?): HandsInstallResult =
            when (state) {
                SharedSettingsUpdateTransactionState.Idle ->
                        HandsInstallResult(statusLabel = "Ready", actionLabel = "Download", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Checking ->
                        HandsInstallResult(statusLabel = "Checking", actionLabel = "Checking", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Patching ->
                        HandsInstallResult(statusLabel = "Preparing", actionLabel = "Preparing", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Downloading ->
                        HandsInstallResult(statusLabel = "Downloading", actionLabel = "Downloading", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.ReadyToInstall,
                SharedSettingsUpdateTransactionState.InstallerOpened ->
                        HandsInstallResult(statusLabel = "Install", actionLabel = "Install", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Failed,
                SharedSettingsUpdateTransactionState.Stale ->
                        HandsInstallResult(statusLabel = "Download", actionLabel = "Download", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Installed ->
                        HandsInstallResult(statusLabel = "Installed", actionLabel = "Installed", errorCode = errorCode)
                SharedSettingsUpdateTransactionState.Unknown ->
                        HandsInstallResult(statusLabel = "Ready", actionLabel = "Download", errorCode = errorCode)
            }

    private fun handsDeltaUpdateAllowedForRuntime(): Boolean {
        val allowed =
                handsDeltaUpdateAllowed(
                        configuredEnabled = BuildConfig.HANDS_DELTA_UPDATE_ENABLED,
                        buildApplicationId = BuildConfig.APPLICATION_ID,
                        runtimePackageName = appContext.packageName,
                        appSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                        channel = BuildConfig.HANDS_DELTA_CHANNEL
                )
        if (BuildConfig.HANDS_DELTA_UPDATE_ENABLED && !allowed) {
            SlockAppLog.record(
                    "SlockHandsUpdate",
                    "alpha_delta_gate_denied buildApplicationId=${BuildConfig.APPLICATION_ID} " +
                            "runtimePackageName=${appContext.packageName}"
            )
        }
        return allowed
    }

    private fun handsUpdateRouteForRuntime(): HandsUpdateRoute =
            resolveHandsUpdateRoute(
                    alphaDeltaEnabled = handsDeltaUpdateAllowedForRuntime(),
                    defaultAppSlug = BuildConfig.HANDS_APP_SLUG,
                    defaultChannel = BuildConfig.HANDS_CHANNEL,
                    deltaAppSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                    deltaChannel = BuildConfig.HANDS_DELTA_CHANNEL
            )

    private suspend fun installHandsUpdate(
            response: UpdateCheckResponse,
            arch: String?
    ): HandsInstallResult {
        return runCatching {
                    synchronized(handsUpdateDownloadLock) {
                        val tracked = currentHandsDownload(response, arch)
                        if (tracked != null) {
                            val status = tracked.status()
                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    tracked.completed = true
                                    tracked.toInstallResult(appContext, source = "button")
                                }
                                DownloadManager.STATUS_PENDING,
                                DownloadManager.STATUS_RUNNING,
                                DownloadManager.STATUS_PAUSED -> {
                                    HandsInstallResult(
                                            statusLabel = "Downloading",
                                            detail = "Raft is downloading the update APK. The button will change to Install when the download is ready.",
                                            actionLabel = "Downloading"
                                    )
                                }
                                else -> {
                                    handsUpdateDownloads.remove(tracked.key)
                                    enqueueHandsApkInstall(response, arch).toDownloadingInstallResult(response)
                                }
                            }
                        } else {
                            enqueueHandsApkInstall(response, arch).toDownloadingInstallResult(response)
                        }
                    }
                }
                .getOrElse { error ->
                    SlockAppLog.record(
                            "SlockHandsUpdate",
                            "install_failed slug=${BuildConfig.HANDS_APP_SLUG} latest=${response.latest?.version_code ?: 0} error=${error.javaClass.simpleName}"
                    )
                    HandsInstallResult(
                            statusLabel = "Install failed",
                            detail = error.message ?: "Unable to download or install the Hands APK.",
                            actionLabel = "Download",
                            errorCode = error.javaClass.simpleName
                    )
                }
    }

    private fun enqueueHandsApkInstall(response: UpdateCheckResponse, arch: String?): HandsUpdateDownload {
        val latest = response.latest ?: error("Hands response did not include a latest release.")
        val asset = response.asset ?: error("Hands response did not include an APK asset.")
        val downloadUrl = asset.download_url
        require(downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
            "Can only download HTTP/HTTPS URIs: $downloadUrl"
        }
        val downloadManager =
                ContextCompat.getSystemService(appContext, DownloadManager::class.java)
                        ?: error("DownloadManager not available")
        val fileName = "raft-hands-${latest.version_code}.apk"
        val key = response.handsDownloadKey(arch)
        val title =
                buildString {
                    append("Raft")
                    latest.version.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
                }
        val request =
                DownloadManager.Request(Uri.parse(downloadUrl))
                        .setTitle(title)
                        .setDescription("Downloading latest version...")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .setMimeType(ANDROID_PACKAGE_ARCHIVE_MIME_TYPE)
                        .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
        var downloadId = -1L
        val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                        if (completedId != downloadId) return
                        runCatching { appContext.unregisterReceiver(this) }
                        val status = handsDownloadStatus(downloadManager, completedId)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            handsUpdateDownloads[key]?.completed = true
                            SlockAppLog.record(
                                    "SlockHandsUpdate",
                                    "download_ready downloadId=$completedId latest=${latest.version_code}"
                            )
                            handsUpdateDownloads[key]?.let { download ->
                                openHandsDownloadedApkOrRequestPermission(
                                        context = appContext,
                                        download = download,
                                        source = "download_complete"
                                )
                            }
                        } else {
                            handsUpdateDownloads[key]?.failed = true
                            SlockAppLog.record(
                                    "SlockHandsUpdate",
                                    "download_failed downloadId=$completedId status=$status"
                            )
                        }
                        emitHandsUpdateStatusEvent()
                    }
                }
        ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
        )
        try {
            downloadId = downloadManager.enqueue(request)
            return HandsUpdateDownload(
                    key = key,
                    versionCode = latest.version_code,
                    downloadId = downloadId,
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    arch = arch.orEmpty(),
                    downloadManager = downloadManager
            ).also { download ->
                handsUpdateDownloads[key] = download
            }
        } catch (error: Throwable) {
            runCatching { appContext.unregisterReceiver(receiver) }
            throw error
        }
    }

    private fun currentHandsDownloadResult(response: UpdateCheckResponse, arch: String?): HandsInstallResult {
        val tracked = handsUpdateDownloads[response.handsDownloadKey(arch)] ?: return HandsInstallResult()
        return when (if (tracked.failed) DownloadManager.STATUS_FAILED else tracked.status()) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                tracked.completed = true
                HandsInstallResult(
                        statusLabel = "Install",
                        detail = "Raft update downloaded. Tap Install to open Android installer.",
                        actionLabel = "Install"
                )
            }
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED ->
                HandsInstallResult(
                        statusLabel = "Downloading",
                        detail = "Raft is downloading the update APK. The button will change to Install when the download is ready.",
                        actionLabel = "Downloading"
                )
            else -> {
                HandsInstallResult(
                    statusLabel = "Failed", actionLabel = "Download",
                    errorCode = "settings_hands_update_download_failed"
                )
            }
        }
    }

    private fun currentHandsDownload(response: UpdateCheckResponse, arch: String?): HandsUpdateDownload? {
        val key = response.handsDownloadKey(arch)
        val tracked =
                handsUpdateDownloads[key]
                        ?: run {
                            if (handsUpdateDownloads.isNotEmpty()) {
                                SlockAppLog.record(
                                        "SlockHandsUpdate",
                                        "download_state_miss key=$key tracked=${handsUpdateDownloads.size}"
                                )
                            }
                            return null
                        }
        if (tracked.failed) {
            handsUpdateDownloads.remove(key)
            return null
        }
        return tracked
    }

    private fun HandsUpdateDownload.toDownloadingInstallResult(response: UpdateCheckResponse): HandsInstallResult {
        SlockAppLog.record(
                "SlockHandsUpdate",
                "download_queued slug=${BuildConfig.HANDS_APP_SLUG} latest=${response.latest?.version_code ?: 0} assetSize=${response.asset?.size_bytes ?: 0} downloadId=$downloadId"
        )
        emitHandsUpdateStatusEvent()
        return HandsInstallResult(
                statusLabel = "Downloading",
                detail = "Raft update download started. The button will change to Install when the APK is ready.",
                actionLabel = "Downloading"
        )
    }

    private fun UpdateCheckResponse.handsDownloadKey(arch: String?): String =
            handsUpdateDownloadKey(
                    slug = BuildConfig.HANDS_APP_SLUG,
                    channel = channel,
                    versionCode = latest?.version_code ?: latest_version_code ?: 0L,
                    arch = arch
            )

    private fun emitHandsUpdateStatusEvent() {
        postAndroidAppScopeEvent(
                eventName = SLOCK_APP_UPDATE_EVENT,
                eventData = JSONObject().put("source", "android")
        )
    }

    private fun HandsUpdateDownload.toInstallResult(context: Context, source: String): HandsInstallResult =
            when (openHandsDownloadedApkOrRequestPermission(context, this, source)) {
                HANDS_INSTALL_OPENED ->
                    HandsInstallResult(
                            statusLabel = "Install",
                            detail = "Android installer opened for the downloaded Raft update.",
                            actionLabel = "Install"
                    )
                HANDS_INSTALL_PERMISSION_REQUIRED ->
                    HandsInstallResult(
                            statusLabel = "Permission required",
                            detail = "Raft update is downloaded. Allow Raft to install unknown apps, then return to continue installation.",
                            actionLabel = "Install",
                            errorCode = "install_permission_required"
                    )
                else ->
                    HandsInstallResult(
                            statusLabel = "Install failed",
                            detail = "Raft update is downloaded, but Android installer could not be opened.",
                            actionLabel = "Install",
                            errorCode = "install_intent_failed"
                    )
            }

    private fun handsDownloadStatus(downloadManager: DownloadManager, downloadId: Long): Int {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return DownloadManager.STATUS_FAILED
        cursor.use {
            if (!it.moveToFirst()) return DownloadManager.STATUS_FAILED
            val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (index >= 0) it.getInt(index) else DownloadManager.STATUS_FAILED
        }
    }

    private data class HandsInstallResult(
            val statusLabel: String = "",
            val detail: String = "",
            val actionLabel: String = "",
            val errorCode: String? = null
    )

    private fun UpdateCheckResponse.handsStatusLabel(): String =
            if (requireUpdate() != null) "Update available" else "Current"

    private fun UpdateCheckResponse.handsDetail(displaySizes: HandsUpdateDisplaySizes): String =
            SlockHostStrings.appUpdateDetail(
                    updateAvailable = requireUpdate() != null,
                    sizeLabel = displaySizes.fullSizeBytes?.formatStorageSizeLabel(),
                    patchSizeLabel = displaySizes.patchSizeBytes?.formatStorageSizeLabel()
            )

    private fun setApiEnvironment(request: JSONObject): Map<String, Any> {
        val environmentKey = request.optString("environmentKey")
        val store = AndroidApiServerEnvironmentStore(appContext)
        val before = store.current()
        val after =
                if (environmentKey == AndroidApiServerEnvironmentStore.CustomKey) {
                    store.save(
                            AndroidApiServerEnvironment(
                                    key = environmentKey,
                                    label = request.optString("environmentLabel").ifBlank { "Custom" },
                                    apiBaseUrl = request.optString("apiBaseUrl"),
                                    socketBaseUrl = request.optString("socketBaseUrl"),
                                    webUrl = request.optString("webUrl")
                            )
                    )
                } else {
                    store.select(environmentKey)
                }
                        ?: return mapOf("status" to "error", "code" to "settings_developer_environment_unknown")
        val changed = before != after
        if (changed) {
            runCatching {
                updateSlockNetworkKmmRecoveryEnvironment(
                    environmentKey = after.key,
                    apiBaseUrl = after.apiBaseUrl
                )
            }.onSuccess { diagnostics ->
                diagnostics?.let { SlockAppLog.record("SlockNetworkKmm", it) }
            }.onFailure { error ->
                SlockAppLog.record(
                    "SlockNetworkKmm",
                    "networkkmm_environment_update_failed " +
                        "reason=${error::class.simpleName ?: "unknown"}"
                )
            }
            runBlocking {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).clearSession(
                    reason = "api_environment_changed",
                    source = "android_activity"
                )
            }
            AndroidAppScopeRealtimeOwner.disconnectForSessionEnd("api_environment_changed")
            SlockAppLog.record(
                    "SlockDeveloper",
                    "api_environment_changed from=${before.key} to=${after.key}"
            )
        }
        return loadSettingsDeveloper(
                feedback =
                        if (changed) {
                            "API server switched to ${after.label}. Sign in again to use this environment."
                        } else {
                            "${after.label} is already selected."
                        }
        )
    }

    private fun onApiEnvironmentChanged(request: JSONObject): Map<String, Any> {
        AndroidAppScopeRealtimeOwner.disconnectForSessionEnd("api_environment_changed")
        SlockAppLog.record(
                "SlockDeveloper",
                "api_environment_changed from=${request.optString("beforeEnvironmentKey")} to=${request.optString("afterEnvironmentKey")}"
        )
        return mapOf("status" to "ok")
    }

    private fun loadSettingsDiagnostics(): Map<String, Any> {
        ProcessDiagnosticFeatureFlagOverrides.registry.configureEntitlement(
                BuildConfig.READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED
        )
        val entries =
                JSONArray().also { array ->
                    val journalEntries = SlockAuthCriticalJournal.recentEntries(appContext)
                    journalEntries.forEach { entry ->
                        array.put(
                                JSONObject()
                                        .put("timestampLabel", entry.timestampLabel)
                                        .put("tag", "${entry.tag}/${entry.event}")
                                        .put("message", entry.message)
                        )
                    }
                    if (journalEntries.isEmpty()) {
                        array.put(
                                JSONObject()
                                        .put("timestampLabel", System.currentTimeMillis().toString())
                                        .put("tag", "SlockAuth")
                                        .put("message", "No auth critical events have been recorded yet.")
                        )
                    }
                }
        return mapOf(
                "status" to "ok",
                "entriesJson" to entries.toString(),
                "readStateV1DiagnosticJson" to
                        ProcessReadStateV1DiagnosticOverride.controller.snapshot().toRedactedJson().toString(),
                "diagnosticFeatureFlagOverridesJson" to
                        ProcessDiagnosticFeatureFlagOverrides.registry.snapshots().toRedactedJson().toString()
        )
    }

    private fun setDiagnosticFeatureFlagOverride(request: JSONObject): Map<String, Any> {
        ProcessDiagnosticFeatureFlagOverrides.registry.configureEntitlement(
                BuildConfig.READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED
        )
        val key = request.optString("key").trim()
        val mode =
                when (request.optString("mode")) {
                    "force_off" -> DiagnosticFeatureFlagOverrideMode.ForceOff
                    "force_on" -> DiagnosticFeatureFlagOverrideMode.ForceOn
                    "server" -> DiagnosticFeatureFlagOverrideMode.Server
                    else -> return mapOf("status" to "error", "code" to "diagnostic_feature_flag_override_mode_unknown")
                }
        return when (val result = ProcessDiagnosticFeatureFlagOverrides.registry.setMode(key, mode)) {
            is DiagnosticFeatureFlagOverrideMutationResult.Applied -> {
                val snapshot = result.snapshot
                SlockAppLog.record(
                        "DiagnosticFeatureFlagOverride",
                        "source=diagnostic_override key=${snapshot.key} mode=${snapshot.mode.name} " +
                                "scope=${snapshot.boundScope?.toStableRedactedLabel() ?: "none"}"
                )
                mapOf(
                        "status" to "ok",
                        "diagnosticFeatureFlagOverridesJson" to
                                ProcessDiagnosticFeatureFlagOverrides.registry.snapshots().toRedactedJson().toString()
                )
            }
            is DiagnosticFeatureFlagOverrideMutationResult.Rejected ->
                mapOf(
                        "status" to "error",
                        "code" to result.code,
                        "diagnosticFeatureFlagOverridesJson" to
                                ProcessDiagnosticFeatureFlagOverrides.registry.snapshots().toRedactedJson().toString()
                )
        }
    }

    private data class DiagnosticsFeedbackFile(val file: File, val summaryText: String)

    /**
     * Submit in-app feedback to the Hands ticket system (kind=bug) with the
     * diagnostics archive attached. Returns status=ok when the ticket was
     * created so the settings UI can confirm.
     */
    private suspend fun submitFeedbackToHands(request: JSONObject? = null): Map<String, Any> {
        SlockAppLog.record(
                "SlockDiagnostics",
                "hands_feedback_submit_enter source=${if (request != null) "shared" else "native_prompt"} " +
                        "messageLength=${request?.optString("message")?.length ?: 0} " +
                        "fileCount=${request?.optJSONArray("files")?.length() ?: 0}"
        )
        val description = request?.optString("message")?.trim()?.takeIf { it.isNotBlank() }
            ?: promptFeedbackDescription()
            ?: return mapOf("status" to "cancelled").also {
                SlockAppLog.record("SlockDiagnostics", "hands_feedback_submit_cancelled reason=blank_or_cancelled")
            }
        val userFilesResult =
                withContext(Dispatchers.IO) {
                    runCatching { request?.optJSONArray("files").toFeedbackAttachmentFiles() }
                }
        val userFiles =
                userFilesResult.getOrElse { throwable ->
                    SlockAppLog.record(
                            "SlockDiagnostics",
                            "hands_feedback_attachment_prepare_failed error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                    )
                    return mapOf<String, Any>(
                            "status" to "error",
                            "code" to feedbackAttachmentPrepareErrorCode(throwable),
                            "message" to (throwable.message ?: "Unable to read feedback attachment")
                    )
                }
        val diagnosticsFile =
                if (HandsFeedbackAttachmentPolicy.canAttachDiagnostics(userFiles)) {
                    withContext(Dispatchers.IO) { prepareOptionalDiagnosticsFeedbackFile() }
                } else {
                    null
                }
        var attachments = HandsFeedbackAttachmentPolicy.selectAttachments(userFiles, diagnosticsFile)
        SlockAppLog.record(
                "SlockDiagnostics",
                "hands_feedback_submit_prepared messageLength=${description.length} " +
                        "userAttachmentCount=${userFiles.size} diagnosticsAttached=${diagnosticsFile != null} " +
                        "attachmentCount=${attachments.size}"
        )
        return runCatching {
            val feedbackClient = HandsFeedback(
                    context = appContext,
                    baseUrl = BuildConfig.HANDS_BASE_URL,
                    appSlug = BuildConfig.HANDS_APP_SLUG,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    channel = BuildConfig.HANDS_CHANNEL,
                    clientKey = BuildConfig.HANDS_CLIENT_KEY,
            )
            val ticketId =
                    try {
                        attachments =
                                attachments.map(HandsFeedbackAttachmentPolicy::requireValidFile)
                        feedbackClient.submit(
                                message = description,
                                kind = "bug",
                                attachments = attachments,
                        )
                    } catch (throwable: Throwable) {
                        if (diagnosticsFile == null ||
                                !shouldRetryHandsFeedbackWithoutDiagnostics(throwable, diagnosticsFile)
                        ) {
                            throw throwable
                        }
                        attachments =
                                userFiles
                                        .take(HandsFeedbackAttachmentPolicy.maxAttachments)
                                        .map(HandsFeedbackAttachmentPolicy::requireValidFile)
                        SlockAppLog.record(
                                "SlockDiagnostics",
                                "hands_feedback_diagnostics_submit_skipped error=${throwable.javaClass.simpleName} " +
                                        "message=${throwable.message ?: "-"} retryAttachmentCount=${attachments.size}"
                        )
                        feedbackClient.submit(
                                message = description,
                                kind = "bug",
                                attachments = attachments,
                        )
                    }
            // Shareable reference the user can paste into the community channel.
            val attachmentNames = handsFeedbackAttachmentNames(attachments.map(File::getName))
            val shareText = buildHandsFeedbackShareText(ticketId, description, attachmentNames)
            withContext(Dispatchers.Main) {
                copyText("Hands feedback", shareText)
                Toast.makeText(appContext, SlockHostStrings.feedbackCopiedMessage(), Toast.LENGTH_LONG).show()
            }
            SlockAppLog.record("SlockDiagnostics", "hands_feedback_submitted ticket=${ticketId.take(8)}")
            mapOf<String, Any>(
                    "status" to "ok",
                    "ticketId" to ticketId,
                    "shareText" to shareText,
                    "attachment_names" to attachmentNames
            )
        }.getOrElse {
            SlockAppLog.record(
                    "SlockDiagnostics",
                    "hands_feedback_submit_failed error=${it.javaClass.simpleName} message=${it.message ?: "-"}"
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, SlockHostStrings.feedbackFailedMessage(it.message), Toast.LENGTH_LONG).show()
            }
            mapOf<String, Any>(
                    "status" to "error",
                    "code" to handsFeedbackSubmitErrorCode(it),
                    "message" to (it.message ?: "feedback submission failed"),
            )
        }
    }

    private fun startFeedbackDiagnosticsUpload(
        request: JSONObject,
        callback: KuiklyRenderCallback?,
    ) {
        // A diagnostics request has no useful owner without a callback. Do not generate or
        // upload a native archive that cannot deliver its terminal reference to the WebView.
        if (callback == null) return
        val requestId = request.optString("requestId").trim()
        val binding =
            FeedbackDiagnosticsBinding(
                accountId = request.optString("accountId").trim(),
                sessionGeneration = request.optLong("sessionGeneration", -1L),
                webViewGeneration = request.optLong("webViewGeneration", -1L),
            )
        // A replacement WebView/session can race a stale page's callback. Retire the old
        // transport before admitting the new binding so the single-request ledger remains
        // available to the replacement generation.
        feedbackDiagnosticsUploads.values.firstOrNull { it.binding != binding }?.let { stale ->
            cancelFeedbackDiagnosticsOperation(stale)
        }
        if (!feedbackDiagnosticsLifecycle.start(requestId, binding)) {
            callback?.invoke(
                feedbackDiagnosticsEventPayload(
                    requestId,
                    binding,
                    type = "failed",
                    reason = FeedbackDiagnosticsFailureReason.GenerateFailed,
                    terminal = true,
                )
            )
            return
        }
        val uploader =
            HandsDiagnosticsPreupload(
                baseUrl = BuildConfig.HANDS_BASE_URL,
                appSlug = BuildConfig.HANDS_APP_SLUG,
                clientKey = BuildConfig.HANDS_CLIENT_KEY,
                httpClient = feedbackDiagnosticsHttpClient,
            )
        val operation = FeedbackDiagnosticsUpload(requestId, binding, uploader)
        feedbackDiagnosticsUploads[requestId] = operation
        val hostActivity = activity as? ComponentActivity
        if (hostActivity == null) {
            cancelFeedbackDiagnosticsOperation(operation)
            callback?.invoke(
                feedbackDiagnosticsEventPayload(
                    requestId,
                    binding,
                    type = "failed",
                    reason = FeedbackDiagnosticsFailureReason.GenerateFailed,
                    terminal = true,
                )
            )
            return
        }
        operation.job = hostActivity.lifecycleScope.launch {
            var archive: File? = null
            try {
                // Finish archive creation even if the lifecycle scope is cancelled so the path is
                // assigned and the finally block can delete the temporary file deterministically.
                val archiveFile = withContext(NonCancellable + Dispatchers.IO) { writeDiagnosticsFeedbackFile().file }
                archive = archiveFile
                val archiveSize = archiveFile.length()
                if (!feedbackDiagnosticsLifecycle.beginUpload(requestId, binding, archiveSize)) {
                    val reason =
                        if (archiveSize > FeedbackDiagnosticsLifecycle.MAX_BYTES) {
                            FeedbackDiagnosticsFailureReason.TooLarge
                        } else {
                            FeedbackDiagnosticsFailureReason.GenerateFailed
                        }
                    feedbackDiagnosticsLifecycle.failed(requestId, binding, reason)?.let { event ->
                        callback?.invoke(feedbackDiagnosticsEventPayload(event))
                    }
                    return@launch
                }
                val reference =
                    withContext(Dispatchers.IO) {
                        uploader.upload(archiveFile) { sent, total ->
                            hostActivity.runOnUiThread {
                                if (feedbackDiagnosticsLifecycle.phase(requestId, binding) == null) return@runOnUiThread
                                feedbackDiagnosticsLifecycle.progress(requestId, binding, sent, total)?.let { event ->
                                    callback?.invoke(feedbackDiagnosticsEventPayload(event))
                                }
                            }
                        }
                    }
                feedbackDiagnosticsLifecycle.done(requestId, binding, reference)?.let { event ->
                    callback?.invoke(feedbackDiagnosticsEventPayload(event))
                }
            } catch (_: CancellationException) {
                // Cancellation invalidates the ledger; never emit a late failed/done event.
            } catch (error: Throwable) {
                val reason =
                    when {
                        error is IllegalArgumentException && error.message.orEmpty().contains("size") ->
                            FeedbackDiagnosticsFailureReason.TooLarge
                        feedbackDiagnosticsLifecycle.phase(requestId, binding) ==
                                build.raft.app.FeedbackDiagnosticsRequestPhase.Preparing ->
                            FeedbackDiagnosticsFailureReason.GenerateFailed
                        else -> FeedbackDiagnosticsFailureReason.UploadFailed
                    }
                feedbackDiagnosticsLifecycle.failed(requestId, binding, reason)?.let { event ->
                    callback?.invoke(feedbackDiagnosticsEventPayload(event))
                }
            } finally {
                archive?.let { file -> withContext(NonCancellable + Dispatchers.IO) { runCatching { file.delete() } } }
                feedbackDiagnosticsUploads.remove(requestId, operation)
                operation.authMonitorJob?.cancel()
            }
        }
        monitorFeedbackDiagnosticsAuth(operation)
    }

    private fun monitorFeedbackDiagnosticsAuth(operation: FeedbackDiagnosticsUpload) {
        val hostActivity = activity as? ComponentActivity ?: return
        val authStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
        operation.authMonitorJob = hostActivity.lifecycleScope.launch {
            val initialAccount = withContext(Dispatchers.IO) { authStore.readSession()?.user?.id }
            if (initialAccount != operation.binding.accountId) {
                cancelFeedbackDiagnosticsOperation(operation)
                return@launch
            }
            authStore.authSessionEvents.collect { event ->
                val currentAccount = withContext(Dispatchers.IO) { authStore.readSession()?.user?.id }
                val shouldCancel =
                    event is AuthSessionEvent.Cleared ||
                        event is AuthSessionEvent.Authenticated ||
                        currentAccount != operation.binding.accountId
                if (shouldCancel) {
                    cancelFeedbackDiagnosticsOperation(operation)
                    return@collect
                }
            }
        }
    }

    private fun cancelFeedbackDiagnosticsUpload(
        request: JSONObject,
        callback: KuiklyRenderCallback?,
    ) {
        val requestId = request.optString("requestId").trim()
        val binding =
            FeedbackDiagnosticsBinding(
                accountId = request.optString("accountId").trim(),
                sessionGeneration = request.optLong("sessionGeneration", -1L),
                webViewGeneration = request.optLong("webViewGeneration", -1L),
            )
        val operation = feedbackDiagnosticsUploads[requestId]
        val cancelled = feedbackDiagnosticsLifecycle.cancel(requestId, binding)
        if (cancelled && operation != null) {
            cancelFeedbackDiagnosticsOperation(operation)
        }
        callback?.invoke(
            mapOf(
                "status" to if (cancelled) "ok" else "ignored",
                "requestId" to requestId,
                "binding" to feedbackDiagnosticsBindingPayload(binding),
                "cancelled" to cancelled,
                "terminal" to true,
            )
        )
    }

    private fun cancelFeedbackDiagnosticsOperation(operation: FeedbackDiagnosticsUpload) {
        feedbackDiagnosticsLifecycle.cancel(operation.requestId, operation.binding)
        feedbackDiagnosticsUploads.remove(operation.requestId, operation)
        operation.uploader.cancel()
        operation.job?.cancel()
        operation.authMonitorJob?.cancel()
    }

    private fun feedbackDiagnosticsEventPayload(
        event: build.raft.app.FeedbackDiagnosticsLifecycleEvent,
    ): Map<String, Any> =
        when (event) {
            is build.raft.app.FeedbackDiagnosticsLifecycleEvent.Progress ->
                feedbackDiagnosticsEventPayload(
                    event.requestId,
                    event.binding,
                    type = "progress",
                    sent = event.sent,
                    total = event.total,
                    terminal = false,
                )
            is build.raft.app.FeedbackDiagnosticsLifecycleEvent.Done ->
                feedbackDiagnosticsEventPayload(
                    event.requestId,
                    event.binding,
                    type = "done",
                    reference = event.ref,
                    terminal = true,
                )
            is build.raft.app.FeedbackDiagnosticsLifecycleEvent.Failed ->
                feedbackDiagnosticsEventPayload(
                    event.requestId,
                    event.binding,
                    type = "failed",
                    reason = event.reason,
                    terminal = true,
                )
        }

    private fun feedbackDiagnosticsEventPayload(
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
        type: String? = null,
        sent: Long? = null,
        total: Long? = null,
        reference: FeedbackDiagnosticsReference? = null,
        reason: FeedbackDiagnosticsFailureReason? = null,
        terminal: Boolean,
    ): Map<String, Any> =
        buildMap {
            put("status", "ok")
            put("requestId", requestId)
            put("binding", feedbackDiagnosticsBindingPayload(binding))
            put("terminal", terminal)
            type?.let { put("type", it) }
            sent?.let { put("sent", it) }
            total?.let { put("total", it) }
            reason?.let {
                put(
                    "reason",
                    when (it) {
                        FeedbackDiagnosticsFailureReason.GenerateFailed -> "generate_failed"
                        FeedbackDiagnosticsFailureReason.TooLarge -> "too_large"
                        FeedbackDiagnosticsFailureReason.UploadFailed -> "upload_failed"
                    },
                )
            }
            reference?.let {
                put(
                    "ref",
                    mapOf(
                        "r2_key" to it.r2Key,
                        "filename" to it.filename,
                        "content_type" to it.contentType,
                        "size" to it.size,
                    ),
                )
            }
        }

    private fun feedbackDiagnosticsBindingPayload(binding: FeedbackDiagnosticsBinding): Map<String, Any> =
        mapOf(
            "accountId" to binding.accountId,
            "sessionGeneration" to binding.sessionGeneration,
            "webViewGeneration" to binding.webViewGeneration,
        )

    private fun buildHandsFeedbackShareText(
            ticketId: String,
            description: String,
            attachmentNames: List<String>
    ): String {
        val updateRoute = handsUpdateRouteForRuntime()
        val environment = settingsFeedbackShareEnvironmentSection(buildMergedSettingsDiagnosticsExportText())
        val utcTime =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date())
        val localTime =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                        .format(Date())
        return formatHandsFeedbackShareText(
                ticketId = ticketId,
                description = description,
                environment = environment,
                utcTime = utcTime,
                localTime = localTime,
                feedbackAppSlug = BuildConfig.HANDS_APP_SLUG,
                feedbackChannel = BuildConfig.HANDS_CHANNEL,
                updateRoute = updateRoute,
                deviceId = HandsDeviceId.get(appContext),
                attachmentNames = attachmentNames
        )
    }

    /** Native description prompt; returns null when cancelled or left blank. */
    private suspend fun promptFeedbackDescription(): String? {
        // AlertDialog needs a themed Activity context; appContext would crash and
        // the module itself is not a Context. No visible Activity → treat as cancelled.
        val host = activity ?: appContext as? Activity ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val input = EditText(host).apply {
                    hint = "Describe your feedback"
                    minLines = 3
                    setPadding(48, 32, 48, 32)
                }
                val dialog = AlertDialog.Builder(host)
                        .setTitle("Send feedback")
                        .setMessage("Your message plus device diagnostics will be sent to Hands. You'll get a copyable reference to share in the community.")
                        .setView(input)
                        .setPositiveButton("Send") { _, _ ->
                            val text = input.text?.toString()?.trim().orEmpty()
                            if (cont.isActive) cont.resume(text.ifBlank { null })
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            if (cont.isActive) cont.resume(null)
                        }
                        .setOnCancelListener {
                            if (cont.isActive) cont.resume(null)
                        }
                        .create()
                cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
                dialog.show()
            }
        }
    }

    private fun writeDiagnosticsFeedbackFile(): DiagnosticsFeedbackFile {
        val summaryText = buildMergedSettingsDiagnosticsExportText()
        val archive = SlockAndroidLocalDiagnostics.prepareDiagnosticsFeedbackArchive(appContext, summaryText)
        SlockAndroidLocalDiagnostics.uploadDiagnostics(
                context = appContext,
                attachment = archive,
                summary = "settings_feedback"
        )
        return DiagnosticsFeedbackFile(file = archive, summaryText = summaryText)
    }

    private fun prepareOptionalDiagnosticsFeedbackFile(): File? {
        return runCatching { writeDiagnosticsFeedbackFile().file }
                .getOrElse { throwable ->
                    SlockAppLog.record(
                            "SlockDiagnostics",
                            "hands_feedback_diagnostics_prepare_skipped error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                    )
                    null
                }
                ?.takeIf { file ->
                    val fits = file.length() in 1..HandsFeedbackAttachmentPolicy.maxAttachmentBytes
                    if (!fits) {
                        SlockAppLog.record(
                                "SlockDiagnostics",
                                "hands_feedback_diagnostics_attachment_skipped bytes=${file.length()} " +
                                        "max=${HandsFeedbackAttachmentPolicy.maxAttachmentBytes}"
                        )
                    }
                    fits
                }
    }

    private fun JSONArray?.toFeedbackAttachmentFiles(): List<File> {
        if (this == null) return emptyList()
        val files = mutableListOf<File>()
        for (index in 0 until length()) {
            if (files.size >= HandsFeedbackAttachmentPolicy.maxAttachments) break
            val fileJson = optJSONObject(index) ?: continue
            val fileName = fileJson.optString("fileName").ifBlank { "attachment-${index + 1}" }
            val mimeType = fileJson.optString("mimeType").ifBlank { "application/octet-stream" }
            val fileRef = fileJson.optString("fileRef").takeIf { it.isNotBlank() }
            val bodyBase64 = fileJson.optString("bodyBase64").takeIf { it.isNotBlank() }
            val file =
                    when {
                        bodyBase64 != null ->
                            writeHandsFeedbackBase64TempFile(
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    bodyBase64 = bodyBase64,
                                    index = index
                            )
                        fileRef != null ->
                            copyHandsFeedbackFileRef(
                                    fileRef = fileRef,
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    index = index
                            )
                        else -> null
                    }
            if (file != null) files.add(file)
        }
        return files
    }

    private fun promoteFeedbackDraftAttachments(request: JSONObject): Map<String, Any> {
        val filesJson = request.optJSONArray("files") ?: throw FeedbackAttachmentReadException("Missing files")
        val sources =
                buildList {
                    for (index in 0 until filesJson.length()) {
                        val file = filesJson.optJSONObject(index) ?: continue
                        add(
                                SettingsHandsFeedbackDraftAttachmentSource(
                                        id = file.optString("id"),
                                        fileName = file.optString("fileName").ifBlank { "attachment-${index + 1}" },
                                        mimeType = file.optString("mimeType").ifBlank { "application/octet-stream" },
                                        fileRef = file.optString("fileRef")
                                )
                        )
                    }
                }
        val storage = feedbackDraftAttachmentStorage()
        val promoted =
                storage.promoteGroup(
                        accountId = request.optString("accountId"),
                        groupId = request.optString("groupId"),
                        sources = sources,
                        maxBytes = request.optLong("maxBytes", 0L)
                )
        val files =
                JSONArray().also { array ->
                    promoted.forEach { file ->
                        array.put(
                                JSONObject()
                                        .put("id", file.id)
                                        .put("fileName", file.fileName)
                                        .put("mimeType", file.mimeType)
                                        .put("sizeBytes", file.sizeBytes)
                                        .put("previewUri", file.previewUri)
                                        .put("fileRef", file.fileRef)
                                        .put("source", "settings_feedback_draft")
                        )
                    }
                }
        return mapOf("status" to "ok", "files" to files)
    }

    private fun discardFeedbackDraftAttachments(request: JSONObject): Map<String, Any> {
        val refsJson = request.optJSONArray("fileRefs")
        val refs =
                buildList {
                    if (refsJson != null) {
                        for (index in 0 until refsJson.length()) {
                            refsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
        feedbackDraftAttachmentStorage().discard(
                accountId = request.optString("accountId"),
                fileRefs = refs
        )
        return mapOf("status" to "ok")
    }

    private fun feedbackDraftAttachmentStorage(): SettingsHandsFeedbackDraftAttachmentStorage =
            SettingsHandsFeedbackDraftAttachmentStorage(
                    rootDirectory = File(appContext.filesDir, "settings-hands-feedback-drafts"),
                    openSource = { fileRef -> openHandsFeedbackFileRefStream(Uri.parse(fileRef)) }
            )

    private fun copyHandsFeedbackFileRef(
            fileRef: String,
            fileName: String,
            mimeType: String,
            index: Int
    ): File {
        val uri = Uri.parse(fileRef)
        if (uri.scheme.isNullOrBlank() || uri.scheme.equals("file", ignoreCase = true)) {
            val path = if (uri.scheme.isNullOrBlank()) fileRef else uri.path
                ?: throw FeedbackAttachmentReadException("No file path")
            return HandsFeedbackAttachmentPolicy.requireValidFile(File(path))
        }
        val target = createHandsFeedbackTempFile(fileName, mimeType, index)
        return try {
            openHandsFeedbackFileRefStream(uri).use { input ->
                target.outputStream().use { output ->
                    HandsFeedbackAttachmentPolicy.copyBounded(input, output)
                }
            }
            HandsFeedbackAttachmentPolicy.requireValidFile(target)
        } catch (throwable: Throwable) {
            runCatching { target.delete() }
            throw throwable
        }
    }

    private fun openHandsFeedbackFileRefStream(uri: Uri): java.io.InputStream {
        if (uri.toString().isBlank()) throw IOException("No readable feedback attachment")
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: throw IOException("No file path")
            File(path).takeIf { it.isFile }?.inputStream()
                    ?: throw IOException("Unable to read feedback attachment")
        } else {
            appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to read feedback attachment")
        }
    }

    private fun writeHandsFeedbackBase64TempFile(
            fileName: String,
            mimeType: String,
            bodyBase64: String,
            index: Int
    ): File {
        if (HandsFeedbackAttachmentPolicy.estimatedDecodedBase64Bytes(bodyBase64) >
                        HandsFeedbackAttachmentPolicy.maxAttachmentBytes
        ) {
            throw FeedbackAttachmentTooLargeException(HandsFeedbackAttachmentPolicy.maxAttachmentBytes)
        }
        val target = createHandsFeedbackTempFile(fileName, mimeType, index)
        return try {
            Base64InputStream(Base64StringInputStream(bodyBase64), Base64.DEFAULT).use { input ->
                target.outputStream().use { output ->
                    HandsFeedbackAttachmentPolicy.copyBounded(input, output)
                }
            }
            HandsFeedbackAttachmentPolicy.requireValidFile(target)
        } catch (throwable: Throwable) {
            runCatching { target.delete() }
            throw throwable
        }
    }

    private fun createHandsFeedbackTempFile(
            fileName: String,
            mimeType: String,
            index: Int
    ): File {
        val cacheDir = File(appContext.cacheDir, HANDS_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME).apply { mkdirs() }
        cleanupHandsFeedbackAttachmentCache(cacheDir)
        val safeName = sanitizeHandsFeedbackFileName(fileName, mimeType, index)
        return File(cacheDir, "${System.currentTimeMillis()}-${System.nanoTime()}-$safeName")
    }

    private fun sanitizeHandsFeedbackFileName(fileName: String, mimeType: String, index: Int): String {
        val cleaned = fileName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_', '.', '-')
                .take(80)
        val extension =
                cleaned.substringAfterLast('.', missingDelimiterValue = "")
                        .takeIf { it.isNotBlank() }
                        ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty()
        val base = cleaned.substringBeforeLast('.', missingDelimiterValue = cleaned).ifBlank { "attachment-${index + 1}" }
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun cleanupHandsFeedbackAttachmentCache(cacheDir: File) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()
                ?.filter { file -> now - file.lastModified() > HANDS_FEEDBACK_ATTACHMENT_CACHE_MAX_AGE_MS }
                ?.forEach { file -> runCatching { file.delete() } }
    }

    private fun diagnosticsFeedbackMimeType(file: File): String =
            when (file.extension.lowercase()) {
                "zip" -> "application/zip"
                else -> "text/plain"
            }

    private fun buildMergedSettingsDiagnosticsExportText(): String {
        val about = loadSettingsAbout()
        recordSettingsDiagnosticsEnvironment(about)
        val recentLogs = SlockAndroidLocalDiagnostics.exportRecentDiagnosticsText(appContext)
        val base = formatMergedSettingsDiagnosticsExportText(
                about = about,
                recentLogs = recentLogs,
                hasFallbackDiagnosticsLogs = SlockAndroidLocalDiagnostics.hasFallbackDiagnosticsLogs(appContext)
        )
        val readState =
                ProcessReadStateV1DiagnosticOverride.controller.snapshot().toRedactedJson().toString(2)
        val featureFlags =
                ProcessDiagnosticFeatureFlagOverrides.registry.snapshots().toRedactedJson().toString(2)
        return "$base\n\n=== DIAGNOSTIC FEATURE FLAG OVERRIDES (REDACTED) ===\n$featureFlags" +
                "\n\n=== READ STATE V1 DIAGNOSTIC (REDACTED) ===\n$readState"
    }

    private fun List<DiagnosticFeatureFlagOverrideSnapshot>.toRedactedJson(): JSONArray =
            JSONArray().also { array -> forEach { snapshot -> array.put(snapshot.toRedactedJson()) } }

    private fun DiagnosticFeatureFlagOverrideSnapshot.toRedactedJson(): JSONObject =
            JSONObject()
                    .put("key", key)
                    .put("supported", supported)
                    .put("scopeKind", scopeKind?.name ?: "unsupported")
                    .put("entitled", entitled)
                    .put("clientOverride", mode.name)
                    .put("expiresAtMillis", expiresAtMillis ?: 0L)
                    .put("clearReason", clearReason.orEmpty())
                    .put("observedScope", observedScope?.toStableRedactedLabel().orEmpty())
                    .put("boundScope", boundScope?.toStableRedactedLabel().orEmpty())
                    .put("serverEffective", lastDecision?.serverEffective?.toString().orEmpty())
                    .put("finalEffective", diagnosticFinalEffective()?.toString().orEmpty())
                    .put("precedence", diagnosticPrecedence())
                    .put("exactScopeMatched", lastDecision?.exactScopeMatched ?: false)
                    .put(
                            "evidence",
                            listOfNotNull(
                                    evidence.selectedScope?.let { scope ->
                                        "selected=${scope.toStableRedactedLabel()}"
                                    },
                                    evidence.witnessGeneration?.let { "generation=$it" },
                                    evidence.durableReadStateVersion?.let { version ->
                                        "durable=($version,${evidence.durableMaxReadSeq ?: "--"})"
                                    },
                                    evidence.latestAdjudication?.takeIf { it.isNotBlank() }?.let { "adjudication=$it" },
                                    evidence.destinationPublication?.takeIf { it.isNotBlank() }?.let { "publication=$it" },
                                    evidence.barrierState?.takeIf { it.isNotBlank() }?.let { "barrier=$it" },
                                    evidence.stalePublicationRejectedCount.takeIf { it > 0L }?.let { "staleRejected=$it" },
                            ).joinToString(" · ")
                    )

    private fun ReadStateV1DiagnosticOverrideSnapshot.toRedactedJson(): JSONObject =
            JSONObject()
                    .put("entitled", entitled)
                    .put("clientOverride", mode.name)
                    .put("expiresAtMillis", expiresAtMillis ?: 0L)
                    .put("clearReason", clearReason.orEmpty())
                    .put("observedScope", observedScope?.toStableRedactedLabel().orEmpty())
                    .put("boundScope", boundScope?.toStableRedactedLabel().orEmpty())
                    .put("serverEffective", lastDecision?.serverEffective?.toString().orEmpty())
                    .put("finalEffective", diagnosticFinalEffective()?.toString().orEmpty())
                    .put("precedence", diagnosticPrecedence())
                    .put("exactScopeMatched", lastDecision?.exactScopeMatched ?: false)
                    .put("selectedScope", selectedScope?.toStableRedactedLabel().orEmpty())
                    .put("witnessGeneration", witnessGeneration ?: 0L)
                    .put("durableReadStateVersion", durableReadStateVersion ?: 0L)
                    .put("durableMaxReadSeq", durableMaxReadSeq ?: -1L)
                    .put("latestAdjudication", latestAdjudication.orEmpty())
                    .put("destinationPublication", destinationPublication.orEmpty())
                    .put("barrierState", barrierState.orEmpty())
                    .put("stalePublicationRejectedCount", stalePublicationRejectedCount)

    private fun DiagnosticFeatureFlagOverrideSnapshot.diagnosticFinalEffective(): Boolean? =
            when (mode) {
                DiagnosticFeatureFlagOverrideMode.ForceOff -> false
                DiagnosticFeatureFlagOverrideMode.ForceOn -> true
                DiagnosticFeatureFlagOverrideMode.Server -> lastDecision?.serverEffective
            }

    private fun DiagnosticFeatureFlagOverrideSnapshot.diagnosticPrecedence(): String =
            when (mode) {
                DiagnosticFeatureFlagOverrideMode.ForceOff -> "diagnostic_override_force_off"
                DiagnosticFeatureFlagOverrideMode.ForceOn -> "diagnostic_override_force_on"
                DiagnosticFeatureFlagOverrideMode.Server -> lastDecision?.precedence ?: "not_observed"
            }

    private fun ReadStateV1DiagnosticOverrideSnapshot.diagnosticFinalEffective(): Boolean? =
            when (mode) {
                ReadStateV1DiagnosticOverrideMode.ForceOff -> false
                ReadStateV1DiagnosticOverrideMode.ForceOn -> true
                ReadStateV1DiagnosticOverrideMode.Server -> lastDecision?.serverEffective
            }

    private fun ReadStateV1DiagnosticOverrideSnapshot.diagnosticPrecedence(): String =
            when (mode) {
                ReadStateV1DiagnosticOverrideMode.ForceOff -> "diagnostic_override_force_off"
                ReadStateV1DiagnosticOverrideMode.ForceOn -> "diagnostic_override_force_on"
                ReadStateV1DiagnosticOverrideMode.Server -> lastDecision?.precedence ?: "not_observed"
            }

    private fun build.raft.app.network.sync.ReadStateV1DiagnosticExactScope.toStableRedactedLabel(): String =
            "env=${environmentId.stableDiagnosticId()} account=${accountId.stableDiagnosticId()} " +
                    "server=${serverId.stableDiagnosticId()} channel=${channelId.stableDiagnosticId()}"

    private fun DiagnosticFeatureFlagScope.toStableRedactedLabel(): String =
            buildString {
                append("env=${environmentId.stableDiagnosticId()} account=${accountId.stableDiagnosticId()} ")
                append("server=${serverId.stableDiagnosticId()}")
                channelId?.let { append(" channel=${it.stableDiagnosticId()}") }
            }

    private fun String.stableDiagnosticId(): String {
        var hash = 0xcbf29ce484222325UL
        encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
        }
        return "#${hash.toString(16).takeLast(12).padStart(12, '0')}"
    }

    private fun recordSettingsDiagnosticsEnvironment(about: Map<String, Any>) {
        SlockAndroidLocalDiagnostics.recordLog(
                "info",
                "SlockDiagnostics",
                "environment app=${about.stringValue("appName").ifBlank { "Raft" }} " +
                        "version=${about.stringValue("versionLabel")} " +
                        "versionCode=${about.stringValue("versionCodeLabel")} " +
                        "build=${about.stringValue("buildLabel")} " +
                        "source=${about.stringValue("sourceLabel")} " +
                        "package=${about.stringValue("packageName")} " +
                        "platform=${about.stringValue("platformLabel")} " +
                        "sdk=${about.stringValue("sdkLabel")} " +
                        "device=${about.stringValue("deviceLabel")} " +
                        "model=${about.stringValue("modelLabel")} " +
                        "installTime=${about.stringValue("installTimeLabel")} " +
                        "screen=${about.stringValue("screenLabel")} " +
                        "density=${about.stringValue("densityLabel")} " +
                        "fontScale=${about.stringValue("fontScaleLabel")} " +
                        "session=${about.stringValue("sessionStatusLabel")} " +
                        "signedInUser=${about.stringValue("signedInUserLabel")} " +
                        "selectedServer=${about.stringValue("selectedServerLabel")} " +
                        "socket=${about.stringValue("socketStatusLabel")}"
        )
    }

    private fun Map<String, Any>.stringValue(key: String): String =
            this[key]?.toString().orEmpty()

    private fun shareText(subject: String, text: String) {
        val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        runCatching {
            appContext.startActivity(
                    Intent.createChooser(intent, subject).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
        }.onFailure {
            Toast.makeText(appContext, appContext.getString(R.string.share_sheet_unavailable_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(label: String, content: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.versionCodeCompat(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode.toString()
            } else {
                versionCode.toString()
            }

    private fun openUrl(params: String?): Any? {
        val request = JSONObject(params ?: "{}")
        val url = request.optString("url").takeIf { it.isNotBlank() } ?: return null
        val mimeType = request.optString("mimeType").takeIf { it.isNotBlank() }
        val uri = Uri.parse(url)
        val viewIntent = externalUrlIntent(uri, mimeType)
        runCatching {
            appContext.startActivity(viewIntent)
        }.onFailure { throwable ->
            if (throwable is ActivityNotFoundException) {
                if (openInAppBrowserFallback(url, mimeType)) {
                    return@onFailure
                }
                Toast.makeText(appContext, appContext.getString(R.string.open_link_no_app_toast), Toast.LENGTH_SHORT)
                        .show()
            } else {
                Toast.makeText(appContext, appContext.getString(R.string.open_link_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
        return null
    }

    /**
     * When no external activity accepts a web URL, keep the link usable by opening the shared
     * in-app browser page instead of dropping the tap on the floor.
     */
    private fun openInAppBrowserFallback(url: String, mimeType: String?): Boolean {
        val isWebUrl = mimeType == null && Uri.parse(url).scheme?.lowercase() in setOf("http", "https")
        if (!isWebUrl) return false
        val pageData =
                JSONObject()
                        .put("pageName", SlockKuiklyPages.IN_APP_BROWSER)
                        .put("url", url)
        return runCatching {
            SlockKuiklyActivity.start(activity ?: appContext, SlockKuiklyPages.IN_APP_BROWSER, pageData)
        }.onFailure { throwable ->
            SlockAppLog.record(
                    "SlockOpenUrl",
                    "inAppBrowserFallback=failed type=${throwable::class.java.simpleName}"
            )
        }.isSuccess
    }

    /**
     * HTTP(S) links must leave Raft even when Android App Links resolve the same URL back to this
     * application. Other schemes retain the platform's ordinary implicit-handler behavior.
     */
    private fun externalUrlIntent(uri: Uri, mimeType: String?): Intent {
        val isWebUrl = mimeType == null && uri.scheme?.lowercase() in setOf("http", "https")
        val implicit =
                Intent(Intent.ACTION_VIEW).apply {
                    if (mimeType != null) {
                        setDataAndType(uri, mimeType)
                    } else {
                        data = uri
                    }
                    if (isWebUrl) {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        if (!isWebUrl) {
            return implicit
        }
        // MATCH_DEFAULT_ONLY only lists apps the user picked as defaults; devices without a
        // default browser (or that never show the disambiguation sheet) report an empty list.
        // Retry without the flag before giving up on a target package.
        var candidates =
                appContext.packageManager.queryIntentActivities(
                        implicit,
                        PackageManager.MATCH_DEFAULT_ONLY
                )
        if (candidates.isEmpty()) {
            candidates =
                    appContext.packageManager.queryIntentActivities(
                            implicit,
                            PackageManager.MATCH_ALL
                    )
        }
        val defaultPackage =
                appContext.packageManager
                        .resolveActivity(implicit, PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo
                        ?.packageName
        val selectedPackage =
                selectExternalWebHandlerPackage(
                        ownPackage = appContext.packageName,
                        defaultPackage = defaultPackage,
                        candidatePackages = candidates.map { it.activityInfo.packageName }
                ) ?: return implicit
        return implicit.setPackage(selectedPackage)
    }

    private fun openAttachment(params: String?, callback: KuiklyRenderCallback?) {
        SlockKuiklyThreadAdapter.execute("attachment-open") {
            val request = JSONObject(params ?: "{}")
            val startedAt = System.currentTimeMillis()
            val trackProgress = request.optBoolean("trackProgress", false)
            val dispatch =
                    runCatching {
                        downloadAndOpenAttachment(
                                request = request,
                                progressCallback =
                                        if (trackProgress) {
                                            { progress -> callback?.invoke(progress) }
                                        } else {
                                            null
                                        }
                        )
                    }.getOrElse { throwable ->
                        SlockAppLog.record(
                                "SlockAttachmentOpen",
                                "attachmentId=${request.optString("attachmentId").take(8)} status=failed type=${throwable::class.java.simpleName} durationMs=${System.currentTimeMillis() - startedAt}"
                        )
                        AndroidAttachmentOpenDispatch.Complete(AndroidAttachmentOpenResult.failed())
                    }
            when (dispatch) {
                is AndroidAttachmentOpenDispatch.Complete -> callback?.invoke(dispatch.result.toBridgePayload())
                is AndroidAttachmentOpenDispatch.SaveAs -> {
                    val host = activity as? SlockKuiklyActivity
                    if (host == null) {
                        callback?.invoke(AndroidAttachmentOpenResult.storageFailed().toBridgePayload())
                    } else {
                        host.requestAttachmentSaveAs(dispatch, callback)
                    }
                }
            }
        }
    }

    private fun saveAttachment(params: String?, callback: KuiklyRenderCallback?) {
        SlockKuiklyThreadAdapter.execute("attachment-save") {
            val request = JSONObject(params ?: "{}")
            val owner = request.attachmentOwnerLease()
                    ?: run {
                        callback?.invoke(mapOf("status" to "failed", "message" to "Authentication required"))
                        return@execute
                    }
            val identity = request.publicAttachmentIdentity(owner)
            val trackProgress = request.optBoolean("trackProgress", false)
            val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
            if (!ProcessAndroidAttachmentMediaStoreCleanup.awaitCompletion() ||
                    !ProcessAndroidAttachmentPublicDownloads.awaitRecovery()) {
                callback?.invoke(
                        mapOf(
                                "status" to "storage_failed",
                                "cached" to false,
                                "message" to "Attachment storage recovery did not finish"
                        )
                )
                return@execute
            }
            val result =
                    runCatching {
                                authority.withIdentityLock(identity) {
                                    if (!ProcessAndroidAttachmentOwners.admit(owner)) {
                                        return@withIdentityLock mapOf(
                                                "status" to "cancelled",
                                                "cached" to false,
                                                "message" to "Attachment scope retired"
                                        )
                                    }
                                    authority.resolve(identity)?.let { committed ->
                                        return@withIdentityLock committed.toSavedAttachmentPayload()
                                    }
                                    val privateTarget = attachmentCacheFile(request, owner.principalKey)
                                    if (deleteDirectAttachmentCache(privateTarget, expectedAttemptToken = null) == "failed") {
                                        return@withIdentityLock mapOf(
                                                "status" to "storage_failed",
                                                "cached" to false,
                                                "message" to "Unable to prepare attachment storage"
                                        )
                                    }
                                    val preparationAttempt =
                                            runCatching {
                                                        prepareAttachment(
                                                                request = request,
                                                                operationId = request.optString("operationId"),
                                                                progressCallback =
                                                                    if (trackProgress) {
                                                                        { progress -> callback?.invoke(progress) }
                                                                    } else {
                                                                        null
                                                                    }
                                                        )
                                                    }
                                    if (preparationAttempt.isFailure) {
                                        val throwable = preparationAttempt.exceptionOrNull()
                                        val payload = attachmentSavePreparationFailurePayload(throwable)
                                        SlockAppLog.record(
                                                "SlockAttachmentExport",
                                                "phase=download status=${payload["status"]} type=${throwable?.javaClass?.simpleName}"
                                        )
                                        return@withIdentityLock payload
                                    }
                                    val preparation = preparationAttempt.getOrThrow()
                                    when (preparation) {
                                        is AttachmentPreparationResult.Terminal -> preparation.payload
                                        is AttachmentPreparationResult.Ready -> {
                                            val displayName =
                                                    publicDownloadDisplayName(
                                                            requestedFileName = preparation.requestedFileName,
                                                            fallbackExtension =
                                                                MimeTypeMap.getSingleton()
                                                                    .getExtensionFromMimeType(preparation.mimeType)
                                                    )
                                            runCatching {
                                                        publishAttachmentToDownloads(
                                                                identity = identity,
                                                                downloadId = preparation.downloadId,
                                                                owner = preparation.owner,
                                                                source = preparation.targetFile,
                                                                displayName = displayName,
                                                                mimeType = preparation.mimeType,
                                                                progressCallback =
                                                                    if (trackProgress) {
                                                                        { progress -> callback?.invoke(progress) }
                                                                    } else {
                                                                        null
                                                                    }
                                                        )
                                                    }
                                                    .getOrElse { throwable ->
                                                        deleteDirectAttachmentCache(
                                                                preparation.targetFile,
                                                                expectedAttemptToken = null
                                                        )
                                                        throw throwable
                                                    }
                                        }
                                    }
                                }
                            }
                            .getOrElse { throwable ->
                                SlockAppLog.record(
                                        "SlockAttachmentExport",
                                        "phase=public_authority status=failed type=${throwable::class.java.simpleName}"
                                )
                                mapOf(
                                        "status" to "storage_failed",
                                        "cached" to false,
                                        "message" to "Unable to save attachment"
                                )
                            }
            callback?.invoke(result)
        }
    }

    private fun saveImageToPhotos(params: String?, callback: KuiklyRenderCallback?) {
        val request = JSONObject(params ?: "{}")
        val downloadId = request.optString("downloadId").takeIf(String::isNotBlank)
                ?: "${request.optString("attachmentId")}:${request.optString("fileName")}".take(160)
        val operationId = request.optString("operationId").takeIf(String::isNotBlank)
        val owner = request.attachmentOwnerLease()
        if (operationId == null || owner == null) {
            callback?.invoke(mapOf("status" to "failed", "message" to "Missing attachment export authority"))
            return
        }
        SlockKuiklyThreadAdapter.execute("attachment-save-photos") {
            if (!ProcessAndroidAttachmentExportAuthorities.bind(owner, downloadId, operationId)) {
                callback?.invoke(mapOf("status" to "failed", "message" to "Missing attachment export authority"))
                return@execute
            }
            val authorityProgressCallback: KuiklyRenderCallback? = callback?.let { downstream ->
                { payload ->
                    if (ProcessAndroidAttachmentExportAuthorities.isCurrent(owner, downloadId, operationId)) {
                        downstream(payload)
                    }
                }
            }
            val authorityTerminalCallback: KuiklyRenderCallback? = callback?.let { downstream ->
                { payload ->
                    if (ProcessAndroidAttachmentExportAuthorities.retireIfCurrent(owner, downloadId, operationId)) {
                        downstream(payload)
                    }
                }
            }
            if (!ProcessAndroidAttachmentMediaStoreCleanup.awaitCompletion()) {
                authorityTerminalCallback?.invoke(
                        mapOf(
                                "status" to "storage_failed",
                                "message" to "Photos storage is still initializing"
                        )
                )
                return@execute
            }
            val trackProgress = request.optBoolean("trackProgress", false)
            val preparation =
                    runCatching {
                                prepareAttachment(
                                        request = request,
                                        operationId = operationId,
                                        operationIsCurrent = {
                                            ProcessAndroidAttachmentExportAuthorities.isCurrent(
                                                    owner,
                                                    downloadId,
                                                    operationId
                                            )
                                        },
                                        progressCallback =
                                                if (trackProgress) {
                                                    { progress -> authorityProgressCallback?.invoke(progress) }
                                                } else {
                                                    null
                                                }
                                )
                            }
                            .getOrElse {
                                authorityTerminalCallback?.invoke(mapOf("status" to "failed", "message" to "Unable to download attachment"))
                                return@execute
                            }
            when (preparation) {
                is AttachmentPreparationResult.Terminal -> authorityTerminalCallback?.invoke(preparation.payload)
                is AttachmentPreparationResult.Ready -> {
                    if (!ProcessAndroidAttachmentExportAuthorities.isCurrent(owner, downloadId, operationId)) {
                        return@execute
                    }
                    val validation =
                            validateAndroidGalleryImage(
                                    source = preparation.targetFile,
                                    fileName = preparation.requestedFileName,
                                    claimedMimeType = preparation.mimeType
                            )
                    if (validation == null) {
                        authorityTerminalCallback?.invoke(
                                mapOf(
                                        "status" to "unsupported_image",
                                        "message" to "This attachment is not a supported image",
                                        "retryable" to false
                                )
                        )
                        return@execute
                    }
                    val displayName =
                            publicDownloadDisplayName(
                                    requestedFileName = preparation.requestedFileName,
                                    fallbackExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(validation.mimeType)
                            )
                    authorityTerminalCallback?.invoke(
                            publishAttachmentToImages(
                                    downloadId = preparation.downloadId,
                                    operationId = operationId,
                                    owner = preparation.owner,
                                    source = preparation.targetFile,
                                    displayName = displayName,
                                    mimeType = validation.mimeType,
                                    width = validation.width,
                                    height = validation.height,
                                    cached = preparation.wasCached,
                                    progressCallback =
                                            if (trackProgress) {
                                                { progress -> authorityProgressCallback?.invoke(progress) }
                                            } else {
                                                null
                                            }
                            )
                    )
                }
            }
        }
    }

    private fun cancelAttachmentExport(params: String?) {
        val request = JSONObject(params ?: "{}")
        val downloadId = request.optString("downloadId").takeIf(String::isNotBlank) ?: return
        val operationId = request.optString("operationId").takeIf(String::isNotBlank) ?: return
        val owner = request.attachmentOwnerLease() ?: return
        val attemptToken = request.optString("attemptToken").takeIf(String::isNotBlank)
        ProcessAndroidAttachmentExportAuthorities.cancelIfCurrent(
                owner = owner,
                downloadId = downloadId,
                operationId = operationId,
                dispatch = { cleanup ->
                    SlockKuiklyThreadAdapter.execute("attachment-save-photos-cancel", cleanup)
                },
                cleanup = {
                    runCatching {
                        AttachmentDownloadCoordinator.controlExisting(downloadId, attemptToken, "cancel")
                    }.onFailure { throwable ->
                        SlockAppLog.record(
                                "SlockAttachmentExport",
                                "phase=cancel_common status=failed type=${throwable::class.java.simpleName}"
                        )
                    }
                    runCatching {
                        attachmentDownloadsExportCoordinator.controlExisting(
                                downloadId = downloadId,
                                operationId = operationId,
                                expectedAttemptToken = attemptToken,
                                action = "cancel",
                                owner = owner
                        )
                    }.onFailure { throwable ->
                        SlockAppLog.record(
                                "SlockAttachmentExport",
                                "phase=cancel_gallery status=failed type=${throwable::class.java.simpleName}"
                        )
                    }
                }
        )
    }

    private fun attachmentDownloadOwnerChanged(params: String?) {
        val request = JSONObject(params ?: "{}")
        when (request.optString("action")) {
            "bind" -> request.attachmentOwnerLease()?.let(ProcessAndroidAttachmentOwners::bind)
            "retire_scope" ->
                request.optString("shellInstanceId").takeIf(String::isNotBlank)
                    ?.let(ProcessAndroidAttachmentOwners::retireShell)
            "retire_account" -> ProcessAndroidAttachmentOwners.retireAccount()
        }
    }

    private fun attachmentCacheStatus(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val owner = request.attachmentOwnerLease()
                ?: return mapOf("status" to "failed", "cached" to false, "message" to "Authentication required")
        if (!ProcessAndroidAttachmentOwners.admit(owner)) {
                return mapOf("status" to "cancelled", "cached" to false, "message" to "Attachment scope retired")
        }
        val identity = request.publicAttachmentIdentity(owner)
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        val privateTarget =
                attachmentCacheFile(request, owner.principalKey)
        val privateCached = privateTarget.isUsableAttachmentCacheFile()
        return runCatching {
                    authority.withIdentityLock(identity) {
                        val committed = authority.resolve(identity)
                        attachmentCacheStatusPayload(
                                committed = committed,
                                privateCached = privateCached,
                                privatePath =
                                        if (privateCached) privateTarget.absolutePath else ""
                        )
                    }
                }
                .getOrElse {
                    mapOf(
                            "status" to "storage_failed",
                            "cached" to false,
                            "message" to "Unable to inspect downloaded attachment"
                    )
                }
    }

    private fun deleteAttachmentCache(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val owner = request.attachmentOwnerLease()
                ?: return mapOf("status" to "failed", "message" to "Authentication required")
        if (!ProcessAndroidAttachmentOwners.admit(owner)) return mapOf("status" to "missing")
        val identity = request.publicAttachmentIdentity(owner)
        val targetFile = attachmentCacheFile(request, owner.principalKey)
        val openTargetFile =
                attachmentCacheFile(
                        attachmentId = request.optString("attachmentId").takeIf(String::isNotBlank) ?: "attachment",
                        principalKey = owner.principalKey,
                        cacheDir = attachmentOpenCacheDir()
                )
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        return runCatching {
                    authority.withIdentityLock(identity) {
                        val expectedAttemptToken = request.optString("attemptToken").takeIf(String::isNotBlank)
                        val privateClean =
                                listOf(targetFile, openTargetFile).all { privateTarget ->
                                    deleteDirectAttachmentCache(
                                            targetFile = privateTarget,
                                            expectedAttemptToken = expectedAttemptToken
                                    ) != "failed"
                                }
                        if (!privateClean || !authority.delete(identity)) {
                            mapOf("status" to "failed", "message" to "Unable to delete downloaded attachment")
                        } else {
                            mapOf("status" to "ok", "cached" to false, "deleted" to true)
                        }
                    }
                }
                .getOrElse {
                    mapOf("status" to "failed", "message" to "Unable to delete downloaded attachment")
                }
    }

    private fun retireFilePreviewTemporaryFiles(params: String?): String {
        val request = runCatching { JSONObject(params ?: "{}") }.getOrNull()
                ?: return "{\"status\":\"invalid\"}"
        val sessionId = request.optString("sessionId").trim()
        if (sessionId.isBlank() || sessionId.length > 256 || sessionId.any(Char::isISOControl)) {
            return "{\"status\":\"invalid\"}"
        }
        val refsJson = request.optJSONArray("fileRefs")
        val refs =
            buildList {
                if (refsJson != null) {
                    for (index in 0 until refsJson.length()) {
                        refsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
        val quarantine =
            quarantineFilePreviewTemporaryFiles(
                cacheRoots = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir),
                fileRefs = refs
            )
        // The original pathname is vacant at this point.  Deleting only the returned tombstones
        // asynchronously is safe even if a later draft recreates that pathname.
        if (quarantine.quarantineFiles.isNotEmpty()) {
            SlockKuiklyThreadAdapter.execute("file-preview-temp-tombstone-delete") {
                quarantine.quarantineFiles.forEach { tombstone ->
                    runCatching { tombstone.delete() }
                }
            }
        }
        SlockAppLog.record(
            "SlockFilePreviewCleanup",
            "session=$sessionId requested=${quarantine.requestedCount} admitted=${quarantine.admittedCount} " +
                "quarantined=${quarantine.quarantinedCount} rejected=${quarantine.rejectedCount}"
        )
        return JSONObject()
                .put("status", "quarantined")
                .put("requested", quarantine.requestedCount)
                .put("admitted", quarantine.admittedCount)
                .put("quarantined", quarantine.quarantinedCount)
                .put("rejected", quarantine.rejectedCount)
                .toString()
    }

    private fun attachmentDownloadControl(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val owner = request.attachmentOwnerLease()
                ?: return mapOf("status" to "failed", "message" to "Authentication required")
        if (!ProcessAndroidAttachmentOwners.admit(owner)) return mapOf("status" to "missing")
        val downloadId = request.optString("downloadId").takeIf { it.isNotBlank() }
                ?: return mapOf("status" to "failed", "message" to "Missing download id")
        val action = request.optString("action").lowercase(Locale.US)
        val expectedAttemptToken = request.optString("attemptToken").takeIf { it.isNotBlank() }
        val commonHandled = request.optBoolean("commonHandled", false)
        val downloadStatus =
                if (commonHandled) {
                    "missing"
                } else {
                    AttachmentDownloadCoordinator.control(downloadId, expectedAttemptToken, action)
                }
        val exportStatus = attachmentDownloadsExportCoordinator.control(downloadId, expectedAttemptToken, action, owner)
        val status = when {
            exportStatus != "missing" -> exportStatus
            else -> downloadStatus
        }
        return if (status == "failed") {
            mapOf("status" to "failed", "message" to "Unknown download action")
        } else if (status == "missing") {
            mapOf("status" to "missing", "message" to "Download is no longer active")
        } else {
            mapOf("status" to status)
        }
    }

    private fun prepareAttachment(
            request: JSONObject,
            cacheDir: File = attachmentCacheDir(),
            operationId: String = "",
            operationIsCurrent: () -> Boolean = { true },
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): AttachmentPreparationResult {
        if (!operationIsCurrent()) {
            return AttachmentPreparationResult.Terminal(
                    mapOf("status" to "cancelled", "message" to "Save stopped")
            )
        }
        val urlText = request.optString("url").takeIf { it.isNotBlank() }
                ?: return AttachmentPreparationResult.Terminal(
                        mapOf("status" to "failed", "message" to "Unable to download attachment")
                )
        val attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment"
        val requestedFileName = request.optString("fileName").takeIf { it.isNotBlank() } ?: "attachment"
        val downloadId = request.optString("downloadId").takeIf { it.isNotBlank() }
                ?: "${attachmentId}:${requestedFileName}".take(160)
        val owner = request.attachmentOwnerLease()
                ?: return AttachmentPreparationResult.Terminal(
                        mapOf("status" to "failed", "message" to "Authentication required")
                )
        if (!ProcessAndroidAttachmentOwners.admit(owner)) {
            return AttachmentPreparationResult.Terminal(
                    mapOf("status" to "cancelled", "message" to "Attachment scope retired")
            )
        }
        val requestedMimeType = request.optString("mimeType").takeIf { it.isNotBlank() }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return AttachmentPreparationResult.Terminal(
                    mapOf("status" to "storage_failed", "message" to "Storage unavailable")
            )
        }
        cleanupAttachmentCache(cacheDir, owner.principalKey)
        val targetFile =
                attachmentCacheFile(
                        attachmentId,
                        owner.principalKey,
                        cacheDir
                )
        val wasCached = targetFile.isUsableAttachmentCacheFile()
        val directCompletion =
                if (!wasCached) {
                    try {
                        downloadAttachmentToFile(
                                owner,
                                urlText,
                                targetFile,
                                downloadId,
                                requestedMimeType,
                                operationId,
                                operationIsCurrent,
                                progressCallback
                        )
                    } catch (ignored: AttachmentDownloadStoppedException) {
                        return AttachmentPreparationResult.Terminal(
                                mapOf(
                                        "status" to "cancelled",
                                        "message" to "Download stopped",
                                        "cached" to false
                                )
                        )
                    } catch (failure: AttachmentDownloadFailedException) {
                        return AttachmentPreparationResult.Terminal(
                                attachmentDownloadFailurePayload(failure)
                        )
                    }
                } else {
                    null
                }
        val buildReady = {
            AttachmentPreparationResult.Ready(
                    downloadId = downloadId,
                    owner = owner,
                    attachmentId = attachmentId,
                    requestedFileName = requestedFileName,
                    targetFile = targetFile,
                    mimeType =
                            guessAttachmentMimeType(
                                    requestedFileName,
                                    requestedMimeType ?: directCompletion?.contentType
                            ),
                    wasCached = wasCached
            )
        }
        val ready =
                if (directCompletion != null) {
                    if (operationIsCurrent()) {
                        directCompletion.ownerOperation.finishIfCurrent(buildReady)
                    } else {
                        directCompletion.ownerOperation.cancelAndCleanup()
                        null
                    }
                } else if (operationIsCurrent()) {
                    ProcessAndroidAttachmentOwners.runIfCurrent(owner, buildReady)
                } else {
                    null
                }
        return ready
                ?: AttachmentPreparationResult.Terminal(
                        mapOf(
                                "status" to "cancelled",
                                "message" to "Attachment scope retired",
                                "cached" to false
                        )
                )
    }

    private fun downloadAndOpenAttachment(
            request: JSONObject,
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): AndroidAttachmentOpenDispatch {
        val owner = request.attachmentOwnerLease()
                ?: return AndroidAttachmentOpenDispatch.Complete(AndroidAttachmentOpenResult.failed())
        val identity = request.publicAttachmentIdentity(owner)
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        var resolverEmptySource: AndroidAttachmentContentUriSource? = null
        val coordinator =
                AndroidAttachmentOpenCoordinator<AttachmentPreparationResult.Ready>(
                        ownerIsCurrent = { ProcessAndroidAttachmentOwners.admit(owner) },
                        withIdentityLock = { requestedIdentity, block ->
                            authority.withIdentityLock(requestedIdentity) { block() }
                        },
                        resolvePublicSource = { requestedIdentity ->
                            authority.resolve(requestedIdentity)?.let(::androidAttachmentPublicOpenSource)
                        },
                        awaitPrivateRecovery = ProcessAndroidAttachmentPublicDownloads::awaitRecovery,
                        materializePrivate = {
                            when (
                                    val preparation =
                                            prepareAttachment(
                                                    request = request,
                                                    cacheDir = attachmentOpenCacheDir(),
                                                    progressCallback = progressCallback
                                            )
                            ) {
                                is AttachmentPreparationResult.Terminal ->
                                        AndroidAttachmentPrivateMaterialization.Terminal(
                                                AndroidAttachmentOpenResult.terminal(
                                                        preparation.payload["status"]
                                                                ?.toString()
                                                                .orEmpty()
                                                )
                                        )
                                is AttachmentPreparationResult.Ready ->
                                        AndroidAttachmentPrivateMaterialization.Ready(preparation)
                            }
                        },
                        buildPrivateSource = { preparation ->
                            preparation.toPrivateAttachmentOpenSource()
                        },
                        launch = { source, privatePreparation ->
                            openResolvedAttachment(
                                    request = request,
                                    owner = owner,
                                    source = source,
                                    privateSourceFile = privatePreparation?.targetFile
                            ).also { result ->
                                if (result.status == AndroidAttachmentOpenStatus.NoApp) {
                                    resolverEmptySource = source
                                }
                            }
                        },
                        onPrivateSourceFailure = { throwable ->
                            SlockAppLog.record(
                                    "SlockAttachmentOpen",
                                    "attachmentId=${identity.attachmentId.take(8)} phase=private_uri status=storage_failed type=${throwable::class.java.simpleName}"
                            )
                        },
                        failureResult = { throwable ->
                            SlockAppLog.record(
                                    "SlockAttachmentOpen",
                                    "attachmentId=${identity.attachmentId.take(8)} phase=resolve status=failed type=${throwable::class.java.simpleName}"
                            )
                            if (throwable is AndroidAttachmentPublicStorageException) {
                                AndroidAttachmentOpenResult.storageFailed()
                            } else {
                                AndroidAttachmentOpenResult.failed()
                            }
                        }
                )
        val requirement =
                if (request.optBoolean("requirePublicDownload", false)) {
                    AndroidAttachmentOpenRequirement.PublicDownloadOnly
                } else {
                    AndroidAttachmentOpenRequirement.PublicOrPrivate
                }
        val result = coordinator.open(identity, requirement)
        return androidAttachmentOpenDispatch(
                result = result,
                resolverEmptySource = resolverEmptySource,
                owner = owner,
                attachmentId = identity.attachmentId
        )
    }

    private fun AttachmentPreparationResult.Ready.toPrivateAttachmentOpenSource(): AndroidAttachmentContentUriSource? {
        return androidAttachmentPrivateOpenSource(
                context = appContext,
                file = targetFile,
                displayName = requestedFileName,
                mimeType = mimeType
        )
    }

    private fun openResolvedAttachment(
            request: JSONObject,
            owner: AndroidAttachmentOwnerLease,
            source: AndroidAttachmentContentUriSource,
            privateSourceFile: File? = null
    ): AndroidAttachmentOpenResult {
        val attachmentId = request.optString("attachmentId").ifBlank { "attachment" }
        val startedAt = System.currentTimeMillis()
        val requestedFileName = source.displayName
        val openMimeType = source.mimeType
        val isPackageArchive = isAndroidPackageArchive(requestedFileName, openMimeType)
        val openSpec = androidAttachmentOpenLaunchSpec(source, isPackageArchive)
        val openIntents =
                androidAttachmentOpenIntents(
                        context = appContext,
                        spec = openSpec,
                        chooserTitle = appContext.getString(R.string.attachment_open_with_chooser_title)
                )
        val result =
                AndroidAttachmentSourceLauncher(
                        sourceIsReadable = {
                            privateSourceFile.isReadableAttachmentOpenSource(source)
                        },
                        hasVisibleHandler = {
                            appContext.packageManager
                                    .queryIntentActivities(
                                            openIntents.targetIntent,
                                            PackageManager.MATCH_DEFAULT_ONLY
                                    )
                                    .isNotEmpty()
                        },
                        runIfOwnerCurrent = { block ->
                            ProcessAndroidAttachmentOwners.runIfCurrent(owner, block)
                        },
                        launchSource = {
                            executeAndroidAttachmentPlatformLaunch(
                                    allowViewFallback = isPackageArchive,
                                    launchPrimary = {
                                        appContext.startActivity(openIntents.launchIntent)
                                    },
                                    launchViewFallback = {
                                    val viewIntents =
                                            androidAttachmentOpenIntents(
                                                    context = appContext,
                                                    spec =
                                                        androidAttachmentOpenLaunchSpec(
                                                                source = source,
                                                                packageArchive = false
                                                        ).copy(launchInChooser = false),
                                                    chooserTitle = ""
                                            )
                                    appContext.startActivity(viewIntents.launchIntent)
                                    },
                                    isActivityNotFound = { throwable ->
                                        throwable is ActivityNotFoundException
                                    }
                            )
                        },
                        isActivityNotFound = { throwable ->
                            throwable is ActivityNotFoundException
                        },
                        onFailure = { throwable ->
                            SlockAppLog.record(
                                    "SlockAttachmentOpen",
                                    "attachmentId=${attachmentId.take(8)} phase=launch status=failed source=${source.kind.diagnosticName} type=${throwable::class.java.simpleName}"
                            )
                        }
                ).launch(
                        source = source,
                        action =
                            if (isPackageArchive) {
                                AndroidAttachmentOpenAction.Install
                            } else {
                                AndroidAttachmentOpenAction.Open
                            },
                        requiresVisibleHandler = !isPackageArchive
                )
        if (result.status == AndroidAttachmentOpenStatus.Opened) {
            SlockAppLog.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=opened source=${source.kind.diagnosticName} mimeType=$openMimeType cached=${source.durableCache} durationMs=${System.currentTimeMillis() - startedAt}"
            )
        } else if (result.status == AndroidAttachmentOpenStatus.NoApp) {
            SlockAppLog.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=no_app source=${source.kind.diagnosticName} mimeType=$openMimeType durationMs=${System.currentTimeMillis() - startedAt}"
            )
        }
        return result
    }

    private fun publishAttachmentToDownloads(
            identity: AndroidAttachmentPublicIdentity,
            downloadId: String,
            owner: AndroidAttachmentOwnerLease,
            source: File,
            displayName: String,
            mimeType: String,
            progressCallback: ((Map<String, Any>) -> Unit)?
    ): Map<String, Any> {
        val resolver = appContext.contentResolver
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        val mediaStoreRequestToken = UUID.randomUUID().toString().replace("-", "")
        val transaction =
                AndroidAttachmentPublicDownloadTransaction(
                        authority = authority,
                        identity = identity,
                        displayName = displayName,
                        mimeType = mimeType,
                        deleteExact = { uri ->
                            runCatching { resolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
                        }
                )
        val result =
                try {
                    attachmentDownloadsExportCoordinator.exportIfCurrent(
                        request =
                                AttachmentDownloadsExportRequest(
                                        downloadId = downloadId,
                                        source = source,
                                        displayName = displayName,
                                        mimeType = mimeType,
                                        owner = owner
                                ),
                        backend =
                                object : AttachmentDownloadsExportBackend {
                                    override fun insert(displayName: String, mimeType: String): String? {
                                        val values =
                                                ContentValues().apply {
                                                    put(
                                                            MediaStore.MediaColumns.DISPLAY_NAME,
                                                            pendingAttachmentMediaStoreDisplayName(
                                                                    mediaStoreRequestToken,
                                                                    displayName
                                                            )
                                                    )
                                                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                                    put(
                                                            MediaStore.MediaColumns.RELATIVE_PATH,
                                                            ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH
                                                    )
                                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                                }
                                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.toString()
                                                ?: return null
                                        if (!transaction.attach(uri)) return null
                                        return uri
                                    }

                                    override fun openOutput(uri: String) = resolver.openOutputStream(Uri.parse(uri), "w")

                                    override fun publish(uri: String): Boolean {
                                        val values =
                                                ContentValues().apply {
                                                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                                }
                                        if (resolver.update(Uri.parse(uri), values, null, null) <= 0) return false
                                        return transaction.verifyPublished(uri, source.length())
                                    }

                                    override fun deletePending(uri: String): Boolean =
                                            resolver.delete(
                                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                                    "${MediaStore.MediaColumns._ID} = ? AND " +
                                                            "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                                                            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                                                            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                                                    arrayOf(
                                                            android.content.ContentUris.parseId(Uri.parse(uri)).toString(),
                                                            "1",
                                                            ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH,
                                                            "$ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH/",
                                                            "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX%"
                                                    )
                                            ) > 0

                                    override fun rowState(uri: String): AttachmentDownloadsRowState =
                                            runCatching {
                                                        val cursor = resolver.query(
                                                                Uri.parse(uri),
                                                                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                                                                null,
                                                                null,
                                                                null
                                                        ) ?: return@runCatching AttachmentDownloadsRowState.Unknown
                                                        cursor.use {
                                                            if (!cursor.moveToFirst()) {
                                                                AttachmentDownloadsRowState.Missing
                                                            } else {
                                                                val pendingIndex =
                                                                        cursor.getColumnIndex(
                                                                                MediaStore.MediaColumns.IS_PENDING
                                                                        )
                                                                if (pendingIndex < 0) {
                                                                    AttachmentDownloadsRowState.Unknown
                                                                } else if (cursor.getInt(pendingIndex) == 1) {
                                                                    AttachmentDownloadsRowState.Pending
                                                                } else {
                                                                    AttachmentDownloadsRowState.Published
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .getOrDefault(AttachmentDownloadsRowState.Unknown)
                                },
                        operationIsCurrent = { ProcessAndroidAttachmentOwners.admit(owner) },
                        observer = { progress ->
                            SlockAppLog.record(
                                    "SlockAttachmentExport",
                                    "phase=${progress.phase} keyHash=${downloadId.hashCode().toString(16)} " +
                                            "downloadedBytes=${progress.downloadedBytes} totalBytes=${progress.totalBytes}"
                            )
                            progressCallback?.invoke(progress.toAndroidBridgePayload())
                                }
                    )
                } catch (throwable: Throwable) {
                    transaction.abort()
                    deleteDirectAttachmentCache(source, expectedAttemptToken = null)
                    throw throwable
                }
        val finalResult =
                finalizePublishedAttachmentDownload(
                        transaction = transaction,
                        result = result,
                        deletePrivateStaging = {
                            deleteDirectAttachmentCache(source, expectedAttemptToken = null)
                        }
                )
        SlockAppLog.record(
                "SlockAttachmentExport",
                "phase=terminal keyHash=${downloadId.hashCode().toString(16)} status=${finalResult.status} " +
                        "failurePhase=${finalResult.failurePhase.orEmpty()} sizeBytes=${finalResult.sizeBytes} " +
                        "storageCode=${finalResult.storageDiagnosticCode.orEmpty()} " +
                        "storageVerifyInspections=${finalResult.storageVerificationAttempts} " +
                        "storageCommitInspections=${finalResult.storageCommitAttempts}"
        )
        return buildMap {
            put("status", finalResult.status)
            put("message", finalResult.message)
            put("cached", finalResult.status == "saved")
            put("sizeBytes", finalResult.sizeBytes)
            finalResult.uri?.let { put("uri", it) }
            finalResult.fileName?.let { put("fileName", it) }
            finalResult.failurePhase?.let { put("phase", it) }
        }
    }

    private fun publishAttachmentToImages(
            downloadId: String,
            operationId: String,
            owner: AndroidAttachmentOwnerLease,
            source: File,
            displayName: String,
            mimeType: String,
            width: Int,
            height: Int,
            cached: Boolean,
            progressCallback: ((Map<String, Any>) -> Unit)?
    ): Map<String, Any> {
        val resolver = appContext.contentResolver
        val mediaStoreRequestToken = UUID.randomUUID().toString().replace("-", "")
        val result =
                attachmentDownloadsExportCoordinator.exportIfCurrent(
                        request =
                                AttachmentDownloadsExportRequest(
                                        downloadId = downloadId,
                                        operationId = operationId,
                                        source = source,
                                        displayName = displayName,
                                        mimeType = mimeType,
                                        owner = owner
                                ),
                        backend =
                                object : AttachmentDownloadsExportBackend {
                                    override fun insert(displayName: String, mimeType: String): String? {
                                        val values = ContentValues()
                                        values.put(
                                                MediaStore.MediaColumns.DISPLAY_NAME,
                                                pendingAttachmentMediaStoreDisplayName(mediaStoreRequestToken, displayName)
                                        )
                                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, ANDROID_ATTACHMENT_IMAGES_RELATIVE_PATH)
                                        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.toString()
                                    }

                                    override fun openOutput(uri: String) = resolver.openOutputStream(Uri.parse(uri), "w")

                                    override fun publish(uri: String): Boolean {
                                        val values = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                                        }
                                        return resolver.update(Uri.parse(uri), values, null, null) > 0
                                    }

                                    override fun deletePending(uri: String): Boolean {
                                        return resolver.delete(
                                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                "${MediaStore.MediaColumns._ID} = ? AND " +
                                                        "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                                                        "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                                                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                                                arrayOf(
                                                        android.content.ContentUris.parseId(Uri.parse(uri)).toString(),
                                                        "1",
                                                        ANDROID_ATTACHMENT_IMAGES_RELATIVE_PATH,
                                                        "$ANDROID_ATTACHMENT_IMAGES_RELATIVE_PATH/",
                                                        "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX%"
                                                )
                                        ) > 0
                                    }

                                    override fun rowState(uri: String): AttachmentDownloadsRowState {
                                        return runCatching {
                                                    resolver.query(
                                                            Uri.parse(uri),
                                                            arrayOf(MediaStore.MediaColumns.IS_PENDING),
                                                            null,
                                                            null,
                                                            null
                                                    )?.use { cursor ->
                                                        if (!cursor.moveToFirst()) return@use AttachmentDownloadsRowState.Missing
                                                        val index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                                                        if (index < 0) AttachmentDownloadsRowState.Unknown
                                                        else if (cursor.getInt(index) == 1) AttachmentDownloadsRowState.Pending
                                                        else AttachmentDownloadsRowState.Published
                                                    } ?: AttachmentDownloadsRowState.Unknown
                                                }
                                                .getOrDefault(AttachmentDownloadsRowState.Unknown)
                                    }
                                },
                        operationIsCurrent = {
                            ProcessAndroidAttachmentExportAuthorities.isCurrent(
                                    owner,
                                    downloadId,
                                    operationId
                            )
                        },
                        observer = { progress -> progressCallback?.invoke(progress.toAndroidBridgePayload()) }
                )
        return buildMap {
            put("status", result.status)
            put("message", if (result.status == "saved") "Saved to Photos" else result.message)
            put("cached", cached)
            put("sizeBytes", result.sizeBytes)
            put("mimeType", mimeType)
            put("pixelWidth", width)
            put("pixelHeight", height)
            result.uri?.let { put("uri", it) }
            result.fileName?.let { put("fileName", it) }
            result.failurePhase?.let { put("phase", it) }
        }
    }

    private fun attachmentCacheDir(): File =
            File(appContext.cacheDir, ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME)

    private fun attachmentOpenCacheDir(): File =
            File(attachmentCacheDir(), ANDROID_ATTACHMENT_PRIVATE_OPEN_DIR_NAME)

    private fun attachmentCacheFile(request: JSONObject, principalKey: String): File =
            attachmentCacheFile(
                    attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment",
                    principalKey = principalKey
            )

    private fun attachmentCacheFile(
            attachmentId: String,
            principalKey: String,
            cacheDir: File = attachmentCacheDir()
    ): File =
            File(
                    cacheDir,
                    androidAttachmentPrivateCacheFileName(
                            AndroidAttachmentPublicIdentity(
                                    principalKey = principalKey,
                                    attachmentId = attachmentId
                            )
                    )
            )

    private fun File.isUsableAttachmentCacheFile(): Boolean =
            isFile &&
                    length() > 0L &&
                    System.currentTimeMillis() - lastModified() <= ATTACHMENT_CACHE_MAX_AGE_MS

    private fun downloadAttachmentToFile(
            owner: AndroidAttachmentOwnerLease,
            urlText: String,
            targetFile: File,
            downloadId: String,
            requestedMimeType: String?,
            operationId: String = "",
            operationIsCurrent: () -> Boolean = { true },
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): DirectAttachmentDownloadCompletion {
        val requestStartedAt = SystemClock.elapsedRealtime()
        val downloadKeyHash = downloadId.hashCode().toString(16)
        val resultRef = AtomicReference<AttachmentDownloadResult?>()
        val completed = CountDownLatch(1)
        val ownerOperation =
                AndroidAttachmentDirectDownloadOwnerOperation(
                        registry = ProcessAndroidAttachmentOwners,
                        owner = owner,
                        operationId = "direct-download:$downloadId:${operationId.ifBlank { "default" }}",
                        cleanupAttempt = { attemptToken ->
                            cleanupDirectAttachmentAttempt(targetFile, attemptToken)
                        }
                )
        val observer =
                object : AttachmentDownloadObserver {
                    override fun onProgress(progress: AttachmentDownloadProgress) {
                        if (!ProcessAndroidAttachmentOwners.admit(owner) || !operationIsCurrent()) {
                            AttachmentDownloadCoordinator.control(
                                    downloadId = downloadId,
                                    expectedAttemptToken = progress.attemptToken,
                                    action = "cancel"
                            )
                            return
                        }
                        SlockAppLog.record(
                                "SlockAttachmentDownload",
                                "engine=direct phase=${progress.phase} keyHash=$downloadKeyHash " +
                                        "downloadedBytes=${progress.downloadedBytes} " +
                                        "expectedBytes=${progress.totalBytes ?: -1L} " +
                                        "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt}"
                        )
                        progressCallback?.invoke(progress.toAndroidBridgePayload())
                    }

                    override fun onComplete(result: AttachmentDownloadResult) {
                        resultRef.set(result)
                        completed.countDown()
                    }
                }
        if (!operationIsCurrent() ||
                !ownerOperation.start {
                    AttachmentDownloadCoordinator.start(
                            downloadId = downloadId,
                            targetKey = targetFile.absolutePath,
                            url = urlText,
                            targetPath = targetFile.absolutePath,
                            observer = observer
                    )
                }) {
            throw AttachmentDownloadStoppedException()
        }
        try {
            completed.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            ownerOperation.cancelAndCleanup()
            throw AttachmentDownloadStoppedException()
        }
        val result =
                resultRef.get()
                        ?: run {
                            ownerOperation.cancelAndCleanup()
                            throw IllegalStateException("Attachment download ended without a result")
                        }
        if (!operationIsCurrent()) {
            ownerOperation.cancelAndCleanup()
            throw AttachmentDownloadStoppedException()
        }
        return when (result.status) {
            "complete" ->
                    DirectAttachmentDownloadCompletion(
                            contentType = result.contentType ?: requestedMimeType,
                            ownerOperation = ownerOperation
                    )
            "cancelled" -> {
                ownerOperation.close()
                throw AttachmentDownloadStoppedException()
            }
            else -> {
                ownerOperation.cancelAndCleanup()
                throw AttachmentDownloadFailedException(
                        errorKind = result.errorKind,
                        message = result.message ?: result.errorKind ?: "Attachment download failed"
                )
            }
        }
    }

    private fun cleanupDirectAttachmentAttempt(targetFile: File, attemptToken: String) {
        val targetKey = targetFile.absolutePath
        val reservation =
                AttachmentDownloadCoordinator.beginTargetCleanup(
                        targetKey = targetKey,
                        expectedAttemptToken = attemptToken
                )
        if (reservation.status == "stale") return
        try {
            runCatching { targetFile.delete() }
        } finally {
            AttachmentDownloadCoordinator.finishTargetCleanup(targetKey, reservation.token)
        }
    }

    private fun cleanupAttachmentCache(cacheDir: File, principalKey: String) {
        cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("$principalKey-") && !it.name.contains(".part") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(ATTACHMENT_CACHE_MAX_FILES)
                ?.forEach { runCatching { it.delete() } }
    }

    private fun guessAttachmentMimeType(fileName: String, mimeType: String?): String {
        if (fileName.substringAfterLast('.', missingDelimiterValue = "").equals("apk", ignoreCase = true)) {
            return ANDROID_PACKAGE_ARCHIVE_MIME_TYPE
        }
        val normalized = mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
        if (normalized.equals(ANDROID_PACKAGE_ARCHIVE_MIME_TYPE, ignoreCase = true)) {
            return ANDROID_PACKAGE_ARCHIVE_MIME_TYPE
        }
        if (!normalized.isNullOrBlank()) return normalized
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
    }

    private fun isAndroidPackageArchive(fileName: String, mimeType: String): Boolean =
            mimeType.equals(ANDROID_PACKAGE_ARCHIVE_MIME_TYPE, ignoreCase = true) ||
                    fileName.substringAfterLast('.', missingDelimiterValue = "").equals("apk", ignoreCase = true)

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun socialAuth(params: String?, callback: KuiklyRenderCallback?) {
        val provider = JSONObject(params ?: "{}").optString("provider").trim().lowercase()
        if (!AndroidMobileOAuth.isSupportedProvider(provider)) {
            callback?.invoke(
                    mapOf(
                            "code" to 0,
                            "status" to "error",
                            "message" to "This sign-in provider is not available on Android yet"
                    )
            )
            return
        }
        val hostActivity = activity as? SlockKuiklyActivity
        val lifecycleOwner = hostActivity ?: appContext as? SlockKuiklyActivity
        if (lifecycleOwner == null) {
            callback?.invoke(
                    mapOf(
                            "code" to 0,
                            "status" to "error",
                            "message" to "Unable to open ${provider.socialProviderDisplayName()} sign-in"
                    )
            )
            return
        }

        lifecycleOwner.lifecycleScope.launch {
            val pkce = AndroidMobileOAuth.createPkce()
            val authDataSource =
                    SharedAuthDataSource(
                            api = AndroidAuthApiFactory.createAuthApi(appContext),
                            sessionStore =
                                    KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)),
                            diagnosticsLogger = ::logSharedAuthDiagnostics
                    )
            val startResult =
                    withContext(Dispatchers.IO) {
                        authDataSource.startMobileOAuthLogin(
                                provider = provider,
                                appEnv = AndroidMobileOAuth.AppEnv,
                                returnUri = AndroidMobileOAuth.ReturnUri,
                                codeChallenge = pkce.codeChallenge
                        )
                    }
            when (startResult) {
                is SharedAuthResult.Success -> {
                    AndroidMobileOAuthPendingStore(appContext).save(
                            AndroidMobileOAuthPendingRequest(
                                    provider = provider,
                                    requestId = startResult.data.requestId,
                                    codeVerifier = pkce.codeVerifier,
                                    mode = AndroidMobileOAuth.ModeLogin
                            )
                    )
                    val intent =
                            Intent(Intent.ACTION_VIEW, Uri.parse(startResult.data.authorizationUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                    runCatching {
                                appContext.startActivity(intent)
                            }
                            .onSuccess {
                                callback?.invoke(mapOf("code" to 202, "status" to "started"))
                            }
                            .onFailure { throwable ->
                                AndroidMobileOAuthPendingStore(appContext).clear()
                                val message =
                                        if (throwable is ActivityNotFoundException) {
                                            "No browser can open ${provider.socialProviderDisplayName()} sign-in"
                                        } else {
                                            "Unable to open ${provider.socialProviderDisplayName()} sign-in"
                                        }
                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                callback?.invoke(
                                        mapOf(
                                                "code" to 0,
                                                "status" to "error",
                                                "message" to message
                                        )
                                )
                            }
                }
                is SharedAuthResult.Failure -> {
                    callback?.invoke(
                            mapOf(
                                    "code" to 0,
                                    "status" to "error",
                                    "message" to startResult.message
                            )
                    )
                }
                SharedAuthResult.Unauthenticated -> {
                    callback?.invoke(
                            mapOf(
                                    "code" to 0,
                                    "status" to "error",
                                    "message" to "${provider.socialProviderDisplayName()} sign-in failed. Try again."
                            )
                    )
                }
            }
        }
    }

    private fun safeUrlForDiagnostics(rawUrl: String): String =
            runCatching {
                val url = URL(rawUrl)
                "${url.protocol}://${url.host}${url.path}"
            }.getOrDefault(rawUrl.substringBefore("?").take(160))

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        private val handsUpdateDownloads = ConcurrentHashMap<String, HandsUpdateDownload>()
        private val handsUpdateDownloadLock = Any()
        private val alphaHandsUpdateCallOwner = HandsDeltaUpdateCallOwner()
        @Volatile private var pendingHandsAutoInstallKey: String? = null
        private const val REQUEST_NOTIFICATIONS_CODE = 4207
        private const val ATTACHMENT_CACHE_MAX_FILES = 80
        private const val ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val THREAD_SHARE_IMAGE_CACHE_DIR_NAME = "slock-share-images"
        private const val THREAD_SHARE_IMAGE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val HANDS_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME = "hands-feedback-attachments"
        private const val HANDS_FEEDBACK_ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val ANDROID_PACKAGE_ARCHIVE_MIME_TYPE = "application/vnd.android.package-archive"
        private const val HANDS_INSTALL_OPENED = "opened"
        private const val HANDS_INSTALL_PERMISSION_REQUIRED = "permission_required"
        private const val HANDS_INSTALL_FAILED = "failed"

        internal fun resumePendingHandsAutoInstall(context: Context) {
            if (!BuildConfig.HANDS_APK_UPDATE_ENABLED) return
            val key = pendingHandsAutoInstallKey ?: return
            val download =
                    handsUpdateDownloads[key]
                            ?: run {
                                pendingHandsAutoInstallKey = null
                                return
                            }
            if (download.failed || download.status() != DownloadManager.STATUS_SUCCESSFUL) return
            if (!canRequestPackageInstalls(context)) return
            openHandsDownloadedApkOrRequestPermission(
                    context = context,
                    download = download,
                    source = "permission_resume"
            )
        }

        private fun openHandsDownloadedApkOrRequestPermission(
                context: Context,
                download: HandsUpdateDownload,
                source: String
        ): String {
            if (!BuildConfig.HANDS_APK_UPDATE_ENABLED) return HANDS_INSTALL_FAILED
            if (!canRequestPackageInstalls(context)) {
                pendingHandsAutoInstallKey = download.key
                openUnknownAppSourcesSettings(context)
                SlockAppLog.record(
                        "SlockHandsUpdate",
                        "install_permission_required source=$source key=${download.key} downloadId=${download.downloadId}"
                )
                return HANDS_INSTALL_PERMISSION_REQUIRED
            }
            pendingHandsAutoInstallKey = download.key
            return runCatching {
                        openDownloadedHandsApk(context, download.downloadManager, download.downloadId, download.fileName)
                        pendingHandsAutoInstallKey = null
                        HANDS_INSTALL_OPENED
                    }
                    .getOrElse { error ->
                        SlockAppLog.record(
                                "SlockHandsUpdate",
                                "install_intent_failed source=$source key=${download.key} downloadId=${download.downloadId} error=${error.javaClass.simpleName}"
                        )
                        HANDS_INSTALL_FAILED
                    }
        }

        private fun openDownloadedHandsApk(
                context: Context,
                downloadManager: DownloadManager,
                downloadId: Long,
                fileName: String
        ) {
            val downloadedUri =
                    downloadManager.getUriForDownloadedFile(downloadId)
                            ?: queryDownloadedHandsApkFile(downloadManager, downloadId)?.let { file ->
                                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            }
                            ?: error("Downloaded APK URI unavailable")
            val installIntent =
                    Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        setDataAndType(downloadedUri, ANDROID_PACKAGE_ARCHIVE_MIME_TYPE)
                        clipData = ClipData.newUri(context.contentResolver, fileName, downloadedUri)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    }
            context.startActivity(installIntent)
            SlockAppLog.record(
                    "SlockHandsUpdate",
                    "install_intent_opened downloadId=$downloadId uriScheme=${downloadedUri.scheme.orEmpty()}"
            )
        }

        private fun queryDownloadedHandsApkFile(downloadManager: DownloadManager, downloadId: Long): File? {
            val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                val index = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (index < 0) return null
                val localUri = it.getString(index)?.takeIf { value -> value.isNotBlank() } ?: return null
                val uri = Uri.parse(localUri)
                return if (uri.scheme == "file" && !uri.path.isNullOrBlank()) File(uri.path!!) else null
            }
        }

        private fun canRequestPackageInstalls(context: Context): Boolean =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        context.packageManager.canRequestPackageInstalls()

        private fun openUnknownAppSourcesSettings(context: Context) {
            val intent =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
            runCatching { context.startActivity(intent) }
                    .onFailure {
                        runCatching {
                            context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                            )
                        }
                    }
        }
    }
}

internal class AttachmentDownloadStoppedException : RuntimeException()

internal class AttachmentDownloadFailedException(
        val errorKind: String?,
        message: String
) : RuntimeException(message)

internal fun attachmentSavePreparationFailurePayload(
        throwable: Throwable?
): Map<String, Any> =
        when (throwable) {
            is AttachmentDownloadStoppedException ->
                    mapOf(
                            "status" to "cancelled",
                            "cached" to false,
                            "message" to (throwable.message ?: "Download stopped")
                    )
            is AttachmentDownloadFailedException -> attachmentDownloadFailurePayload(throwable)
            else ->
                    mapOf(
                            "status" to "storage_failed",
                            "cached" to false,
                            "message" to "Unable to prepare attachment storage"
                    )
        }

/**
 * task #319: payload for `attachmentCacheStatus`. `cached` is strictly "public download
 * committed" — it drives the Download-vs-Open-with UI choice and must NOT count the
 * private staging copy. The private copy is reported separately on `privateCached` +
 * `privatePath` so the open path can reuse it without re-fetching the signed URL.
 */
internal fun attachmentCacheStatusPayload(
        committed: AndroidAttachmentPublicRecord?,
        privateCached: Boolean,
        privatePath: String
): Map<String, Any> =
        mapOf(
                "status" to "ok",
                "cached" to (committed != null),
                "privateCached" to privateCached,
                "privatePath" to privatePath,
                "active" to false,
                "attemptToken" to "",
                "recordVersion" to (committed?.generation ?: -1L),
                "sizeBytes" to (committed?.sizeBytes ?: 0L),
                "uri" to committed?.uri.orEmpty()
        )

internal fun attachmentDownloadFailurePayload(
        failure: AttachmentDownloadFailedException
): Map<String, Any> {
    val storageFailure =
            failure.errorKind in setOf("storage_write", "storage_prepare", "storage_publish")
    return mapOf(
            "status" to if (storageFailure) "storage_failed" else "download_failed",
            "cached" to false,
            "message" to (failure.message ?: "Attachment download failed")
    )
}

private data class DirectAttachmentDownloadCompletion(
        val contentType: String?,
        val ownerOperation: AndroidAttachmentDirectDownloadOwnerOperation
)

private fun AttachmentDownloadProgress.toAndroidBridgePayload(): Map<String, Any> =
        buildMap {
            put("downloadId", downloadId)
            put("attemptToken", attemptToken)
            put("sequence", sequence)
            put("phase", phase)
            put("status", "progress")
            put("downloadedBytes", downloadedBytes)
            totalBytes?.let { put("totalBytes", it) }
            percent?.let { put("percent", it.coerceIn(0, 100)) }
            bytesPerSecond?.takeIf { it > 0L }?.let { put("bytesPerSecond", it) }
        }

internal fun attachmentDownloadPartFiles(targetFile: File): List<File> {
    val legacyName = "${targetFile.name}.part"
    val attemptPrefix = "$legacyName-"
    return targetFile.parentFile
            ?.listFiles()
            ?.filter { file -> file.name == legacyName || file.name.startsWith(attemptPrefix) }
            .orEmpty()
}

internal fun deleteDirectAttachmentCache(
        targetFile: File,
        expectedAttemptToken: String?
): String {
    val targetKey = targetFile.absolutePath
    val reservation =
            AttachmentDownloadCoordinator.beginTargetCleanup(
                    targetKey = targetKey,
                    expectedAttemptToken = expectedAttemptToken
            )
    if (reservation.status == "stale") return "missing"
    try {
        if (reservation.status == "timeout") return "failed"
        val partsDeleted = attachmentDownloadPartFiles(targetFile).all { part -> part.delete() || !part.exists() }
        val targetDeleted = targetFile.delete() || !targetFile.exists()
        return if (partsDeleted && targetDeleted) "deleted" else "failed"
    } finally {
        AttachmentDownloadCoordinator.finishTargetCleanup(targetKey, reservation.token)
    }
}

internal fun clearDirectAttachmentStaging(directory: File): Boolean {
    val targetPrefix = directory.absolutePath.trimEnd(File.separatorChar) + File.separator
    return attachmentDownloadsExportCoordinator.runWithSourceDirectoryCleanup(targetPrefix) {
        val directoryReservation = AttachmentDownloadCoordinator.beginDirectoryCleanup(targetPrefix)
        try {
            val targets = linkedSetOf<File>()
            directoryReservation.targetKeys.mapTo(targets, ::File)
            directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                val partMarker = file.name.lastIndexOf(".part-")
                when {
                    partMarker > 0 -> targets += File(file.parentFile, file.name.substring(0, partMarker))
                    file.name.endsWith(".part") -> targets += File(file.parentFile, file.name.removeSuffix(".part"))
                    else -> targets += file
                }
            }
            val targetsClean = targets.all { target ->
                deleteDirectAttachmentCache(target, expectedAttemptToken = null) != "failed"
            }
            val unexpectedChildrenClean = directory.listFiles().orEmpty().all { child ->
                child.deleteRecursively() || !child.exists()
            }
            targetsClean && unexpectedChildrenClean
        } finally {
            AttachmentDownloadCoordinator.finishDirectoryCleanup(targetPrefix, directoryReservation.token)
        }
    }
}

private fun File?.isReadableAttachmentOpenSource(source: AndroidAttachmentContentUriSource): Boolean =
        when (source.kind) {
            AndroidAttachmentOpenSourceKind.PublicDownload -> this == null
            AndroidAttachmentOpenSourceKind.PrivateCache ->
                    this != null && isFile && length() == source.sizeBytes && source.sizeBytes > 0L
        }

private sealed interface AttachmentPreparationResult {
    data class Ready(
            val downloadId: String,
            val owner: AndroidAttachmentOwnerLease,
            val attachmentId: String,
            val requestedFileName: String,
            val targetFile: File,
            val mimeType: String,
            val wasCached: Boolean
    ) : AttachmentPreparationResult

    data class Terminal(
            val payload: Map<String, Any>
    ) : AttachmentPreparationResult
}

private fun JSONObject.attachmentOwnerLease(): AndroidAttachmentOwnerLease? {
    val shellInstanceId = optString("shellInstanceId").takeIf(String::isNotBlank) ?: return null
    val scopeId = optString("ownerScopeId").takeIf(String::isNotBlank) ?: return null
    val principalKey =
            optString("ownerKey").takeIf { it.matches(AndroidAttachmentPrincipalKeyPattern) }
                    ?: return null
    val authEpoch = optLong("ownerAuthEpoch", -1L).takeIf { it >= 0L } ?: return null
    val generation = optLong("ownerGeneration", -1L).takeIf { it >= 0L } ?: return null
    return AndroidAttachmentOwnerLease(shellInstanceId, scopeId, authEpoch, principalKey, generation)
}

private fun JSONObject.publicAttachmentIdentity(
        owner: AndroidAttachmentOwnerLease
): AndroidAttachmentPublicIdentity =
        AndroidAttachmentPublicIdentity(
                principalKey = owner.principalKey,
                attachmentId = optString("attachmentId").takeIf(String::isNotBlank) ?: "attachment"
        )

private fun AndroidAttachmentPublicRecord.toSavedAttachmentPayload(): Map<String, Any> =
        buildMap {
            put("status", "saved")
            put("message", "Already downloaded")
            put("cached", true)
            put("sizeBytes", sizeBytes)
            put("fileName", displayName)
            uri?.let { put("uri", it) }
        }

private val attachmentDownloadsExportCoordinator = AttachmentDownloadsExportCoordinator()

private fun AttachmentDownloadsExportProgress.toAndroidBridgePayload(): Map<String, Any> =
        buildMap {
            put("downloadId", downloadId)
            put("attemptToken", attemptToken)
            put("sequence", sequence)
            put("phase", phase)
            put("status", "progress")
            put("downloadedBytes", downloadedBytes)
            put("totalBytes", totalBytes)
            percent?.let { put("percent", it.coerceIn(0, 100)) }
        }

internal fun publicDownloadDisplayName(requestedFileName: String, fallbackExtension: String?): String {
    val baseName =
            requestedFileName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .map { character ->
                        if (
                            character.isLetterOrDigit() ||
                            character == '.' ||
                            character == '-' ||
                            character == '_' ||
                            character == ' '
                        ) {
                            character
                        } else {
                            '_'
                        }
                    }
                    .joinToString("")
                    .trim()
                    .trim('.')
                    .take(120)
                    .ifBlank { "attachment" }
    if (baseName.contains('.') && !baseName.endsWith('.')) return baseName
    val extension = fallbackExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() }
    return if (extension.isNullOrBlank()) baseName else "$baseName.$extension"
}

private data class HandsUpdateDownload(
        val key: String,
        val versionCode: Long,
        val downloadId: Long,
        val fileName: String,
        val downloadUrl: String,
        val arch: String,
        val downloadManager: DownloadManager,
        @Volatile var completed: Boolean = false,
        @Volatile var failed: Boolean = false
) {
    fun progressPayload(): Map<String, Any> {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor?.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return mapOf(
                    "status" to "ok",
                    "token" to downloadId.toString(),
                    "phase" to when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> "ready"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        else -> "downloading"
                    },
                    "downloadedBytes" to it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    "totalBytes" to it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
        }
        return mapOf("status" to "ok", "token" to downloadId.toString(), "phase" to "failed")
    }

    fun status(): Int =
            if (completed) {
                DownloadManager.STATUS_SUCCESSFUL
            } else {
                queryStatus()
            }

    private fun queryStatus(): Int {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                ?: return DownloadManager.STATUS_FAILED
        cursor.use {
            if (!it.moveToFirst()) return DownloadManager.STATUS_FAILED
            val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (index >= 0) it.getInt(index) else DownloadManager.STATUS_FAILED
        }
    }
}

internal fun handsUpdateDownloadKey(
        slug: String,
        channel: String?,
        versionCode: Long,
        arch: String?
): String =
        listOf(
                slug,
                channel.orEmpty(),
                versionCode,
                arch.orEmpty()
        ).joinToString("|")

private fun String.socialProviderDisplayName(): String =
        when (lowercase(Locale.US)) {
            "github" -> "GitHub"
            "google" -> "Google"
            else -> "Social"
        }

private enum class SlockBridgeLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        fun fromWire(value: String?): SlockBridgeLogLevel =
                when (value?.trim()?.lowercase()) {
                    "debug" -> DEBUG
                    "warn" -> WARN
                    "error",
                    "fatal" -> ERROR
                    else -> INFO
                }
    }
}

private fun logSharedAuthDiagnostics(message: String) {
    SlockAppLog.auth("SlockKuikly", "shared_auth $message")
}

internal fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val value = opt(key)) {
            is JSONObject -> map[key] = value.toMap()
            null -> Unit
            else -> map[key] = value
        }
    }
    return map
}

private fun JSONObject?.toStableLogText(): String {
    this ?: return ""
    val parts = mutableListOf<String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = optString(key).take(80)
        if (key.isNotBlank() && value.isNotBlank()) {
            parts += "${key.take(40)}:$value"
        }
    }
    return parts.sorted().joinToString(separator = ",")
}

private fun JSONObject?.toStableStringMap(): Map<String, String> {
    this ?: return emptyMap()
    val result = linkedMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key.isNotBlank()) result[key] = optString(key)
    }
    return result
}

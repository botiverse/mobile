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
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.net.Uri
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
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import build.raft.app.R
import build.raft.app.BuildConfig
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
import build.raft.app.widget.SlockActivityWidgetProvider
import build.raft.app.widget.SlockAgentStatusWidgetProvider
import build.raft.app.data.SlockInProcessNotifyModule
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.SLOCK_APP_UPDATE_EVENT
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
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
import build.raft.app.network.SharedBinaryDownloader
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
import build.hands.update.UpdateChecker
import build.hands.update.internal.HandsClient
import build.hands.update.models.UpdateCheckResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    appSlug: String,
    channel: String,
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
        append("Hands: slug=").append(appSlug).append(", channel=").append(channel)
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

open class SlockKuiklyActivity :
    ComponentActivity(),
    KuiklyRenderViewBaseDelegatorDelegate,
    SlockKuiklyChildPageLoadListener {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var pageSpec: SlockKuiklyPageSpec? = null
    private var homeColdStartSplashGate: HomeColdStartSplashGate? = null
    private var homeColdStartSplashTimeoutLogged = false
    private var homeColdStartUsesNativeHold = false
    private var homeColdStartHoldView: View? = null
    private val homeColdStartTimeoutRunnable = Runnable { handleHomeColdStartTimeout() }
    private var homeColdStartFirstDrawListener: ViewTreeObserver.OnDrawListener? = null
    private var kuiklyHostAttached = false
    private var inputFontLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingThreadAttachmentUpload: PendingThreadAttachmentUpload? = null
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
                "home_content_splash_timeout timeoutMs=$HOME_COLD_START_SPLASH_TIMEOUT_MILLIS"
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
        removeHomeColdStartNativeHold(reason = "timeout")
    }

    private fun removeHomeColdStartNativeHold(reason: String) {
        window.decorView.removeCallbacks(homeColdStartTimeoutRunnable)
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
        if (!isHomeAppShellChildPageData(pageDataText)) return
        if (homeColdStartSplashGate?.release() != true) return
        removeHomeColdStartNativeHold(reason = "child_page_load")
        SlockAppLog.d(
            "SlockBoot",
            "home_content_splash_released source=child_page_load success=$isSucceed page=$pageName"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        screenshotFeedbackListeningGate.stop()
        screenCaptureObserver?.stop()
        screenCaptureObserver = null
        hideScreenshotFeedbackAction()
        removeHomeColdStartFirstDrawListener()
        removeHomeColdStartNativeHold(reason = "destroy")
        removeSlockInputFontBridge()
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
        SlockKuiklyBridgeModule.resumePendingQuiverAutoInstall(applicationContext)
    }

    private fun reportRealtimeVisibility(
        visibility: AndroidHostVisibility,
        source: String,
    ) {
        AndroidAppScopeRealtimeOwner.onHostVisibilityChanged(
            context = applicationContext,
            visibility = visibility,
            shellInstanceId = realtimeVisibilityOwnerId,
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
        if (isSucceed) return
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

    internal fun requestThreadAttachmentUpload(
            channelId: String,
            imageOnly: Boolean,
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
                PendingThreadAttachmentUpload(channelId = channelId, callback = callback)
        runCatching {
                    if (imageOnly) {
                        threadImageAttachmentPickerLauncher.launch(threadMediaAttachmentPickerRequest())
                    } else {
                        threadFileAttachmentPickerLauncher.launch(arrayOf("*/*"))
                    }
                }
                .onFailure { throwable ->
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

    internal fun requestGoogleNativeSocialAuth(callback: KuiklyRenderCallback?): Boolean {
        if (!googleSocialAuthGateway.isNativeConfigured()) return false

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
                            callback?.invoke(mapOf("code" to 200, "status" to "success"))
                            SlockKuiklyActivity.startAppShell(
                                    this@SlockKuiklyActivity,
                                    clearTask = true
                            )
                            finish()
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

    private fun handleThreadAttachmentPickerResult(uris: List<Uri>, persistable: Boolean = false) {
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
            val result = readThreadPlatformPickedFiles(uris)
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

    private fun readThreadPlatformPickedFiles(uris: List<Uri>): Map<String, Any> {
        cleanupPickedThreadAttachmentCache()
        val files = mutableListOf<PickedThreadAttachment>()
        val errorsJson = JSONArray()
        uris.forEachIndexed { index, uri ->
            runCatching {
                        pickedThreadAttachmentRef(uri)
                    }
                    .onSuccess { file ->
                        files += file
                    }
                    .onFailure { throwable ->
                        errorsJson.put(
                                JSONObject()
                                        .put("index", index)
                                        .put("errorCode", "attachment_read_failed")
                                        .put("message", throwable.message ?: "Attachment read failed")
                        )
                        SlockAppLog.record(
                                "SlockKuiklyAttachment",
                                "picker_file_read_failed index=$index uriScheme=${uri.scheme.orEmpty()} error=${throwable.javaClass.simpleName}"
                        )
                    }
        }
        if (files.isEmpty()) {
            return mapOf(
                    "code" to 400,
                    "bodyText" to "",
                    "errorCode" to "attachment_read_failed",
                    "errorBodyText" to "No readable attachments"
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
                            .put("source", "platform_picker")
            )
        }
        SlockAppLog.record(
                "SlockKuiklyAttachment",
                "picker_files_ready fileCount=${files.size} bytes=${files.sumOf { it.sizeBytes ?: 0L }}"
        )
        val skippedCount = errorsJson.length()
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
                put("errorCode", "attachment_read_partial")
                put("errorBodyText", "$skippedCount attachment(s) could not be read")
            }
        }
    }

    private fun pickedThreadAttachmentRef(uri: Uri): PickedThreadAttachment {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val cachedFile = cachePickedThreadAttachment(uri, fileName, mimeType)
        val fileRef = Uri.fromFile(cachedFile).toString()
        val sizeBytes = cachedFile.length().takeIf { it > 0L } ?: queryPickedFileSize(uri)
        return PickedThreadAttachment(
                fileRef = fileRef,
                fileName = fileName,
                mimeType = mimeType,
                previewUri = fileRef,
                sizeBytes = sizeBytes
        )
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
        val bytesCopied =
                contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Unable to read attachment")
        if (bytesCopied <= 0L || !targetFile.isFile || targetFile.length() <= 0L) {
            runCatching { targetFile.delete() }
            throw IOException("Unable to read attachment")
        }
        SlockAppLog.record(
                "SlockKuiklyAttachment",
                "picker_cached_file uriScheme=${uri.scheme.orEmpty()} fileName=${fileName.take(80)} bytes=${targetFile.length()}"
        )
        return targetFile
    }

    private fun pickedThreadAttachmentCacheDir(): File =
            File(applicationContext.cacheDir, PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME)

    private fun cleanupPickedThreadAttachmentCache(cacheDir: File = pickedThreadAttachmentCacheDir()) {
        val now = System.currentTimeMillis()
        val files = cacheDir.listFiles()?.filter { it.isFile }.orEmpty()
        files
                .filter { now - it.lastModified() > PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS }
                .forEach { runCatching { it.delete() } }
        files
                .filter { it.exists() && now - it.lastModified() <= PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS }
                .sortedByDescending { it.lastModified() }
                .drop(PICKED_THREAD_ATTACHMENT_CACHE_MAX_FILES)
                .forEach { runCatching { it.delete() } }
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
            SlockNativeFloatingTabBarRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockNativeFloatingTabBarRenderView(context) }
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

    private fun SlockKuiklyPageData.withAndroidRuntimeWindowInsets(): SlockKuiklyPageData =
            SlockKuiklyPageData.fromJson(
                    JSONObject(text).apply {
                        put(KEY_HOST_EXCLUDES_NAVIGATION_BAR, androidHostExcludesNavigationBar())
                        put(KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA, true)
                    }
            )

    private fun androidHostExcludesNavigationBar(): Boolean =
            androidHostExcludesNavigationBarForMode(androidNavigationMode())

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
            insets
        }
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
        private const val KEY_DIAGNOSTICS_DEEP_LINK_ENABLE_PROFILER = "slockDiagnosticsDeepLinkEnableProfiler"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_RESET_SCENE = "slockDiagnosticsDeepLinkResetScene"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_EXPORT_REPORT = "slockDiagnosticsDeepLinkExportReport"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS = "slockDiagnosticsDeepLinkAutoCloseMs"
        private const val PICKED_THREAD_ATTACHMENT_CACHE_MAX_FILES = 40
        private const val PICKED_THREAD_ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME = "slock-picked-thread-attachments"

        fun start(
            context: Context,
            pageName: String,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false,
            documentShellId: String? = null,
            hostActivityClass: Class<out Activity>? = null,
        ) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val hostPageData = pageData.withAndroidHostPageData(pageName)
            val starter =
                    Intent(context, hostActivityClass ?: slockKuiklyHostActivityClass(pageName))
                            .putExtra(KEY_PAGE_NAME, pageName)
                            .putExtra(KEY_PAGE_DATA, SlockKuiklyPageData.fromJson(hostPageData).text)
            if (clearTask) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else if (!documentShellId.isNullOrBlank()) {
                starter.data = Uri.parse("raft-shell://window/${Uri.encode(documentShellId)}")
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
                    PAGE_APP_SHELL
                } else {
                    intent?.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_ROUTER
                }

        private fun resolvedPageData(intent: Intent?): SlockKuiklyPageData {
            val rawPageData =
                    if (intent.isDiagnosticsDeepLinkIntent()) {
                        diagnosticsDeepLinkPageData(intent)
                    } else {
                        JSONObject(SlockKuiklyPageData.fromText(intent?.getStringExtra(KEY_PAGE_DATA)).text)
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
                    "md_node_sink_receipt mdNodeSink=${if (suppressed) "suppressed" else "enabled"} " +
                        "profilerExtra=${intent.getBooleanExtra("enable_profiler", false)}"
                )
            }
            val pageData =
                    JSONObject()
                            .put(KEY_PAGE_NAME, PAGE_APP_SHELL)
                            .put(KEY_APP_SHELL_INITIAL_TAB, "settings")
                            .put(KEY_APP_SHELL_INITIAL_SETTINGS_DESTINATION, "diagnostics")
                            .put(KEY_DIAGNOSTICS_DEEP_LINK_ID, "android-diagnostics-${System.currentTimeMillis()}")
                            .put(
                                    KEY_DIAGNOSTICS_DEEP_LINK_ENABLE_PROFILER,
                                    intent?.getBooleanExtra("enable_profiler", false) == true
                            )
                            .put(
                                    KEY_DIAGNOSTICS_DEEP_LINK_RESET_SCENE,
                                    intent?.getBooleanExtra("reset_scene", false) == true
                            )
                            .put(
                                    KEY_DIAGNOSTICS_DEEP_LINK_EXPORT_REPORT,
                                    intent?.getBooleanExtra("export_report", false) == true
                            )
                            .put(
                                    KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS,
                                    intent?.getLongExtra("auto_close_ms", 0L)?.coerceAtLeast(0L) ?: 0L
                            )
            SlockAppLog.record(
                    "SlockDiagnostics",
                    "diagnostics_deeplink_received " +
                            "enableProfiler=${pageData.optBoolean(KEY_DIAGNOSTICS_DEEP_LINK_ENABLE_PROFILER, false)} " +
                            "resetScene=${pageData.optBoolean(KEY_DIAGNOSTICS_DEEP_LINK_RESET_SCENE, false)} " +
                            "exportReport=${pageData.optBoolean(KEY_DIAGNOSTICS_DEEP_LINK_EXPORT_REPORT, false)} " +
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
        SlockAndroidSqlDatabaseContext.init(context)
        SharedBinaryDownloader.setDiagnosticsLogger { tag, message ->
            SlockAppLog.record(tag, message)
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
        val callback: KuiklyRenderCallback?
)

private data class PickedThreadAttachment(
        val fileRef: String,
        val fileName: String,
        val mimeType: String,
        val previewUri: String,
        val sizeBytes: Long?
)

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
        if (pageName == SlockKuiklyPages.APP_SHELL) {
            SlockKuiklyActivity.startAppShell(context, pageData)
        } else {
            SlockKuiklyActivity.start(context, pageName, pageData)
        }
        if (SlockKuiklyActivity.shouldReplaceCurrentPage(pageData)) {
            (context as? Activity)?.finish()
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
            val weight: Int
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
        val fontSpec = when (fontName) {
            "SpaceGrotesk-Regular", "space_grotesk_regular" -> SlockFontSpec(R.font.space_grotesk_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "SpaceGrotesk-Medium", "space_grotesk_medium" -> SlockFontSpec(R.font.space_grotesk_medium, FontStyle.FONT_WEIGHT_MEDIUM)
            "SpaceGrotesk-SemiBold", "space_grotesk_semibold" -> SlockFontSpec(R.font.space_grotesk_semibold, FontStyle.FONT_WEIGHT_SEMI_BOLD)
            "SpaceGrotesk-Bold", "space_grotesk_bold" -> SlockFontSpec(R.font.space_grotesk_bold, FontStyle.FONT_WEIGHT_BOLD)
            "SpaceGrotesk-Light", "space_grotesk_light" -> SlockFontSpec(R.font.space_grotesk_light, FontStyle.FONT_WEIGHT_LIGHT)
            "SpaceGrotesk-Variable", "space_grotesk_variable" -> SlockFontSpec(R.font.space_grotesk_variable, FontStyle.FONT_WEIGHT_NORMAL)
            "Space Grotesk", "space_grotesk" -> SlockFontSpec(R.font.space_grotesk, FontStyle.FONT_WEIGHT_NORMAL)
            "SpaceMono-Regular", "space_mono_regular" -> SlockFontSpec(R.font.space_mono_regular, FontStyle.FONT_WEIGHT_NORMAL)
            "SpaceMono-Bold", "space_mono_bold" -> SlockFontSpec(R.font.space_mono_bold, FontStyle.FONT_WEIGHT_BOLD)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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

private class SlockKuiklyImageAdapter(
        private val context: Context
) : IKRImageAdapter {
    private companion object {
        private const val NETWORK_BITMAP_CACHE_BYTES = 12 * 1024 * 1024
        private const val MAX_SINGLE_NETWORK_BITMAP_BYTES = 2 * 1024 * 1024
        private const val MAX_BITMAP_DECODE_EDGE_PX = 4096

        private val networkBitmapCache =
                object : LruCache<SlockNetworkImageRequestKey, Bitmap>(NETWORK_BITMAP_CACHE_BYTES) {
                    override fun sizeOf(key: SlockNetworkImageRequestKey, value: Bitmap): Int {
                        return value.allocationByteCount.coerceAtLeast(value.byteCount)
                    }
                }
        private val networkImageFlights =
                SlockImageRequestSingleFlight<SlockNetworkImageRequestKey, Bitmap>()
    }

    override fun fetchDrawable(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        val sourceKind = imageLoadOption.slockSourceKind()
        SlockAppLog.record(
                "SlockImageLoad",
                "source=$sourceKind status=request requestWidth=${imageLoadOption.requestWidth} " +
                        "requestHeight=${imageLoadOption.requestHeight} ${binaryDownloadUrlDiagnostics(imageLoadOption.src)}"
        )
        when {
            imageLoadOption.isSvgSource() -> loadSvg(imageLoadOption, callback)
            imageLoadOption.isBase64() -> loadFromBase64(imageLoadOption, callback)
            imageLoadOption.isAssets() -> loadFromAssets(imageLoadOption, callback)
            imageLoadOption.isLocalUri() -> loadFromLocalUri(imageLoadOption, callback)
            imageLoadOption.isWebUrl() -> loadFromNetwork(imageLoadOption, callback)
            else -> {
                SlockAppLog.record(
                        "SlockImageLoad",
                        "source=unsupported status=failed requestWidth=${imageLoadOption.requestWidth} " +
                                "requestHeight=${imageLoadOption.requestHeight} ${binaryDownloadUrlDiagnostics(imageLoadOption.src)}"
                )
                callback(null)
            }
        }
    }

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
                        SlockAppLog.record(
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
                        val bytes =
                                when {
                                    imageLoadOption.isFile() -> {
                                        val path = uri.path
                                        if (!path.isNullOrBlank()) {
                                            File(path).readBytes()
                                        } else {
                                            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                                        }
                                    }
                                    else ->
                                            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                                } ?: return@runCatching null
                        val contentType =
                                if (imageLoadOption.isContentUri()) {
                                    context.contentResolver.getType(uri)
                                } else {
                                    null
                                }
                        drawableFromBytes(bytes, imageLoadOption, contentType)
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
        val subscription =
                networkImageFlights.load(
                        key = requestKey,
                        start = { complete ->
                            SlockKuiklyThreadAdapter.executeOnSubThread {
                                val result =
                                        runCatching {
                                            cachedNetworkBitmap(requestKey)?.let { bitmap ->
                                                SlockAppLog.record(
                                                        "SlockImageLoad",
                                                        "source=network status=cache_hit bitmapWidth=${bitmap.width} " +
                                                                "bitmapHeight=${bitmap.height} " +
                                                                "requestWidth=${imageLoadOption.requestWidth} " +
                                                                "requestHeight=${imageLoadOption.requestHeight} " +
                                                                binaryDownloadUrlDiagnostics(imageLoadOption.src)
                                                )
                                                return@runCatching bitmap
                                            }
                                            val response =
                                                    runBlocking {
                                                        SharedBinaryDownloader.fetchBytes(imageLoadOption.src)
                                                    }
                                            recordNetworkImageBytes(imageLoadOption, response.bytes.size, response.contentType)
                                            bitmapFromNetworkBytes(response.bytes, imageLoadOption, response.contentType)
                                                    ?: throw IllegalStateException("Image decode failed")
                                        }
                                result.exceptionOrNull()?.let { throwable ->
                                    recordImageLoadFailure("network", imageLoadOption, throwable)
                                }
                                complete(result)
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

    private fun HRImageLoadOption.bitmapDecodeSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        val target = targetDecodeEdgePx()
        var sampleSize = 1
        var sampledWidth = sourceWidth
        var sampledHeight = sourceHeight
        while (max(sampledWidth, sampledHeight) / 2 >= target) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

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
                .coerceAtMost(MAX_BITMAP_DECODE_EDGE_PX)
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
        SlockAppLog.record(
                "SlockImageLoad",
                "source=network status=decode_failed bytes=$byteCount contentType=${contentType ?: "unknown"} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight} " +
                        binaryDownloadUrlDiagnostics(imageLoadOption.src)
        )
    }

    private fun recordImageLoadFailure(sourceKind: String, imageLoadOption: HRImageLoadOption, throwable: Throwable) {
        SlockAppLog.record(
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
        val raw = src.trimStart()
        val normalized = raw.lowercase(Locale.US)
        val path = normalized.substringBefore("?").substringBefore("#")
        return raw.startsWith("<svg", ignoreCase = true) ||
                normalized.startsWith("data:image/svg+xml") ||
                path.endsWith(".svg")
    }

    private fun HRImageLoadOption.isContentUri(): Boolean {
        return src.startsWith("content://", ignoreCase = true)
    }

    private fun HRImageLoadOption.isLocalUri(): Boolean {
        return isFile() || isContentUri()
    }

    private fun HRImageLoadOption.slockSourceKind(): String =
            when {
                isSvgSource() -> "svg"
                isBase64() -> "base64"
                isAssets() -> "assets"
                isLocalUri() -> "local"
                isWebUrl() -> "network"
                else -> "unsupported"
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

internal class SlockKuiklyBridgeModule(
        private val appContext: Context,
) : KuiklyRenderBaseModule() {
    internal suspend fun submitNativeFeedback(request: JSONObject): Map<String, Any> =
            submitFeedbackToQuiver(request)

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
            "attachmentDownloadControl" -> {
                callback?.invoke(attachmentDownloadControl(params))
                null
            }
            "attachmentDownloadOwnerChanged" -> {
                attachmentDownloadOwnerChanged(params)
                null
            }
            "closePage" -> {
                val hostActivity = activity
                if (hostActivity is SlockKuiklyActivity) {
                    hostActivity.closeKuiklyHostPage()
                } else {
                    hostActivity?.finish()
                }
                null
            }
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
            "threadAttachmentUpload" -> {
                val request = JSONObject(params ?: "{}")
                (activity as? SlockKuiklyActivity)?.requestThreadAttachmentUpload(
                        channelId = request.optString("channelId"),
                        imageOnly = request.optBoolean("imageOnly", false),
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
            "socialAuth" -> {
                socialAuth(params, callback)
                null
            }
            "currentTimestamp" -> System.currentTimeMillis().toString()
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
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

    private fun settingsCapability(params: String?, callback: KuiklyRenderCallback?) {
        val request = JSONObject(params ?: "{}")
        val action = request.optString("action")
        if (action == "quiverUpdateCheck" || action == "quiverUpdateStatus" || action == "quiverUpdateInstall") {
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result =
                        runCatching {
                                    val install = action != "quiverUpdateStatus"
                                    checkQuiverUpdate(install = install)
                                }
                                .getOrElse { throwable ->
                                    SlockAppLog.record(
                                            "SlockQuiverUpdate",
                                            "check_failed action=$action base=${BuildConfig.QUIVER_BASE_URL} " +
                                                    "error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                                    )
                                    mapOf(
                                            "status" to "error",
                                            "code" to "settings_quiver_update_check_failed",
                                            "message" to (throwable.message ?: "Quiver update check failed")
                                    )
                                }
                callback?.invoke(result)
            }
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
                val result = submitFeedbackToQuiver(request.takeIf { action == "submitFeedback" })
                SlockAppLog.record(
                        "SlockDiagnostics",
                        "settings_capability_feedback_result action=$action status=${result["status"]} code=${result["code"] ?: "-"}"
                )
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
                        "setRecompositionProfilerEnabled" -> {
                            val enabled = request.optBoolean("enabled", false)
                            SlockAppLog.record(
                                    "SlockDiagnostics",
                                    "shared_recomposition_profiler_enabled=$enabled"
                            )
                            mapOf("status" to "ok", "enabled" to enabled)
                        }
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
                            // Quiver via sendFeedback. A non-draft response skips
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
        if (clearTarget == StorageClearTarget.TemporaryAndLogs) {
            SlockAndroidLocalDiagnostics.clearLocalDiagnosticsFiles(appContext)
        }
        val feedback =
                when (clearTarget) {
                    StorageClearTarget.ImageCache -> "Image cache cleared."
                    StorageClearTarget.AttachmentDownloads -> "Attachment staging cleared. Completed Downloads were not removed."
                    StorageClearTarget.TemporaryAndLogs -> "Temporary files and diagnostics logs cleared."
                }
        SlockAppLog.record("SlockStorage", "shared_clear target=$target")
        return loadSettingsStorage(snapshot = snapshot, feedback = feedback)
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

    private fun Long.formatStorageSizeLabel(): String {
        if (this <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var value = toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

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

    private fun loadSettingsDeveloper(feedback: String? = null): Map<String, Any> {
        val current = AndroidApiServerEnvironmentStore(appContext).current()
        val alphaDeltaEnabled = handsDeltaUpdateAllowedForRuntime()
        val updateChannel =
                if (alphaDeltaEnabled) BuildConfig.HANDS_DELTA_CHANNEL else BuildConfig.QUIVER_CHANNEL
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
                "updateCheckSupported" to true,
                "updateCheckStatusLabel" to "Ready",
                "updateCheckDetail" to "Check Hands channel $updateChannel for a newer Android APK.",
                // Serialized like entriesJson (#484): options cross the render
                // bridge as a primitive string, independent of container
                // marshal support. OHOS/iOS hosts still send structured
                // "options"; the shared parser accepts both.
                "optionsJson" to options.toString(),
                "feedback" to feedback.orEmpty()
        )
    }

    private suspend fun checkQuiverUpdate(install: Boolean = true): Map<String, Any> {
        val arch = Build.SUPPORTED_ABIS.firstOrNull { it.isNotBlank() }
        val alphaDeltaEnabled = handsDeltaUpdateAllowedForRuntime()
        val appSlug =
                if (alphaDeltaEnabled) BuildConfig.HANDS_DELTA_APP_SLUG else BuildConfig.QUIVER_APP_SLUG
        val channel =
                if (alphaDeltaEnabled) BuildConfig.HANDS_DELTA_CHANNEL else BuildConfig.QUIVER_CHANNEL
        var alphaInstallStartedThisCall = false
        val ownedResponse =
                alphaHandsUpdateCallOwner.runIfEligible(
                        eligible = alphaDeltaEnabled && install
                ) {
                    UpdateChecker(
                            context = appContext,
                            baseUrl = BuildConfig.QUIVER_BASE_URL,
                            appSlug = appSlug,
                            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            channel = channel,
                            productType = "android-apk",
                            platform = "android",
                            arch = arch,
                            deviceId = HandsDeviceId.get(appContext),
                            deltaApplyEnabled = true
                    ).checkAndInstall()
                }
        val response =
                if (ownedResponse != null) {
                    alphaInstallStartedThisCall = true
                    ownedResponse
                } else {
                    HandsClient(BuildConfig.QUIVER_BASE_URL)
                            .checkForUpdate(
                                    slug = appSlug,
                                    channel = channel,
                                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                                    productType = "android-apk",
                                    platform = "android",
                                    arch = arch,
                                    filetype = "apk",
                                    deviceId = HandsDeviceId.get(appContext)
                            )
                }
        val updateAvailable = response.requireUpdate() != null
        SlockAppLog.record(
                "SlockQuiverUpdate",
                "check status=ok mode=${if (alphaDeltaEnabled) "alpha_delta" else "full_apk"} " +
                        "action=${if (install) "install" else "status"} started=$alphaInstallStartedThisCall " +
                        "slug=$appSlug channel=$channel current=${BuildConfig.VERSION_CODE} " +
                        "latest=${response.latest?.version_code ?: response.latest_version_code ?: 0} available=$updateAvailable"
        )
        val installResult =
                when {
                    alphaDeltaEnabled &&
                        updateAvailable &&
                        (alphaInstallStartedThisCall || alphaHandsUpdateCallOwner.isRunning()) ->
                            QuiverInstallResult(
                                    statusLabel = "Installing",
                                    detail = "Android is preparing the update. Follow the installer or download notification.",
                                    actionLabel = "Installing"
                            )
                    alphaDeltaEnabled -> QuiverInstallResult()
                    install && updateAvailable -> installQuiverUpdate(response, arch)
                    updateAvailable -> currentQuiverDownloadResult(response, arch)
                    else -> QuiverInstallResult()
                }
        val updateStatusLabel = installResult.statusLabel.ifBlank { response.quiverStatusLabel() }
        val updateDetail = installResult.detail.ifBlank { response.quiverDetail() }
        val feedback =
                when {
                    updateAvailable -> "Update available: ${response.latest?.version?.ifBlank { null } ?: "version code ${response.latest?.version_code ?: 0L}"}."
                    else -> "No Hands update available on $channel."
                }
        return loadSettingsDeveloper(feedback = feedback) +
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
                        "updateDownloadUrl" to response.asset?.download_url.orEmpty(),
                        "updateCheckedAtLabel" to System.currentTimeMillis().toString(),
                        "updateCheckErrorCode" to installResult.errorCode.orEmpty()
                )
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
                    "SlockQuiverUpdate",
                    "alpha_delta_gate_denied buildApplicationId=${BuildConfig.APPLICATION_ID} " +
                            "runtimePackageName=${appContext.packageName}"
            )
        }
        return allowed
    }

    private suspend fun installQuiverUpdate(
            response: UpdateCheckResponse,
            arch: String?
    ): QuiverInstallResult {
        return runCatching {
                    synchronized(quiverUpdateDownloadLock) {
                        val tracked = currentQuiverDownload(response, arch)
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
                                    QuiverInstallResult(
                                            statusLabel = "Downloading",
                                            detail = "Raft is downloading the update APK. The button will change to Install when the download is ready.",
                                            actionLabel = "Downloading"
                                    )
                                }
                                else -> {
                                    quiverUpdateDownloads.remove(tracked.key)
                                    enqueueQuiverApkInstall(response, arch).toDownloadingInstallResult(response)
                                }
                            }
                        } else {
                            enqueueQuiverApkInstall(response, arch).toDownloadingInstallResult(response)
                        }
                    }
                }
                .getOrElse { error ->
                    SlockAppLog.record(
                            "SlockQuiverUpdate",
                            "install_failed slug=${BuildConfig.QUIVER_APP_SLUG} latest=${response.latest?.version_code ?: 0} error=${error.javaClass.simpleName}"
                    )
                    QuiverInstallResult(
                            statusLabel = "Install failed",
                            detail = error.message ?: "Unable to download or install the Quiver APK.",
                            actionLabel = "Download",
                            errorCode = error.javaClass.simpleName
                    )
                }
    }

    private fun enqueueQuiverApkInstall(response: UpdateCheckResponse, arch: String?): QuiverUpdateDownload {
        val latest = response.latest ?: error("Quiver response did not include a latest release.")
        val asset = response.asset ?: error("Quiver response did not include an APK asset.")
        val downloadUrl = asset.download_url
        require(downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
            "Can only download HTTP/HTTPS URIs: $downloadUrl"
        }
        val downloadManager =
                ContextCompat.getSystemService(appContext, DownloadManager::class.java)
                        ?: error("DownloadManager not available")
        val fileName = "raft-quiver-${latest.version_code}.apk"
        val key = response.quiverDownloadKey(arch)
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
                        val status = quiverDownloadStatus(downloadManager, completedId)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            quiverUpdateDownloads[key]?.completed = true
                            SlockAppLog.record(
                                    "SlockQuiverUpdate",
                                    "download_ready downloadId=$completedId latest=${latest.version_code}"
                            )
                            quiverUpdateDownloads[key]?.let { download ->
                                openQuiverDownloadedApkOrRequestPermission(
                                        context = appContext,
                                        download = download,
                                        source = "download_complete"
                                )
                            }
                        } else {
                            quiverUpdateDownloads[key]?.failed = true
                            SlockAppLog.record(
                                    "SlockQuiverUpdate",
                                    "download_failed downloadId=$completedId status=$status"
                            )
                        }
                        emitQuiverUpdateStatusEvent()
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
            return QuiverUpdateDownload(
                    key = key,
                    versionCode = latest.version_code,
                    downloadId = downloadId,
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    arch = arch.orEmpty(),
                    downloadManager = downloadManager
            ).also { download ->
                quiverUpdateDownloads[key] = download
            }
        } catch (error: Throwable) {
            runCatching { appContext.unregisterReceiver(receiver) }
            throw error
        }
    }

    private fun currentQuiverDownloadResult(response: UpdateCheckResponse, arch: String?): QuiverInstallResult {
        val tracked = currentQuiverDownload(response, arch) ?: return QuiverInstallResult()
        return when (tracked.status()) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                tracked.completed = true
                QuiverInstallResult(
                        statusLabel = "Install",
                        detail = "Raft update downloaded. Tap Install to open Android installer.",
                        actionLabel = "Install"
                )
            }
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED ->
                QuiverInstallResult(
                        statusLabel = "Downloading",
                        detail = "Raft is downloading the update APK. The button will change to Install when the download is ready.",
                        actionLabel = "Downloading"
                )
            else -> {
                quiverUpdateDownloads.remove(tracked.key)
                QuiverInstallResult()
            }
        }
    }

    private fun currentQuiverDownload(response: UpdateCheckResponse, arch: String?): QuiverUpdateDownload? {
        val key = response.quiverDownloadKey(arch)
        val tracked =
                quiverUpdateDownloads[key]
                        ?: run {
                            if (quiverUpdateDownloads.isNotEmpty()) {
                                SlockAppLog.record(
                                        "SlockQuiverUpdate",
                                        "download_state_miss key=$key tracked=${quiverUpdateDownloads.size}"
                                )
                            }
                            return null
                        }
        if (tracked.failed) {
            quiverUpdateDownloads.remove(key)
            return null
        }
        return tracked
    }

    private fun QuiverUpdateDownload.toDownloadingInstallResult(response: UpdateCheckResponse): QuiverInstallResult {
        SlockAppLog.record(
                "SlockQuiverUpdate",
                "download_queued slug=${BuildConfig.QUIVER_APP_SLUG} latest=${response.latest?.version_code ?: 0} assetSize=${response.asset?.size_bytes ?: 0} downloadId=$downloadId"
        )
        emitQuiverUpdateStatusEvent()
        return QuiverInstallResult(
                statusLabel = "Downloading",
                detail = "Raft update download started. The button will change to Install when the APK is ready.",
                actionLabel = "Downloading"
        )
    }

    private fun UpdateCheckResponse.quiverDownloadKey(arch: String?): String =
            quiverUpdateDownloadKey(
                    slug = BuildConfig.QUIVER_APP_SLUG,
                    channel = channel,
                    versionCode = latest?.version_code ?: latest_version_code ?: 0L,
                    arch = arch
            )

    private fun emitQuiverUpdateStatusEvent() {
        postAndroidAppScopeEvent(
                eventName = SLOCK_APP_UPDATE_EVENT,
                eventData = JSONObject().put("source", "android")
        )
    }

    private fun QuiverUpdateDownload.toInstallResult(context: Context, source: String): QuiverInstallResult =
            when (openQuiverDownloadedApkOrRequestPermission(context, this, source)) {
                QUIVER_INSTALL_OPENED ->
                    QuiverInstallResult(
                            statusLabel = "Install",
                            detail = "Android installer opened for the downloaded Raft update.",
                            actionLabel = "Install"
                    )
                QUIVER_INSTALL_PERMISSION_REQUIRED ->
                    QuiverInstallResult(
                            statusLabel = "Permission required",
                            detail = "Raft update is downloaded. Allow Raft to install unknown apps, then return to continue installation.",
                            actionLabel = "Install",
                            errorCode = "install_permission_required"
                    )
                else ->
                    QuiverInstallResult(
                            statusLabel = "Install failed",
                            detail = "Raft update is downloaded, but Android installer could not be opened.",
                            actionLabel = "Install",
                            errorCode = "install_intent_failed"
                    )
            }

    private fun quiverDownloadStatus(downloadManager: DownloadManager, downloadId: Long): Int {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return DownloadManager.STATUS_FAILED
        cursor.use {
            if (!it.moveToFirst()) return DownloadManager.STATUS_FAILED
            val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (index >= 0) it.getInt(index) else DownloadManager.STATUS_FAILED
        }
    }

    private data class QuiverInstallResult(
            val statusLabel: String = "",
            val detail: String = "",
            val actionLabel: String = "",
            val errorCode: String? = null
    )

    private fun UpdateCheckResponse.quiverStatusLabel(): String =
            if (requireUpdate() != null) "Update available" else "Current"

    private fun UpdateCheckResponse.quiverDetail(): String =
            if (requireUpdate() != null) {
                val version = latest?.version?.takeIf { it.isNotBlank() } ?: "version code ${latest?.version_code ?: 0L}"
                val size = asset?.size_bytes?.takeIf { it > 0L }?.formatStorageSizeLabel()
                buildString {
                    append("Quiver has ")
                    append(version)
                    append(" on ")
                    append(channel)
                    size?.let { append(" (").append(it).append(" APK).") } ?: append(".")
                }
            } else {
                "No Quiver update is available on $channel for version code $current_version_code."
            }

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

    /** Legacy host entry point retained fail-closed for old callers. */
    private suspend fun submitFeedbackToQuiver(request: JSONObject? = null): Map<String, Any> {
        SlockAppLog.record("SlockDiagnostics", "native_feedback_submit_rejected authority=raft_server")
        return mapOf(
                "status" to "unsupported",
                "code" to "settings_feedback_shared_transport_required"
        )
    }

    private fun buildQuiverFeedbackShareText(
            ticketId: String,
            description: String,
            attachmentNames: List<String>
    ): String {
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
                appSlug = BuildConfig.QUIVER_APP_SLUG,
                channel = BuildConfig.QUIVER_CHANNEL,
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
                            "quiver_feedback_diagnostics_prepare_skipped error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                    )
                    null
                }
                ?.takeIf { file ->
                    val fits = file.length() in 1..HandsFeedbackAttachmentPolicy.maxAttachmentBytes
                    if (!fits) {
                        SlockAppLog.record(
                                "SlockDiagnostics",
                                "quiver_feedback_diagnostics_attachment_skipped bytes=${file.length()} " +
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
                            writeQuiverFeedbackBase64TempFile(
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    bodyBase64 = bodyBase64,
                                    index = index
                            )
                        fileRef != null ->
                            copyQuiverFeedbackFileRef(
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

    private fun copyQuiverFeedbackFileRef(
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
        val target = createQuiverFeedbackTempFile(fileName, mimeType, index)
        return try {
            openQuiverFeedbackFileRefStream(uri).use { input ->
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

    private fun openQuiverFeedbackFileRefStream(uri: Uri): java.io.InputStream {
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

    private fun writeQuiverFeedbackBase64TempFile(
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
        val target = createQuiverFeedbackTempFile(fileName, mimeType, index)
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

    private fun createQuiverFeedbackTempFile(
            fileName: String,
            mimeType: String,
            index: Int
    ): File {
        val cacheDir = File(appContext.cacheDir, QUIVER_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME).apply { mkdirs() }
        cleanupQuiverFeedbackAttachmentCache(cacheDir)
        val safeName = sanitizeQuiverFeedbackFileName(fileName, mimeType, index)
        return File(cacheDir, "${System.currentTimeMillis()}-${System.nanoTime()}-$safeName")
    }

    private fun sanitizeQuiverFeedbackFileName(fileName: String, mimeType: String, index: Int): String {
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

    private fun cleanupQuiverFeedbackAttachmentCache(cacheDir: File) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()
                ?.filter { file -> now - file.lastModified() > QUIVER_FEEDBACK_ATTACHMENT_CACHE_MAX_AGE_MS }
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
        if (viewIntent == null) {
            Toast.makeText(appContext, appContext.getString(R.string.open_link_no_app_toast), Toast.LENGTH_SHORT)
                    .show()
            return null
        }
        runCatching {
            appContext.startActivity(viewIntent)
        }.onFailure { throwable ->
            if (throwable is ActivityNotFoundException) {
                Toast.makeText(appContext, appContext.getString(R.string.open_link_no_app_toast), Toast.LENGTH_SHORT)
                        .show()
            } else {
                Toast.makeText(appContext, appContext.getString(R.string.open_link_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }
        return null
    }

    /**
     * HTTP(S) links must leave Raft even when Android App Links resolve the same URL back to this
     * application. Other schemes retain the platform's ordinary implicit-handler behavior.
     */
    private fun externalUrlIntent(uri: Uri, mimeType: String?): Intent? {
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
        val candidates =
                appContext.packageManager.queryIntentActivities(
                        implicit,
                        PackageManager.MATCH_DEFAULT_ONLY
                )
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
                ) ?: return null
        return implicit.setPackage(selectedPackage)
    }

    private fun openAttachment(params: String?, callback: KuiklyRenderCallback?) {
        SlockKuiklyThreadAdapter.execute("attachment-open") {
            val request = JSONObject(params ?: "{}")
            val startedAt = System.currentTimeMillis()
            val trackProgress = request.optBoolean("trackProgress", false)
            val result =
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
                        mapOf(
                                "status" to "failed",
                                "message" to "Unable to download attachment"
                        )
                    }
            callback?.invoke(result)
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
                                    val preparation =
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
        return runCatching {
                    authority.withIdentityLock(identity) {
                        val committed = authority.resolve(identity)
                        mapOf(
                                "status" to "ok",
                                "cached" to (committed != null),
                                "active" to false,
                                "attemptToken" to "",
                                "recordVersion" to (committed?.generation ?: -1L),
                                "sizeBytes" to (committed?.sizeBytes ?: 0L),
                                "uri" to committed?.uri.orEmpty()
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
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        return runCatching {
                    authority.withIdentityLock(identity) {
                        val privateStatus =
                                deleteDirectAttachmentCache(
                                        targetFile = targetFile,
                                        expectedAttemptToken = request.optString("attemptToken").takeIf(String::isNotBlank)
                                )
                        if (privateStatus == "failed" || !authority.delete(identity)) {
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
        val cacheDir = attachmentCacheDir()
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return AttachmentPreparationResult.Terminal(
                    mapOf("status" to "storage_failed", "message" to "Storage unavailable")
            )
        }
        cleanupAttachmentCache(cacheDir, owner.principalKey)
        val targetFile = attachmentCacheFile(attachmentId, requestedFileName, requestedMimeType, owner.principalKey)
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
            @Suppress("UNUSED_PARAMETER") progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): Map<String, Any> {
        val owner = request.attachmentOwnerLease()
                ?: return mapOf("status" to "failed", "cached" to false, "message" to "Authentication required")
        if (!ProcessAndroidAttachmentOwners.admit(owner)) {
            return mapOf("status" to "cancelled", "cached" to false, "message" to "Attachment scope retired")
        }
        val identity = request.publicAttachmentIdentity(owner)
        val authority = ProcessAndroidAttachmentPublicDownloads.get(appContext)
        return runCatching {
                    authority.withIdentityLock(identity) {
                        val committed =
                                authority.resolve(identity)
                                        ?: return@withIdentityLock mapOf(
                                                "status" to "missing",
                                                "cached" to false,
                                                "message" to "Downloaded file is no longer available"
                                        )
                        openCommittedAttachment(request, owner, committed)
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

    private fun openCommittedAttachment(
            request: JSONObject,
            owner: AndroidAttachmentOwnerLease,
            committed: AndroidAttachmentPublicRecord
    ): Map<String, Any> {
        val attachmentId = request.optString("attachmentId").ifBlank { "attachment" }
        val requestedFileName = committed.displayName
        val startedAt = System.currentTimeMillis()
        val openMimeType = committed.mimeType
        val isPackageArchive = isAndroidPackageArchive(requestedFileName, openMimeType)
        val openSpec = androidAttachmentPublicOpenSpec(committed, isPackageArchive)
        val attachmentUri = Uri.parse(openSpec.uri)
        val intent =
                Intent(openSpec.action).apply {
                    setDataAndType(attachmentUri, openMimeType)
                    clipData = ClipData.newUri(appContext.contentResolver, openSpec.displayName, attachmentUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (isPackageArchive) {
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    }
                }
        val handlers = appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty() && !isPackageArchive) {
            showToast(appContext.getString(R.string.attachment_no_app_toast))
            SlockAppLog.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=no_app mimeType=$openMimeType durationMs=${System.currentTimeMillis() - startedAt}"
            )
            return mapOf("status" to "no_app", "message" to "No app can open this file")
        }
        return runCatching {
            val launched = ProcessAndroidAttachmentOwners.runIfCurrent(owner) {
                handlers.forEach { handler ->
                    appContext.grantUriPermission(
                            handler.activityInfo.packageName,
                            attachmentUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                if (isPackageArchive) {
                    runCatching {
                        appContext.startActivity(intent)
                    }.getOrElse { firstThrowable ->
                        if (firstThrowable !is ActivityNotFoundException) throw firstThrowable
                        val viewIntent =
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(attachmentUri, openMimeType)
                                    clipData = ClipData.newUri(appContext.contentResolver, committed.displayName, attachmentUri)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                        appContext.startActivity(viewIntent)
                    }
                } else {
                    val chooser =
                            Intent.createChooser(intent, appContext.getString(R.string.attachment_open_with_chooser_title)).apply {
                                clipData = intent.clipData
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                    appContext.startActivity(chooser)
                }
                true
            }
            if (launched == null) return mapOf("status" to "cancelled", "message" to "Attachment scope retired")
            SlockAppLog.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=opened mimeType=$openMimeType cached=true durationMs=${System.currentTimeMillis() - startedAt}"
            )
            mapOf(
                    "status" to "opened",
                    "cached" to true,
                    "uri" to attachmentUri.toString(),
                    "sizeBytes" to committed.sizeBytes,
                    "action" to if (isPackageArchive) "install" else "open"
            )
        }.getOrElse { throwable ->
            if (throwable is ActivityNotFoundException) {
                showToast(appContext.getString(R.string.attachment_no_app_toast))
                mapOf("status" to "no_app", "message" to "No app can open this file")
            } else {
                showToast(appContext.getString(R.string.attachment_open_failed_toast))
                mapOf("status" to "failed", "message" to "Unable to open attachment")
            }
        }
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
                        "failurePhase=${finalResult.failurePhase.orEmpty()} sizeBytes=${finalResult.sizeBytes}"
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

    private fun attachmentCacheFile(request: JSONObject, principalKey: String): File =
            attachmentCacheFile(
                    attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment",
                    requestedFileName = request.optString("fileName").takeIf { it.isNotBlank() } ?: "attachment",
                    requestedMimeType = request.optString("mimeType").takeIf { it.isNotBlank() },
                    principalKey = principalKey
            )

    private fun attachmentCacheFile(
            attachmentId: String,
            requestedFileName: String,
            requestedMimeType: String?,
            principalKey: String
    ): File =
            File(
                    attachmentCacheDir(),
                    safeAttachmentCacheFileName(attachmentId, requestedFileName, requestedMimeType, principalKey)
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
                throw IllegalStateException(result.message ?: result.errorKind ?: "Attachment download failed")
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

    private fun safeAttachmentCacheFileName(
            attachmentId: String,
            requestedFileName: String,
            mimeType: String?,
            principalKey: String
    ): String {
        val safeId =
                attachmentId
                        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                        .take(36)
                        .ifBlank { "attachment" }
        val baseName =
                requestedFileName
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
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty())
                    if (extension.isNullOrBlank()) baseName else "$baseName.$extension"
                }
        return "$principalKey-$safeId-$withExtension"
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
        private val quiverUpdateDownloads = ConcurrentHashMap<String, QuiverUpdateDownload>()
        private val quiverUpdateDownloadLock = Any()
        private val alphaHandsUpdateCallOwner = HandsDeltaUpdateCallOwner()
        @Volatile private var pendingQuiverAutoInstallKey: String? = null
        private const val REQUEST_NOTIFICATIONS_CODE = 4207
        private const val ATTACHMENT_CACHE_MAX_FILES = 80
        private const val ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val THREAD_SHARE_IMAGE_CACHE_DIR_NAME = "slock-share-images"
        private const val THREAD_SHARE_IMAGE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val QUIVER_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME = "quiver-feedback-attachments"
        private const val QUIVER_FEEDBACK_ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val ANDROID_PACKAGE_ARCHIVE_MIME_TYPE = "application/vnd.android.package-archive"
        private const val QUIVER_INSTALL_OPENED = "opened"
        private const val QUIVER_INSTALL_PERMISSION_REQUIRED = "permission_required"
        private const val QUIVER_INSTALL_FAILED = "failed"

        internal fun resumePendingQuiverAutoInstall(context: Context) {
            val key = pendingQuiverAutoInstallKey ?: return
            val download =
                    quiverUpdateDownloads[key]
                            ?: run {
                                pendingQuiverAutoInstallKey = null
                                return
                            }
            if (download.failed || download.status() != DownloadManager.STATUS_SUCCESSFUL) return
            if (!canRequestPackageInstalls(context)) return
            openQuiverDownloadedApkOrRequestPermission(
                    context = context,
                    download = download,
                    source = "permission_resume"
            )
        }

        private fun openQuiverDownloadedApkOrRequestPermission(
                context: Context,
                download: QuiverUpdateDownload,
                source: String
        ): String {
            if (!canRequestPackageInstalls(context)) {
                pendingQuiverAutoInstallKey = download.key
                openUnknownAppSourcesSettings(context)
                SlockAppLog.record(
                        "SlockQuiverUpdate",
                        "install_permission_required source=$source key=${download.key} downloadId=${download.downloadId}"
                )
                return QUIVER_INSTALL_PERMISSION_REQUIRED
            }
            pendingQuiverAutoInstallKey = download.key
            return runCatching {
                        openDownloadedQuiverApk(context, download.downloadManager, download.downloadId, download.fileName)
                        pendingQuiverAutoInstallKey = null
                        QUIVER_INSTALL_OPENED
                    }
                    .getOrElse { error ->
                        SlockAppLog.record(
                                "SlockQuiverUpdate",
                                "install_intent_failed source=$source key=${download.key} downloadId=${download.downloadId} error=${error.javaClass.simpleName}"
                        )
                        QUIVER_INSTALL_FAILED
                    }
        }

        private fun openDownloadedQuiverApk(
                context: Context,
                downloadManager: DownloadManager,
                downloadId: Long,
                fileName: String
        ) {
            val downloadedUri =
                    downloadManager.getUriForDownloadedFile(downloadId)
                            ?: queryDownloadedQuiverApkFile(downloadManager, downloadId)?.let { file ->
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
                    "SlockQuiverUpdate",
                    "install_intent_opened downloadId=$downloadId uriScheme=${downloadedUri.scheme.orEmpty()}"
            )
        }

        private fun queryDownloadedQuiverApkFile(downloadManager: DownloadManager, downloadId: Long): File? {
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

private class AttachmentDownloadStoppedException : RuntimeException()

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
    val principalKey = optString("ownerKey").takeIf { it.length == 64 } ?: return null
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

private data class QuiverUpdateDownload(
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

internal fun quiverUpdateDownloadKey(
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

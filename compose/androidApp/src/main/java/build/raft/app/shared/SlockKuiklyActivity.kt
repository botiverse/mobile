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
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.lifecycle.lifecycleScope
import build.raft.app.R
import build.raft.app.BuildConfig
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
import build.raft.app.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import build.raft.app.core.data.diagnostics.SlockDiagnosticsLogger
import build.raft.app.core.data.diagnostics.SlockPerformanceDiagnostics
import build.raft.app.core.data.storage.StorageClearTarget
import build.raft.app.core.data.storage.StorageManagementRepository
import build.raft.app.core.data.storage.StorageManagementSnapshot
import build.raft.app.widget.SlockActivityWidgetProvider
import build.raft.app.widget.SlockAgentStatusWidgetProvider
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SLOCK_APP_UPDATE_EVENT
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.model.MemberKind
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.data.SlockAndroidSqlDatabaseContext
import build.raft.app.network.SharedBinaryDownloader
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRFontAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRUncaughtExceptionHandlerAdapter
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
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
import io.quiver.update.QuiverDeviceId
import io.quiver.update.QuiverFeedback
import io.quiver.update.QuiverFeedbackException
import io.quiver.update.internal.QuiverClient
import io.quiver.update.models.UpdateCheckResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
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

class SlockKuiklyActivity : ComponentActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var pageSpec: SlockKuiklyPageSpec? = null
    private var inputFontLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingThreadAttachmentUpload: PendingThreadAttachmentUpload? = null
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

    private val pageName: String
        get() = resolvedPageName(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, kuiklySystemBackCallback)
        SlockKuiklyAdapters.init(applicationContext)
        setContentView(R.layout.activity_slock_kuikly)
        setupImmersiveMode()
        suppressVisualKeyboard()
        val container = findViewById<ViewGroup>(R.id.slock_kuikly_container)
        installSlockInputFontBridge(container)
        val spec = createPageSpec()
        pageSpec = spec
        SlockPerformanceDiagnostics.install(window)
        SlockPerformanceDiagnostics.updateRoute(container, spec.pageName)
        SlockPerformanceDiagnostics.updateOperation(container, "kuikly_attach")
        kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, spec.pageData.toKuiklyMap())
        SlockAndroidBuglyDiagnostics.reportAppFullLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SlockDiagnosticsLogger.record(
                "SlockKuiklyActivity",
                "host_new_intent page=${resolvedPageName(intent)} currentPage=${pageSpec?.pageName.orEmpty()}"
        )
        recreate()
    }

    override fun onDestroy() {
        removeSlockInputFontBridge()
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    override fun onPause() {
        AndroidAppScopeRealtimeOwner.onAppHidden(source = "activity_on_pause")
        super.onPause()
        kuiklyRenderViewDelegator.onPause()
    }

    override fun onResume() {
        super.onResume()
        suppressVisualKeyboard()
        applySlockInputTypeface()
        kuiklyRenderViewDelegator.onResume()
        sendAppShellLifecycleEvent("resume")
        AndroidAppScopeRealtimeOwner.onAppShown(
            context = applicationContext,
            source = "activity_on_resume"
        )
        AndroidRealtimeKeepAliveController.start(
            context = applicationContext,
            source = "activity_on_resume"
        )
        SlockActivityWidgetProvider.refreshAllWidgets(applicationContext)
        SlockAgentStatusWidgetProvider.refreshAllWidgets(applicationContext)
        SlockKuiklyBridgeModule.resumePendingQuiverAutoInstall(applicationContext)
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

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(applicationContext)
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

    override fun useHostDisplayMetrics(): Boolean = true

    private fun installSlockInputFontBridge(container: ViewGroup) {
        SlockKuiklyFontAdapter.updateHostDisplayMetrics(container)
        val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    container.applySlockInputTypeface()
                    SlockKuiklyFontAdapter.updateHostDisplayMetrics(container)
                }
        inputFontLayoutListener = listener
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        container.post {
            container.applySlockInputTypeface()
            SlockKuiklyFontAdapter.updateHostDisplayMetrics(container)
        }
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
                        threadImageAttachmentPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
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
        SlockDiagnosticsLogger.record(
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
        SlockKuiklyThreadAdapter.execute("thread-attachment-upload") {
                    val result =
                            runCatching {
                                        readThreadPlatformPickedFiles(uris)
                                    }
                                    .getOrElse { throwable ->
                                        mapOf(
                                                "code" to 0,
                                                "bodyText" to "",
                                                "errorCode" to "attachment_read_failed",
                                                "errorBodyText" to (throwable.message ?: "Attachment read failed")
                                        )
                                    }
                    runOnUiThread {
                        SlockDiagnosticsLogger.record(
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
                        SlockDiagnosticsLogger.record(
                                "SlockKuiklyAttachment",
                                "picker_persist_permission_failed uriScheme=${uri.scheme.orEmpty()} error=${throwable.javaClass.simpleName}"
                        )
                    }
        }
    }

    private fun readThreadPlatformPickedFiles(uris: List<Uri>): Map<String, Any> {
        val files = uris.map { uri -> pickedThreadAttachmentRef(uri) }
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
        SlockDiagnosticsLogger.record(
                "SlockKuiklyAttachment",
                "picker_files_ready fileCount=${files.size} bytes=${files.sumOf { it.sizeBytes ?: 0L }}"
        )
        return mapOf(
                "code" to 200,
                "bodyText" to JSONObject().put("files", filesJson).toString(),
                "files" to filesJson
        )
    }

    private fun pickedThreadAttachmentRef(uri: Uri): PickedThreadAttachment {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileRef = uri.toString()
        val sizeBytes = queryPickedFileSize(uri)
        return PickedThreadAttachment(
                fileRef = fileRef,
                fileName = fileName,
                mimeType = mimeType,
                previewUri = fileRef,
                sizeBytes = sizeBytes
        )
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

    private fun SlockKuiklyPageData.withAndroidRuntimeWindowInsets(): SlockKuiklyPageData =
            SlockKuiklyPageData.fromJson(
                    JSONObject(text).apply {
                        if (!has(KEY_HOST_EXCLUDES_NAVIGATION_BAR) && androidHostExcludesNavigationBar()) {
                            put(KEY_HOST_EXCLUDES_NAVIGATION_BAR, true)
                        }
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

    private fun setupImmersiveMode() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    internal fun updatePerformanceScene(action: String, sceneName: String) {
        val cleanScene = sceneName.takeIf { it.isNotBlank() } ?: return
        val container = findViewById<ViewGroup?>(R.id.slock_kuikly_container) ?: return
        when (action) {
            "enter" -> {
                SlockPerformanceDiagnostics.updateRoute(container, cleanScene)
                SlockPerformanceDiagnostics.updateOperation(container, "scene_enter")
                SlockAndroidBuglyDiagnostics.enterScene(cleanScene)
            }
            "exit" -> {
                SlockPerformanceDiagnostics.updateOperation(container, "scene_exit")
                SlockAndroidBuglyDiagnostics.exitScene(cleanScene)
            }
            else ->
                    SlockDiagnosticsLogger.w(
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
        lastSeq: Long,
        forceReconnect: Boolean = false
    ) {
        if (serverId.isBlank() || accessToken.isBlank()) return
        AndroidAppScopeRealtimeOwner.connectFromSharedRequest(
            context = applicationContext,
            serverId = ServerId(serverId),
            accessToken = accessToken,
            requestedLastSeq = lastSeq,
            forceReconnect = forceReconnect
        )
    }

    internal fun requestRealtimeDisconnect(reason: String) {
        AndroidRealtimeKeepAliveController.stop(applicationContext, reason)
        AndroidAppScopeRealtimeOwner.disconnectForSessionEnd(reason)
    }

    private fun sendAppShellLifecycleEvent(event: String) {
        val pageName = pageSpec?.pageName ?: return
        if (pageName != PAGE_APP_SHELL && pageName != PAGE_THREAD) return
        AndroidAppScopeRealtimeOwner.publishCurrentNetworkStatus(
                applicationContext,
                source = "app_shell_lifecycle_$event"
        )
        postAndroidAppScopeEvent(
                eventName = SlockRealtimeNotifyContract.EventName,
                eventData =
                        JSONObject()
                                .put("action", "appLifecycle")
                                .put("event", event)
                                .put("pageName", pageName)
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
        const val PAGE_SETTINGS_COMPUTERS = SlockKuiklyPages.SETTINGS_COMPUTERS
        const val PAGE_SETTINGS_COMPUTER_DETAIL = SlockKuiklyPages.SETTINGS_COMPUTER_DETAIL
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
        internal const val KEY_APP_SHELL_NOTIFICATION_CATEGORY = "slockNotificationCategory"
        private const val KEY_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
        private const val KEY_VERIFY_KUIKLY_THREAD = "slockKuiklyVerifyThread"
        private const val KEY_VERIFY_KUIKLY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val KEY_HOST_EXCLUDES_NAVIGATION_BAR = "slockHostExcludesNavigationBar"
        private const val NAVIGATION_MODE_SETTING = "navigation_mode"
        private const val NAVIGATION_MODE_RESOURCE = "config_navBarInteractionMode"
        private const val NAVIGATION_MODE_GESTURAL = 2
        private const val THREAD_SURFACE_CHANNEL = "channel"
        private const val THREAD_SURFACE_DIRECT_MESSAGE = "dm"
        private const val THREAD_SURFACE_REPLY_THREAD = "thread"
        private const val NOTIFICATION_CATEGORY_DIRECT_MESSAGE = "direct_message"
        private const val ACTION_OPEN_DIAGNOSTICS = "build.raft.app.diagnostics.OPEN"
        private const val KEY_APP_SHELL_INITIAL_TAB = "slockInitialTab"
        private const val KEY_APP_SHELL_INITIAL_SETTINGS_DESTINATION = "slockInitialSettingsDestination"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_ID = "slockDiagnosticsDeepLinkId"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_ENABLE_PROFILER = "slockDiagnosticsDeepLinkEnableProfiler"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_RESET_SCENE = "slockDiagnosticsDeepLinkResetScene"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_EXPORT_REPORT = "slockDiagnosticsDeepLinkExportReport"
        private const val KEY_DIAGNOSTICS_DEEP_LINK_AUTO_CLOSE_MS = "slockDiagnosticsDeepLinkAutoCloseMs"

        fun start(
            context: Context,
            pageName: String,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false
        ) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val hostPageData = pageData.withAndroidHostPageData(pageName)
            val starter =
                    Intent(context, SlockKuiklyActivity::class.java)
                            .putExtra(KEY_PAGE_NAME, pageName)
                            .putExtra(KEY_PAGE_DATA, SlockKuiklyPageData.fromJson(hostPageData).text)
            if (clearTask) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else if (context !is Activity) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(starter)
        }

        fun startAppShell(
            context: Context,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false
        ) {
            start(context, PAGE_APP_SHELL, pageData, clearTask)
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
                if (intent?.getStringExtra(KEY_PAGE_NAME) == PAGE_APP_SHELL) {
                    runCatching { JSONObject(intent.getStringExtra(KEY_PAGE_DATA).orEmpty()) }.getOrElse { JSONObject() }
                } else {
                    JSONObject()
                }

        private fun resolvedPageName(intent: Intent?): String =
                if (intent.isDiagnosticsDeepLinkIntent()) {
                    PAGE_APP_SHELL
                } else {
                    intent?.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_ROUTER
                }

        private fun resolvedPageData(intent: Intent?): SlockKuiklyPageData =
                if (intent.isDiagnosticsDeepLinkIntent()) {
                    SlockKuiklyPageData.fromJson(diagnosticsDeepLinkPageData(intent))
                } else {
                    SlockKuiklyPageData.fromText(intent?.getStringExtra(KEY_PAGE_DATA))
                }

        private fun Intent?.isDiagnosticsDeepLinkIntent(): Boolean =
                this?.action == ACTION_OPEN_DIAGNOSTICS

        private fun diagnosticsDeepLinkPageData(intent: Intent?): JSONObject {
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
            SlockDiagnosticsLogger.record(
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

        internal fun androidHostExcludesNavigationBarForMode(navigationMode: Int?): Boolean =
                navigationMode != null && navigationMode != NAVIGATION_MODE_GESTURAL
    }
}

internal object SlockKuiklyAdapters {
    private var initialized = false

    fun init(context: Context) {
        installAndroidAppScopeSharedKeyValueStores(context)
        installAndroidAppScopePlatformFileResolver(context)
        installAndroidAppScopeEventBus(context)
        SlockAndroidSqlDatabaseContext.init(context)
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

private object SlockKuiklyRouterAdapter : IKRRouterAdapter {
    override fun openPage(context: Context, pageName: String, pageData: JSONObject) {
        SlockKuiklyActivity.start(context, pageName, pageData)
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
        Log.e(tag, msg)
    }
}

private object SlockKuiklyExceptionAdapter : IKRUncaughtExceptionHandlerAdapter {
    override fun uncaughtException(throwable: Throwable) {
        Log.e("SlockKuikly", "Kuikly render error", throwable)
        SlockAndroidBuglyDiagnostics.reportCaughtException(
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
                object : LruCache<String, Bitmap>(NETWORK_BITMAP_CACHE_BYTES) {
                    override fun sizeOf(key: String, value: Bitmap): Int {
                        return value.allocationByteCount.coerceAtLeast(value.byteCount)
                    }
                }
    }

    override fun fetchDrawable(
            imageLoadOption: HRImageLoadOption,
            callback: (drawable: Drawable?) -> Unit
    ) {
        when {
            imageLoadOption.isSvgSource() -> loadSvg(imageLoadOption, callback)
            imageLoadOption.isBase64() -> loadFromBase64(imageLoadOption, callback)
            imageLoadOption.isAssets() -> loadFromAssets(imageLoadOption, callback)
            imageLoadOption.isLocalUri() -> loadFromLocalUri(imageLoadOption, callback)
            imageLoadOption.isWebUrl() -> loadFromNetwork(imageLoadOption, callback)
            else -> callback(null)
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
                        SlockDiagnosticsLogger.record(
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
        SlockKuiklyThreadAdapter.executeOnSubThread {
            runCatching {
                        cachedNetworkBitmap(imageLoadOption.src)?.let { bitmap ->
                            return@runCatching bitmap.toBitmapDrawable()
                        }
                        val response =
                                runBlocking {
                                    SharedBinaryDownloader.fetchBytes(imageLoadOption.src)
                                }
                        drawableFromNetworkBytes(response.bytes, imageLoadOption, response.contentType)
                                ?: throw IllegalStateException("Image decode failed")
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure { throwable ->
                        recordImageLoadFailure("network", imageLoadOption, throwable)
                        callback(null)
                    }
        }
    }

    private fun cachedNetworkBitmap(src: String): Bitmap? =
            synchronized(networkBitmapCache) {
                networkBitmapCache.get(src)?.takeUnless { it.isRecycled }
            }

    private fun cacheNetworkBitmap(src: String, bitmap: Bitmap) {
        val bitmapBytes = bitmap.allocationByteCount.coerceAtLeast(bitmap.byteCount)
        if (bitmapBytes > MAX_SINGLE_NETWORK_BITMAP_BYTES) {
            return
        }
        synchronized(networkBitmapCache) {
            networkBitmapCache.put(src, bitmap)
        }
    }

    private fun drawableFromNetworkBytes(
            bytes: ByteArray,
            imageLoadOption: HRImageLoadOption,
            contentType: String?
    ): Drawable? {
        if (bytes.looksLikeSvg(contentType)) {
            return svgBitmapDrawable(bytes.toString(StandardCharsets.UTF_8), imageLoadOption)
        }
        val bitmap = bitmapFromBytes(bytes, imageLoadOption) ?: return null
        cacheNetworkBitmap(imageLoadOption.src, bitmap)
        return bitmap.toBitmapDrawable()
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

    private fun recordImageLoadFailure(sourceKind: String, imageLoadOption: HRImageLoadOption, throwable: Throwable) {
        SlockDiagnosticsLogger.record(
                "SlockImageLoad",
                "source=$sourceKind status=failed type=${throwable::class.java.simpleName} " +
                        "srcHash=${imageLoadOption.src.hashCode().toString(16)} " +
                        "requestWidth=${imageLoadOption.requestWidth} requestHeight=${imageLoadOption.requestHeight}"
        )
    }

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
    private val attachmentDownloadControls = ConcurrentHashMap<String, AttachmentDownloadControl>()
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
            "openUrl" -> openUrl(params)
            "openAttachment" -> {
                openAttachment(params, callback)
                null
            }
            "attachmentCacheStatus" -> {
                callback?.invoke(attachmentCacheStatus(params))
                null
            }
            "deleteAttachmentCache" -> {
                callback?.invoke(deleteAttachmentCache(params))
                null
            }
            "attachmentDownloadControl" -> {
                callback?.invoke(attachmentDownloadControl(params))
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
                SlockAndroidBuglyDiagnostics.recordLog(level.name.lowercase(), "SlockKuikly", content)
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
            "connect" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeConnect(
                            serverId = request.optString("serverId"),
                            accessToken = request.optString("accessToken"),
                            lastSeq = request.optLong("lastSeq", 0L),
                            forceReconnect = request.optBoolean("forceReconnect", false)
                    )
            "disconnect" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeDisconnect(
                            request.optString("reason").ifBlank { "shared_disconnect" }
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
        SlockPerformanceDiagnostics.recordMarker(
                view = container,
                routeName = routeName,
                phase = phase,
                event = request.optString("event").trim(),
                sceneId = request.optString("sceneId").trim(),
                durationMs = durationMs,
                metadata = request.optJSONObject("metadata").toStableLogText()
        )
    }

    private fun kuiklyPerformanceSnapshot(params: String?) {
        SlockAndroidBuglyDiagnostics.reportKuiklySnapshot(JSONObject(params ?: "{}"))
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
            callback?.invoke(captureThreadShareImage(hostActivity, request))
        }
    }

    private fun captureThreadShareImage(hostActivity: SlockKuiklyActivity, request: JSONObject): Map<String, Any> {
        val container =
                hostActivity.findViewById<ViewGroup?>(R.id.slock_kuikly_container)
                        ?: return mapOf("status" to "failed", "message" to "Thread view unavailable")
        if (container.width <= 0 || container.height <= 0) {
            return mapOf("status" to "failed", "message" to "Thread view is not ready")
        }
        val fragmentPaths =
                request.optString("fragments")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toList()
        if (fragmentPaths.isNotEmpty()) {
            return stitchThreadShareFragments(fragmentPaths)
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
                runCatching {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                        val canvas = Canvas(output)
                        canvas.drawColor(Color.WHITE)
                        canvas.translate(-left.toFloat(), -top.toFloat())
                        container.draw(canvas)
                    }
                }.getOrElse {
                    return mapOf("status" to "failed", "message" to "Unable to capture image")
                }
        return saveThreadShareBitmap(bitmap, frame = request.optBoolean("frame", true))
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
        val headerHeight = (width * 0.18f).roundToInt().coerceIn(96, 132)
        val footerHeight = (width * 0.12f).roundToInt().coerceIn(68, 92)
        val horizontalInset = (width * 0.048f).roundToInt().coerceIn(26, 38).toFloat()
        val framed =
                Bitmap.createBitmap(width, height + headerHeight + footerHeight, Bitmap.Config.ARGB_8888)
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
            textSize = (width * 0.052f).coerceIn(26f, 34f)
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
        fill.color = Color.WHITE
        val footerTop = (headerHeight + height).toFloat()
        canvas.drawRect(0f, footerTop, width.toFloat(), framed.height.toFloat(), fill)
        canvas.drawText(
                "Shared from Raft",
                horizontalInset,
                footerTop + footerHeight.centeredTextBaseline(caption),
                caption
        )
        canvas.drawLine(0f, headerHeight.toFloat(), width.toFloat(), headerHeight.toFloat(), stroke)
        canvas.drawLine(0f, footerTop, width.toFloat(), footerTop, stroke)
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
                                    SlockDiagnosticsLogger.record(
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
            SlockDiagnosticsLogger.record(
                    "SlockDiagnostics",
                    "settings_capability_feedback_action action=$action hasMessage=${request.optString("message").isNotBlank()} " +
                            "fileCount=${request.optJSONArray("files")?.length() ?: 0}"
            )
            val scope = (activity as? ComponentActivity)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result = submitFeedbackToQuiver(request.takeIf { action == "submitFeedback" })
                SlockDiagnosticsLogger.record(
                        "SlockDiagnostics",
                        "settings_capability_feedback_result action=$action status=${result["status"]} code=${result["code"] ?: "-"}"
                )
                callback?.invoke(result)
            }
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
                        "setRecompositionProfilerEnabled" -> {
                            val enabled = request.optBoolean("enabled", false)
                            SlockDiagnosticsLogger.record(
                                    "SlockDiagnostics",
                                    "shared_recomposition_profiler_enabled=$enabled"
                            )
                            mapOf("status" to "ok", "enabled" to enabled)
                        }
                        "copyDiagnostics" -> {
                            copyText("Raft diagnostics", buildMergedSettingsDiagnosticsExportText())
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_copy_diagnostics")
                            mapOf("status" to "ok")
                        }
                        "shareDiagnostics" -> {
                            shareText(
                                    subject = "Raft diagnostics",
                                    text = buildMergedSettingsDiagnosticsExportText()
                            )
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_share_diagnostics")
                            mapOf("status" to "ok")
                        }
                        "clearDiagnostics" -> {
                            SlockAndroidBuglyDiagnostics.clearLocalDiagnosticsFiles(appContext)
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_logs_cleared")
                            mapOf("status" to "ok")
                        }
                        "prepareDiagnosticsFeedback" -> {
                            // Raft-channel upload flow retired: feedback goes to
                            // Quiver via sendFeedback. A non-draft response skips
                            // the channel picker in the shared settings UI.
                            mapOf("status" to "unsupported")
                        }
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
                    "rows" to
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
                                                    title = "Downloaded files",
                                                    detail = "Files downloaded for opening or installing from previews.",
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
        val snapshot = repository.clear(clearTarget)
        if (clearTarget == StorageClearTarget.TemporaryAndLogs) {
            SlockAndroidBuglyDiagnostics.clearLocalDiagnosticsFiles(appContext)
        }
        val feedback =
                when (clearTarget) {
                    StorageClearTarget.ImageCache -> "Image cache cleared."
                    StorageClearTarget.AttachmentDownloads -> "Downloaded files cleared."
                    StorageClearTarget.TemporaryAndLogs -> "Temporary files and diagnostics logs cleared."
                }
        SlockDiagnosticsLogger.record("SlockStorage", "shared_clear target=$target")
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

    private fun sendTestNotificationCapability(): Map<String, Any> {
        if (!hasNotificationPermission()) {
            return requestNotificationPermissionCapability()
        }
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        if (AndroidUnreadNotificationPoster.isNotificationChannelBlocked(appContext, AndroidUnreadNotificationPoster.SHARED_NOTIFICATION_CHANNEL_ID)) {
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
                                    NotificationCompat.Builder(appContext, AndroidUnreadNotificationPoster.SHARED_NOTIFICATION_CHANNEL_ID)
                                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                                            .setContentTitle(appContext.getString(R.string.settings_notification_test_title))
                                            .setContentText(appContext.getString(R.string.settings_notification_test_text))
                                            .setStyle(
                                                    NotificationCompat.BigTextStyle()
                                                            .bigText(appContext.getString(R.string.settings_notification_test_text))
                                            )
                                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                            .setCategory(NotificationCompat.CATEGORY_STATUS)
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
        SlockDiagnosticsLogger.record("SlockSettingsNotification", "permission_request_dialog_opened")
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
                "buildLabel" to packageInfo.versionCodeCompat(),
                "sourceLabel" to BuildConfig.GIT_COMMIT.ifBlank { "unknown" },
                "packageName" to appContext.packageName,
                "signedInUserLabel" to (session?.user?.bestDisplayName ?: "Signed out"),
                "selectedServerLabel" to (selectedServer?.name ?: "No selected server"),
                "sessionStatusLabel" to if (session != null) "Signed in" else "Signed out",
                "socketStatusLabel" to "Host active",
                "notificationStatusLabel" to "Managed by system settings",
                "deviceLabel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "platformLabel" to "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
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
                            SlockDiagnosticsLogger.record(
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
                            SlockDiagnosticsLogger.record(
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
                "updateCheckDetail" to "Check Quiver channel ${BuildConfig.QUIVER_CHANNEL} for a newer Android APK. If an update is available, Raft downloads it and opens the Android installer.",
                "options" to options,
                "feedback" to feedback.orEmpty()
        )
    }

    private suspend fun checkQuiverUpdate(install: Boolean = true): Map<String, Any> {
        val arch = Build.SUPPORTED_ABIS.firstOrNull { it.isNotBlank() }
        val response =
                QuiverClient(BuildConfig.QUIVER_BASE_URL)
                        .checkForUpdate(
                                slug = BuildConfig.QUIVER_APP_SLUG,
                                channel = BuildConfig.QUIVER_CHANNEL,
                                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                                productType = "android-apk",
                                platform = "android",
                                arch = arch,
                                filetype = "apk",
                                deviceId = QuiverDeviceId.get(appContext)
                        )
        SlockDiagnosticsLogger.record(
                "SlockQuiverUpdate",
                "check status=ok slug=${BuildConfig.QUIVER_APP_SLUG} channel=${BuildConfig.QUIVER_CHANNEL} " +
                        "current=${BuildConfig.VERSION_CODE} latest=${response.latest?.version_code ?: response.latest_version_code ?: 0} available=${response.requireUpdate() != null}"
        )
        val updateAvailable = response.requireUpdate() != null
        val installResult =
                when {
                    install && updateAvailable -> installQuiverUpdate(response, arch)
                    updateAvailable -> currentQuiverDownloadResult(response, arch)
                    else -> QuiverInstallResult()
                }
        val updateStatusLabel = installResult.statusLabel.ifBlank { response.quiverStatusLabel() }
        val updateDetail = installResult.detail.ifBlank { response.quiverDetail() }
        val feedback =
                when {
                    updateAvailable -> "Update available: ${response.latest?.version?.ifBlank { null } ?: "version code ${response.latest?.version_code ?: 0L}"}."
                    else -> "No Quiver update available on ${BuildConfig.QUIVER_CHANNEL}."
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
                    SlockDiagnosticsLogger.record(
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
                            SlockDiagnosticsLogger.record(
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
                            SlockDiagnosticsLogger.record(
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
                                SlockDiagnosticsLogger.record(
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
        SlockDiagnosticsLogger.record(
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
            runBlocking {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).clearSession()
            }
            AndroidAppScopeRealtimeOwner.disconnectForSessionEnd("api_environment_changed")
            SlockDiagnosticsLogger.record(
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
        SlockDiagnosticsLogger.record(
                "SlockDeveloper",
                "api_environment_changed from=${request.optString("beforeEnvironmentKey")} to=${request.optString("afterEnvironmentKey")}"
        )
        return mapOf("status" to "ok")
    }

    private fun loadSettingsDiagnostics(): Map<String, Any> {
        val entries =
                JSONArray().also { array ->
                    array.put(
                            JSONObject()
                                    .put("timestampLabel", System.currentTimeMillis().toString())
                                    .put("tag", "SlockDiagnostics")
                                    .put(
                                            "message",
                                            "Bugly TDLog is disabled. Send diagnostics exports app-owned JSONL logs."
                                    )
                    )
                }
        return mapOf("status" to "ok", "entries" to entries)
    }

    private data class DiagnosticsFeedbackFile(val file: File, val summaryText: String)

    /**
     * Submit in-app feedback to the Quiver ticket system (kind=bug) with the
     * diagnostics archive attached. Returns status=ok when the ticket was
     * created so the settings UI can confirm.
     */
    private suspend fun submitFeedbackToQuiver(request: JSONObject? = null): Map<String, Any> {
        SlockDiagnosticsLogger.record(
                "SlockDiagnostics",
                "quiver_feedback_submit_enter source=${if (request != null) "shared" else "native_prompt"} " +
                        "messageLength=${request?.optString("message")?.length ?: 0} " +
                        "fileCount=${request?.optJSONArray("files")?.length() ?: 0}"
        )
        val description = request?.optString("message")?.trim()?.takeIf { it.isNotBlank() }
            ?: promptFeedbackDescription()
            ?: return mapOf("status" to "cancelled").also {
                SlockDiagnosticsLogger.record("SlockDiagnostics", "quiver_feedback_submit_cancelled reason=blank_or_cancelled")
            }
        val userFilesResult =
                withContext(Dispatchers.IO) {
                    runCatching { request?.optJSONArray("files").toFeedbackAttachmentFiles() }
                }
        val userFiles =
                userFilesResult.getOrElse { throwable ->
                    SlockDiagnosticsLogger.record(
                            "SlockDiagnostics",
                            "quiver_feedback_attachment_prepare_failed error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                    )
                    return mapOf<String, Any>(
                            "status" to "error",
                            "code" to "settings_feedback_attachment_read_failed",
                            "message" to (throwable.message ?: "Unable to read feedback attachment")
                    )
                }
        val diagnosticsFile =
                if (userFiles.size < QUIVER_FEEDBACK_MAX_ATTACHMENTS) {
                    withContext(Dispatchers.IO) { prepareOptionalDiagnosticsFeedbackFile() }
                } else {
                    null
                }
        val attachments = (userFiles + listOfNotNull(diagnosticsFile)).take(QUIVER_FEEDBACK_MAX_ATTACHMENTS)
        SlockDiagnosticsLogger.record(
                "SlockDiagnostics",
                "quiver_feedback_submit_prepared messageLength=${description.length} " +
                        "userAttachmentCount=${userFiles.size} diagnosticsAttached=${diagnosticsFile != null} " +
                        "attachmentCount=${attachments.size}"
        )
        return runCatching {
            val ticketId = QuiverFeedback(
                    context = appContext,
                    baseUrl = BuildConfig.QUIVER_BASE_URL,
                    appSlug = BuildConfig.QUIVER_APP_SLUG,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    channel = BuildConfig.QUIVER_CHANNEL,
                    clientKey = BuildConfig.QUIVER_CLIENT_KEY,
            ).submit(
                    message = description,
                    kind = "bug",
                    attachments = attachments,
            )
            // Shareable reference the user can paste into the community channel.
            val shareText = buildString {
                append("Quiver feedback ").append(ticketId.take(8))
                appendLine()
                append(description)
            }
            withContext(Dispatchers.Main) {
                copyText("Quiver feedback", shareText)
                Toast.makeText(appContext, "Copied — paste it in the community.", Toast.LENGTH_LONG).show()
            }
            SlockDiagnosticsLogger.record("SlockDiagnostics", "quiver_feedback_submitted ticket=${ticketId.take(8)}")
            mapOf<String, Any>("status" to "ok", "ticketId" to ticketId, "shareText" to shareText)
        }.getOrElse {
            SlockDiagnosticsLogger.record(
                    "SlockDiagnostics",
                    "quiver_feedback_submit_failed error=${it.javaClass.simpleName} message=${it.message ?: "-"}"
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, "Feedback failed: ${it.message ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
            mapOf<String, Any>(
                    "status" to "error",
                    "code" to quiverFeedbackSubmitErrorCode(it),
                    "message" to (it.message ?: "feedback submission failed"),
            )
        }
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
                        .setMessage("Your message plus device diagnostics will be sent to Quiver. You'll get a copyable reference to share in the community.")
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
        val archive = SlockAndroidBuglyDiagnostics.prepareDiagnosticsFeedbackArchive(appContext, summaryText)
        SlockAndroidBuglyDiagnostics.uploadDiagnostics(
                context = appContext,
                attachment = archive,
                summary = "settings_feedback"
        )
        return DiagnosticsFeedbackFile(file = archive, summaryText = summaryText)
    }

    private fun prepareOptionalDiagnosticsFeedbackFile(): File? {
        return runCatching { writeDiagnosticsFeedbackFile().file }
                .getOrElse { throwable ->
                    SlockDiagnosticsLogger.record(
                            "SlockDiagnostics",
                            "quiver_feedback_diagnostics_prepare_skipped error=${throwable.javaClass.simpleName} message=${throwable.message ?: "-"}"
                    )
                    null
                }
                ?.takeIf { file ->
                    val fits = file.length() in 1..QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES
                    if (!fits) {
                        SlockDiagnosticsLogger.record(
                                "SlockDiagnostics",
                                "quiver_feedback_diagnostics_attachment_skipped bytes=${file.length()} max=$QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES"
                        )
                    }
                    fits
                }
    }

    private fun quiverFeedbackSubmitErrorCode(throwable: Throwable): String {
        val httpCode = (throwable as? QuiverFeedbackException)?.code
        return when {
            httpCode == 401 || httpCode == 403 -> "settings_feedback_auth_failed"
            httpCode == 429 -> "settings_feedback_rate_limited"
            httpCode == 413 -> "settings_feedback_attachment_too_large"
            httpCode == 400 && "too large" in throwable.message.orEmpty().lowercase(Locale.US) ->
                "settings_feedback_attachment_too_large"
            httpCode in 500..599 -> "settings_feedback_server_unavailable"
            throwable is IOException -> "settings_feedback_network_failed"
            else -> "settings_feedback_submit_failed"
        }
    }

    private fun JSONArray?.toFeedbackAttachmentFiles(): List<File> {
        if (this == null) return emptyList()
        val files = mutableListOf<File>()
        for (index in 0 until length()) {
            if (files.size >= QUIVER_FEEDBACK_MAX_ATTACHMENTS) break
            val fileJson = optJSONObject(index) ?: continue
            val fileName = fileJson.optString("fileName").ifBlank { "attachment-${index + 1}" }
            val mimeType = fileJson.optString("mimeType").ifBlank { "application/octet-stream" }
            val fileRef = fileJson.optString("fileRef").takeIf { it.isNotBlank() }
            val bodyBase64 = fileJson.optString("bodyBase64").takeIf { it.isNotBlank() }
            val file =
                    when {
                        bodyBase64 != null ->
                            writeQuiverFeedbackTempFile(
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    bytes = Base64.decode(bodyBase64, Base64.DEFAULT),
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
        val bytes =
                openQuiverFeedbackFileRefStream(uri)
                        .use { input -> input.readBytes() }
        return writeQuiverFeedbackTempFile(fileName = fileName, mimeType = mimeType, bytes = bytes, index = index)
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

    private fun writeQuiverFeedbackTempFile(
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
            index: Int
    ): File {
        if (bytes.isEmpty()) throw IOException("Feedback attachment is empty")
        if (bytes.size > QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES) {
            throw IOException("Feedback attachment exceeds 10 MB")
        }
        val cacheDir = File(appContext.cacheDir, QUIVER_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME).apply { mkdirs() }
        cleanupQuiverFeedbackAttachmentCache(cacheDir)
        val safeName = sanitizeQuiverFeedbackFileName(fileName, mimeType, index)
        val target = File(cacheDir, "${System.currentTimeMillis()}-${System.nanoTime()}-$safeName")
        target.outputStream().use { output -> output.write(bytes) }
        return target
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
        val recentLogs = SlockAndroidBuglyDiagnostics.exportRecentDiagnosticsText(appContext)
        return buildString {
            appendLine("Raft diagnostics")
            appendLine()
            appendLine("Environment")
            appendDiagnosticsLine("App", about.stringValue("appName").ifBlank { "Raft" })
            appendDiagnosticsLine("Version", about.stringValue("versionLabel"))
            appendDiagnosticsLine("Build", about.stringValue("buildLabel"))
            appendDiagnosticsLine("Source", about.stringValue("sourceLabel"))
            appendDiagnosticsLine("Package", about.stringValue("packageName"))
            appendDiagnosticsLine("Platform", about.stringValue("platformLabel"))
            appendDiagnosticsLine("Device", about.stringValue("deviceLabel"))
            appendDiagnosticsLine("Session", about.stringValue("sessionStatusLabel"))
            appendDiagnosticsLine("Signed in user", about.stringValue("signedInUserLabel"))
            appendDiagnosticsLine("Selected server", about.stringValue("selectedServerLabel"))
            appendDiagnosticsLine("Socket", about.stringValue("socketStatusLabel"))
            appendLine()
            appendLine("Bugly diagnostics")
            appendDiagnosticsLine("TDLog", "disabled")
            appendDiagnosticsLine(
                "App JSONL log",
                if (SlockAndroidBuglyDiagnostics.hasFallbackDiagnosticsLogs(appContext)) {
                    "enabled"
                } else {
                    "pending"
                }
            )
            appendDiagnosticsLine("Attachment", "App-owned diagnostics JSONL files are included in this zip when available.")
            appendLine()
            appendLine("Recent logs")
            appendLine()
            appendLine(recentLogs.trim().ifBlank { "No local diagnostics log files are currently available." })
        }
    }

    private fun recordSettingsDiagnosticsEnvironment(about: Map<String, Any>) {
        SlockAndroidBuglyDiagnostics.recordLog(
                "info",
                "SlockDiagnostics",
                "environment app=${about.stringValue("appName").ifBlank { "Raft" }} " +
                        "version=${about.stringValue("versionLabel")} " +
                        "build=${about.stringValue("buildLabel")} " +
                        "source=${about.stringValue("sourceLabel")} " +
                        "package=${about.stringValue("packageName")} " +
                        "platform=${about.stringValue("platformLabel")} " +
                        "device=${about.stringValue("deviceLabel")} " +
                        "session=${about.stringValue("sessionStatusLabel")} " +
                        "signedInUser=${about.stringValue("signedInUserLabel")} " +
                        "selectedServer=${about.stringValue("selectedServerLabel")} " +
                        "socket=${about.stringValue("socketStatusLabel")}"
        )
    }

    private fun Map<String, Any>.stringValue(key: String): String =
            this[key]?.toString().orEmpty()

    private fun StringBuilder.appendDiagnosticsLine(label: String, value: String?) {
        append(label)
        append(": ")
        appendLine(value?.takeIf { it.isNotBlank() } ?: "--")
    }

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
            Toast.makeText(appContext, "Unable to open share sheet", Toast.LENGTH_SHORT).show()
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
        val viewIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    if (mimeType != null) {
                        setDataAndType(Uri.parse(url), mimeType)
                    } else {
                        data = Uri.parse(url)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        runCatching {
            appContext.startActivity(viewIntent)
        }.onFailure { throwable ->
            if (throwable is ActivityNotFoundException) {
                Toast.makeText(appContext, "No app can open this link", Toast.LENGTH_SHORT)
                        .show()
            } else {
                Toast.makeText(appContext, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        }
        return null
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
                        SlockDiagnosticsLogger.record(
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

    private fun attachmentCacheStatus(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val targetFile = attachmentCacheFile(request)
        val cached = targetFile.isUsableAttachmentCacheFile()
        return mapOf(
                "status" to "ok",
                "cached" to cached,
                "sizeBytes" to if (cached) targetFile.length() else 0L
        )
    }

    private fun deleteAttachmentCache(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val targetFile = attachmentCacheFile(request)
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val existed = targetFile.exists() || partFile.exists()
        val deleted =
                listOf(targetFile, partFile)
                        .map { file -> if (file.exists()) runCatching { file.delete() }.getOrDefault(false) else true }
                        .all { it }
        return if (deleted) {
            mapOf("status" to "ok", "cached" to false, "deleted" to existed)
        } else {
            mapOf("status" to "failed", "message" to "Unable to delete downloaded file")
        }
    }

    private fun attachmentDownloadControl(params: String?): Map<String, Any> {
        val request = JSONObject(params ?: "{}")
        val downloadId = request.optString("downloadId").takeIf { it.isNotBlank() }
                ?: return mapOf("status" to "failed", "message" to "Missing download id")
        val control = attachmentDownloadControls[downloadId]
                ?: return mapOf("status" to "missing", "message" to "Download is no longer active")
        return when (request.optString("action").lowercase(Locale.US)) {
            "pause" -> {
                control.pause()
                emitAttachmentDownloadEvent(
                        attachmentDownloadProgress(
                                downloadId = downloadId,
                                status = "paused",
                                downloadedBytes = control.downloadedBytes,
                                totalBytes = control.totalBytes,
                                percent = control.percent,
                                bytesPerSecond = null
                        )
                )
                mapOf("status" to "paused")
            }
            "resume" -> {
                control.resume()
                emitAttachmentDownloadEvent(
                        attachmentDownloadProgress(
                                downloadId = downloadId,
                                status = "resumed",
                                downloadedBytes = control.downloadedBytes,
                                totalBytes = control.totalBytes,
                                percent = control.percent,
                                bytesPerSecond = null
                        )
                )
                mapOf("status" to "resumed")
            }
            "stop", "cancel" -> {
                control.stop()
                emitAttachmentDownloadEvent(
                        attachmentDownloadProgress(
                                downloadId = downloadId,
                                status = "cancelled",
                                downloadedBytes = control.downloadedBytes,
                                totalBytes = control.totalBytes,
                                percent = control.percent,
                                bytesPerSecond = null
                        )
                )
                mapOf("status" to "cancelled")
            }
            else -> mapOf("status" to "failed", "message" to "Unknown download action")
        }
    }

    private fun downloadAndOpenAttachment(
            request: JSONObject,
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): Map<String, Any> {
        val urlText = request.optString("url").takeIf { it.isNotBlank() }
                ?: return mapOf("status" to "failed", "message" to "Unable to download attachment")
        val attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment"
        val requestedFileName = request.optString("fileName").takeIf { it.isNotBlank() } ?: "attachment"
        val downloadId = request.optString("downloadId").takeIf { it.isNotBlank() }
                ?: "${attachmentId}:${requestedFileName}".take(160)
        val requestedMimeType = request.optString("mimeType").takeIf { it.isNotBlank() }
        val startedAt = System.currentTimeMillis()
        val cacheDir = attachmentCacheDir()
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return mapOf("status" to "storage_failed", "message" to "Storage unavailable")
        }
        cleanupAttachmentCache(cacheDir)
        val targetFile = attachmentCacheFile(attachmentId, requestedFileName, requestedMimeType)
        val needsDownload = !targetFile.isUsableAttachmentCacheFile()
        val responseMimeType =
                if (needsDownload) {
                    val control = AttachmentDownloadControl(downloadId)
                    attachmentDownloadControls[downloadId] = control
                    try {
                        downloadAttachmentToFile(urlText, targetFile, downloadId, control, progressCallback)
                    } catch (ignored: AttachmentDownloadStoppedException) {
                        return mapOf(
                                "status" to "cancelled",
                                "message" to "Download stopped",
                                "cached" to false
                        )
                    } finally {
                        attachmentDownloadControls.remove(downloadId, control)
                    }
                } else {
                    null
                }
        val openMimeType = guessAttachmentMimeType(requestedFileName, requestedMimeType ?: responseMimeType)
        val attachmentUri =
                runCatching {
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", targetFile)
                }.getOrElse {
                    return mapOf("status" to "storage_failed", "message" to "Storage unavailable")
                }
        val isPackageArchive = isAndroidPackageArchive(requestedFileName, openMimeType)
        val intent =
                Intent(if (isPackageArchive) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW).apply {
                    setDataAndType(attachmentUri, openMimeType)
                    clipData = ClipData.newUri(appContext.contentResolver, targetFile.name, attachmentUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (isPackageArchive) {
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    }
                }
        val handlers = appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty() && !isPackageArchive) {
            showToast("No app can open this attachment")
            SlockDiagnosticsLogger.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=no_app mimeType=$openMimeType durationMs=${System.currentTimeMillis() - startedAt}"
            )
            return mapOf("status" to "no_app", "message" to "No app can open this file")
        }
        handlers.forEach { handler ->
            appContext.grantUriPermission(
                    handler.activityInfo.packageName,
                    attachmentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        return runCatching {
            if (isPackageArchive) {
                runCatching {
                    appContext.startActivity(intent)
                }.getOrElse { firstThrowable ->
                    if (firstThrowable !is ActivityNotFoundException) throw firstThrowable
                    val viewIntent =
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(attachmentUri, openMimeType)
                                clipData = ClipData.newUri(appContext.contentResolver, targetFile.name, attachmentUri)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                    appContext.startActivity(viewIntent)
                }
            } else {
                val chooser =
                        Intent.createChooser(intent, "Open with").apply {
                            clipData = intent.clipData
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                appContext.startActivity(chooser)
            }
            SlockDiagnosticsLogger.record(
                    "SlockAttachmentOpen",
                    "attachmentId=${attachmentId.take(8)} status=opened mimeType=$openMimeType cached=${!needsDownload} durationMs=${System.currentTimeMillis() - startedAt}"
            )
            mapOf(
                    "status" to "opened",
                    "cached" to true,
                    "sizeBytes" to targetFile.length(),
                    "action" to if (isPackageArchive) "install" else "open"
            )
        }.getOrElse { throwable ->
            if (throwable is ActivityNotFoundException) {
                showToast("No app can open this attachment")
                mapOf("status" to "no_app", "message" to "No app can open this file")
            } else {
                showToast("Unable to open attachment")
                mapOf("status" to "failed", "message" to "Unable to open attachment")
            }
        }
    }

    private fun attachmentCacheDir(): File = File(appContext.cacheDir, ATTACHMENT_CACHE_DIR_NAME)

    private fun attachmentCacheFile(request: JSONObject): File =
            attachmentCacheFile(
                    attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment",
                    requestedFileName = request.optString("fileName").takeIf { it.isNotBlank() } ?: "attachment",
                    requestedMimeType = request.optString("mimeType").takeIf { it.isNotBlank() }
            )

    private fun attachmentCacheFile(
            attachmentId: String,
            requestedFileName: String,
            requestedMimeType: String?
    ): File =
            File(attachmentCacheDir(), safeAttachmentCacheFileName(attachmentId, requestedFileName, requestedMimeType))

    private fun File.isUsableAttachmentCacheFile(): Boolean =
            isFile &&
                    length() > 0L &&
                    System.currentTimeMillis() - lastModified() <= ATTACHMENT_CACHE_MAX_AGE_MS

    private fun downloadAttachmentToFile(
            urlText: String,
            targetFile: File,
            downloadId: String,
            control: AttachmentDownloadControl,
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): String? {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        runCatching { tempFile.delete() }
        try {
            val downloadStartedAt = System.currentTimeMillis()
            var lastDownloadedBytes = 0L
            var lastTotalBytes: Long? = null
            var lastPercent = -1
            var lastProgressAt = System.currentTimeMillis()
            val responseContentType =
                    runBlocking {
                        tempFile.outputStream().use { output ->
                            SharedBinaryDownloader.download(
                                url = urlText,
                                onStart = { totalBytes ->
                                    lastTotalBytes = totalBytes
                                    lastPercent = if (totalBytes == null) -1 else 0
                                    control.totalBytes = totalBytes
                                    emitAttachmentDownloadProgress(
                                            progressCallback,
                                            attachmentDownloadProgress(
                                                    downloadId = downloadId,
                                                    downloadedBytes = 0L,
                                                    totalBytes = totalBytes,
                                                    percent = totalBytes?.let { 0 },
                                                    bytesPerSecond = null
                                            )
                                    )
                                },
                                beforeRead = {
                                    control.waitIfPausedOrStopped()
                                },
                                onChunk = { bytes, downloadedBytes, totalBytes ->
                                    output.write(bytes)
                                    lastDownloadedBytes = downloadedBytes
                                    lastTotalBytes = totalBytes
                                    val percent =
                                            totalBytes?.let { total ->
                                                ((downloadedBytes * 100L) / total).toInt().coerceIn(0, 100)
                                            }
                                    control.updateProgress(downloadedBytes, totalBytes, percent)
                                    val now = System.currentTimeMillis()
                                    val shouldEmit =
                                            when {
                                                percent != null && percent >= 100 -> true
                                                percent != null && percent != lastPercent && percent - lastPercent >= 2 -> true
                                                percent == null && now - lastProgressAt >= 500L -> true
                                                now - lastProgressAt >= 750L -> true
                                                else -> false
                                            }
                                    if (shouldEmit) {
                                        emitAttachmentDownloadProgress(
                                                progressCallback,
                                                attachmentDownloadProgress(
                                                        downloadId = downloadId,
                                                        downloadedBytes = downloadedBytes,
                                                        totalBytes = totalBytes,
                                                        percent = percent,
                                                        bytesPerSecond = downloadedBytes.downloadBytesPerSecondSince(downloadStartedAt, now)
                                                )
                                        )
                                        lastPercent = percent ?: lastPercent
                                        lastProgressAt = now
                                    }
                                }
                            )
                        }
                    }
            emitAttachmentDownloadProgress(
                    progressCallback,
                    attachmentDownloadProgress(
                            downloadId = downloadId,
                            downloadedBytes = lastDownloadedBytes,
                            totalBytes = lastTotalBytes,
                            percent = lastTotalBytes?.let { 100 },
                            bytesPerSecond = lastDownloadedBytes.downloadBytesPerSecondSince(downloadStartedAt, System.currentTimeMillis())
                    )
            )
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            return responseContentType
        } finally {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    private fun attachmentDownloadProgress(
            downloadId: String,
            status: String = "progress",
            downloadedBytes: Long,
            totalBytes: Long?,
            percent: Int?,
            bytesPerSecond: Long?
    ): Map<String, Any> =
            buildMap {
                put("downloadId", downloadId)
                put("status", status)
                put("downloadedBytes", downloadedBytes)
                totalBytes?.let { put("totalBytes", it) }
                percent?.let { put("percent", it.coerceIn(0, 100)) }
                bytesPerSecond?.takeIf { it > 0L }?.let { put("bytesPerSecond", it) }
            }

    private fun emitAttachmentDownloadProgress(
            progressCallback: ((Map<String, Any>) -> Unit)?,
            progress: Map<String, Any>
    ) {
        progressCallback?.invoke(progress)
        emitAttachmentDownloadEvent(progress)
    }

    private fun emitAttachmentDownloadEvent(progress: Map<String, Any>) {
        val payload = JSONObject()
        progress.forEach { (key, value) ->
            payload.put(key, value)
        }
        postAndroidAppScopeEvent(
                eventName = ATTACHMENT_DOWNLOAD_EVENT_NAME,
                eventData = payload
        )
    }

    private fun Long.downloadBytesPerSecondSince(startedAt: Long, now: Long): Long? {
        val elapsedMs = (now - startedAt).coerceAtLeast(1L)
        return ((this * 1_000L) / elapsedMs).takeIf { it > 0L }
    }

    private fun cleanupAttachmentCache(cacheDir: File) {
        cacheDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(ATTACHMENT_CACHE_MAX_FILES)
                ?.forEach { runCatching { it.delete() } }
    }

    private fun safeAttachmentCacheFileName(
            attachmentId: String,
            requestedFileName: String,
            mimeType: String?
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
        return "$safeId-$withExtension"
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
        @Volatile private var pendingQuiverAutoInstallKey: String? = null
        private const val REQUEST_NOTIFICATIONS_CODE = 4207
        private const val ATTACHMENT_CACHE_MAX_FILES = 80
        private const val ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val ATTACHMENT_CACHE_DIR_NAME = "slock-attachments"
        private const val THREAD_SHARE_IMAGE_CACHE_DIR_NAME = "slock-share-images"
        private const val THREAD_SHARE_IMAGE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val QUIVER_FEEDBACK_MAX_ATTACHMENTS = 3
        private const val QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
        private const val QUIVER_FEEDBACK_ATTACHMENT_CACHE_DIR_NAME = "quiver-feedback-attachments"
        private const val QUIVER_FEEDBACK_ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val ANDROID_PACKAGE_ARCHIVE_MIME_TYPE = "application/vnd.android.package-archive"
        private const val ATTACHMENT_DOWNLOAD_EVENT_NAME = "slock.attachmentDownloadProgress"
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
                SlockDiagnosticsLogger.record(
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
                        SlockDiagnosticsLogger.record(
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
            SlockDiagnosticsLogger.record(
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

private class AttachmentDownloadControl(
        val downloadId: String
) {
    private val lock = java.lang.Object()

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var stopped: Boolean = false

    @Volatile
    var downloadedBytes: Long = 0L
        private set

    @Volatile
    var totalBytes: Long? = null

    @Volatile
    var percent: Int? = null
        private set

    fun updateProgress(downloadedBytes: Long, totalBytes: Long?, percent: Int?) {
        this.downloadedBytes = downloadedBytes
        this.totalBytes = totalBytes
        this.percent = percent
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            paused = false
            lock.notifyAll()
        }
    }

    fun waitIfPausedOrStopped() {
        synchronized(lock) {
            while (paused && !stopped) {
                lock.wait(250L)
            }
            if (stopped) {
                throw AttachmentDownloadStoppedException()
            }
        }
    }
}

private class AttachmentDownloadStoppedException : RuntimeException()

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
    SlockDiagnosticsLogger.d("SlockKuikly", "shared_auth $message")
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

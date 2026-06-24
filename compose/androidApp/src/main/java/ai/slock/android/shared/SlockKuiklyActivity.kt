package ai.slock.android.shared

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import ai.slock.android.R
import ai.slock.android.BuildConfig
import ai.slock.android.core.data.auth.GoogleSocialAuthGateway
import ai.slock.android.core.data.auth.GoogleSocialAuthResult
import ai.slock.android.core.data.auth.completeGoogleSocialAuth
import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.android.core.data.auth.AndroidApiServerEnvironment
import ai.slock.android.core.data.auth.AndroidApiServerEnvironmentStore
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import ai.slock.android.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.diagnostics.SlockPerformanceDiagnostics
import ai.slock.android.core.data.storage.StorageClearTarget
import ai.slock.android.core.data.storage.StorageManagementRepository
import ai.slock.android.core.data.storage.StorageManagementSnapshot
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.compose.data.SlockAndroidSqlDatabaseContext
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
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val realtimeSurfaceId: String by lazy {
        "$pageName:${System.identityHashCode(this)}"
    }
    private val kuiklySystemBackCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleKuiklySystemBack()
                }
            }
    private val threadImageAttachmentPickerLauncher: ActivityResultLauncher<String> =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
                handleThreadAttachmentPickerResult(uris)
            }
    private val threadFileAttachmentPickerLauncher: ActivityResultLauncher<String> =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
                handleThreadAttachmentPickerResult(uris)
            }

    private val pageName: String
        get() = intent.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_ROUTER

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
        declareForegroundRealtimeRooms()
    }

    override fun onDestroy() {
        detachRealtimeConsumerIfNeeded(retainDeclaredRooms = false)
        removeSlockInputFontBridge()
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    override fun onPause() {
        detachRealtimeConsumerIfNeeded(retainDeclaredRooms = true)
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
        attachRealtimeConsumerIfNeeded()
        declareForegroundRealtimeRooms()
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
        kuiklyRenderExport.moduleExport(SlockKuiklyNetworkModule.MODULE_NAME) {
            SlockKuiklyNetworkModule()
        }
        kuiklyRenderExport.moduleExport(SlockKuiklyMMKVModule.MODULE_NAME) {
            SlockKuiklyMMKVModule(applicationContext)
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

    private fun installSlockInputFontBridge(container: ViewGroup) {
        val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    container.applySlockInputTypeface()
                }
        inputFontLayoutListener = listener
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        container.post { container.applySlockInputTypeface() }
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
                        threadImageAttachmentPickerLauncher.launch("image/*")
                    } else {
                        threadFileAttachmentPickerLauncher.launch("*/*")
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
                                                KuiklyMMKVAuthSessionStore(
                                                        androidMMKVAuthKeyValueStore(applicationContext)
                                                )
                                ).completeGoogleSocialAuth(nativeResult)
                            }
                    when (result) {
                        is SharedAuthResult.Success -> {
                            callback?.invoke(mapOf("code" to 200, "status" to "success"))
                            Toast.makeText(
                                    this@SlockKuiklyActivity,
                                    "Signed in with Google",
                                    Toast.LENGTH_SHORT
                            ).show()
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

    private fun handleThreadAttachmentPickerResult(uris: List<Uri>) {
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
        SlockKuiklyThreadAdapter.execute("thread-attachment-upload") {
                    val result =
                            runCatching {
                                        readThreadAttachmentUploadFiles(uris)
                                    }
                                    .getOrElse { throwable ->
                                        mapOf(
                                                "code" to 0,
                                                "bodyText" to "",
                                                "errorCode" to "attachment_read_failed",
                                                "errorBodyText" to (throwable.message ?: "Attachment read failed")
                                        )
                                    }
                    pending.callback?.invoke(result)
                }
    }

    private fun readThreadAttachmentUploadFiles(uris: List<Uri>): Map<String, Any> {
        val files = uris.mapIndexed { index, uri -> pickedThreadAttachmentRef(uri, index) }
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

    private fun pickedThreadAttachmentRef(uri: Uri, index: Int): PickedThreadAttachment {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val cachedFile = cachePickedThreadAttachment(uri, fileName, index)
        val copiedBytes = cachedFile.length()
        if (copiedBytes <= 0L) {
            throw IllegalStateException("Picked attachment is empty or unreadable")
        }
        val sizeBytes = queryPickedFileSize(uri) ?: copiedBytes
        return PickedThreadAttachment(
                fileRef = Uri.fromFile(cachedFile).toString(),
                fileName = fileName,
                mimeType = mimeType,
                previewUri = uri.toString(),
                sizeBytes = sizeBytes
        )
    }

    private fun cachePickedThreadAttachment(uri: Uri, fileName: String, index: Int): File {
        val directory = File(cacheDir, "thread-attachment-picks").apply { mkdirs() }
        val safeName = fileName.toSafeCacheFileName().ifBlank { "attachment-$index" }
        val target = File(directory, "${System.nanoTime()}-$index-$safeName")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Picked attachment cannot be opened")
        return target
    }

    private fun String.toSafeCacheFileName(): String =
            trim()
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_', '.', '-')
                    .take(96)

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
    }

    private fun createPageSpec(): SlockKuiklyPageSpec {
        val pageData =
                SlockKuiklyPageData.fromText(intent.getStringExtra(KEY_PAGE_DATA))
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

    private fun attachRealtimeConsumerIfNeeded() {
        if (!pageName.isRealtimeBridgeConsumerPage()) return
        AndroidAppScopeRealtimeOwner.attachKuiklyRawConsumer(realtimeSurfaceId) { serverId, eventName, rawPayload ->
            sendRawRealtimeEvent(serverId, eventName, rawPayload)
        }
    }

    private fun detachRealtimeConsumerIfNeeded(retainDeclaredRooms: Boolean) {
        if (!pageName.isRealtimeBridgeConsumerPage()) return
        AndroidAppScopeRealtimeOwner.detachKuiklyRawConsumer(
            surfaceId = realtimeSurfaceId,
            retainDeclaredRooms = retainDeclaredRooms
        )
    }

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
        AndroidAppScopeRealtimeOwner.disconnectForSessionEnd(reason)
    }

    internal fun requestRealtimeJoinChannel(channelId: String) {
        if (channelId.isBlank()) return
        AndroidAppScopeRealtimeOwner.joinChannel(ChannelId(channelId))
    }

    private fun declareForegroundRealtimeRooms() {
        if (!pageName.isRealtimeBridgeConsumerPage()) return
        AndroidAppScopeRealtimeOwner.declareKuiklySurfaceRooms(
            surfaceId = realtimeSurfaceId,
            rooms = foregroundThreadRealtimeChannelIds().map(::ChannelId).toSet()
        )
    }

    private fun foregroundThreadRealtimeChannelIds(): List<String> {
        val data = runCatching { JSONObject(pageSpec?.pageData?.text.orEmpty()) }.getOrNull() ?: return emptyList()
        val payload =
                data.optString("payload")
                        .takeIf { it.isNotBlank() }
                        ?.let { payloadText -> runCatching { JSONObject(payloadText) }.getOrNull() }
        return listOf("sourceThreadId", "streamChannelId", "threadId")
                .flatMap { key ->
                    listOfNotNull(
                            data.optString(key).takeIf { it.isNotBlank() },
                            payload?.optString(key)?.takeIf { it.isNotBlank() }
                    )
                }
                .distinct()
    }

    private fun String.isRealtimeBridgeConsumerPage(): Boolean =
            this == PAGE_APP_SHELL || this == PAGE_THREAD

    private fun sendAppShellLifecycleEvent(event: String) {
        if (pageSpec?.pageName != PAGE_APP_SHELL) return
        kuiklyRenderViewDelegator.sendEvent(
                REALTIME_EVENT_NAME,
                mapOf(
                        "action" to "appLifecycle",
                        "event" to event,
                        "pageName" to PAGE_APP_SHELL
                )
        )
    }

    private fun sendRawRealtimeEvent(serverId: ServerId, eventName: String, rawPayload: Any?) {
        val rawPayloadStr = when (rawPayload) {
            is String -> rawPayload
            is org.json.JSONObject -> rawPayload.toString()
            is org.json.JSONArray -> rawPayload.toString()
            else -> rawPayload?.toString() ?: "{}"
        }
        kuiklyRenderViewDelegator.sendEvent(
                REALTIME_EVENT_NAME,
                mapOf(
                        "serverId" to serverId.value,
                        "eventName" to eventName,
                        "rawPayload" to rawPayloadStr
                )
        )
        SlockDiagnosticsLogger.d(
                "SlockKuiklyRealtime",
                "raw_event_forwarded eventName=$eventName serverId=${serverId.value}"
        )
    }

    companion object {
        const val PAGE_LOGIN = SlockKuiklyPages.LOGIN
        const val PAGE_REGISTER = SlockKuiklyPages.REGISTER
        const val PAGE_FORGOT_PASSWORD = SlockKuiklyPages.FORGOT_PASSWORD
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

        private const val KEY_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
        private const val KEY_VERIFY_KUIKLY_THREAD = "slockKuiklyVerifyThread"
        private const val KEY_VERIFY_KUIKLY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val KEY_HOST_EXCLUDES_NAVIGATION_BAR = "slockHostExcludesNavigationBar"
        private const val NAVIGATION_MODE_SETTING = "navigation_mode"
        private const val NAVIGATION_MODE_RESOURCE = "config_navBarInteractionMode"
        private const val NAVIGATION_MODE_GESTURAL = 2
        private const val REALTIME_EVENT_NAME = "slockRealtimeEvent"
        private const val THREAD_SURFACE_CHANNEL = "channel"
        private const val THREAD_SURFACE_DIRECT_MESSAGE = "dm"
        private const val THREAD_SURFACE_REPLY_THREAD = "thread"
        private const val NOTIFICATION_CATEGORY_DIRECT_MESSAGE = "direct_message"

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

        internal fun shouldReplaceCurrentPage(pageData: JSONObject): Boolean {
            return pageData.optBoolean(KEY_REPLACE_CURRENT_PAGE, false)
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
                        put("slockSocialAuthProviders", "google")
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
    private var context: Context? = null
    private var inputTypeface: Typeface? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun inputTypeface(context: Context): Typeface? {
        inputTypeface?.let { return it }
        return ResourcesCompat.getFont(context.applicationContext, R.font.space_grotesk_regular)
                ?.also { inputTypeface = it }
    }

    fun resolveTypeface(fontFamily: String?, context: Context): Typeface? {
        val fontName = fontFamily?.substringBefore(",")?.trim().orEmpty()
        val fontRes = when (fontName) {
            "space_grotesk_variable" -> R.font.space_grotesk_variable
            "space_grotesk_bold" -> R.font.space_grotesk_bold
            "space_grotesk_semibold" -> R.font.space_grotesk_semibold
            "space_grotesk_medium" -> R.font.space_grotesk_medium
            "space_grotesk_regular" -> R.font.space_grotesk_regular
            "space_grotesk_light" -> R.font.space_grotesk_light
            "space_grotesk" -> R.font.space_grotesk
            else -> null
        }
        return fontRes?.let { ResourcesCompat.getFont(context.applicationContext, it) }
            ?: inputTypeface(context)
    }

    override fun getTypeface(fontFamily: String, result: (Typeface?) -> Unit) {
        val currentContext = context
        result(currentContext?.let { resolveTypeface(fontFamily, it) })
    }
}

private class SlockKuiklyImageAdapter(
        private val context: Context
) : IKRImageAdapter {
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
                        bitmapDrawableFromBytes(bytes)
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
                    .onFailure {
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
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure {
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
                        val connection = URL(imageLoadOption.src).openConnection() as HttpURLConnection
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = true
                        val contentType = connection.contentType
                        val bytes = connection.inputStream.use { input -> input.readBytes() }
                        drawableFromBytes(bytes, imageLoadOption, contentType)
                    }
                    .onSuccess { drawable ->
                        callback(drawable)
                    }
                    .onFailure {
                        callback(null)
                    }
        }
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
            bitmapDrawableFromBytes(bytes)
        }
    }

    private fun bitmapDrawableFromBytes(bytes: ByteArray): Drawable? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BitmapDrawable(context.resources, bitmap)
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
            val connection = URL(src).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            return connection.inputStream.use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
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
        private val badgeApplier: ai.slock.android.badge.BadgeApplier =
            ai.slock.android.badge.AndroidNotificationBadgeApplier(appContext),
) : KuiklyRenderBaseModule() {
    private val databases = ConcurrentHashMap<String, SQLiteDatabase>()
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
            "openUrl" -> openUrl(params)
            "openAttachment" -> {
                openAttachment(params, callback)
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
                SanitizedDiagnosticsLogBuffer.record("SlockKuikly", content)
                when (level) {
                    SlockBridgeLogLevel.DEBUG -> Unit
                    SlockBridgeLogLevel.INFO -> Log.i("SlockKuikly", content)
                    SlockBridgeLogLevel.WARN -> Log.w("SlockKuikly", content)
                    SlockBridgeLogLevel.ERROR -> Log.e("SlockKuikly", content)
                }
                null
            }
            "appBadge" -> {
                applyAppBadge(params)
                null
            }
            "copyToPasteboard" -> {
                copyToPasteboard(params)
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
            "relationalStore" -> {
                relationalStore(params, callback)
                null
            }
            "currentTimestamp" -> System.currentTimeMillis().toString()
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
        }
    }

    /**
     * #100: host consumer of the shared `bridge.appBadge(count, source, isStale)`
     * contract (see #81). Parses the JSON params, dispatches to the [BadgeApplier].
     *
     * Boundary rule (per #81, 2026-06-15 12:59): `source` and `isStale` are passed
     * to the applier for diagnostics only. The applier MUST NOT branch on `source`
     * to decide which UI to show, and MUST keep the previous `count` visible when
     * `isStale=true` (the shared side already did the `previous.copy(isStale=true)`
     * projection, so `count` here is already the unchanged value).
     */
    private fun applyAppBadge(params: String?) {
        val request = JSONObject(params ?: "{}")
        val count = request.optInt("count", 0).coerceAtLeast(0)
        val source = request.optString("source", "UNKNOWN")
        val isStale = request.optBoolean("isStale", false)
        badgeApplier.apply(count = count, source = source, isStale = isStale)
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
            "joinChannel" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeJoinChannel(
                            request.optString("channelId")
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

    private fun relationalStore(params: String?, callback: KuiklyRenderCallback?) {
        SlockKuiklyThreadAdapter.execute("relational-store") {
            val result =
                    runCatching {
                        val request = JSONObject(params ?: "{}")
                        val action = request.optString("action")
                        val sql = request.optString("sql")
                        val bindArgs = request.optJSONArray("bindArgs").toSqlBindArgs()
                        val databaseName =
                                request.optString("databaseName")
                                        .takeIf { it.isNotBlank() }
                                        ?: "slock_shared.db"
                        val db = database(databaseName)
                        when (action) {
                            "query" -> query(db, sql, bindArgs)
                            else -> {
                                db.execSQL(sql, bindArgs.toTypedArray())
                                mapOf("status" to "ok")
                            }
                        }
                    }.getOrElse { throwable ->
                        mapOf(
                                "status" to "error",
                                "code" to -1,
                                "message" to (throwable.message ?: "relationalStore failed")
                        )
                    }
            callback?.invoke(result)
        }
    }

    private fun database(databaseName: String): SQLiteDatabase =
            databases.getOrPut(databaseName) {
                appContext.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            }

    private fun query(
            db: SQLiteDatabase,
            sql: String,
            bindArgs: List<Any?>
    ): Map<String, Any> {
        db.rawQuery(sql, bindArgs.map { it?.toString() }.toTypedArray()).use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = mutableListOf<List<Any?>>()
            while (cursor.moveToNext()) {
                rows +=
                        columns.indices.map { index ->
                            when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                android.database.Cursor.FIELD_TYPE_BLOB ->
                                        cursor.getBlob(index)?.toString(StandardCharsets.UTF_8)
                                else -> cursor.getString(index)
                            }
                        }
            }
            return mapOf("status" to "ok", "columns" to columns, "rows" to rows)
        }
    }

    private fun JSONArray?.toSqlBindArgs(): List<Any?> =
            buildList {
                if (this@toSqlBindArgs == null) return@buildList
                for (index in 0 until length()) {
                    add(
                            when (val value = opt(index)) {
                                JSONObject.NULL -> null
                                is Boolean -> if (value) 1L else 0L
                                is Int -> value.toLong()
                                is Long -> value
                                is Float -> value.toDouble()
                                is Double -> value
                                else -> value?.toString()
                            }
                    )
                }
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

    private fun settingsCapability(params: String?, callback: KuiklyRenderCallback?) {
        val request = JSONObject(params ?: "{}")
        val result =
                runCatching {
                    when (request.optString("action")) {
                        "loadStorage" -> loadSettingsStorage()
                        "clearStorage" -> clearSettingsStorage(request.optString("target"))
                        "loadAbout" -> loadSettingsAbout()
                        "loadDeveloper" -> loadSettingsDeveloper()
                        "setApiEnvironment" -> setApiEnvironment(request)
                        "loadDiagnostics" -> loadSettingsDiagnostics()
                        "probeNetwork" -> probeSettingsNetwork()
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
                            SanitizedDiagnosticsLogBuffer.clear()
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_logs_cleared")
                            mapOf("status" to "ok")
                        }
                        "prepareDiagnosticsFeedback" -> {
                            prepareDiagnosticsFeedbackDraft()
                        }
                        "sendFeedback" -> {
                            shareFeedbackWithDiagnosticsFile()
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_send_feedback")
                            mapOf("status" to "ok")
                        }
                        "requestNotificationPermission" -> requestNotificationPermissionCapability()
                        "openNotificationSettings" -> {
                            openNotificationSettingsCapability()
                            mapOf("status" to "ok")
                        }
                        "sendTestNotification" -> sendTestNotificationCapability()
                        "postUnreadNotification" -> postUnreadNotificationCapability(request)
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
                    "temporaryAndLogs" -> StorageClearTarget.TemporaryAndLogs
                    else -> null
                } ?: return mapOf("status" to "error", "code" to "settings_storage_clear_unsupported")
        val repository = StorageManagementRepository(appContext)
        val snapshot = repository.clear(clearTarget)
        if (clearTarget == StorageClearTarget.TemporaryAndLogs) {
            SanitizedDiagnosticsLogBuffer.clear()
        }
        val feedback =
                when (clearTarget) {
                    StorageClearTarget.ImageCache -> "Image cache cleared."
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
                    KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).readSession()
                }
        val selectedServer =
                session?.currentServerId?.let { serverId -> session.servers.firstOrNull { it.id == serverId } }
                        ?: session?.servers?.firstOrNull()
        return mapOf(
                "status" to "ok",
                "appName" to "Raft",
                "versionLabel" to (packageInfo.versionName ?: "unknown"),
                "buildLabel" to packageInfo.versionCodeCompat(),
                "sourceLabel" to AUTH_DIAGNOSTICS_SOURCE_MARKER,
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
                "options" to options,
                "feedback" to feedback.orEmpty()
        )
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
                KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).clearSession()
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

    private fun loadSettingsDiagnostics(): Map<String, Any> {
        val entries =
                JSONArray().also { array ->
                    SanitizedDiagnosticsLogBuffer.recent().takeLast(80).forEach { entry ->
                        array.put(
                                JSONObject()
                                        .put("timestampLabel", entry.timestampMillis.toString())
                                        .put("tag", entry.tag)
                                        .put("message", entry.message)
                        )
                    }
                }
        return mapOf("status" to "ok", "entries" to entries)
    }

    private fun probeSettingsNetwork(): Map<String, Any> {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val baseUrl = AndroidAuthApiFactory.currentBaseUrl(appContext)
            val connection =
                    (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("Accept", "application/json")
                    }
            val code = connection.responseCode
            val duration = System.currentTimeMillis() - startedAt
            val detail = connection.responseMessage.orEmpty()
            connection.disconnect()
            SlockDiagnosticsLogger.record(
                    "SlockNetworkProbe",
                    "baseUrl=${safeUrlForDiagnostics(baseUrl)} code=$code durationMs=$duration"
            )
            mapOf(
                    "status" to "ok",
                    "baseUrl" to baseUrl,
                    "reachable" to (code > 0),
                    "code" to code,
                    "durationMillis" to duration,
                    "statusLabel" to if (code > 0) "API host reachable" else "API host unreachable",
                    "detail" to if (code > 0) "Host responded with HTTP $code${detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}" else "No HTTP response"
            )
        }.getOrElse { throwable ->
            val duration = System.currentTimeMillis() - startedAt
            val errorType = throwable::class.java.simpleName.ifBlank { "Network error" }
            val detail = throwable.message?.takeIf { it.isNotBlank() }?.let { "$errorType: $it" } ?: errorType
            SlockDiagnosticsLogger.record(
                    "SlockNetworkProbe",
                    "baseUrl=${safeUrlForDiagnostics(AndroidAuthApiFactory.currentBaseUrl(appContext))} failed=${throwable::class.java.simpleName} durationMs=$duration"
            )
            mapOf(
                    "status" to "ok",
                    "baseUrl" to AndroidAuthApiFactory.currentBaseUrl(appContext),
                    "reachable" to false,
                    "code" to 0,
                    "durationMillis" to duration,
                    "statusLabel" to "API host unreachable",
                    "detail" to "Could not reach the configured API host. $detail"
            )
        }
    }

    private fun buildFeedbackText(diagnosticsFileName: String): String =
            buildString {
                appendLine("## Raft feedback")
                appendLine()
                appendLine("<!-- Describe what happened here. -->")
                appendLine()
                appendLine("## Diagnostics")
                appendLine()
                appendLine("Sanitized diagnostics are attached as `$diagnosticsFileName`.")
            }

    private fun shareFeedbackWithDiagnosticsFile() {
        val diagnosticsFile = writeDiagnosticsFeedbackFile()
        val buglyAttachmentRegistered = SlockAndroidBuglyDiagnostics.registerDiagnosticsAttachment(diagnosticsFile)
        SlockAndroidBuglyDiagnostics.reportDiagnosticsFeedbackPrepared(
                fileName = diagnosticsFile.name,
                sizeBytes = diagnosticsFile.length(),
                attachmentRegistered = buglyAttachmentRegistered
        )
        shareTextWithAttachment(
                subject = "Raft feedback",
                text = buildFeedbackText(diagnosticsFile.name),
                attachment = diagnosticsFile,
                attachmentMimeType = "text/plain"
        )
    }

    private fun prepareDiagnosticsFeedbackDraft(): Map<String, Any> {
        val diagnosticsFile = writeDiagnosticsFeedbackFile()
        val bytes = diagnosticsFile.readBytes()
        val buglyAttachmentRegistered = SlockAndroidBuglyDiagnostics.registerDiagnosticsAttachment(diagnosticsFile)
        SlockAndroidBuglyDiagnostics.reportDiagnosticsFeedbackPrepared(
                fileName = diagnosticsFile.name,
                sizeBytes = bytes.size.toLong(),
                attachmentRegistered = buglyAttachmentRegistered
        )
        SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_prepare_channel_feedback")
        return mapOf(
                "status" to "ok",
                "message" to buildFeedbackText(diagnosticsFile.name),
                "fileName" to diagnosticsFile.name,
                "mimeType" to "text/plain",
                "sizeBytes" to bytes.size.toLong(),
                "bodyBase64" to Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    }

    private fun writeDiagnosticsFeedbackFile(): File {
        val feedbackDir = File(appContext.cacheDir, "slock-feedback").apply { mkdirs() }
        feedbackDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("slock-feedback-") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        val file = File(feedbackDir, "slock-feedback-${System.currentTimeMillis()}.txt")
        file.writeText(buildMergedSettingsDiagnosticsExportText(), StandardCharsets.UTF_8)
        return file
    }

    private fun buildMergedSettingsDiagnosticsExportText(): String {
        val about = loadSettingsAbout()
        val probe = probeSettingsNetwork()
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
            appendLine("Network probe")
            appendDiagnosticsLine("Base URL", probe.stringValue("baseUrl"))
            appendDiagnosticsLine("Status", probe.stringValue("statusLabel"))
            appendDiagnosticsLine("Reachable", probe["reachable"]?.toString())
            appendDiagnosticsLine("HTTP code", probe["code"]?.takeIf { it != 0 }?.toString())
            appendDiagnosticsLine("Duration", probe["durationMillis"]?.takeIf { it != 0L }?.let { "${it}ms" })
            appendDiagnosticsLine("Detail", probe.stringValue("detail"))
            appendLine()
            appendLine("Recent logs")
            append(SanitizedDiagnosticsLogBuffer.exportText())
        }
    }

    private fun Map<String, Any>.stringValue(key: String): String =
            this[key]?.toString().orEmpty()

    private fun StringBuilder.appendDiagnosticsLine(label: String, value: String?) {
        append(label)
        append(": ")
        appendLine(value?.takeIf { it.isNotBlank() } ?: "--")
    }

    private fun shareTextWithAttachment(
            subject: String,
            text: String,
            attachment: File,
            attachmentMimeType: String
    ) {
        val attachmentUri =
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", attachment)
        val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = attachmentMimeType
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_STREAM, attachmentUri)
                    clipData = ClipData.newUri(appContext.contentResolver, attachment.name, attachmentUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        runCatching {
            appContext.startActivity(
                    Intent.createChooser(intent, subject).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
            )
        }.onFailure {
            Toast.makeText(appContext, "Unable to open share sheet", Toast.LENGTH_SHORT).show()
        }
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
        val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    if (mimeType != null) {
                        setDataAndType(Uri.parse(url), mimeType)
                    } else {
                        data = Uri.parse(url)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        runCatching {
            appContext.startActivity(intent)
        }.onFailure { throwable ->
            if (throwable is ActivityNotFoundException) {
                Toast.makeText(appContext, "No app can open this attachment", Toast.LENGTH_SHORT)
                        .show()
            } else {
                Toast.makeText(appContext, "Unable to open attachment", Toast.LENGTH_SHORT).show()
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

    private fun downloadAndOpenAttachment(
            request: JSONObject,
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): Map<String, Any> {
        val urlText = request.optString("url").takeIf { it.isNotBlank() }
                ?: return mapOf("status" to "failed", "message" to "Unable to download attachment")
        val attachmentId = request.optString("attachmentId").takeIf { it.isNotBlank() } ?: "attachment"
        val requestedFileName = request.optString("fileName").takeIf { it.isNotBlank() } ?: "attachment"
        val requestedMimeType = request.optString("mimeType").takeIf { it.isNotBlank() }
        val startedAt = System.currentTimeMillis()
        val cacheDir = File(appContext.cacheDir, "slock-attachments")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return mapOf("status" to "storage_failed", "message" to "Storage unavailable")
        }
        cleanupAttachmentCache(cacheDir)
        val targetFile = File(cacheDir, safeAttachmentCacheFileName(attachmentId, requestedFileName, requestedMimeType))
        val needsDownload =
                !targetFile.isFile ||
                        targetFile.length() <= 0L ||
                        System.currentTimeMillis() - targetFile.lastModified() > ATTACHMENT_CACHE_MAX_AGE_MS
        val responseMimeType =
                if (needsDownload) {
                    downloadAttachmentToFile(urlText, targetFile, progressCallback)
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
            mapOf("status" to "opened")
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

    private fun downloadAttachmentToFile(
            urlText: String,
            targetFile: File,
            progressCallback: ((Map<String, Any>) -> Unit)? = null
    ): String? {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        runCatching { tempFile.delete() }
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Attachment download failed: HTTP $code")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            val downloadStartedAt = System.currentTimeMillis()
            progressCallback?.invoke(
                    attachmentDownloadProgress(
                            downloadedBytes = 0L,
                            totalBytes = totalBytes,
                            percent = totalBytes?.let { 0 },
                            bytesPerSecond = null
                    )
            )
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastPercent = if (totalBytes == null) -1 else 0
                    var lastProgressAt = System.currentTimeMillis()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        val percent =
                                totalBytes?.let { total ->
                                    ((downloadedBytes * 100L) / total).toInt().coerceIn(0, 100)
                                }
                        val now = System.currentTimeMillis()
                        val shouldEmit =
                                when {
                                    progressCallback == null -> false
                                    percent != null && percent >= 100 -> true
                                    percent != null && percent != lastPercent && percent - lastPercent >= 2 -> true
                                    percent == null && now - lastProgressAt >= 500L -> true
                                    now - lastProgressAt >= 750L -> true
                                    else -> false
                                }
                        if (shouldEmit) {
                            progressCallback?.invoke(
                                    attachmentDownloadProgress(
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
                    progressCallback?.invoke(
                            attachmentDownloadProgress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    percent = totalBytes?.let { 100 },
                                    bytesPerSecond = downloadedBytes.downloadBytesPerSecondSince(downloadStartedAt, System.currentTimeMillis())
                            )
                    )
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            return connection.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    private fun attachmentDownloadProgress(
            downloadedBytes: Long,
            totalBytes: Long?,
            percent: Int?,
            bytesPerSecond: Long?
    ): Map<String, Any> =
            buildMap {
                put("status", "progress")
                put("downloadedBytes", downloadedBytes)
                totalBytes?.let { put("totalBytes", it) }
                percent?.let { put("percent", it.coerceIn(0, 100)) }
                bytesPerSecond?.takeIf { it > 0L }?.let { put("bytesPerSecond", it) }
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
        if (provider != "google") {
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
        if (hostActivity?.requestGoogleNativeSocialAuth(callback) == true) return

        val returnTo = URLEncoder.encode("/", StandardCharsets.UTF_8.name())
        val url = "${AndroidAuthApiFactory.currentBaseUrl(appContext).trimEnd('/')}/auth/google/start?returnTo=$returnTo"
        val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        runCatching {
            appContext.startActivity(intent)
        }.onSuccess {
            callback?.invoke(mapOf("code" to 202, "status" to "started"))
        }.onFailure { throwable ->
            val message =
                    if (throwable is ActivityNotFoundException) {
                        "No browser can open Google sign-in"
                    } else {
                        "Unable to open Google sign-in"
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

    private fun safeUrlForDiagnostics(rawUrl: String): String =
            runCatching {
                val url = URL(rawUrl)
                "${url.protocol}://${url.host}${url.path}"
            }.getOrDefault(rawUrl.substringBefore("?").take(160))

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        private const val REQUEST_NOTIFICATIONS_CODE = 4207
        private const val AUTH_DIAGNOSTICS_SOURCE_MARKER = "auth-bootstrap-diagnostics/e36bc138+"
        private const val ATTACHMENT_CACHE_MAX_FILES = 80
        private const val ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val ANDROID_PACKAGE_ARCHIVE_MIME_TYPE = "application/vnd.android.package-archive"
    }
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

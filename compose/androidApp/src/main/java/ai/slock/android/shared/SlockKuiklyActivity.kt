package ai.slock.android.shared

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import ai.slock.android.R
import ai.slock.android.BuildConfig
import ai.slock.compose.data.StoredAuthSession as AuthSession
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.auth.GoogleSocialAuthGateway
import ai.slock.android.core.data.auth.GoogleSocialAuthResult
import ai.slock.android.core.data.auth.completeGoogleSocialAuth
import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.storage.StorageClearTarget
import ai.slock.android.core.data.storage.StorageManagementRepository
import ai.slock.android.core.data.storage.StorageManagementSnapshot
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.compose.data.RealtimeRepository
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthResult
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageReaction
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.RealtimeRefreshRequired
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerMembershipRemoved
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.compose.data.SlockAndroidSqlDatabaseContext
import ai.slock.compose.data.realtime.RealtimeCursorPolicy
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRFontAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRThreadAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRUncaughtExceptionHandlerAdapter
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.performace.KRMonitorType
import com.tencent.kuikly.core.render.android.performace.KRPerformanceData
import com.tencent.kuikly.core.render.android.performace.launch.KRLaunchData
import com.tencent.kuikly.core.render.android.expand.component.KRTextFieldView
import com.caverock.androidsvg.SVG
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SlockKuiklyActivity : ComponentActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var realtimeRepository: RealtimeRepository? = null
    private var realtimeSubscription: RealtimeSubscription? = null
    private var realtimeConnectJob: Job? = null
    private var realtimeServerId: ServerId? = null
    private var realtimeSessionKey: String? = null
    private var realtimeLastSeq: Long = 0L
    private var inputFontLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingThreadAttachmentUpload: PendingThreadAttachmentUpload? = null
    private val googleSocialAuthGateway = GoogleSocialAuthGateway()
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
        SlockKuiklyAdapters.init(applicationContext)
        setContentView(R.layout.activity_slock_kuikly)
        setupImmersiveMode()
        suppressVisualKeyboard()
        val container = findViewById<ViewGroup>(R.id.slock_kuikly_container)
        installSlockInputFontBridge(container)
        val spec = createPageSpec()
        kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, spec.pageData.toKuiklyMap())
        startRealtimeBridgeIfNeeded()
    }

    override fun onDestroy() {
        stopRealtimeBridge()
        removeSlockInputFontBridge()
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    override fun onPause() {
        super.onPause()
        stopRealtimeBridge()
        kuiklyRenderViewDelegator.onPause()
    }

    override fun onResume() {
        super.onResume()
        suppressVisualKeyboard()
        applySlockInputTypeface()
        kuiklyRenderViewDelegator.onResume()
        startRealtimeBridgeIfNeeded()
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
        if (!kuiklyRenderViewDelegator.onBackPressed()) {
            super.onBackPressed()
        }
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(applicationContext)
        }
        kuiklyRenderExport.moduleExport(SlockKuiklyNetworkModule.MODULE_NAME) {
            SlockKuiklyNetworkModule()
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
                                        api = AndroidAuthApiFactory.createAuthApi(),
                                        sessionStore = EncryptedAuthSessionStore(applicationContext)
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
        if (uris.isEmpty()) {
            pending.callback?.invoke(mapOf("code" to 204, "bodyText" to ""))
            return
        }
        Thread {
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
                .start()
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
        return mapOf(
                "code" to 200,
                "bodyText" to "",
                "files" to filesJson
        )
    }

    private fun pickedThreadAttachmentRef(uri: Uri, index: Int): PickedThreadAttachment {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val sizeBytes = queryPickedFileSize(uri)
        return PickedThreadAttachment(
                fileRef = uri.toString(),
                fileName = fileName,
                mimeType = mimeType,
                previewUri = uri.toString(),
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
            SlockLucideIconRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
        kuiklyRenderExport.renderViewExport(
            "SlockLucidePathIconView",
            renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
    }

    private fun createPageSpec(): SlockKuiklyPageSpec {
        return SlockKuiklyPageSpec(
                pageName = pageName,
                pageData = SlockKuiklyPageData.fromText(intent.getStringExtra(KEY_PAGE_DATA))
        )
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

    private fun startRealtimeBridgeIfNeeded() {
        if (pageName != PAGE_APP_SHELL) return
        val repository = ensureRealtimeRepository()
        val sessionStore = EncryptedAuthSessionStore(applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
                lifecycleScope.launch {
                    try {
                        val session = sessionStore.readSession()
                        val serverId =
                                session?.currentServerId?.let(::ServerId)
                                        ?: run {
                                            SlockDiagnosticsLogger.w(
                                                    "SlockKuiklyRealtime",
                                                    "connect_skip reason=missing_selected_server"
                                            )
                                            return@launch
                                        }
                        val accessToken = session.accessToken.takeIf { it.isNotBlank() }
                                ?: run {
                                    SlockDiagnosticsLogger.w(
                                            "SlockKuiklyRealtime",
                                            "connect_skip reason=missing_access_token serverId=${serverId.value}"
                                    )
                                    return@launch
                                }
                        connectRealtimeRepository(
                                repository = repository,
                                serverId = serverId,
                                accessToken = accessToken,
                                requestedLastSeq = realtimeLastSeq
                        )
                    } finally {
                        realtimeConnectJob = null
                    }
                }
    }

    private fun ensureRealtimeRepository(): RealtimeRepository {
        realtimeRepository?.let { return it }
        val sessionStore = EncryptedAuthSessionStore(applicationContext)
        val client =
                SocketIoRealtimeClient(
                        transport = SocketIoSocketTransport(),
                        logger = { message ->
                            SlockDiagnosticsLogger.d("SlockKuiklyRealtime", message)
                        }
                )
        val repository =
                SessionRealtimeRepository(
                        store = sessionStore,
                        client = client,
                        tokenRefresher = AuthTokenRefresher(sessionStore)
                )
        realtimeRepository = repository
        realtimeSubscription =
                repository.observeEvents { event ->
                    lifecycleScope.launch {
                        val serverId = realtimeServerId ?: return@launch
                        realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                        if (event is DirectMessageCreated) {
                            repository.joinChannel(event.channelId)
                        }
                    }
                }
        client.observeRawEvents { eventName, rawPayload ->
            lifecycleScope.launch {
                val serverId = realtimeServerId ?: return@launch
                sendRawRealtimeEvent(serverId, eventName, rawPayload)
            }
        }
        return repository
    }

    private suspend fun connectRealtimeRepository(
            repository: RealtimeRepository,
            serverId: ServerId,
            accessToken: String,
            requestedLastSeq: Long
    ) {
        val nextSessionKey = "$accessToken|${serverId.value}"
        val previousSessionKey = realtimeSessionKey
        val resumeSeq =
                if (previousSessionKey != null && previousSessionKey != nextSessionKey) {
                    0L
                } else {
                    requestedLastSeq
                }
        if (realtimeServerId == serverId && realtimeSessionKey == nextSessionKey) {
            return
        }
        realtimeSessionKey = nextSessionKey
        realtimeServerId = serverId
        if (resumeSeq == 0L) {
            realtimeLastSeq = 0L
        }
        val result = repository.connect(serverId, resumeSeq)
        SlockDiagnosticsLogger.d(
                "SlockKuiklyRealtime",
                "connect_result serverId=${serverId.value} result=${result::class.simpleName} lastSeq=$resumeSeq"
        )
    }

    internal fun requestRealtimeConnect(serverId: String, accessToken: String, lastSeq: Long) {
        if (pageName != PAGE_APP_SHELL || serverId.isBlank() || accessToken.isBlank()) return
        val repository = ensureRealtimeRepository()
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
                lifecycleScope.launch {
                    try {
                        connectRealtimeRepository(
                                repository = repository,
                                serverId = ServerId(serverId),
                                accessToken = accessToken,
                                requestedLastSeq = lastSeq
                        )
                    } finally {
                        realtimeConnectJob = null
                    }
                }
    }

    internal fun requestRealtimeDisconnect(reason: String) {
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        realtimeRepository?.disconnect()
        realtimeServerId = null
        realtimeSessionKey = null
        realtimeLastSeq = 0L
        SlockDiagnosticsLogger.d(
                "SlockKuiklyRealtime",
                "disconnect_requested reason=$reason"
        )
    }

    internal fun requestRealtimeJoinChannel(channelId: String) {
        if (channelId.isBlank()) return
        (realtimeRepository ?: return).joinChannel(ai.slock.android.core.model.ChannelId(channelId))
    }

    private fun stopRealtimeBridge() {
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        realtimeSubscription?.close()
        realtimeSubscription = null
        realtimeRepository?.disconnect()
        realtimeRepository = null
        realtimeServerId = null
        realtimeSessionKey = null
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

    private fun sendRealtimeEventToSharedShell(serverId: ServerId, event: RealtimeEvent) {
        val json = event.toSharedShellJson()
        val eventName = json.optString("type", "unsupported")
        json.remove("type")
        val rawPayload = json.toString()
        kuiklyRenderViewDelegator.sendEvent(
                REALTIME_EVENT_NAME,
                mapOf(
                        "serverId" to serverId.value,
                        "eventName" to eventName,
                        "rawPayload" to rawPayload
                )
        )
        SlockDiagnosticsLogger.d(
                "SlockKuiklyRealtime",
                "event_forwarded eventName=$eventName serverId=${serverId.value}"
        )
    }

    companion object {
        const val PAGE_LOGIN = SlockKuiklyPages.LOGIN
        const val PAGE_REGISTER = SlockKuiklyPages.REGISTER
        const val PAGE_FORGOT_PASSWORD = SlockKuiklyPages.FORGOT_PASSWORD
        const val PAGE_APP_SHELL = SlockKuiklyPages.APP_SHELL
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

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"
        const val KEY_SUPPRESS_SOFT_KEYBOARD = "slockSuppressSoftKeyboard"

        private const val KEY_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
        private const val REALTIME_EVENT_NAME = "slockRealtimeEvent"

        fun start(
            context: Context,
            pageName: String,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false
        ) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val hostPageData = pageData.withAndroidAuthCapabilities(pageName)
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

        private fun JSONObject.withAndroidAuthCapabilities(pageName: String): JSONObject =
                JSONObject(toString()).apply {
                    if (pageName == PAGE_LOGIN || pageName == PAGE_APP_SHELL) {
                        put("slockSocialAuthProviders", "google")
                    }
                }
    }
}

private fun Long.updateFrom(event: RealtimeEvent): Long =
        RealtimeCursorPolicy.updateAppliedSeq(this, event)

private fun RealtimeEvent.toSharedShellJson(): JSONObject =
        when (this) {
            is MessageNew -> JSONObject().put("type", "message_new").put("message", message.toJson())
            is MessageUpdated -> JSONObject().put("type", "message_updated").put("message", message.toJson())
            is SyncResumeResponse ->
                    JSONObject()
                            .put("type", "sync_resume")
                            .put("currentSeq", currentSeq)
                            .put("hasMore", hasMore)
                            .put("messages", JSONArray().also { array -> messages.forEach { array.put(it.toJson()) } })
            is ThreadUpdated ->
                    JSONObject()
                            .put("type", "thread_updated")
                            .put("parentMessageId", parentMessageId.value)
                            .put("threadChannelId", threadChannelId.value)
                            .put("replyCount", replyCount)
                            .put("lastReplyAt", lastReplyAt)
                            .put("participantIds", JSONArray().also { array -> participantIds.forEach { array.put(it.value) } })
            is MemberPresenceUpdated ->
                    JSONObject()
                            .put("type", "member_presence")
                            .put("memberId", memberId.value)
                            .put("presence", presence.toWireValue())
                            .put("kind", kind?.toWireValue())
                            .put("agentStatus", agentStatus?.name)
                            .put("statusLabel", statusLabel)
            is RealtimeRefreshRequired ->
                    JSONObject()
                            .put("type", "refresh_required")
                            .put("scope", scope.name)
                            .put("reason", reason)
                            .put("serverId", serverId?.value)
                            .put("channelId", channelId?.value)
                            .put("memberId", memberId?.value)
                            .put("subjectId", subjectId)
            is ServerMembershipRemoved ->
                    JSONObject()
                            .put("type", "server_membership_removed")
                            .put("serverId", serverId.value)
            is DirectMessageCreated ->
                    JSONObject()
                            .put("type", "direct_message_created")
                            .put("channelId", channelId.value)
            is Heartbeat -> JSONObject().put("type", "heartbeat").put("seq", seq)
            else -> JSONObject().put("type", "unsupported")
        }

private fun ai.slock.android.core.model.Message.toJson(): JSONObject =
        JSONObject()
                .put("id", id.value)
                .put("threadId", threadId.value)
                .put("channelId", channelId?.value)
                .put("threadChannelId", threadChannelId?.value)
                .put("seq", seq)
                .put("author", author.toJson())
                .put("timestampLabel", timestampLabel)
                .put("body", body)
                .put("messageType", messageType.name)
                .put("systemEventType", systemEventType)
                .put("replyCount", replyCount)
                .put("parentMessageId", parentMessageId?.value)
                .apply {
                    reactions?.let { items ->
                        put("reactions", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } })
                    }
                }

private fun MessageReaction.toJson(): JSONObject =
        JSONObject()
                .put("emoji", emoji)
                .put("count", count)
                .put("reactorIds", JSONArray().also { array -> reactorIds.forEach { array.put(it) } })
                .put("reactorNames", JSONArray().also { array -> reactorNames.forEach { array.put(it) } })

private fun ai.slock.android.core.model.MemberProfile.toJson(): JSONObject =
        JSONObject()
                .put("id", id.value)
                .put("displayName", displayName)
                .put("handle", handle)
                .put("kind", kind.toWireValue())
                .put("avatarUrl", avatarUrl)
                .put("avatarLabel", avatarLabel)
                .put("presence", presence.toWireValue())
                .put("agentStatus", agentStatus?.name)

private fun MemberKind.toWireValue(): String =
        when (this) {
            MemberKind.Agent -> "agent"
            MemberKind.Human -> "human"
            MemberKind.System -> "system"
        }

private fun Presence.toWireValue(): String =
        when (this) {
            Presence.Active -> "active"
            Presence.Away -> "away"
            Presence.Offline -> "offline"
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
        (context as? Activity)?.finish()
    }
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

private object SlockKuiklyThreadAdapter : IKRThreadAdapter {
    private val executor = Executors.newFixedThreadPool(2)

    override fun executeOnSubThread(task: () -> Unit) {
        executor.execute(task)
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
        private val appContext: Context
) : KuiklyRenderBaseModule() {
    private val databases = ConcurrentHashMap<String, SQLiteDatabase>()
    private val gson = Gson()

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
            "openUrl" -> openUrl(params)
            "openAttachment" -> {
                openAttachment(params, callback)
                null
            }
            "closePage" -> {
                activity?.finish()
                null
            }
            "log" -> {
                val content = JSONObject(params ?: "{}").optString("content")
                Log.i("SlockKuikly", content)
                SlockDiagnosticsLogger.d("SlockKuikly", content)
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
            "authRequest" -> {
                authRequest(params, callback)
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

    private fun reportRealtime(params: String?) {
        val request = JSONObject(params ?: "{}")
        when (request.optString("action")) {
            "connect" ->
                    (activity as? SlockKuiklyActivity)?.requestRealtimeConnect(
                            serverId = request.optString("serverId"),
                            accessToken = request.optString("accessToken"),
                            lastSeq = request.optLong("lastSeq", 0L)
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

    private fun relationalStore(params: String?, callback: KuiklyRenderCallback?) {
        Thread {
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
                                mirrorAuthSessionWrite(sql, bindArgs)
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
        }.start()
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

    private fun mirrorAuthSessionWrite(sql: String, bindArgs: List<Any?>) {
        if (!sql.contains("slock_kv", ignoreCase = true)) return
        val key = bindArgs.firstOrNull()?.toString() ?: return
        if (key != AUTH_SESSION_KEY) return

        val store = EncryptedAuthSessionStore(appContext)
        when {
            sql.startsWith("DELETE", ignoreCase = true) -> runBlocking { store.clearSession() }
            sql.contains("INSERT", ignoreCase = true) || sql.contains("REPLACE", ignoreCase = true) -> {
                val encodedSession = bindArgs.getOrNull(1)?.toString().orEmpty()
                if (encodedSession.isBlank()) return
                val session = gson.fromJson(encodedSession, AuthSession::class.java)
                runBlocking { store.saveSession(session) }
            }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Slock", content))
    }

    private fun settingsCapability(params: String?, callback: KuiklyRenderCallback?) {
        val request = JSONObject(params ?: "{}")
        val result =
                runCatching {
                    when (request.optString("action")) {
                        "loadStorage" -> loadSettingsStorage()
                        "clearStorage" -> clearSettingsStorage(request.optString("target"))
                        "loadAbout" -> loadSettingsAbout()
                        "loadDiagnostics" -> loadSettingsDiagnostics()
                        "probeNetwork" -> probeSettingsNetwork()
                        "copyDiagnostics" -> {
                            copyText("Slock diagnostics", SanitizedDiagnosticsLogBuffer.exportText())
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_copy_logs")
                            mapOf("status" to "ok")
                        }
                        "shareDiagnostics" -> {
                            shareText(
                                    subject = "Slock diagnostics",
                                    text = SanitizedDiagnosticsLogBuffer.exportText()
                            )
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_share_logs")
                            mapOf("status" to "ok")
                        }
                        "clearDiagnostics" -> {
                            SanitizedDiagnosticsLogBuffer.clear()
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_logs_cleared")
                            mapOf("status" to "ok")
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
            ensureSharedNotificationChannels()
            return if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
                mapOf("status" to "ok", "code" to "permission_granted")
            } else {
                mapOf("status" to "error", "code" to "notifications_disabled")
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = appContext as? Activity
            if (activity != null) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS_CODE)
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
            return mapOf("status" to "error", "code" to "permission_denied")
        }
        ensureSharedNotificationChannels()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        if (isNotificationChannelBlocked(SHARED_NOTIFICATION_CHANNEL_ID)) {
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
                                    NotificationCompat.Builder(appContext, SHARED_NOTIFICATION_CHANNEL_ID)
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

    private fun postUnreadNotificationCapability(request: JSONObject): Map<String, Any> {
        if (!hasNotificationPermission()) {
            return mapOf("status" to "error", "code" to "permission_denied")
        }
        ensureSharedNotificationChannels()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        val category = request.optString("category")
        val channelId = notificationChannelIdForCategory(category)
        if (isNotificationChannelBlocked(channelId)) {
            return mapOf("status" to "error", "code" to "channel_blocked", "channelId" to channelId)
        }
        val title = request.optString("title").takeIf { it.isNotBlank() } ?: appContext.getString(R.string.app_name)
        val body = request.optString("body").takeIf { it.isNotBlank() } ?: appContext.getString(R.string.notification_unread_default_text)
        val notificationKey =
                request.optString("notificationKey").takeIf { it.isNotBlank() }
                        ?: "${request.optString("serverId")}:${request.optString("threadId")}:${request.optString("messageId")}"
        val notificationId = stableNotificationId(notificationKey)
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
                                    NotificationCompat.Builder(appContext, channelId)
                                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                                            .setContentTitle(title)
                                            .setContentText(body)
                                            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                            .setWhen(System.currentTimeMillis())
                                            .setShowWhen(true)
                                            .setContentIntent(contentIntent)
                                            .setAutoCancel(true)
                                            .build()
                            )
                    mapOf(
                            "status" to "ok",
                            "code" to "posted",
                            "notificationId" to notificationId,
                            "channelId" to channelId
                    )
                }
                .getOrElse { error ->
                    mapOf(
                            "status" to "error",
                            "code" to "post_failed",
                            "message" to (error.message ?: error.javaClass.simpleName)
                    )
                }
    }

    private fun hasNotificationPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED

    private fun ensureSharedNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
                listOf(
                        NotificationChannel(
                                SHARED_NOTIFICATION_CHANNEL_ID,
                                appContext.getString(R.string.settings_notification_channel_name),
                                NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = appContext.getString(R.string.settings_notification_channel_description)
                            enableVibration(false)
                        },
                        NotificationChannel(
                                UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID,
                                appContext.getString(R.string.notification_unread_general_channel_name),
                                NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = appContext.getString(R.string.notification_unread_general_channel_description)
                        },
                        NotificationChannel(
                                UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                                appContext.getString(R.string.notification_unread_channel_channel_name),
                                NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = appContext.getString(R.string.notification_unread_channel_channel_description)
                        },
                        NotificationChannel(
                                UNREAD_DM_NOTIFICATION_CHANNEL_ID,
                                appContext.getString(R.string.notification_unread_dm_channel_name),
                                NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = appContext.getString(R.string.notification_unread_dm_channel_description)
                        },
                        NotificationChannel(
                                UNREAD_MENTION_NOTIFICATION_CHANNEL_ID,
                                appContext.getString(R.string.notification_unread_mention_channel_name),
                                NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = appContext.getString(R.string.notification_unread_mention_channel_description)
                        }
                )
        )
    }

    private fun isNotificationChannelBlocked(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel =
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .getNotificationChannel(channelId)
        return channel?.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun notificationChannelIdForCategory(category: String): String =
            when (category) {
                "channel_unread" -> UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID
                "direct_message" -> UNREAD_DM_NOTIFICATION_CHANNEL_ID
                "mention" -> UNREAD_MENTION_NOTIFICATION_CHANNEL_ID
                else -> UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID
            }

    private fun stableNotificationId(key: String): Int {
        val hash = key.hashCode()
        return if (hash == Int.MIN_VALUE) {
            Int.MAX_VALUE
        } else {
            kotlin.math.abs(hash).coerceAtLeast(1)
        }
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
        val session = runBlocking { EncryptedAuthSessionStore(appContext).readSession() }
        val selectedServer =
                session?.currentServerId?.let { serverId -> session.servers.firstOrNull { it.id == serverId } }
                        ?: session?.servers?.firstOrNull()
        return mapOf(
                "status" to "ok",
                "appName" to "Slock",
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
                "baseUrl" to AndroidAuthApiFactory.BASE_URL
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
            val baseUrl = AndroidAuthApiFactory.BASE_URL
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
                    "statusLabel" to if (code > 0) "API host reachable" else "Network probe failed",
                    "detail" to if (code > 0) "Host responded with HTTP $code${detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}" else "No HTTP response"
            )
        }.getOrElse { throwable ->
            val duration = System.currentTimeMillis() - startedAt
            val detail = throwable.message ?: throwable::class.java.simpleName
            SlockDiagnosticsLogger.record(
                    "SlockNetworkProbe",
                    "baseUrl=${safeUrlForDiagnostics(AndroidAuthApiFactory.BASE_URL)} failed=${throwable::class.java.simpleName} durationMs=$duration"
            )
            mapOf(
                    "status" to "ok",
                    "baseUrl" to AndroidAuthApiFactory.BASE_URL,
                    "reachable" to false,
                    "code" to 0,
                    "durationMillis" to duration,
                    "statusLabel" to "Network probe failed",
                    "detail" to detail
            )
        }
    }

    private fun buildFeedbackText(diagnosticsFileName: String): String =
            buildString {
                appendLine("## Slock feedback")
                appendLine()
                appendLine("<!-- Describe what happened here. -->")
                appendLine()
                appendLine("## Diagnostics")
                appendLine()
                appendLine("Sanitized diagnostics are attached as `$diagnosticsFileName`.")
            }

    private fun shareFeedbackWithDiagnosticsFile() {
        val diagnosticsFile = writeDiagnosticsFeedbackFile()
        shareTextWithAttachment(
                subject = "Slock feedback",
                text = buildFeedbackText(diagnosticsFile.name),
                attachment = diagnosticsFile,
                attachmentMimeType = "text/plain"
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
        file.writeText(SanitizedDiagnosticsLogBuffer.exportText(), StandardCharsets.UTF_8)
        return file
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
        Thread {
            val request = JSONObject(params ?: "{}")
            val startedAt = System.currentTimeMillis()
            val result =
                    runCatching {
                        downloadAndOpenAttachment(request)
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
        }.start()
    }

    private fun downloadAndOpenAttachment(request: JSONObject): Map<String, Any> {
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
                    downloadAttachmentToFile(urlText, targetFile)
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
        val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(attachmentUri, openMimeType)
                    clipData = ClipData.newUri(appContext.contentResolver, targetFile.name, attachmentUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        val handlers = appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty()) {
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
            appContext.startActivity(intent)
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

    private fun downloadAttachmentToFile(urlText: String, targetFile: File): String? {
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
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
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
        val normalized = mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }
        if (!normalized.isNullOrBlank()) return normalized
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
    }

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
        val url = "${AndroidAuthApiFactory.BASE_URL.trimEnd('/')}/auth/google/start?returnTo=$returnTo"
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

    private fun authRequest(params: String?, callback: KuiklyRenderCallback?) {
        Thread {
            val request = JSONObject(params ?: "{}")
            val urlText = request.optString("url")
            val method = request.optString("method").ifBlank { "GET" }
            val startedAt = System.currentTimeMillis()
            val result =
                    runCatching {
                        val url = URL(urlText)
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = method
                            connectTimeout = 30_000
                            readTimeout = 30_000
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Content-Type", "application/json")
                            request.optString("authorization").takeIf { it.isNotBlank() }?.let {
                                setRequestProperty("Authorization", it)
                            }
                            request.optString("serverId").takeIf { it.isNotBlank() }?.let {
                                setRequestProperty("X-Server-Id", it)
                            }
                        }
                        val bodyText = request.optString("bodyText")
                        if (bodyText.isNotBlank()) {
                            connection.doOutput = true
                            connection.outputStream.use { output ->
                                output.write(bodyText.toByteArray(StandardCharsets.UTF_8))
                            }
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                        val duration = System.currentTimeMillis() - startedAt
                        SlockDiagnosticsLogger.record(
                                "SlockAuthNetwork",
                                "method=$method url=${safeUrlForDiagnostics(urlText)} code=$code durationMs=$duration"
                        )
                        mapOf(
                                "code" to code,
                                "bodyText" to if (code in 200..299) body else "",
                                "errorBodyText" to if (code in 200..299) "" else body
                        )
                    }.getOrElse { throwable ->
                        val duration = System.currentTimeMillis() - startedAt
                        SlockDiagnosticsLogger.record(
                                "SlockAuthNetwork",
                                "method=$method url=${safeUrlForDiagnostics(urlText)} failed=${throwable::class.java.simpleName} durationMs=$duration"
                        )
                        mapOf(
                                "code" to 0,
                                "bodyText" to "",
                                "errorBodyText" to (throwable.message ?: "Network unavailable"),
                                "message" to (throwable.message ?: "Network unavailable")
                        )
                    }
            callback?.invoke(result)
        }.start()
    }

    private fun safeUrlForDiagnostics(rawUrl: String): String =
            runCatching {
                val url = URL(rawUrl)
                "${url.protocol}://${url.host}${url.path}"
            }.getOrDefault(rawUrl.substringBefore("?").take(160))

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        private const val AUTH_SESSION_KEY = "auth_session"
        private const val SHARED_NOTIFICATION_CHANNEL_ID = "shared-system-notifications-v1"
        private const val UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID = "slock-unread-general-v1"
        private const val UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID = "slock-unread-channel-v1"
        private const val UNREAD_DM_NOTIFICATION_CHANNEL_ID = "slock-unread-dm-v1"
        private const val UNREAD_MENTION_NOTIFICATION_CHANNEL_ID = "slock-unread-mention-v1"
        private const val REQUEST_NOTIFICATIONS_CODE = 4207
        private const val AUTH_DIAGNOSTICS_SOURCE_MARKER = "auth-bootstrap-diagnostics/e36bc138+"
        private const val ATTACHMENT_CACHE_MAX_FILES = 80
        private const val ATTACHMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
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

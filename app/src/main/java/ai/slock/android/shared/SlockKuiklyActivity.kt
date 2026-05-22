package ai.slock.android.shared

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import ai.slock.android.R
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.data.repository.RealtimeRepository
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.ThreadUpdated
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
import com.caverock.androidsvg.SVG
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class SlockKuiklyActivity : ComponentActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var realtimeRepository: RealtimeRepository? = null
    private var realtimeSubscription: RealtimeSubscription? = null
    private var realtimeConnectJob: Job? = null
    private var realtimeServerId: ServerId? = null
    private var realtimeLastSeq: Long = 0L
    private var pendingThreadAttachmentUpload: PendingThreadAttachmentUpload? = null
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
        val container = findViewById<ViewGroup>(R.id.slock_kuikly_container)
        val spec = createPageSpec()
        kuiklyRenderViewDelegator.onAttach(container, "", spec.pageName, spec.pageData.toKuiklyMap())
        startRealtimeBridgeIfNeeded()
    }

    override fun onDestroy() {
        stopRealtimeBridge()
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
        kuiklyRenderViewDelegator.onResume()
        startRealtimeBridgeIfNeeded()
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
                                        uploadThreadAttachments(
                                                channelId = pending.channelId,
                                                uris = uris
                                        )
                                    }
                                    .getOrElse { throwable ->
                                        mapOf(
                                                "code" to 0,
                                                "bodyText" to "",
                                                "errorCode" to "attachment_upload_failed",
                                                "errorBodyText" to (throwable.message ?: "Attachment upload failed")
                                        )
                                    }
                    pending.callback?.invoke(result)
                }
                .start()
    }

    private fun uploadThreadAttachments(channelId: String, uris: List<Uri>): Map<String, Any> {
        val session = runBlocking { EncryptedAuthSessionStore(applicationContext).readSession() }
        val token = session?.accessToken.orEmpty()
        val serverId = session?.currentServerId.orEmpty()
        if (token.isBlank() || serverId.isBlank()) {
            return mapOf(
                    "code" to 401,
                    "bodyText" to "",
                    "errorCode" to "auth_required",
                    "errorBodyText" to "Auth required"
            )
        }
        val files = uris.mapNotNull(::readPickedThreadAttachment)
        if (files.isEmpty()) {
            return mapOf(
                    "code" to 400,
                    "bodyText" to "",
                    "errorCode" to "attachment_read_failed",
                    "errorBodyText" to "No readable attachments"
            )
        }
        val boundary = "SlockBoundary${System.currentTimeMillis()}"
        val url = URL("${KtorAuthRepository.BASE_URL.trimEnd('/')}/attachments/upload")
        val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Server-Id", serverId)
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
        DataOutputStream(connection.outputStream).use { output ->
            output.writeMultipartField(boundary, "channelId", channelId)
            files.forEach { file ->
                output.writeMultipartFile(boundary, file)
            }
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        return mapOf(
                "code" to code,
                "bodyText" to if (code in 200..299) body else "",
                "errorCode" to if (code in 200..299) "" else "attachment_upload_failed",
                "errorBodyText" to if (code in 200..299) "" else body
        )
    }

    private fun readPickedThreadAttachment(uri: Uri): PickedThreadAttachment? {
        val fileName = queryPickedFileName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().use { output ->
                input.copyTo(output)
                output.toByteArray()
            }
        } ?: return null
        return PickedThreadAttachment(
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes
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

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        kuiklyRenderExport.renderViewExport(
            SlockLucideIconRenderView.VIEW_NAME,
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

    private fun startRealtimeBridgeIfNeeded() {
        if (pageName != PAGE_APP_SHELL || realtimeConnectJob != null) return
        val sessionStore = EncryptedAuthSessionStore(applicationContext)
        val repository =
                SessionRealtimeRepository(
                        store = sessionStore,
                        client =
                                SocketIoRealtimeClient(
                                        transport = SocketIoSocketTransport(),
                                        logger = { message ->
                                            SlockDiagnosticsLogger.d("SlockKuiklyRealtime", message)
                                        }
                                ),
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
                        sendRealtimeEventToSharedShell(serverId, event)
                    }
                }
        realtimeConnectJob =
                lifecycleScope.launch {
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
                    realtimeServerId = serverId
                    val result = repository.connect(serverId, realtimeLastSeq)
                    SlockDiagnosticsLogger.d(
                            "SlockKuiklyRealtime",
                            "connect_result serverId=${serverId.value} result=${result::class.simpleName}"
                    )
                }
    }

    private fun stopRealtimeBridge() {
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        realtimeSubscription?.close()
        realtimeSubscription = null
        realtimeRepository?.disconnect()
        realtimeRepository = null
        realtimeServerId = null
    }

    private fun sendRealtimeEventToSharedShell(serverId: ServerId, event: RealtimeEvent) {
        val payload = event.toSharedShellJson().toString()
        kuiklyRenderViewDelegator.sendEvent(
                REALTIME_EVENT_NAME,
                mapOf(
                        "serverId" to serverId.value,
                        "payload" to payload
                )
        )
        SlockDiagnosticsLogger.d(
                "SlockKuiklyRealtime",
                "event_forwarded type=${event::class.simpleName} serverId=${serverId.value}"
        )
    }

    companion object {
        const val PAGE_LOGIN = SlockKuiklyPages.LOGIN
        const val PAGE_REGISTER = SlockKuiklyPages.REGISTER
        const val PAGE_FORGOT_PASSWORD = SlockKuiklyPages.FORGOT_PASSWORD
        const val PAGE_APP_SHELL = SlockKuiklyPages.APP_SHELL
        const val PAGE_THREAD = SlockKuiklyPages.THREAD
        const val PAGE_THREAD_FILES = SlockKuiklyPages.THREAD_FILES
        const val PAGE_THREAD_COMPOSER = SlockKuiklyPages.THREAD_COMPOSER
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

        private const val KEY_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
        private const val REALTIME_EVENT_NAME = "slockRealtimeEvent"

        fun start(
            context: Context,
            pageName: String,
            pageData: JSONObject = JSONObject(),
            clearTask: Boolean = false
        ) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val starter =
                    Intent(context, SlockKuiklyActivity::class.java)
                            .putExtra(KEY_PAGE_NAME, pageName)
                            .putExtra(KEY_PAGE_DATA, SlockKuiklyPageData.fromJson(pageData).text)
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
    }
}

private fun Long.updateFrom(event: RealtimeEvent): Long =
        when (event) {
            is Heartbeat -> maxOf(this, event.seq)
            is SyncResumeResponse -> maxOf(this, event.currentSeq)
            is MessageNew -> maxOf(this, event.message.seq ?: 0L)
            is MessageUpdated -> maxOf(this, event.message.seq ?: 0L)
            else -> this
        }

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
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
)

private fun DataOutputStream.writeMultipartField(boundary: String, name: String, value: String) {
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
    write(value.toByteArray(StandardCharsets.UTF_8))
    writeBytes("\r\n")
}

private fun DataOutputStream.writeMultipartFile(boundary: String, file: PickedThreadAttachment) {
    writeBytes("--$boundary\r\n")
    writeBytes(
            "Content-Disposition: form-data; name=\"files\"; filename=\"${file.fileName.sanitizeMultipartFileName()}\"\r\n"
    )
    writeBytes("Content-Type: ${file.mimeType}\r\n\r\n")
    write(file.bytes)
    writeBytes("\r\n")
}

private fun String.sanitizeMultipartFileName(): String =
        replace("\"", "_").replace("\r", "_").replace("\n", "_")

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

private object SlockKuiklyFontAdapter : IKRFontAdapter {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    override fun getTypeface(fontFamily: String, result: (Typeface?) -> Unit) {
        val fontName = fontFamily.substringBefore(",").trim()
        val fontRes = when (fontName) {
            "space_grotesk_bold" -> R.font.space_grotesk_bold
            "space_grotesk_semibold" -> R.font.space_grotesk_semibold
            "space_grotesk_medium" -> R.font.space_grotesk_medium
            "space_grotesk_regular" -> R.font.space_grotesk_regular
            "space_grotesk_light" -> R.font.space_grotesk_light
            "space_grotesk" -> R.font.space_grotesk
            else -> null
        }
        val typeface = fontRes?.let { resId ->
            context?.let { ResourcesCompat.getFont(it, resId) }
        }
        result(typeface)
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
        private val appContext: Context,
        private val legacyIslandEventsEnabled: Boolean = false
) : KuiklyRenderBaseModule() {
    private val databases = ConcurrentHashMap<String, SQLiteDatabase>()
    private val gson = Gson()

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
            "openUrl" -> openUrl(params)
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
            "settingsCapability" -> {
                settingsCapability(params, callback)
                null
            }
            "filesEvent" -> {
                dispatchLegacyIslandEvent(method = method, params = params)
                null
            }
            "composerEvent" -> {
                dispatchLegacyIslandEvent(method = method, params = params)
                null
            }
            "settingsEvent" -> {
                dispatchLegacyIslandEvent(method = method, params = params)
                null
            }
            "threadEvent" -> {
                dispatchLegacyIslandEvent(method = method, params = params)
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

    private fun dispatchLegacyIslandEvent(method: String, params: String?) {
        val event = JSONObject(params ?: "{}")
        if (!legacyIslandEventsEnabled) {
            SlockDiagnosticsLogger.w(
                    "SlockKuikly",
                    "legacy_island_event_ignored method=$method instanceId=${event.optString("instanceId")} action=${event.optString("action")} itemId=${event.optString("itemId")} attachmentId=${event.optString("attachmentId")}"
            )
            return
        }
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "legacy_island_event_dispatch method=$method instanceId=${event.optString("instanceId")} action=${event.optString("action")} itemId=${event.optString("itemId")} attachmentId=${event.optString("attachmentId")} tab=${event.optString("tab")} valueBytes=${event.optString("value").length}"
        )
        SlockKuiklyEventRegistry.dispatch(event)
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
                        "loadAbout" -> loadSettingsAbout()
                        "loadDiagnostics" -> loadSettingsDiagnostics()
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
                            shareText(
                                    subject = "Slock feedback",
                                    text = buildFeedbackText()
                            )
                            SlockDiagnosticsLogger.record("SlockDiagnostics", "shared_send_feedback")
                            mapOf("status" to "ok")
                        }
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
                "packageName" to appContext.packageName,
                "signedInUserLabel" to (session?.user?.bestDisplayName ?: "Signed out"),
                "selectedServerLabel" to (selectedServer?.name ?: "No selected server"),
                "sessionStatusLabel" to if (session != null) "Signed in" else "Signed out",
                "socketStatusLabel" to "Host active",
                "notificationStatusLabel" to "Managed by system settings",
                "deviceLabel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "platformLabel" to "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}"
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

    private fun buildFeedbackText(): String =
            buildString {
                appendLine("## Slock feedback")
                appendLine()
                appendLine("<!-- Describe what happened here. -->")
                appendLine()
                appendLine("## Diagnostics")
                appendLine()
                append(SanitizedDiagnosticsLogBuffer.exportText())
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

    private fun authRequest(params: String?, callback: KuiklyRenderCallback?) {
        Thread {
            val result =
                    runCatching {
                        val request = JSONObject(params ?: "{}")
                        val url = URL(request.optString("url"))
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = request.optString("method").ifBlank { "GET" }
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
                        mapOf(
                                "code" to code,
                                "bodyText" to if (code in 200..299) body else "",
                                "errorBodyText" to if (code in 200..299) "" else body
                        )
                    }.getOrElse { throwable ->
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

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        private const val AUTH_SESSION_KEY = "auth_session"
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

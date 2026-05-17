package ai.slock.android.shared

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import ai.slock.android.BuildConfig
import ai.slock.android.R
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
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
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.json.JSONObject

class SlockKuiklyActivity : ComponentActivity(), KuiklyRenderViewBaseDelegatorDelegate {

    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)

    private val pageName: String
        get() = intent.getStringExtra(KEY_PAGE_NAME).takeUnless { it.isNullOrBlank() } ?: PAGE_ROUTER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SlockKuiklyAdapters.init(applicationContext)
        setContentView(R.layout.activity_slock_kuikly)
        setupImmersiveMode()
        val container = findViewById<ViewGroup>(R.id.slock_kuikly_container)
        kuiklyRenderViewDelegator.onAttach(container, "", pageName, createPageData())
    }

    override fun onDestroy() {
        super.onDestroy()
        kuiklyRenderViewDelegator.onDetach()
    }

    override fun onPause() {
        super.onPause()
        kuiklyRenderViewDelegator.onPause()
    }

    override fun onResume() {
        super.onResume()
        kuiklyRenderViewDelegator.onResume()
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalModule(kuiklyRenderExport)
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(applicationContext)
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        super.registerExternalRenderView(kuiklyRenderExport)
        kuiklyRenderExport.renderViewExport(
            SlockLucideIconRenderView.VIEW_NAME,
            renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
    }

    private fun createPageData(): Map<String, Any> {
        return argsToMap().apply {
            put("appId", 1)
        }
    }

    private fun argsToMap(): MutableMap<String, Any> {
        val jsonStr = intent.getStringExtra(KEY_PAGE_DATA) ?: return mutableMapOf()
        return JSONObject(jsonStr).toMap().toMutableMap()
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

    companion object {
        const val PAGE_LOGIN = "compose_login"
        const val PAGE_REGISTER = "compose_register"
        const val PAGE_FORGOT_PASSWORD = "compose_forgot_password"
        const val PAGE_THREAD_FILES = "compose_thread_files"
        const val PAGE_THREAD_COMPOSER = "compose_thread_composer"
        const val PAGE_HOME = "compose_home"
        const val PAGE_MEMBERS = "compose_members"
        const val PAGE_SETTINGS_HOME = "compose_settings_home"
        const val PAGE_SETTINGS_ACCOUNT = "compose_settings_account"
        const val PAGE_SETTINGS_COMPUTERS = "compose_settings_computers"
        const val PAGE_SETTINGS_COMPUTER_DETAIL = "compose_settings_computer_detail"
        const val PAGE_SETTINGS_STORAGE = "compose_settings_storage"
        const val PAGE_ICON_GALLERY = "compose_icon_gallery"
        const val PAGE_ROUTER = "router"

        private const val KEY_PAGE_NAME = "pageName"
        private const val KEY_PAGE_DATA = "pageData"

        fun start(context: Context, pageName: String, pageData: JSONObject = JSONObject()) {
            SlockKuiklyAdapters.init(context.applicationContext)
            val starter =
                    Intent(context, SlockKuiklyActivity::class.java)
                            .putExtra(KEY_PAGE_NAME, pageName)
                            .putExtra(KEY_PAGE_DATA, pageData.toString())
            if (context !is Activity) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(starter)
        }
    }
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

private object SlockKuiklyRouterAdapter : IKRRouterAdapter {
    override fun openPage(context: Context, pageName: String, pageData: JSONObject) {
        SlockKuiklyActivity.start(context, pageName, pageData)
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
        if (BuildConfig.DEBUG) {
            throw throwable
        }
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
        private val appContext: Context
) : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "toast" -> toast(params)
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
            "filesEvent" -> {
                val event = JSONObject(params ?: "{}")
                SlockDiagnosticsLogger.d(
                        "SlockKuikly",
                        "bridge_files_event method=filesEvent instanceId=${event.optString("instanceId")} action=${event.optString("action")} attachmentId=${event.optString("attachmentId")}"
                )
                SlockKuiklyEventRegistry.dispatch(event)
                null
            }
            "composerEvent" -> {
                val event = JSONObject(params ?: "{}")
                SlockDiagnosticsLogger.d(
                        "SlockKuikly",
                        "bridge_composer_event method=composerEvent instanceId=${event.optString("instanceId")} action=${event.optString("action")} valueBytes=${event.optString("value").length} asTask=${event.optString("asTask")} attachmentId=${event.optString("attachmentId")}"
                )
                SlockKuiklyEventRegistry.dispatch(event)
                null
            }
            "settingsEvent" -> {
                val event = JSONObject(params ?: "{}")
                SlockDiagnosticsLogger.d(
                        "SlockKuikly",
                        "bridge_settings_event method=settingsEvent instanceId=${event.optString("instanceId")} action=${event.optString("action")} itemId=${event.optString("itemId")}"
                )
                SlockKuiklyEventRegistry.dispatch(event)
                null
            }
            "currentTimestamp" -> System.currentTimeMillis().toString()
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
        }
    }

    private fun toast(params: String?): Any? {
        val content = JSONObject(params ?: "{}").optString("content")
        if (content.isNotBlank()) {
            Toast.makeText(appContext, content, Toast.LENGTH_SHORT).show()
        }
        return null
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}

internal object SlockKuiklyEventRegistry {
    private val handlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    fun register(instanceId: String, handler: (JSONObject) -> Unit) {
        SlockDiagnosticsLogger.d("SlockKuikly", "event_registry_register instanceId=$instanceId")
        handlers[instanceId] = handler
    }

    fun unregister(instanceId: String) {
        SlockDiagnosticsLogger.d("SlockKuikly", "event_registry_unregister instanceId=$instanceId")
        handlers.remove(instanceId)
    }

    fun dispatch(params: JSONObject) {
        val instanceId = params.optString("instanceId")
        val handler = handlers[instanceId]
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "event_registry_dispatch instanceId=$instanceId hasHandler=${handler != null} action=${params.optString("action")} attachmentId=${params.optString("attachmentId")} itemId=${params.optString("itemId")} valueBytes=${params.optString("value").length}"
        )
        handler?.invoke(params)
    }
}

internal class SlockKuiklyIslandHost(
        context: Context,
        private val pageName: String,
        private val pageData: JSONObject
) : FrameLayout(context), KuiklyRenderViewBaseDelegatorDelegate {
    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var disposed = false
    private var lastPageDataText = pageData.toString()

    init {
        SlockKuiklyAdapters.init(context.applicationContext)
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_attach pageName=$pageName instanceId=${pageData.optString("instanceId")} payloadBytes=${pageData.optString("payload").length}"
        )
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams =
                LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                )
        kuiklyRenderViewDelegator.onAttach(
                this,
                "",
                pageName,
                pageData.toMap().toMutableMap().apply { put("appId", 1) }
        )
    }

    fun updatePageData(pageDataText: String) {
        if (disposed || pageDataText == lastPageDataText) {
            return
        }
        lastPageDataText = pageDataText
        val nextPageData = JSONObject(pageDataText)
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_update pageName=$pageName instanceId=${nextPageData.optString("instanceId")} payloadBytes=${nextPageData.optString("payload").length}"
        )
        kuiklyRenderViewDelegator.sendEvent(
                "slockPageData",
                nextPageData.toMap().toMutableMap().apply { put("appId", 1) }
        )
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(context.applicationContext)
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        kuiklyRenderExport.renderViewExport(
                SlockLucideIconRenderView.VIEW_NAME,
                renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kuiklyRenderViewDelegator.onResume()
    }

    override fun onDetachedFromWindow() {
        kuiklyRenderViewDelegator.onPause()
        super.onDetachedFromWindow()
    }

    fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_dispose pageName=$pageName instanceId=${pageData.optString("instanceId")}"
        )
        kuiklyRenderViewDelegator.onDetach()
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

package build.raft.app.shared

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import build.raft.app.cmp.SlockCmpRoot
import build.raft.app.cmp.SlockCmpRouter
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.SlockCmpHost
import build.raft.app.cmp.host.SlockCmpNavigator
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Hosts one Compose Multiplatform page (see `SlockCmpRouter`), the CMP counterpart of
 * [SlockKuiklyActivity]: same `pageName` / `pageData` extras, same launch path
 * ([SlockKuiklyActivity.start] picks this class via [slockKuiklyHostActivityClass]).
 *
 * - Edge to edge: the page draws behind the system bars and pads itself with
 *   `WindowInsets.safeDrawing`; the IME resizes through insets (`adjustResize` in the manifest).
 * - Back: the system back gesture finishes the Activity (no CMP page intercepts back yet);
 *   `SlockCmpNavigator.closePage` does the same.
 * - Host capabilities: the Kuikly bridge's native implementation ([SlockKuiklyBridgeModule]),
 *   driven with the Kuikly wire format by [BridgeWireHostCapabilities].
 */
class SlockCmpActivity : ComponentActivity(), SlockCmpNavigator {
    private val bridge by lazy {
        val activityRef = WeakReference<Activity>(this)
        SlockKuiklyBridgeModule(applicationContext, hostActivity = { activityRef.get() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val pageName = intent?.getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME).orEmpty()
        if (SlockCmpRouter.page(pageName) == null) {
            // Only reachable with a hand-built Intent: start() routes here only for CMP pages.
            SlockAppLog.e(TAG, "cmp_host_unknown_page page=$pageName")
            finish()
            return
        }
        val args =
            SlockCmpPageArgs.fromJsonText(
                pageName,
                SlockKuiklyPageData.fromText(intent?.getStringExtra(SlockKuiklyActivity.KEY_PAGE_DATA)).text,
            )
        val host =
            SlockCmpHost(
                capabilities = BridgeWireHostCapabilities(KuiklyBridgeModuleTransport(bridge)),
                navigator = this,
            )
        SlockAppLog.record(TAG, "cmp_host_open page=$pageName")
        setContent {
            SlockCmpRoot(args, host, onDarkThemeResolved = ::applySystemBarAppearance)
        }
    }

    /** Light theme: dark status/navigation bar icons; dark theme: light icons. */
    private fun applySystemBarAppearance(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.onDestroy()
    }

    override fun openPage(pageName: String, pageData: JsonObject) {
        SlockKuiklyActivity.start(this, pageName, JSONObject(pageData.toSlockJsonString()))
    }

    override fun closePage() {
        finish()
    }

    companion object {
        private const val TAG = "SlockCmpHost"

        /** Intent for [pageName]; normally built by [SlockKuiklyActivity.start]. */
        internal fun intent(context: Context, pageName: String, pageDataText: String): Intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, pageName)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_DATA, pageDataText)
    }
}

/**
 * Drives [SlockKuiklyBridgeModule.call] the way the Kuikly renderer does: on the main thread, with
 * the params as JSON text; callback results (maps, org.json values, strings) go back as JSON text.
 */
internal class KuiklyBridgeModuleTransport(
    private val module: SlockKuiklyBridgeModule,
) : HostBridgeTransport {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        val invoke = {
            module.call(method, paramsJson, callback?.let { deliver -> { result: Any? -> deliver(bridgeResultJson(result)) } })
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) invoke() else mainHandler.post(invoke)
    }
}

internal fun bridgeResultJson(result: Any?): String? =
    when (result) {
        null -> null
        is String -> result
        is JSONObject -> result.toString()
        is JSONArray -> result.toString()
        is Map<*, *> -> JSONObject(result).toString()
        else -> JSONObject.wrap(result)?.toString()
    }

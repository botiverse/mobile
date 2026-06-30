package build.raft.app.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tencent.kuikly.core.render.android.expand.component.KRView
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.ByteArrayInputStream

internal class SlockSandboxWebViewRenderView(context: Context) : KRView(context) {
    private val webView = WebView(context)
    private var src: String = ""
    private var loadedSrc: String = ""
    private var allowedUri: Uri? = null
    private var allowScripts: Boolean = true
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var navigationBlockedCallback: KuiklyRenderCallback? = null

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_SRC -> {
                updateSrc(propValue.toString())
                true
            }
            PROP_ALLOW_SCRIPTS -> {
                updateAllowScripts(propValue.toBooleanValue(default = true))
                true
            }
            EVENT_LOAD_SUCCESS -> {
                loadSuccessCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_LOAD_FAILURE -> {
                loadFailureCallback = propValue as? KuiklyRenderCallback
                true
            }
            EVENT_NAVIGATION_BLOCKED -> {
                navigationBlockedCallback = propValue as? KuiklyRenderCallback
                true
            }
            else -> super.setProp(propKey, propValue)
        }

    override fun resetProp(propKey: String): Boolean =
        when (propKey) {
            PROP_SRC -> {
                updateSrc("")
                true
            }
            PROP_ALLOW_SCRIPTS -> {
                updateAllowScripts(true)
                true
            }
            EVENT_LOAD_SUCCESS -> {
                loadSuccessCallback = null
                true
            }
            EVENT_LOAD_FAILURE -> {
                loadFailureCallback = null
                true
            }
            EVENT_NAVIGATION_BLOCKED -> {
                navigationBlockedCallback = null
                true
            }
            else -> super.resetProp(propKey)
        }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        webView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = allowScripts
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    notifyNavigationBlocked("")
                    return false
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null && !isAllowedMainDocumentUrl(url)) {
                        view?.stopLoading()
                        notifyNavigationBlocked(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && isAllowedMainDocumentUrl(url)) {
                        loadSuccessCallback?.invoke(emptyMap<String, Any>())
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    shouldBlockNavigation(url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    shouldBlockNavigation(request?.url?.toString())

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (request?.isForMainFrame == true && shouldBlockNavigation(url)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val reason =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString()
                            } else {
                                null
                            } ?: "webview_load_failed"
                        notifyLoadFailure(reason)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    notifyLoadFailure(description ?: "webview_load_failed")
                }
            }
        webView.setDownloadListener(
            DownloadListener { url, _, _, _, _ -> notifyNavigationBlocked(url.orEmpty()) }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun updateAllowScripts(next: Boolean) {
        if (allowScripts == next) return
        allowScripts = next
        webView.settings.javaScriptEnabled = next
    }

    private fun updateSrc(next: String) {
        val normalized = next.trim()
        if (src == normalized) return
        src = normalized
        loadedSrc = ""
        allowedUri = normalized.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (normalized.isBlank()) {
            webView.loadUrl("about:blank")
            return
        }
        loadedSrc = normalized
        webView.loadUrl(normalized)
    }

    private fun shouldBlockNavigation(url: String?): Boolean {
        val target = url ?: return true
        if (isAllowedMainDocumentUrl(target)) return false
        notifyNavigationBlocked(target)
        return true
    }

    private fun isAllowedMainDocumentUrl(url: String): Boolean {
        if (url == loadedSrc) return true
        val allowed = allowedUri ?: return false
        val target = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return target.scheme == allowed.scheme &&
            target.host == allowed.host &&
            target.port == allowed.port &&
            target.path == allowed.path &&
            target.encodedQuery == allowed.encodedQuery
    }

    private fun notifyLoadFailure(reason: String) {
        loadFailureCallback?.invoke(mapOf("reason" to reason))
    }

    private fun notifyNavigationBlocked(url: String) {
        navigationBlockedCallback?.invoke(mapOf("url" to url))
    }

    private fun Any.toBooleanValue(default: Boolean): Boolean =
        when (this) {
            is Boolean -> this
            is String -> this.equals("true", ignoreCase = true)
            is Number -> this.toInt() != 0
            else -> default
        }

    companion object {
        const val VIEW_NAME = "SlockSandboxWebView"
        private const val PROP_SRC = "src"
        private const val PROP_ALLOW_SCRIPTS = "allowScripts"
        private const val EVENT_LOAD_SUCCESS = "loadSuccess"
        private const val EVENT_LOAD_FAILURE = "loadFailure"
        private const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
    }
}

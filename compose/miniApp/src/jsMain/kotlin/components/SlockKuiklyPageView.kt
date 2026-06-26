package components

import com.tencent.kuikly.core.render.web.IKuiklyRenderExport
import com.tencent.kuikly.core.render.web.KuiklyRenderView
import com.tencent.kuikly.core.render.web.context.KuiklyRenderCoreExecuteMode
import com.tencent.kuikly.core.render.web.exception.ErrorReason
import com.tencent.kuikly.core.render.web.expand.KuiklyRenderViewDelegatorDelegate
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.ktx.Frame
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.ktx.SizeI
import com.tencent.kuikly.core.render.web.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.web.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import com.tencent.kuikly.core.render.web.runtime.miniapp.MiniDocument
import com.tencent.kuikly.core.render.web.runtime.miniapp.core.Transform
import dom.MiniWebViewElement
import org.w3c.dom.Element

class SlockKuiklyPageView : IKuiklyRenderViewExport, KuiklyRenderViewDelegatorDelegate {
    private var childRenderView: KuiklyRenderView? = null
    private var pageName = ""
    private var pageData = "{}"
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var isMounted = false
    private var viewDidAppear = false
    private var pageDidAppear = false
    private val lazyEvents = mutableListOf<() -> Unit>()
    private val rootElement = MiniDocument.createElement(ElementType.DIV, null).unsafeCast<Element>()

    override val ele: Element
        get() = rootElement

    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            PROP_PAGE_NAME -> {
                updatePageName(propValue.toString())
                true
            }
            PROP_PAGE_DATA -> {
                updatePageData(propValue.toString())
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
            else -> super.setProp(propKey, propValue)
        }
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            METHOD_SEND_EVENT -> {
                sendEventWithParams(params)
                null
            }
            else -> super.call(method, params, callback)
        }

    override fun onAddToParent(parent: Element) {
        super.onAddToParent(parent)
        isMounted = true
        initChildIfReady()
    }

    override fun onRemoveFromParent(parent: Element) {
        destroyChild()
        isMounted = false
        super.onRemoveFromParent(parent)
    }

    override fun onFrameChange(frame: Frame) {
        frameWidth = frame.width.toInt()
        frameHeight = frame.height.toInt()
        initChildIfReady()
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        Transform.addComponentsAlias(
            MiniWebViewElement.NODE_NAME,
            MiniWebViewElement.componentsAlias
        )
        kuiklyRenderExport.renderViewExport(KRWebView.VIEW_NAME, { KRWebView() })
        kuiklyRenderExport.renderViewExport(KRMyView.VIEW_NAME, { KRMyView() })
        kuiklyRenderExport.renderViewExport(VIEW_NAME, { SlockKuiklyPageView() })
    }

    override fun onPageLoadComplete(
        isSucceed: Boolean,
        errorReason: ErrorReason?,
        executeMode: KuiklyRenderCoreExecuteMode
    ) {
        if (isSucceed) {
            loadSuccessCallback?.invoke(emptyMap<String, Any>())
        } else {
            loadFailureCallback?.invoke(mapOf("reason" to (errorReason?.toString() ?: "unknown")))
        }
    }

    override fun onUnhandledException(
        throwable: Throwable,
        errorReason: ErrorReason,
        executeMode: KuiklyRenderCoreExecuteMode
    ) {
        loadFailureCallback?.invoke(
            mapOf(
                "reason" to errorReason.toString(),
                "message" to (throwable.message ?: ""),
            )
        )
    }

    private fun updatePageName(next: String) {
        if (pageName == next) return
        pageName = next
        resetChildIfAttached()
    }

    private fun updatePageData(next: String) {
        if (pageData == next) return
        pageData = next
        resetChildIfAttached()
    }

    private fun resetChildIfAttached() {
        if (childRenderView == null) return
        destroyChild()
        initChildIfReady()
    }

    private fun initChildIfReady() {
        if (childRenderView != null || !isMounted || pageName.isBlank() || frameWidth <= 0 || frameHeight <= 0) {
            return
        }
        childRenderView = KuiklyRenderView(delegate = this).apply {
            init(ele, pageName, childPageData(), SizeI(frameWidth, frameHeight))
        }
        performAllLazyEvents()
    }

    private fun destroyChild() {
        childRenderView?.destroy()
        childRenderView = null
        lazyEvents.clear()
    }

    private fun sendEventWithParams(params: String?) {
        val json = params.toJSONObjectSafely()
        val event = json.optString("event")
        val data = json.optJSONObject("data") ?: JSONObject()
        when (event) {
            EVENT_DID_APPEAR -> {
                viewDidAppear = true
                if (pageDidAppear) {
                    performTaskWhenChildAttached { it.resume() }
                }
            }
            EVENT_DID_DISAPPEAR -> {
                viewDidAppear = false
                if (pageDidAppear) {
                    performTaskWhenChildAttached { it.pause() }
                }
            }
            EVENT_VIEW_DID_APPEAR -> {
                pageDidAppear = true
                if (viewDidAppear) {
                    performTaskWhenChildAttached { it.resume() }
                }
            }
            EVENT_VIEW_DID_DISAPPEAR -> {
                pageDidAppear = false
                if (viewDidAppear) {
                    performTaskWhenChildAttached { it.pause() }
                }
            }
            EVENT_WINDOW_SIZE_DID_CHANGED,
            EVENT_ROOT_VIEW_SIZE_DID_CHANGED,
            EVENT_PAGE_FIRST_FRAME_PAINT,
            EVENT_PAGE_WILL_DESTROY,
            EVENT_SET_NEED_LAYOUT,
            EVENT_ON_BACK_PRESSED -> Unit
            else -> performTaskWhenChildAttached { it.sendEvent(event, data.toMap()) }
        }
    }

    private fun performTaskWhenChildAttached(task: (KuiklyRenderView) -> Unit) {
        val child = childRenderView
        if (child != null) {
            task(child)
        } else {
            lazyEvents.add {
                childRenderView?.let(task)
            }
        }
    }

    private fun performAllLazyEvents() {
        lazyEvents.forEach { it() }
        lazyEvents.clear()
    }

    private fun childPageData(): Map<String, Any> =
        pageData.toJSONObjectSafely().toMap().toMutableMap().apply {
            put("appId", SLOCK_KUIKLY_APP_ID)
        }

    companion object {
        const val VIEW_NAME = "SlockKuiklyPageView"
        private const val PROP_PAGE_NAME = "pageName"
        private const val PROP_PAGE_DATA = "pageData"
        private const val METHOD_SEND_EVENT = "sendEvent"
        private const val EVENT_DID_APPEAR = "didAppear"
        private const val EVENT_DID_DISAPPEAR = "didDisappear"
        private const val EVENT_VIEW_DID_APPEAR = "viewDidAppear"
        private const val EVENT_VIEW_DID_DISAPPEAR = "viewDidDisappear"
        private const val EVENT_WINDOW_SIZE_DID_CHANGED = "windowSizeDidChanged"
        private const val EVENT_ROOT_VIEW_SIZE_DID_CHANGED = "rootViewSizeDidChanged"
        private const val EVENT_PAGE_FIRST_FRAME_PAINT = "pageFirstFramePaint"
        private const val EVENT_PAGE_WILL_DESTROY = "pageWillDestroy"
        private const val EVENT_SET_NEED_LAYOUT = "setNeedLayout"
        private const val EVENT_ON_BACK_PRESSED = "onBackPressed"
        private const val EVENT_LOAD_SUCCESS = "loadSuccess"
        private const val EVENT_LOAD_FAILURE = "loadFailure"
        private const val SLOCK_KUIKLY_APP_ID = 1
    }
}

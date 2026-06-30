package build.raft.app.shared

import android.content.Context
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import com.tencent.kuikly.core.render.android.expand.KuiklyBaseView
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.performace.KRMonitorType
import com.tencent.kuikly.core.render.android.performace.KRPerformanceData
import com.tencent.kuikly.core.render.android.performace.launch.KRLaunchData
import org.json.JSONObject

internal class SlockKuiklyPageRenderView(
    context: Context,
    private val parentDelegate: KuiklyRenderViewBaseDelegatorDelegate
) : FrameLayout(context), IKuiklyRenderViewExport {

    private var childRenderView: SlockKuiklyChildRenderView? = null
    private var pageName: String = ""
    private var pageDataText: String = "{}"
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var viewDidAppear = false
    private var pageDidAppear = false
    private var childDidAttach = false
    private var childVisible = false
    private val lazyChildEvents = mutableListOf<() -> Unit>()

    private val childDelegate =
        object : KuiklyRenderViewBaseDelegatorDelegate {
            override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
                parentDelegate.registerExternalRenderView(kuiklyRenderExport)
            }

            override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
                parentDelegate.registerExternalModule(kuiklyRenderExport)
            }

            override fun registerTDFModule(kuiklyRenderExport: IKuiklyRenderExport) {
                parentDelegate.registerTDFModule(kuiklyRenderExport)
            }

            override fun registerViewExternalPropHandler(kuiklyRenderExport: IKuiklyRenderExport) {
                parentDelegate.registerViewExternalPropHandler(kuiklyRenderExport)
            }

            override fun coreExecuteModeX(): KuiklyRenderCoreExecuteModeBase =
                parentDelegate.coreExecuteModeX()

            override fun performanceMonitorTypes(): List<KRMonitorType> =
                parentDelegate.performanceMonitorTypes()

            override fun onKuiklyRenderViewCreated() {
                parentDelegate.onKuiklyRenderViewCreated()
            }

            override fun onKuiklyRenderContentViewCreated() {
                parentDelegate.onKuiklyRenderContentViewCreated()
            }

            override fun syncRenderingWhenPageAppear(): Boolean =
                parentDelegate.syncRenderingWhenPageAppear()

            override fun enablePreloadClass(): Boolean =
                parentDelegate.enablePreloadClass()

            override fun softInputMode(): Int? =
                parentDelegate.softInputMode()

            override fun onGetLaunchData(data: KRLaunchData) {
                parentDelegate.onGetLaunchData(data)
            }

            override fun onGetPerformanceData(data: KRPerformanceData) {
                parentDelegate.onGetPerformanceData(data)
            }

            override fun onUnhandledException(
                throwable: Throwable,
                errorReason: ErrorReason,
                executeMode: KuiklyRenderCoreExecuteModeBase
            ) {
                parentDelegate.onUnhandledException(throwable, errorReason, executeMode)
            }

            override fun onPageLoadComplete(
                isSucceed: Boolean,
                errorReason: ErrorReason?,
                executeMode: KuiklyRenderCoreExecuteModeBase
            ) {
                if (isSucceed) {
                    loadSuccessCallback?.invoke(emptyMap<String, Any>())
                } else {
                    loadFailureCallback?.invoke(
                        mapOf("reason" to (errorReason?.toString() ?: "unknown"))
                    )
                }
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

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_PAGE_NAME -> {
                updatePageName(propValue.toString())
                true
            }
            PROP_PAGE_DATA -> {
                updatePageData(propValue.toString())
                true
            }
            PROP_GLASS_STYLE -> true
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

    override fun resetProp(propKey: String): Boolean =
        when (propKey) {
            PROP_PAGE_NAME -> {
                updatePageName("")
                true
            }
            PROP_PAGE_DATA -> {
                updatePageData("{}")
                true
            }
            PROP_GLASS_STYLE -> true
            EVENT_LOAD_SUCCESS -> {
                loadSuccessCallback = null
                true
            }
            EVENT_LOAD_FAILURE -> {
                loadFailureCallback = null
                true
            }
            else -> super.resetProp(propKey)
        }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        params?.let {
            childRenderView?.layoutParams = LayoutParams(it.width, it.height)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        initChildIfReady(right - left, bottom - top)
    }

    override fun onDestroy() {
        destroyChild()
        super.onDestroy()
    }

    private fun updatePageName(next: String) {
        if (pageName == next) return
        pageName = next
        resetChildIfAttached()
    }

    private fun updatePageData(next: String) {
        if (pageDataText == next) return
        pageDataText = next
        resetChildIfAttached()
    }

    private fun resetChildIfAttached() {
        if (childRenderView == null) return
        destroyChild()
        requestLayout()
    }

    private fun initChildIfReady(width: Int, height: Int) {
        if (childRenderView != null || pageName.isBlank() || width <= 0 || height <= 0) {
            return
        }
        val child =
            SlockKuiklyChildRenderView(context, childDelegate).also {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        childRenderView = child
        child.onAttach(
            contextCode = "",
            pageName = pageName,
            pageData = childPageDataMap(),
            size = Size(width, height)
        )
        childDidAttach = true
        syncChildVisibility()
        performAllLazyChildEvents()
    }

    private fun destroyChild() {
        val child = childRenderView ?: return
        if (childVisible) {
            child.onPause()
            childVisible = false
        }
        childRenderView = null
        childDidAttach = false
        lazyChildEvents.clear()
        child.onDetach()
        removeView(child)
    }

    private fun sendEventWithParams(params: String?) {
        val eventData = runCatching { JSONObject(params ?: "{}") }.getOrDefault(JSONObject())
        val event = eventData.optString("event")
        val data = eventData.optJSONObject("data")?.toMap().orEmpty()
        when (event) {
            EVENT_DID_APPEAR -> {
                viewDidAppear = true
                syncChildVisibility()
            }
            EVENT_DID_DISAPPEAR -> {
                viewDidAppear = false
                syncChildVisibility()
            }
            EVENT_VIEW_DID_APPEAR -> {
                pageDidAppear = true
                syncChildVisibility()
            }
            EVENT_VIEW_DID_DISAPPEAR -> {
                pageDidAppear = false
                syncChildVisibility()
            }
            EVENT_WINDOW_SIZE_DID_CHANGED,
            EVENT_ROOT_VIEW_SIZE_DID_CHANGED,
            EVENT_PAGE_FIRST_FRAME_PAINT,
            EVENT_PAGE_WILL_DESTROY,
            EVENT_SET_NEED_LAYOUT,
            EVENT_ON_BACK_PRESSED -> Unit
            else -> {
                performTaskWhenChildAttached { childRenderView?.sendEvent(event, data) }
            }
        }
    }

    private fun syncChildVisibility() {
        val shouldBeVisible = viewDidAppear && pageDidAppear && childDidAttach
        if (childVisible == shouldBeVisible) return
        childVisible = shouldBeVisible
        if (shouldBeVisible) {
            childRenderView?.onResume()
        } else {
            childRenderView?.onPause()
        }
    }

    private fun performTaskWhenChildAttached(task: () -> Unit) {
        if (childDidAttach) {
            task()
        } else {
            lazyChildEvents.add(task)
        }
    }

    private fun performAllLazyChildEvents() {
        lazyChildEvents.forEach { it() }
        lazyChildEvents.clear()
    }

    private fun childPageDataMap(): Map<String, Any> =
        runCatching { JSONObject(pageDataText).toMap().toMutableMap() }
            .getOrElse { mutableMapOf() }
            .apply { put("appId", SLOCK_KUIKLY_APP_ID) }

    companion object {
        const val VIEW_NAME = "SlockKuiklyPageView"
        private const val PROP_PAGE_NAME = "pageName"
        private const val PROP_PAGE_DATA = "pageData"
        private const val PROP_GLASS_STYLE = "glassStyle"
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

private class SlockKuiklyChildRenderView(
    context: Context,
    delegate: KuiklyRenderViewBaseDelegatorDelegate
) : KuiklyBaseView(context, delegate)

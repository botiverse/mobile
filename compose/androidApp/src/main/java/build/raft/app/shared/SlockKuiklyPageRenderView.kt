package build.raft.app.shared

import android.content.Context
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import build.raft.app.R
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

    /** Opaque branded handoff while a newly routed child has not painted. */
    private val startupHoldView =
        LayoutInflater.from(context).inflate(R.layout.slock_app_shell_splash_hold, this, false)

    private var childRenderView: SlockKuiklyChildRenderView? = null
    private var pageName: String = ""
    private var pageDataText: String = "{}"
    private var loadSuccessCallback: KuiklyRenderCallback? = null
    private var loadFailureCallback: KuiklyRenderCallback? = null
    private var viewDidAppear = false
    private var pageDidAppear = false
    private var childActive = true
    private var childDidAttach = false
    private var childVisible = false
    private var childInitPosted = false
    private var childLoadFinished = false
    private var lastChildRootWidthPx: Int? = null
    private var lastChildRootHeightPx: Int? = null
    private var destroyed = false
    private val lazyChildEvents = mutableListOf<() -> Unit>()

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.slock_surface_cream))
        addView(startupHoldView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hideStartupHold()
    }

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
                releaseStartupHold()
                parentDelegate.onUnhandledException(throwable, errorReason, executeMode)
            }

            override fun onPageLoadComplete(
                isSucceed: Boolean,
                errorReason: ErrorReason?,
                executeMode: KuiklyRenderCoreExecuteModeBase
            ) {
                // Release before callbacks: a callback may synchronously update page props and
                // start a replacement child, which must be allowed to show its own hold.
                releaseStartupHold()
                (parentDelegate as? SlockKuiklyChildPageLoadListener)
                    ?.onSlockKuiklyChildPageLoadComplete(
                        pageName = pageName,
                        pageDataText = pageDataText,
                        isSucceed = isSucceed
                    )
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
            PROP_ACTIVE -> {
                updateChildActive(propValue as? Boolean ?: propValue.toString().toBooleanStrictOrNull() ?: true)
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
            PROP_ACTIVE -> {
                updateChildActive(true)
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
        scheduleChildInitIfReady()
        notifyChildRootSizeChangedIfNeeded(width, height)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        notifyChildRootSizeChangedIfNeeded(width, height)
    }

    override fun onDestroy() {
        destroyed = true
        childInitPosted = false
        lazyChildEvents.clear()
        destroyChild()
        releaseStartupHold()
        super.onDestroy()
    }

    private fun updatePageName(next: String) {
        if (pageName == next) return
        pageName = next
        invalidateStartupHold()
        resetChildIfAttached()
    }

    private fun updatePageData(next: String) {
        if (pageDataText == next) return
        pageDataText = next
        invalidateStartupHold()
        resetChildIfAttached()
    }

    private fun updateChildActive(next: Boolean) {
        if (childActive == next) return
        childActive = next
        syncChildVisibility()
        if (next && !childLoadFinished) {
            showStartupHold()
            scheduleChildInitIfReady()
        } else if (!next) {
            hideStartupHold()
        }
    }

    private fun resetChildIfAttached() {
        if (childRenderView == null) return
        showStartupHold()
        destroyChild()
        scheduleChildInitIfReady()
        if (isInLayout) {
            post { requestLayout() }
        } else {
            requestLayout()
        }
    }

    private fun scheduleChildInitIfReady() {
        if (!shouldScheduleSlockKuiklyChildInit(destroyed, childRenderView != null, childInitPosted, pageName, width, height)) {
            return
        }
        childInitPosted = true
        post {
            childInitPosted = false
            if (destroyed) {
                return@post
            }
            initChildIfReady(width, height)
        }
    }

    private fun initChildIfReady(width: Int, height: Int) {
        if (destroyed || childRenderView != null || pageName.isBlank() || width <= 0 || height <= 0) {
            return
        }
        val child =
            SlockKuiklyChildRenderView(context, childDelegate).also {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        childRenderView = child
        childLoadFinished = false
        showStartupHold()
        child.onAttach(
            contextCode = "",
            pageName = pageName,
            pageData = childPageDataMap(),
            size = Size(width, height)
        )
        childDidAttach = true
        lastChildRootWidthPx = width
        lastChildRootHeightPx = height
        syncChildVisibility()
        performAllLazyChildEvents()
    }

    private fun showStartupHold() {
        if (!slockKuiklyChildStartupHoldVisible(childActive, childLoadFinished, pageName)) {
            hideStartupHold()
            return
        }
        startupHoldView.visibility = View.VISIBLE
        startupHoldView.isClickable = true
        startupHoldView.isFocusable = true
        startupHoldView.bringToFront()
    }

    private fun hideStartupHold() {
        startupHoldView.visibility = View.GONE
        startupHoldView.isClickable = false
        startupHoldView.isFocusable = false
    }

    private fun invalidateStartupHold() {
        childLoadFinished = false
        showStartupHold()
    }

    private fun releaseStartupHold() {
        childLoadFinished = true
        hideStartupHold()
    }

    private fun destroyChild() {
        val child = childRenderView ?: return
        if (childVisible) {
            child.onPause()
            childVisible = false
        }
        childRenderView = null
        childDidAttach = false
        lastChildRootWidthPx = null
        lastChildRootHeightPx = null
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
            EVENT_ROOT_VIEW_SIZE_DID_CHANGED -> {
                notifyChildRootSizeChangedIfNeeded(width, height)
            }
            EVENT_PAGE_FIRST_FRAME_PAINT,
            EVENT_PAGE_WILL_DESTROY,
            EVENT_SET_NEED_LAYOUT,
            EVENT_ON_BACK_PRESSED -> Unit
            else -> {
                performTaskWhenChildAttached { childRenderView?.sendEvent(event, data) }
            }
        }
    }

    private fun notifyChildRootSizeChangedIfNeeded(widthPx: Int, heightPx: Int) {
        if (!shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = childDidAttach,
                previousWidthPx = lastChildRootWidthPx,
                previousHeightPx = lastChildRootHeightPx,
                nextWidthPx = widthPx,
                nextHeightPx = heightPx
            )
        ) {
            return
        }
        val child = childRenderView ?: return
        lastChildRootWidthPx = widthPx
        lastChildRootHeightPx = heightPx
        child.updateRootViewSize(
            widthPx = widthPx,
            heightPx = heightPx,
            data = slockKuiklyRootViewSizeDidChangedData(
                widthPx = widthPx,
                heightPx = heightPx,
                density = resources.displayMetrics.density
            )
        )
    }

    private fun syncChildVisibility() {
        // Retained hosts are stacked. Hide the entire inactive host, including its
        // opaque background, so it cannot cover the active sibling's content.
        visibility = if (childActive) View.VISIBLE else View.INVISIBLE
        val shouldBeRendered =
            slockKuiklyChildShouldRender(childActive = childActive, childAttached = childDidAttach)
        val shouldBeLifecycleVisible =
            slockKuiklyChildLifecycleVisible(
                childActive = childActive,
                childAttached = childDidAttach,
                viewDidAppear = viewDidAppear,
                pageDidAppear = pageDidAppear
            )
        childRenderView?.visibility = slockKuiklyChildRenderVisibility(shouldBeRendered)
        if (childVisible == shouldBeLifecycleVisible) return
        childVisible = shouldBeLifecycleVisible
        if (shouldBeLifecycleVisible) {
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
        private const val PROP_ACTIVE = "active"
        private const val METHOD_SEND_EVENT = "sendEvent"
        private const val EVENT_DID_APPEAR = "didAppear"
        private const val EVENT_DID_DISAPPEAR = "didDisappear"
        private const val EVENT_VIEW_DID_APPEAR = "viewDidAppear"
        private const val EVENT_VIEW_DID_DISAPPEAR = "viewDidDisappear"
        private const val EVENT_WINDOW_SIZE_DID_CHANGED = "windowSizeDidChanged"
        internal const val EVENT_ROOT_VIEW_SIZE_DID_CHANGED = "rootViewSizeDidChanged"
        private const val EVENT_PAGE_FIRST_FRAME_PAINT = "pageFirstFramePaint"
        private const val EVENT_PAGE_WILL_DESTROY = "pageWillDestroy"
        private const val EVENT_SET_NEED_LAYOUT = "setNeedLayout"
        private const val EVENT_ON_BACK_PRESSED = "onBackPressed"
        private const val EVENT_LOAD_SUCCESS = "loadSuccess"
        private const val EVENT_LOAD_FAILURE = "loadFailure"
        private const val SLOCK_KUIKLY_APP_ID = 1
    }
}

internal interface SlockKuiklyChildPageLoadListener {
    fun onSlockKuiklyChildPageLoadComplete(
        pageName: String,
        pageDataText: String,
        isSucceed: Boolean
    )
}

private class SlockKuiklyChildRenderView(
    context: Context,
    delegate: KuiklyRenderViewBaseDelegatorDelegate
) : KuiklyBaseView(context, delegate) {
    fun updateRootViewSize(widthPx: Int, heightPx: Int, data: Map<String, Any>) {
        getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
        sendEvent(SlockKuiklyPageRenderView.EVENT_ROOT_VIEW_SIZE_DID_CHANGED, data)
    }
}

internal fun shouldNotifySlockKuiklyChildRootSizeChange(
    childAttached: Boolean,
    previousWidthPx: Int?,
    previousHeightPx: Int?,
    nextWidthPx: Int,
    nextHeightPx: Int
): Boolean =
    childAttached &&
        nextWidthPx > 0 &&
        nextHeightPx > 0 &&
        (previousWidthPx != nextWidthPx || previousHeightPx != nextHeightPx)

internal fun slockKuiklyRootViewSizeDidChangedData(
    widthPx: Int,
    heightPx: Int,
    density: Float
): Map<String, Any> {
    val resolvedDensity = density.takeIf { it > 0f } ?: 1f
    return mapOf(
        "width" to widthPx / resolvedDensity,
        "height" to heightPx / resolvedDensity
    )
}

internal fun slockKuiklyChildStartupHoldVisible(
    childActive: Boolean,
    childLoadFinished: Boolean,
    pageName: String,
): Boolean = childActive && !childLoadFinished && pageName.isNotBlank()

internal fun slockKuiklyChildRenderVisibility(visible: Boolean): Int =
    if (visible) View.VISIBLE else View.INVISIBLE

internal fun slockKuiklyChildShouldRender(childActive: Boolean, childAttached: Boolean): Boolean =
    childActive && childAttached

internal fun shouldScheduleSlockKuiklyChildInit(
    destroyed: Boolean,
    childAttached: Boolean,
    childInitPosted: Boolean,
    pageName: String,
    widthPx: Int,
    heightPx: Int
): Boolean =
    !destroyed &&
        !childAttached &&
        !childInitPosted &&
        pageName.isNotBlank() &&
        widthPx > 0 &&
        heightPx > 0

internal fun slockKuiklyChildLifecycleVisible(
    childActive: Boolean,
    childAttached: Boolean,
    viewDidAppear: Boolean,
    pageDidAppear: Boolean
): Boolean =
    slockKuiklyChildShouldRender(childActive = childActive, childAttached = childAttached) &&
        viewDidAppear &&
        pageDidAppear

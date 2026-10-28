package build.raft.app.base

import build.raft.app.RaftEnvironmentIndicatorHost
import build.raft.app.RAFT_EMBEDDED_CHILD_PAGE_PARAM
import build.raft.app.RaftSearchTabHandoff
import build.raft.app.RaftSearchTabHandoffAction
import build.raft.app.RaftSearchTabHandoffRole
import build.raft.app.RaftSearchTabQueryRequest
import build.raft.app.compat.module.RouterModule
import build.raft.app.raftEnvironmentIndicatorEnabledForPage
import build.raft.app.raftLogError
import build.raft.app.raftLogInfo
import build.raft.app.toJsonObject
import build.raft.app.RaftAdaptivePanePagerEvent
import build.raft.app.RaftSideColumnPosture
import build.raft.app.RaftSideColumnScaffold
import build.raft.app.toRaftSideColumnPosture
import build.raft.app.RAFT_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT
import build.raft.app.data.RaftEnhancedNotifyCenter
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftProductPageView
import build.raft.app.data.RaftStoreThreading
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.compat.pager.ComposeContainer
import build.raft.app.compat.module.Module
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator

internal abstract class BasePager : ComposeContainer() {
    private var nightModel: Boolean? = null
    protected var raftPageVisibleResumeTick by mutableStateOf(0)
        private set
    protected var raftPageVisible by mutableStateOf(false)
        private set
    private var raftHasAppeared = false

    /**
     * iPhone Duo's side column (raft-design #859), from the host's adaptive-pane payload. Only a
     * page with its own window places it ([RaftSideColumnScaffold]); an embedded child page sits
     * inside its host's and inherits it.
     */
    private var raftSideColumnPosture by mutableStateOf(RaftSideColumnPosture.None)

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        return externalModules
    }

    override fun willInit() {
        registerRaftExternalModuleCreators()
        super.willInit()
        configureStoreThreadVerification()
    }

    /**
     * Every Slock Compose page enters through this member rather than Kuikly's extension directly.
     * Member functions win over extensions at each page's existing `setContent { ... }` callsite,
     * which keeps the non-production environment indicator above auth and product surfaces alike.
     */
    override fun setContent(content: @Composable () -> Unit) {
        val embedded = pageData.params.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false)
        super.setContent {
            RaftEnvironmentIndicatorHost(
                enabled = raftEnvironmentIndicatorEnabledForPage(pageData.params),
            ) {
                if (embedded) {
                    content()
                } else {
                    RaftSideColumnScaffold(posture = raftSideColumnPosture, content = content)
                }
            }
        }
    }

    private fun registerRaftExternalModuleCreators() {
        registerModule(BridgeModule.MODULE_NAME, object : IModuleCreator {
            override fun createModule(): Module = BridgeModule()
        })
    }

    override fun created() {
        super.created()
        isNightMode()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        reportProductPageShown()
        raftPageVisible = true
        val initialAppearance = !raftHasAppeared
        raftHasAppeared = true
        if (!initialAppearance) {
            raftPageVisibleResumeTick += 1
        }
        onRaftPageDidAppear(isInitialAppearance = initialAppearance)
        handleSearchTabHandoff(initialAppearance)
    }

    /**
     * Nav spec §10.5 "Search all": while a handoff is pending, a page stacked over the app shell
     * that reappears closes itself, so the stack pops back to the shell; the shell consumes it
     * ([raftSearchTabHandoffRole]). Embedded child pages never act.
     */
    protected open val raftSearchTabHandoffRole: RaftSearchTabHandoffRole
        get() =
            if (pageData.params.optBoolean(RAFT_EMBEDDED_CHILD_PAGE_PARAM, false)) {
                RaftSearchTabHandoffRole.Ignore
            } else {
                RaftSearchTabHandoffRole.PopToShell
            }

    /** The shell's handoff consumer (it reads the request when it shows again). */
    protected open fun onRaftSearchTabHandoff(request: RaftSearchTabQueryRequest) = Unit

    private fun handleSearchTabHandoff(initialAppearance: Boolean) {
        when (RaftSearchTabHandoff.onPageAppeared(raftSearchTabHandoffRole, initialAppearance)) {
            RaftSearchTabHandoffAction.ClosePage ->
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            RaftSearchTabHandoffAction.Consume ->
                RaftSearchTabHandoff.consume()?.let(::onRaftSearchTabHandoff)
            RaftSearchTabHandoffAction.None -> Unit
        }
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        raftPageVisible = false
        runCatching { RaftProductEvents.pageViews.onPageHidden(pagerId) }
        onRaftPageDidDisappear()
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        runCatching { RaftProductEvents.pageViews.onPageClosed(pagerId) }
    }

    /**
     * This page's `page_viewed` identity (product analytics), or null when it is not a product
     * page. Read once, on the first appearance; identity only, never content.
     */
    protected open fun productPageView(): RaftProductPageView? = null

    /**
     * The place this page shows, stable when the same page is rebuilt (a new pager for the same
     * place), or null when every new pager is a new visit. See [build.raft.app.data.RaftPageViewTracker].
     */
    protected open fun productPageVisitKey(): String? = null

    private var productPageViewResolved = false

    private fun reportProductPageShown() {
        runCatching {
            val pageViews = RaftProductEvents.pageViews
            if (!productPageViewResolved) {
                productPageViewResolved = true
                productPageView()?.let { pageViews.onPageRoute(pagerId, it, productPageVisitKey()) }
            }
            pageViews.onPageShown(pagerId)
        }
    }

    protected open fun onRaftPageDidAppear(isInitialAppearance: Boolean) = Unit

    protected open fun onRaftPageDidDisappear() = Unit

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
    }

    override fun onReceivePagerEvent(pagerEvent: String, eventData: JSONObject) {
        super.onReceivePagerEvent(pagerEvent, eventData)
        if (pagerEvent == RaftAdaptivePanePagerEvent) {
            raftSideColumnPosture = eventData.toRaftSideColumnPosture()
            onRaftAdaptivePaneChanged(eventData)
        }
        if (pagerEvent == RAFT_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT) {
            // task #18 wiring gap (Jony's v12 QA): iOS delivers the commit receipt via the target
            // pager's sendWithEvent, not the shared eventBus — forward it in-process so the
            // shell's settle subscription sees it. Token/authority checks stay in the settle path.
            RaftEnhancedNotifyCenter.post(
                eventName = RAFT_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT,
                eventData = eventData.toJsonObject(),
                crossProcess = true,
                source = "pager_commit_result_forward",
            )
        }
    }

    /** Host window facts are not route data. Pages opt in when they own a responsive surface. */
    protected open fun onRaftAdaptivePaneChanged(eventData: JSONObject) = Unit

    // 是否为夜间模式
    override fun isNightMode(): Boolean {
        if (nightModel == null) {
            nightModel = pageData.params.optBoolean(IS_NIGHT_MODE_KEY)
        }
        return nightModel!!
    }

    // 不开启调试UI模式
    override fun debugUIInspector(): Boolean {
        return false
    }

    /**
     * Kuikly's renderer thread verification is gone with Kuikly; the store-dispatcher violation
     * report it switched on stays behind the same pageData flag.
     */
    private fun configureStoreThreadVerification() {
        if (!pageData.params.optBoolean(KEY_VERIFY_THREAD, false)) return
        // Debug thread verification also reports process-wide store writes made outside the
        // store dispatcher (logged, not thrown: see RaftStoreThreading for the known gaps).
        RaftStoreThreading.installViolationHandler { violation -> raftLogError(TAG_VERIFY, violation.toString()) }
        raftLogInfo(TAG_VERIFY, "store_verify_enabled")
    }

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        const val KEY_VERIFY_THREAD = "slockKuiklyVerifyThread"
        const val KEY_VERIFY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val TAG_VERIFY = "SlockKuiklyVerify"
    }

}

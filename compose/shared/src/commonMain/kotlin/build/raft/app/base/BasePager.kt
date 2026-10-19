package build.raft.app.base

import build.raft.app.SlockEnvironmentIndicatorHost
import build.raft.app.slockEnvironmentIndicatorEnabledForPage
import build.raft.app.slockLogError
import build.raft.app.slockLogInfo
import build.raft.app.toJsonObject
import build.raft.app.SlockAdaptivePanePagerEvent
import build.raft.app.SLOCK_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT
import build.raft.app.data.SlockEnhancedNotifyCenter
import build.raft.app.data.SlockStoreThreading
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
    protected var slockPageVisibleResumeTick by mutableStateOf(0)
        private set
    protected var slockPageVisible by mutableStateOf(false)
        private set
    private var slockHasAppeared = false

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        return externalModules
    }

    override fun willInit() {
        registerSlockExternalModuleCreators()
        super.willInit()
        configureStoreThreadVerification()
    }

    /**
     * Every Slock Compose page enters through this member rather than Kuikly's extension directly.
     * Member functions win over extensions at each page's existing `setContent { ... }` callsite,
     * which keeps the non-production environment indicator above auth and product surfaces alike.
     */
    override fun setContent(content: @Composable () -> Unit) {
        super.setContent {
            SlockEnvironmentIndicatorHost(
                enabled = slockEnvironmentIndicatorEnabledForPage(pageData.params),
                content = content,
            )
        }
    }

    private fun registerSlockExternalModuleCreators() {
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
        slockPageVisible = true
        val initialAppearance = !slockHasAppeared
        slockHasAppeared = true
        if (!initialAppearance) {
            slockPageVisibleResumeTick += 1
        }
        onSlockPageDidAppear(isInitialAppearance = initialAppearance)
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        slockPageVisible = false
        onSlockPageDidDisappear()
    }

    protected open fun onSlockPageDidAppear(isInitialAppearance: Boolean) = Unit

    protected open fun onSlockPageDidDisappear() = Unit

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
    }

    override fun onReceivePagerEvent(pagerEvent: String, eventData: JSONObject) {
        super.onReceivePagerEvent(pagerEvent, eventData)
        if (pagerEvent == SlockAdaptivePanePagerEvent) {
            onSlockAdaptivePaneChanged(eventData)
        }
        if (pagerEvent == SLOCK_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT) {
            // task #18 wiring gap (Jony's v12 QA): iOS delivers the commit receipt via the target
            // pager's sendWithEvent, not the shared eventBus — forward it in-process so the
            // shell's settle subscription sees it. Token/authority checks stay in the settle path.
            SlockEnhancedNotifyCenter.post(
                eventName = SLOCK_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT,
                eventData = eventData.toJsonObject(),
                crossProcess = true,
                source = "pager_commit_result_forward",
            )
        }
    }

    /** Host window facts are not route data. Pages opt in when they own a responsive surface. */
    protected open fun onSlockAdaptivePaneChanged(eventData: JSONObject) = Unit

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
        // store dispatcher (logged, not thrown: see SlockStoreThreading for the known gaps).
        SlockStoreThreading.installViolationHandler { violation -> slockLogError(TAG_VERIFY, violation.toString()) }
        slockLogInfo(TAG_VERIFY, "store_verify_enabled")
    }

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        const val KEY_VERIFY_THREAD = "slockKuiklyVerifyThread"
        const val KEY_VERIFY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val TAG_VERIFY = "SlockKuiklyVerify"
    }

}

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
import build.raft.app.slockPlatformConsumesSnapshotsFromKuikly
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.setContent as setKuiklyContent
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IModuleCreator
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.*

internal abstract class BasePager : ComposeContainer() {
    private var nightModel: Boolean? by observable(null)
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
        configureSlockComposeSnapshotConsumption()
        registerSlockExternalModuleCreators()
        super.willInit()
        configureKuiklyThreadVerification()
    }

    /**
     * Every Slock Compose page enters through this member rather than Kuikly's extension directly.
     * Member functions win over extensions at each page's existing `setContent { ... }` callsite,
     * which keeps the non-production environment indicator above auth and product surfaces alike.
     */
    protected fun setContent(content: @Composable () -> Unit) {
        setKuiklyContent {
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

    private fun configureKuiklyThreadVerification() {
        val params = pageData.params
        val verifyThread = params.optBoolean(KEY_VERIFY_THREAD, false)
        val verifyReactiveObserver =
            params.optBoolean(KEY_VERIFY_REACTIVE_OBSERVER, verifyThread)

        Pager.VERIFY_THREAD = verifyThread
        Pager.VERIFY_REACTIVE_OBSERVER = verifyReactiveObserver

        if (!verifyThread && !verifyReactiveObserver) {
            return
        }

        Pager.verifyFailed { exception ->
            slockLogError(
                TAG_VERIFY,
                "kuikly_verify_failed verifyThread=$verifyThread " +
                    "verifyReactiveObserver=$verifyReactiveObserver " +
                    "type=${exception::class.simpleName} message=${exception.message.orEmpty()}"
            )
            throw exception
        }
        if (verifyThread) {
            // Debug thread verification also reports process-wide store writes made outside the
            // store dispatcher (logged, not thrown: see SlockStoreThreading for the known gaps).
            SlockStoreThreading.installViolationHandler { violation -> slockLogError(TAG_VERIFY, violation.toString()) }
        }
        slockLogInfo(
            TAG_VERIFY,
            "kuikly_verify_enabled verifyThread=$verifyThread " +
                "verifyReactiveObserver=$verifyReactiveObserver"
        )
    }

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        const val KEY_VERIFY_THREAD = "slockKuiklyVerifyThread"
        const val KEY_VERIFY_REACTIVE_OBSERVER = "slockKuiklyVerifyReactiveObserver"
        private const val TAG_VERIFY = "SlockKuiklyVerify"
    }

}

internal fun configureSlockComposeSnapshotConsumption() {
    ComposeContainer.enableConsumeSnapshot = slockPlatformConsumesSnapshotsFromKuikly()
}

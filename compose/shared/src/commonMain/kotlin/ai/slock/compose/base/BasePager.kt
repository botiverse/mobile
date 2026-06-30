package ai.slock.compose.base

import ai.slock.compose.slockLogError
import ai.slock.compose.slockLogInfo
import ai.slock.compose.data.KuiklyMMKVModule
import ai.slock.compose.network.SlockNetworkModule
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IModuleCreator
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.*

internal abstract class BasePager : ComposeContainer() {
    private var nightModel: Boolean? by observable(null)

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        externalModules[KuiklyMMKVModule.MODULE_NAME] = KuiklyMMKVModule()
        externalModules[SlockNetworkModule.MODULE_NAME] = SlockNetworkModule()
        externalModules[NotifyModule.MODULE_NAME] = NotifyModule()
        return externalModules
    }

    override fun willInit() {
        configureSlockComposeSnapshotConsumption()
        registerSlockExternalModuleCreators()
        super.willInit()
        configureKuiklyThreadVerification()
    }

    private fun configureSlockComposeSnapshotConsumption() {
        // iOS also needs snapshot consumption so async state transitions
        // render without waiting for the next touch event.
        ComposeContainer.enableConsumeSnapshot = true
    }

    private fun registerSlockExternalModuleCreators() {
        registerModule(BridgeModule.MODULE_NAME, object : IModuleCreator {
            override fun createModule(): Module = BridgeModule()
        })
        registerModule(KuiklyMMKVModule.MODULE_NAME, object : IModuleCreator {
            override fun createModule(): Module = KuiklyMMKVModule()
        })
        registerModule(SlockNetworkModule.MODULE_NAME, object : IModuleCreator {
            override fun createModule(): Module = SlockNetworkModule()
        })
        registerModule(NotifyModule.MODULE_NAME, object : IModuleCreator {
            override fun createModule(): Module = NotifyModule()
        })
    }

    override fun created() {
        super.created()
        isNightMode()
    }

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
    }

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

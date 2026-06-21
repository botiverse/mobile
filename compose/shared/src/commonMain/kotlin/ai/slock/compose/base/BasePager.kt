package ai.slock.compose.base

import ai.slock.compose.slockLogError
import ai.slock.compose.slockLogInfo
import ai.slock.compose.network.SlockNetworkModule
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.*

internal abstract class BasePager : ComposeContainer() {
    private var nightModel: Boolean? by observable(null)

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        externalModules[SlockNetworkModule.MODULE_NAME] = SlockNetworkModule()
        return externalModules
    }

    override fun willInit() {
        super.willInit()
        configureKuiklyThreadVerification()
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

package build.raft.app.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.pager.Pager

const val SLOCK_APP_UPDATE_EVENT = "slock.appUpdateStatus"

class KuiklyAppUpdateEvents(
    private val pager: Pager
) {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addStatusListener(onChanged: () -> Unit): CallbackRef =
        module.addNotify(SLOCK_APP_UPDATE_EVENT) {
            onChanged()
        }

    fun removeStatusListener(callbackRef: CallbackRef) {
        module.removeNotify(SLOCK_APP_UPDATE_EVENT, callbackRef)
    }
}

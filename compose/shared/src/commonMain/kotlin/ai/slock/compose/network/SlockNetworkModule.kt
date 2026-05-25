package ai.slock.compose.network

import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class SlockNetworkModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    suspend fun request(command: JSONObject): SlockNativeNetworkResponse =
        suspendCancellableCoroutine { continuation ->
            val callback: CallbackFn =
                { response: JSONObject? ->
                    if (continuation.isActive) {
                        continuation.resume(response.toSlockNativeNetworkResponse())
                    }
                }
            toNative(
                false,
                METHOD_REQUEST,
                command.toString(),
                callback,
                false
            )
        }

    private fun JSONObject?.toSlockNativeNetworkResponse(): SlockNativeNetworkResponse =
        SlockNativeNetworkResponse(
            code = this?.optInt("code") ?: 0,
            bodyText = this?.optString("bodyText").orEmpty(),
            errorBodyText = this?.optString("errorBodyText").orEmpty(),
            message = this?.optString("message").orEmpty()
        )

    companion object {
        const val MODULE_NAME = "HRSlockNetworkModule"
        private const val METHOD_REQUEST = "request"
    }
}

data class SlockNativeNetworkResponse(
    val code: Int,
    val bodyText: String,
    val errorBodyText: String,
    val message: String
)

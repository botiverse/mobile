package build.raft.app.network

import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class SlockNetworkModule : Module() {
    private var nextRequestOrdinal = 0L

    override fun moduleName(): String = MODULE_NAME

    suspend fun request(command: JSONObject): SlockNativeNetworkResponse =
        suspendCancellableCoroutine { continuation ->
            val requestId = nextRequestId()
            command.put("requestId", requestId)
            val callback: CallbackFn =
                { response: JSONObject? ->
                    if (continuation.isActive) {
                        continuation.resume(response.toSlockNativeNetworkResponse())
                    }
                }
            continuation.invokeOnCancellation {
                val cancelCommand = JSONObject()
                cancelCommand.put("requestId", requestId)
                toNative(
                    false,
                    METHOD_CANCEL,
                    cancelCommand.toString(),
                    null,
                    false
                )
            }
            toNative(
                false,
                METHOD_REQUEST,
                command.toString(),
                callback,
                false
            )
        }

    suspend fun readPlatformFile(fileRef: String): SlockNativeFileReadResponse =
        suspendCancellableCoroutine { continuation ->
            val requestId = nextRequestId()
            val command =
                JSONObject().apply {
                    put("requestId", requestId)
                    put("fileRef", fileRef)
                }
            val callback: CallbackFn =
                { response: JSONObject? ->
                    if (continuation.isActive) {
                        continuation.resume(response.toSlockNativeFileReadResponse())
                    }
                }
            continuation.invokeOnCancellation {
                val cancelCommand = JSONObject()
                cancelCommand.put("requestId", requestId)
                toNative(
                    false,
                    METHOD_CANCEL,
                    cancelCommand.toString(),
                    null,
                    false
                )
            }
            toNative(
                false,
                METHOD_READ_PLATFORM_FILE,
                command.toString(),
                callback,
                false
            )
        }

    private fun nextRequestId(): String =
        "request-${nextRequestOrdinal++}"

    private fun JSONObject?.toSlockNativeNetworkResponse(): SlockNativeNetworkResponse =
        SlockNativeNetworkResponse(
            code = this?.optInt("code") ?: 0,
            bodyText = this?.optString("bodyText").orEmpty(),
            errorBodyText = this?.optString("errorBodyText").orEmpty(),
            message = this?.optString("message").orEmpty()
        )

    private fun JSONObject?.toSlockNativeFileReadResponse(): SlockNativeFileReadResponse =
        SlockNativeFileReadResponse(
            code = this?.optInt("code") ?: 0,
            bodyBase64 = this?.optString("bodyBase64").orEmpty(),
            sizeBytes = this?.optLong("sizeBytes", -1L)?.takeIf { it >= 0L },
            errorCode = this?.optString("errorCode").orEmpty(),
            message = this?.optString("message").orEmpty()
        )

    companion object {
        const val MODULE_NAME = "HRSlockNetworkModule"
        private const val METHOD_REQUEST = "request"
        private const val METHOD_READ_PLATFORM_FILE = "readPlatformFile"
        private const val METHOD_CANCEL = "cancel"
    }
}

data class SlockNativeNetworkResponse(
    val code: Int,
    val bodyText: String,
    val errorBodyText: String,
    val message: String
)

data class SlockNativeFileReadResponse(
    val code: Int,
    val bodyBase64: String,
    val sizeBytes: Long?,
    val errorCode: String,
    val message: String
)

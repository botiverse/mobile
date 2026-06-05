package ai.slock.android.shared

import android.util.Base64
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class SlockKuiklyNetworkModule : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_REQUEST -> {
                request(params, callback)
                null
            }
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
        }
    }

    private fun request(params: String?, callback: KuiklyRenderCallback?) {
        Thread {
            val result =
                    runCatching {
                                val command = JSONObject(params ?: "{}")
                                val headers = command.optJSONObject("headers") ?: JSONObject()
                                val method = command.optString("method", "GET").uppercase()
                                val request =
                                        Request.Builder()
                                                .url(command.optString("url"))
                                                .applyHeaders(headers)
                                                .method(method, command.requestBody(method, headers))
                                                .build()
                                CLIENT.newCall(request).execute().use { response ->
                                    val body = response.body?.string().orEmpty()
                                    mapOf(
                                            "code" to response.code,
                                            "bodyText" to if (response.isSuccessful) body else "",
                                            "errorBodyText" to if (response.isSuccessful) "" else body
                                    )
                                }
                            }
                            .getOrElse { throwable ->
                                mapOf(
                                        "code" to 0,
                                        "bodyText" to "",
                                        "errorBodyText" to (throwable.message ?: "Network unavailable"),
                                        "message" to (throwable.message ?: "Network unavailable")
                                )
                            }
            callback?.invoke(result)
        }.start()
    }

    private fun Request.Builder.applyHeaders(headers: JSONObject): Request.Builder {
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                header(key, value)
            }
        }
        return this
    }

    private fun JSONObject.requestBody(method: String, headers: JSONObject): RequestBody? {
        val mediaType = headers.optString("Content-Type").takeIf { it.isNotBlank() }?.toMediaTypeOrNull()
        if (has("bodyBase64")) {
            return Base64.decode(optString("bodyBase64"), Base64.DEFAULT).toRequestBody(mediaType)
        }
        if (has("bodyText")) {
            return optString("bodyText").toRequestBody(mediaType)
        }
        return if (method in METHODS_REQUIRING_BODY) {
            ByteArray(0).toRequestBody(mediaType)
        } else {
            null
        }
    }

    companion object {
        const val MODULE_NAME = "HRSlockNetworkModule"
        private const val METHOD_REQUEST = "request"
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
        private val CLIENT =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()
    }
}

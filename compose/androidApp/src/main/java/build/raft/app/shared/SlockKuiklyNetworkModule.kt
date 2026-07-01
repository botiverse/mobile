package build.raft.app.shared

import android.net.Uri
import android.util.Base64
import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.diagnostics.SlockDiagnosticsLogger
import build.raft.app.network.HttpClientConfig
import build.raft.app.network.SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
import build.raft.app.network.TimeoutConfig
import build.raft.app.network.createPriorityHttpClient
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class SlockKuiklyNetworkModule : KuiklyRenderBaseModule() {
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_REQUEST -> {
                request(params, callback)
                null
            }
            METHOD_CANCEL -> {
                cancel(params)
                null
            }
            else -> {
                callback?.invoke(mapOf("code" to -1, "message" to "Method not implemented"))
                null
            }
        }
    }

    override fun onDestroy() {
        activeRequests.values.forEach { active ->
            active.cancelled.set(true)
            active.job?.cancel()
        }
        activeRequests.clear()
        networkScope.cancel()
        super.onDestroy()
    }

    private fun request(params: String?, callback: KuiklyRenderCallback?) {
        val command = JSONObject(params ?: "{}")
        val requestId = command.optString("requestId").takeIf { it.isNotBlank() }
                ?: "android-${System.nanoTime()}"
        val active = ActiveNetworkRequest()
        activeRequests[requestId] = active
        val job =
                networkScope.launch {
                    SlockDiagnosticsLogger.record(
                            "SlockKuiklyNetwork",
                            "request_start requestId=$requestId thread=${Thread.currentThread().name}"
                    )
                    val result =
                            runCatching {
                                        executeRequest(command = command, active = active)
                                    }
                                    .getOrElse { throwable ->
                                        mapOf(
                                                "code" to 0,
                                                "bodyText" to "",
                                                "errorBodyText" to (throwable.message ?: "Network unavailable"),
                                                "message" to (throwable.message ?: "Network unavailable")
                                        )
                                    }
                    try {
                        if (active.cancelled.get()) {
                            SlockDiagnosticsLogger.record(
                                    "SlockKuiklyNetwork",
                                    "request_callback_drop requestId=$requestId reason=cancelled thread=${Thread.currentThread().name}"
                            )
                        } else {
                            SlockDiagnosticsLogger.record(
                                    "SlockKuiklyNetwork",
                                    "request_callback requestId=$requestId thread=${Thread.currentThread().name}"
                            )
                            callback?.invoke(result)
                        }
                    } finally {
                        activeRequests.remove(requestId, active)
                    }
                }
        active.job = job
        if (active.cancelled.get()) {
            job.cancel()
        }
    }

    private suspend fun executeRequest(command: JSONObject, active: ActiveNetworkRequest): Map<String, Any> {
        val headers = command.optJSONObject("headers") ?: JSONObject()
        val method = command.optString("method", "GET").uppercase()
        if (active.cancelled.get()) {
            throw RequestCancelledException()
        }
        val response =
                CLIENT.request(rewriteEnvironmentUrl(command.optString("url"))) {
                    this.method = HttpMethod(method)
                    applyHeaders(headers, skipContentType = command.has("multipartFiles"))
                    applyBody(command, method, activity, active)
                }
        val body = response.bodyAsText()
        return mapOf(
                "code" to response.status.value,
                "bodyText" to if (response.status.value in 200..299) body else "",
                "errorBodyText" to if (response.status.value in 200..299) "" else body
        )
    }

    private fun rewriteEnvironmentUrl(url: String): String {
        val hostActivity = activity ?: return url
        return AndroidApiServerEnvironmentStore(hostActivity.applicationContext).rewriteApiUrl(url)
    }

    private fun cancel(params: String?) {
        val requestId = JSONObject(params ?: "{}").optString("requestId").takeIf { it.isNotBlank() } ?: return
        val active = activeRequests.remove(requestId) ?: return
        active.cancelled.set(true)
        active.job?.cancel()
        SlockDiagnosticsLogger.record(
                "SlockKuiklyNetwork",
                "request_cancel requestId=$requestId thread=${Thread.currentThread().name}"
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(headers: JSONObject, skipContentType: Boolean) {
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.optString(key)
            if (skipContentType && key.equals("Content-Type", ignoreCase = true)) {
                continue
            }
            if (key.isNotBlank() && value.isNotBlank()) {
                header(key, value)
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyBody(
            command: JSONObject,
            method: String,
            activity: android.app.Activity?,
            active: ActiveNetworkRequest
    ) {
        if (command.has("multipartFiles")) {
            setBody(MultiPartFormDataContent(command.multipartFormData(activity, active)))
            return
        }
        if (command.has("bodyBase64")) {
            setBody(Base64.decode(command.optString("bodyBase64"), Base64.DEFAULT))
            return
        }
        if (command.has("bodyFileRef")) {
            val sizeBytes = command.optLong("bodySizeBytes", -1L).takeIf { it > 0L }
            sizeBytes?.let { header(HttpHeaders.ContentLength, it.toString()) }
            setBody(openFileRefStream(activity, command.optString("bodyFileRef"), active))
            return
        }
        if (command.has("bodyText")) {
            setBody(command.optString("bodyText"))
            return
        }
        if (method in METHODS_REQUIRING_BODY) {
            setBody(ByteArray(0))
        }
    }

    private fun JSONObject.multipartFormData(
            activity: android.app.Activity?,
            active: ActiveNetworkRequest
    ): List<io.ktor.http.content.PartData> {
        val files = optJSONArray("multipartFiles") ?: org.json.JSONArray()
        val fields = optJSONObject("multipartFields")
        var addedFiles = 0
        val parts =
                formData {
                    fields?.keys()?.let { keys ->
                        while (keys.hasNext()) {
                            val key = keys.next()
                            append(key, fields.optString(key))
                        }
                    }
                    for (index in 0 until files.length()) {
                        val file = files.optJSONObject(index) ?: continue
                        val fieldName = file.optString("name", "files")
                        val fileName = file.optString("fileName", "attachment")
                        val mimeType = file.optString("mimeType", "application/octet-stream")
                        val partHeaders = multipartFileHeaders(fieldName, fileName, mimeType)
                        if (file.has("bodyBase64")) {
                            val bytes = Base64.decode(file.optString("bodyBase64"), Base64.DEFAULT)
                            if (bytes.isEmpty()) {
                                continue
                            }
                            append(fieldName, bytes, partHeaders)
                        } else if (file.has("fileRef")) {
                            val sizeBytes = file.optLong("sizeBytes", -1L).takeIf { it > 0L }
                            append(
                                    fieldName,
                                    ChannelProvider(sizeBytes) {
                                        openFileRefStream(activity, file.optString("fileRef"), active).toByteReadChannel()
                                    },
                                    partHeaders
                            )
                        } else {
                            continue
                        }
                        addedFiles += 1
                    }
                }
        if (files.length() > 0 && addedFiles == 0) {
            throw IOException("No readable multipart files")
        }
        return parts
    }

    private fun multipartFileHeaders(fieldName: String, fileName: String, mimeType: String): Headers =
            Headers.build {
                append(HttpHeaders.ContentDisposition, slockMultipartFileContentDisposition(fieldName, fileName))
                append(HttpHeaders.ContentType, mimeType)
            }

    private fun openFileRefStream(
            activity: android.app.Activity?,
            fileRef: String,
            active: ActiveNetworkRequest
    ): InputStream {
        if (activity == null || fileRef.isBlank()) throw IOException("No readable file")
        val uri = Uri.parse(fileRef)
        val input =
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    val path = uri.path ?: throw IOException("No file path")
                    File(path).takeIf { it.isFile }?.inputStream()
                } else {
                    activity.contentResolver.openInputStream(uri)
                } ?: throw IOException("Unable to read file")
        return CancellableInputStream(input, active)
    }

    private class CancellableInputStream(
            private val delegate: InputStream,
            private val active: ActiveNetworkRequest
    ) : InputStream() {
        override fun read(): Int {
            throwIfCancelled()
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            throwIfCancelled()
            return delegate.read(b, off, len)
        }

        override fun close() {
            delegate.close()
        }

        private fun throwIfCancelled() {
            if (active.cancelled.get()) {
                throw RequestCancelledException()
            }
        }
    }

    companion object {
        const val MODULE_NAME = "HRSlockNetworkModule"
        private const val METHOD_REQUEST = "request"
        private const val METHOD_CANCEL = "cancel"
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
        private val CLIENT: HttpClient =
                createPriorityHttpClient(
                        config = HttpClientConfig(timeout = TimeoutConfig()),
                        maxRequests = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS,
                        maxRequestsPerHost = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
                )
    }

    private class ActiveNetworkRequest {
        val cancelled = AtomicBoolean(false)

        @Volatile
        var job: Job? = null
    }

    private class RequestCancelledException : IOException("Request cancelled")

    private val activeRequests = ConcurrentHashMap<String, ActiveNetworkRequest>()
}

internal fun slockMultipartFileContentDisposition(fieldName: String, fileName: String): String =
        "form-data; name=\"${fieldName.sanitizeMultipartToken()}\"; filename=\"${fileName.sanitizeMultipartToken()}\""

internal fun String.sanitizeMultipartToken(): String =
        replace("\"", "'").replace("\r", "").replace("\n", "")

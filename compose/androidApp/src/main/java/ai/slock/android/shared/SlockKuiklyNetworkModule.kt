package ai.slock.android.shared

import android.net.Uri
import android.util.Base64
import ai.slock.android.core.data.auth.AndroidApiServerEnvironmentStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

internal class SlockKuiklyNetworkModule : KuiklyRenderBaseModule() {
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

    private fun request(params: String?, callback: KuiklyRenderCallback?) {
        val command = JSONObject(params ?: "{}")
        val requestId = command.optString("requestId").takeIf { it.isNotBlank() }
                ?: "android-${System.nanoTime()}"
        val active = ActiveNetworkRequest()
        activeRequests[requestId] = active
        SlockKuiklyThreadAdapter.execute("network-request-$requestId") {
            val result =
                    runCatching {
                                val headers = command.optJSONObject("headers") ?: JSONObject()
                                val method = command.optString("method", "GET").uppercase()
                                val requestBody = command.requestBody(method, headers, activity, active)
                                val request =
                                        Request.Builder()
                                                .url(rewriteEnvironmentUrl(command.optString("url")))
                                                .applyHeaders(headers, skipContentType = requestBody is MultipartBody)
                                                .method(method, requestBody)
                                                .build()
                                if (active.cancelled.get()) {
                                    throw RequestCancelledException()
                                }
                                val call = CLIENT.newCall(request)
                                active.call = call
                                call.execute().use { response ->
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
            activeRequests.remove(requestId, active)
        }
    }

    private fun rewriteEnvironmentUrl(url: String): String {
        val hostActivity = activity ?: return url
        return AndroidApiServerEnvironmentStore(hostActivity.applicationContext).rewriteApiUrl(url)
    }

    private fun cancel(params: String?) {
        val requestId = JSONObject(params ?: "{}").optString("requestId").takeIf { it.isNotBlank() } ?: return
        val active = activeRequests.remove(requestId) ?: return
        active.cancelled.set(true)
        active.call?.cancel()
        SlockDiagnosticsLogger.record(
                "SlockKuiklyNetwork",
                "request_cancel requestId=$requestId thread=${Thread.currentThread().name}"
        )
    }

    private fun Request.Builder.applyHeaders(headers: JSONObject, skipContentType: Boolean): Request.Builder {
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
        return this
    }

    private fun JSONObject.requestBody(
            method: String,
            headers: JSONObject,
            activity: android.app.Activity?,
            active: ActiveNetworkRequest
    ): RequestBody? {
        val mediaType = headers.optString("Content-Type").takeIf { it.isNotBlank() }?.toMediaTypeOrNull()
        if (has("bodyBase64")) {
            return Base64.decode(optString("bodyBase64"), Base64.DEFAULT).toRequestBody(mediaType)
        }
        optJSONArray("multipartFiles")?.let { files ->
            return multipartRequestBody(files, optJSONObject("multipartFields"), activity, active)
        }
        if (has("bodyFileRef")) {
            return fileRefRequestBody(
                    activity = activity,
                    fileRef = optString("bodyFileRef"),
                    mediaType = mediaType,
                    sizeBytes = null,
                    active = active
            )
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

    private fun multipartRequestBody(
            files: org.json.JSONArray,
            fields: JSONObject?,
            activity: android.app.Activity?,
            active: ActiveNetworkRequest
    ): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        var addedFiles = 0
        fields?.keys()?.let { keys ->
            while (keys.hasNext()) {
                val key = keys.next()
                builder.addFormDataPart(key, fields.optString(key))
            }
        }
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val fieldName = file.optString("name", "files")
            val fileName = file.optString("fileName", "attachment")
            val mimeType = file.optString("mimeType", "application/octet-stream").toMediaTypeOrNull()
            val body = (
                    if (file.has("bodyBase64")) {
                        val bytes = Base64.decode(file.optString("bodyBase64"), Base64.DEFAULT)
                        if (bytes.isEmpty()) {
                            null
                        } else {
                            bytes.toRequestBody(mimeType)
                        }
                    } else if (file.has("fileRef")) {
                        fileRefRequestBody(
                                activity = activity,
                                fileRef = file.optString("fileRef"),
                                mediaType = mimeType,
                                sizeBytes = file.optLong("sizeBytes", -1L).takeIf { it > 0L },
                                active = active
                        )
                    } else {
                        null
                    }
                    ) ?: continue
            if (body.contentLengthOrUnknown() == 0L) {
                continue
            }
            builder.addFormDataPart(fieldName, fileName, body)
            addedFiles += 1
        }
        if (files.length() > 0 && addedFiles == 0) {
            throw IOException("No readable multipart files")
        }
        return builder.build()
    }

    private fun fileRefRequestBody(
            activity: android.app.Activity?,
            fileRef: String,
            mediaType: MediaType?,
            sizeBytes: Long?,
            active: ActiveNetworkRequest
    ): RequestBody? {
        if (activity == null || fileRef.isBlank()) return null
        return FileRefRequestBody(activity, fileRef, mediaType, sizeBytes, active)
    }

    private fun RequestBody.contentLengthOrUnknown(): Long =
            try {
                contentLength()
            } catch (_: IOException) {
                -1L
            }

    private class FileRefRequestBody(
            private val activity: android.app.Activity,
            private val fileRef: String,
            private val mediaType: MediaType?,
            private val sizeBytes: Long?,
            private val active: ActiveNetworkRequest
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = sizeBytes ?: fileLengthOrUnknown()

        override fun writeTo(sink: BufferedSink) {
            if (active.cancelled.get()) {
                throw RequestCancelledException()
            }
            val uri = Uri.parse(fileRef)
            val input =
                    if (uri.scheme.equals("file", ignoreCase = true)) {
                        val path = uri.path ?: throw IOException("No file path")
                        File(path).takeIf { it.isFile }?.inputStream()
                    } else {
                        activity.contentResolver.openInputStream(uri)
                    } ?: throw IOException("Unable to read file")
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (active.cancelled.get()) {
                        throw RequestCancelledException()
                    }
                    val read = stream.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                }
            }
        }

        private fun fileLengthOrUnknown(): Long {
            val uri = Uri.parse(fileRef)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: return -1L
                return File(path).takeIf { it.isFile }?.length() ?: -1L
            }
            return activity.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?.use { descriptor -> descriptor.length }
                    ?.takeIf { it >= 0L }
                    ?: -1L
        }
    }

    companion object {
        const val MODULE_NAME = "HRSlockNetworkModule"
        private const val METHOD_REQUEST = "request"
        private const val METHOD_CANCEL = "cancel"
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
        private val CLIENT =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()
    }

    private class ActiveNetworkRequest {
        val cancelled = AtomicBoolean(false)

        @Volatile
        var call: Call? = null
    }

    private class RequestCancelledException : IOException("Request cancelled")

    private val activeRequests = ConcurrentHashMap<String, ActiveNetworkRequest>()
}

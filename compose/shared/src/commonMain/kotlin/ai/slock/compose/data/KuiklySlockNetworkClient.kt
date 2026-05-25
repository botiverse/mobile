package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.network.HEADER_SERVER_ID
import ai.slock.compose.network.MultipartFile
import ai.slock.compose.network.SlockNetworkModule
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.compose.network.SlockRequestScheduler
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class KuiklySlockNetworkClient(
    private val sessionStore: SlockAuthSessionStore,
    private val networkModule: SlockNetworkModule,
    private val baseUrl: String = "https://api.slock.ai/api/",
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
) : SlockNetworkClient {
    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = path,
            reason = method
        ) {
            val tokens = sessionStore.readSession()?.toAuthTokens()
            networkModule.requestRaw(
                url = absoluteUrl(path, query),
                method = method.uppercase(),
                headers = authHeaders(tokens, serverIdOverride, contentType = bodyText?.let { "application/json" }),
                bodyText = bodyText,
                bodyBytes = null
            )
        }

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = "attachments/upload",
            reason = "multipart"
        ) {
            val tokens = sessionStore.readSession()?.toAuthTokens()
            val boundary = "slock-${channelId.value}-${files.size}"
            networkModule.requestRaw(
                url = absoluteUrl("attachments/upload"),
                method = "POST",
                headers =
                    authHeaders(
                        tokens = tokens,
                        serverIdOverride = null,
                        contentType = "multipart/form-data; boundary=$boundary"
                    ),
                bodyBytes =
                    buildMultipartBody(
                        boundary = boundary,
                        fields = mapOf("channelId" to channelId.value),
                        files = files
                    ),
                bodyText = null
            )
        }

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        if (query.isEmpty()) return url
        return "$url?${query.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }}"
    }

    private fun authHeaders(
        tokens: StoredAuthTokens?,
        serverIdOverride: ServerId?,
        contentType: String?
    ): JSONObject =
        JSONObject().apply {
            tokens?.accessToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            (serverIdOverride?.value ?: tokens?.selectedServerId)
                ?.takeIf { it.isNotBlank() }
                ?.let { put(HEADER_SERVER_ID, it) }
            contentType?.let { put("Content-Type", it) }
        }

    private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
        when (this) {
            DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
            DataRequestPriority.Visible -> SlockRequestPriority.Visible
            DataRequestPriority.Background -> SlockRequestPriority.Background
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun SlockNetworkModule.requestRaw(
        url: String,
        method: String,
        headers: JSONObject,
        bodyBytes: ByteArray?,
        bodyText: String? = null
    ): HomeNetworkResponse {
        val response =
            request(
                JSONObject().apply {
                    put("url", url)
                    put("method", method)
                    put("headers", headers)
                    bodyText?.let { put("bodyText", it) }
                    bodyBytes?.let { put("bodyBase64", Base64.Default.encode(it)) }
                    put("timeoutMillis", 30_000)
                }
            )
        val body = response.bodyText
        val code = response.code
        return if (code in 200..299) {
            HomeNetworkResponse(code = code, bodyText = body, errorBodyText = null)
        } else {
            HomeNetworkResponse(
                code = code,
                bodyText = null,
                errorBodyText = response.errorBodyText.ifBlank { response.message }
            )
        }
    }
}

private fun buildMultipartBody(
    boundary: String,
    fields: Map<String, String>,
    files: List<MultipartFile>
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    fields.forEach { (name, value) ->
        chunks += "--$boundary\r\n".encodeToByteArray()
        chunks += "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".encodeToByteArray()
        chunks += value.encodeToByteArray()
        chunks += "\r\n".encodeToByteArray()
    }
    files.forEach { file ->
        chunks += "--$boundary\r\n".encodeToByteArray()
        chunks +=
            (
                "Content-Disposition: form-data; name=\"${file.name}\"; " +
                    "filename=\"${file.fileName.sanitizeMultipartFileName()}\"\r\n"
            ).encodeToByteArray()
        chunks += "Content-Type: ${file.mimeType}\r\n\r\n".encodeToByteArray()
        chunks += file.bytes
        chunks += "\r\n".encodeToByteArray()
    }
    chunks += "--$boundary--\r\n".encodeToByteArray()
    return chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
}

private fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")

private fun String.percentEncode(): String {
    val bytes = encodeToByteArray()
    val builder = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
        ) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return builder.toString()
}

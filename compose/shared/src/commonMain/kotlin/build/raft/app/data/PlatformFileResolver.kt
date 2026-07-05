package build.raft.app.data

import build.raft.app.base.BridgeModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface SlockPlatformFileResolver {
    suspend fun readFile(fileRef: String): SlockPlatformFileReadResult
}

data class SlockPlatformFileReadResult(
    val code: Int,
    val bytes: ByteArray,
    val sizeBytes: Long? = null,
    val errorCode: String = "",
    val message: String = ""
) {
    val success: Boolean
        get() = code in 200..299 && bytes.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SlockPlatformFileReadResult

        if (code != other.code) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (sizeBytes != other.sizeBytes) return false
        if (errorCode != other.errorCode) return false
        if (message != other.message) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        result = 31 * result + errorCode.hashCode()
        result = 31 * result + message.hashCode()
        return result
    }
}

internal class SlockBridgePlatformFileResolver(
    private val bridge: BridgeModule
) : SlockPlatformFileResolver {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
        val response =
            bridge.readPlatformFile(fileRef)
                ?: return SlockPlatformFileReadResult(
                    code = 400,
                    bytes = ByteArray(0),
                    errorCode = "attachment_read_failed",
                    message = "No platform file resolver response"
                )
        val code = response.optInt("code", if (response.optString("status") == "ok") 200 else 400)
        val encoded = response.optString("bodyBase64").takeIf { it.isNotBlank() }
        val bytes = encoded?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: ByteArray(0)
        return SlockPlatformFileReadResult(
            code = code,
            bytes = bytes,
            sizeBytes = response.optLong("sizeBytes", bytes.size.toLong()).takeIf { it > 0L },
            errorCode = response.optString("errorCode")
                .takeIf { it.isNotBlank() }
                ?: response.optString("code")
                    .takeIf { code !in 200..299 && it.isNotBlank() }
                    .orEmpty(),
            message = response.optString("errorBodyText")
                .takeIf { it.isNotBlank() }
                ?: response.optString("message")
                    .takeIf { it.isNotBlank() }
                    .orEmpty()
        )
    }
}

object SlockSharedPlatformFileResolverProvider {
    private var appScopeResolverFactory: (() -> SlockPlatformFileResolver)? = null

    fun installAppScopeResolver(factory: () -> SlockPlatformFileResolver) {
        appScopeResolverFactory = factory
    }

    internal fun create(): SlockPlatformFileResolver? =
        appScopeResolverFactory?.invoke()

    internal fun clearAppScopeResolverForTests() {
        appScopeResolverFactory = null
    }
}

package build.raft.app.data

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

package build.raft.app.network

data class KmpHttpResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String?,
    val headers: Map<String, List<String>> = emptyMap()
) {
    val isSuccessful: Boolean
        get() = code in 200..299

    val payloadText: String?
        get() = bodyText ?: errorBodyText
}

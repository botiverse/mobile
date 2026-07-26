package build.raft.app

internal data class ThreadMarkdownReferencePendingToken(
    val actionId: String,
    val generation: Long
)

internal data class ThreadMarkdownReferencePendingState(
    val active: ThreadMarkdownReferencePendingToken? = null,
    val generation: Long = 0L
) {
    fun start(actionId: String): ThreadMarkdownReferencePendingStart {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank() || active != null) {
            return ThreadMarkdownReferencePendingStart(state = this, token = null)
        }
        val token = ThreadMarkdownReferencePendingToken(normalizedActionId, generation + 1L)
        return ThreadMarkdownReferencePendingStart(
            state = copy(active = token, generation = token.generation),
            token = token
        )
    }

    fun finish(token: ThreadMarkdownReferencePendingToken): ThreadMarkdownReferencePendingState =
        if (active == token) copy(active = null) else this
}

internal data class ThreadMarkdownReferencePendingStart(
    val state: ThreadMarkdownReferencePendingState,
    val token: ThreadMarkdownReferencePendingToken?
)

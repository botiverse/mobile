package build.raft.app

/** Identity-only handoff from the computer SPA. DM IDs are conversation IDs, not members. */
internal data class ComputerNativeTarget(val kind: String, val id: String)

internal sealed interface ComputerNavigationAction {
    data class RestoreComputer(val ticket: Int) : ComputerNavigationAction
    data class Open(val target: ComputerNativeTarget) : ComputerNavigationAction
}

/**
 * Restore the observed computer location before pushing a native page, so closing that page
 * returns to the computer rather than the embedded agent/DM. Repeated URL notifications during
 * the asynchronous history traversal cannot push duplicate native pages.
 */
internal class ComputerNativeNavigation(private val webBaseUrl: String, private val serverSlug: String) {
    private var computerPath: String? = null
    private var pending: ComputerNativeTarget? = null
    private var computerUrl: String? = null
    private var ticket = 0

    fun fallbackUrl(expectedTicket: Int): String? =
        computerUrl.takeIf { pending != null && ticket == expectedTicket }

    fun accept(url: String): ComputerNavigationAction? {
        val path = trustedPath(url) ?: return null
        val prefix = "/s/$serverSlug/"
        if (!path.startsWith(prefix)) return null
        val parts = path.removePrefix(prefix).split('/')
        val isComputer = parts == listOf("computers") ||
            (parts.size == 2 && parts[0] == "computer" && validId(parts[1]))
        if (isComputer) {
            if (pending != null && path != computerPath) return null
            val ready = pending
            pending = null
            computerPath = path
            computerUrl = url
            return ready?.let(ComputerNavigationAction::Open)
        }
        if (pending != null || computerPath == null) return null
        if (parts.size != 2 || parts[0] !in setOf("agent", "dm") || !validId(parts[1])) return null
        pending = ComputerNativeTarget(parts[0], parts[1])
        ticket += 1
        return ComputerNavigationAction.RestoreComputer(ticket)
    }

    private fun trustedPath(url: String): String? {
        // Reject credentials, escaped separators and alternate origins before reading identities.
        val pattern = Regex("^(https?://[A-Za-z0-9.\\-]+(?::[0-9]+)?)(/[^?#]*)?(?:[?#].*)?$")
        val base = pattern.matchEntire(webBaseUrl) ?: return null
        val candidate = pattern.matchEntire(url) ?: return null
        if (!candidate.groupValues[1].equals(base.groupValues[1], ignoreCase = true)) return null
        val path = candidate.groupValues[2]
        if ('%' in path || '\\' in path) return null
        return path
    }

    private fun validId(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

package build.raft.app.shared

import java.util.UUID

/** One pending or mounted login replacement per Android task, not per emitting page. */
internal class AndroidLoginTransitionCoordinator(
    private val processToken: String = UUID.randomUUID().toString(),
) {
    internal sealed interface Admission {
        data class Start(val token: String) : Admission
        data class Reuse(val finishSource: Boolean) : Admission
    }

    data class Authentication(val taskId: Int, val generation: Long)

    private data class Login(val token: String, val owner: String? = null)
    private val tasks = mutableMapOf<Int, Login>()
    // Keep the generation after destruction/completion: an older async auth result must
    // not become current again merely because the newer login has already disappeared.
    private val generations = mutableMapOf<Int, Long>()
    private val restoredTokens = mutableSetOf<String>()
    private var sequence = 0L

    @Synchronized
    fun reserve(taskId: Int, sourceOwner: String): Admission {
        val existing = tasks[taskId]
        if (existing != null) return Admission.Reuse(finishSource = existing.owner != sourceOwner)
        val token = nextToken(taskId)
        tasks[taskId] = Login(token)
        return Admission.Start(token)
    }

    /** Called before rendering; a recreated Activity takes over the same ticket with a new owner. */
    @Synchronized
    fun bind(taskId: Int, requestedToken: String?, owner: String): String? {
        val existing = tasks[taskId]
        var token = requestedToken ?: nextToken(taskId)
        if (requestedToken != null) {
            if (existing != null && existing.token != token) return null
            // A missing ticket from this process was cancelled/superseded. A ticket from a
            // previous process is restored from the Activity Intent after process death.
            if (existing == null && (token.startsWith("$processToken:") || !restoredTokens.add(token))) return null
            // Rebase a restored ticket into this process so it cannot be resurrected
            // again after completion by replaying an old Activity Intent.
            if (existing == null) token = nextToken(taskId)
        }
        tasks[taskId] = Login(token, owner)
        return token
    }

    @Synchronized
    fun captureAuthentication(taskId: Int): Authentication =
        Authentication(taskId, generations[taskId] ?: 0L)

    @Synchronized
    fun isCurrent(authentication: Authentication): Boolean =
        captureAuthentication(authentication.taskId) == authentication

    @Synchronized
    fun cancelPending(taskId: Int, token: String) {
        if (tasks[taskId] == Login(token)) tasks.remove(taskId)
    }

    @Synchronized
    fun completeAuthentication(authentication: Authentication): Boolean {
        if (!isCurrent(authentication)) return false
        tasks.remove(authentication.taskId)
        return true
    }

    @Synchronized
    fun destroyed(taskId: Int, token: String?, owner: String, changingConfigurations: Boolean) {
        if (!changingConfigurations && tasks[taskId] == Login(token ?: return, owner)) {
            tasks.remove(taskId)
        }
    }

    private fun nextToken(taskId: Int): String {
        generations[taskId] = ++sequence
        return "$processToken:$sequence"
    }
}

/** Production launch/finish ordering, shared with focused JVM tests. */
internal fun replaceAndroidLogin(
    coordinator: AndroidLoginTransitionCoordinator,
    taskId: Int,
    sourceOwner: String,
    sourceUnavailable: Boolean,
    sourceRetired: Boolean,
    setSourceRetired: (Boolean) -> Unit,
    startLogin: (String) -> Unit,
    retireTaskSources: () -> Unit,
    finishSource: () -> Unit,
) {
    if (sourceUnavailable) return
    if (sourceRetired) {
        finishSource()
        return
    }
    when (val admission = coordinator.reserve(taskId, sourceOwner)) {
        is AndroidLoginTransitionCoordinator.Admission.Reuse -> {
            if (admission.finishSource) {
                setSourceRetired(true)
                finishSource()
            }
        }
        is AndroidLoginTransitionCoordinator.Admission.Start -> {
            setSourceRetired(true)
            try {
                startLogin(admission.token)
            } catch (failure: Throwable) {
                coordinator.cancelPending(taskId, admission.token)
                setSourceRetired(false)
                throw failure
            }
            retireTaskSources()
            finishSource()
        }
    }
}

internal fun completeAndroidAuthenticationAfterLaunch(
    coordinator: AndroidLoginTransitionCoordinator,
    authentication: AndroidLoginTransitionCoordinator.Authentication,
    sourceUnavailable: Boolean,
    launchDestination: () -> Unit,
): Boolean {
    if (sourceUnavailable || !coordinator.isCurrent(authentication)) return false
    launchDestination()
    return coordinator.completeAuthentication(authentication)
}

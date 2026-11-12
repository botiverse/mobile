package build.raft.app.data

/**
 * Our own (Botiverse) servers, per API environment. An account that has joined any of them is an
 * internal account: staff must not be asked to rate the app (App Store and Google Play review
 * rules), and other internal-only behaviour can key off the same list.
 *
 * Only production has a list. Every server on any other environment (staging, a local or custom
 * API) is ours, so an account there is always internal.
 */
object RaftInternalServers {
    /** Production: the `raft` server. */
    val ProductionServerIds: Set<String> = setOf("bccd5969-5c94-498c-9221-625f87d5ca12")

    /**
     * The internal server ids on the API environment [environmentKey]
     * ([SharedSettingsApiEnvironmentOption.key]); null when every server there is internal.
     */
    fun serverIdsFor(environmentKey: String): Set<String>? =
        if (environmentKey.trim().equals(SharedApiEnvironmentStore.Production.key, ignoreCase = true)) {
            ProductionServerIds
        } else {
            null
        }

    /** True when the account that joined [joinedServerIds] on [environmentKey] is one of ours. */
    fun isInternalAccount(environmentKey: String, joinedServerIds: Collection<String>): Boolean {
        val internal = serverIdsFor(environmentKey) ?: return true
        return joinedServerIds.any { it.trim().lowercase() in internal }
    }
}

package build.raft.app.shared

import android.app.Activity
import java.util.UUID
import java.util.WeakHashMap
import org.json.JSONObject

/** Native Activity ownership only. Credentials and terminal-auth decisions stay in shared. */
internal object AndroidLoginTransitionRouting {
    const val KEY_LOGIN_TRANSITION_TOKEN = "build.raft.app.loginTransitionToken"
    private const val KEY_SOURCE_RETIRED = "build.raft.app.loginSourceRetired"
    private val coordinator = AndroidLoginTransitionCoordinator()
    internal data class AuthenticationAttempt(
        val sourceOwner: String,
        val authentication: AndroidLoginTransitionCoordinator.Authentication,
    )
    private class Source(
        val taskId: Int,
        var authentication: AndroidLoginTransitionCoordinator.Authentication,
        var retired: Boolean,
    ) {
        val owner = UUID.randomUUID().toString()
    }
    private val sources = WeakHashMap<Activity, Source>()

    @Synchronized
    private fun source(activity: Activity): Source =
        sources.getOrPut(activity) {
            Source(
                activity.taskId,
                coordinator.captureAuthentication(activity.taskId),
                activity.intent?.getBooleanExtra(KEY_SOURCE_RETIRED, false) == true,
            )
        }

    /** Register before rendering, including hosts that have not emitted a route yet. */
    fun observeHost(activity: Activity): Boolean = !source(activity).retired

    fun retireHost(activity: Activity) = setRetired(activity, source(activity), true)

    /** MainActivity may receive a fresh callback Intent while an older async attempt exists. */
    @Synchronized
    fun observeNewIntent(activity: Activity) {
        sources.remove(activity)
        source(activity)
    }

    private fun setRetired(activity: Activity, source: Source, retired: Boolean) {
        source.retired = retired
        // The same launch stays retired across recreation; a new route Intent carries no marker.
        activity.intent?.putExtra(KEY_SOURCE_RETIRED, retired)
    }

    private fun retireTaskSources(taskId: Int) {
        val snapshot = synchronized(this) {
            sources.entries.filter { it.value.taskId == taskId }.map { it.key to it.value }
        }
        snapshot.forEach { (activity, source) -> setRetired(activity, source, true) }
    }

    fun openReplacement(activity: Activity, pageData: JSONObject) {
        val source = source(activity)
        replaceAndroidLogin(
            coordinator = coordinator,
            taskId = activity.taskId,
            sourceOwner = source.owner,
            sourceUnavailable = activity.isFinishing || activity.isDestroyed,
            sourceRetired = source.retired,
            setSourceRetired = { setRetired(activity, source, it) },
            startLogin = { token ->
                RaftCmpActivity.start(
                    activity,
                    RaftCmpActivity.PAGE_LOGIN,
                    pageData,
                    loginTransitionToken = token,
                )
            },
            retireTaskSources = { retireTaskSources(activity.taskId) },
            finishSource = activity::finish,
        )
    }

    fun bindLogin(activity: Activity, requestedToken: String?): String? {
        val source = source(activity)
        return coordinator.bind(activity.taskId, requestedToken, source.owner)
            ?.also { source.authentication = coordinator.captureAuthentication(activity.taskId) }
    }

    fun onDestroyed(activity: Activity, token: String?, changingConfigurations: Boolean) {
        coordinator.destroyed(activity.taskId, token, source(activity).owner, changingConfigurations)
    }

    fun captureAuthentication(activity: Activity): AuthenticationAttempt {
        val source = source(activity)
        return AuthenticationAttempt(source.owner, source.authentication)
    }

    /** Only an accepted post-auth launch calls this, never About, browser cancellation or resume. */
    fun afterSuccessfulAuthentication(
        activity: Activity,
        attempt: AuthenticationAttempt = captureAuthentication(activity),
        launchDestination: () -> Unit,
    ): Boolean {
        val source = source(activity)
        val accepted = completeAndroidAuthenticationAfterLaunch(
                coordinator,
                attempt.authentication,
                source.retired || source.owner != attempt.sourceOwner ||
                    activity.isFinishing || activity.isDestroyed,
                launchDestination,
            )
        if (accepted) {
            retireTaskSources(activity.taskId)
        }
        return accepted
    }

    fun isPostAuthReplacement(sourcePage: String?, destination: String, replaceCurrent: Boolean): Boolean =
        replaceCurrent &&
            destination in setOf(RaftPages.APP_SHELL, RaftPages.AUTH_CHOOSE_SERVER) &&
            sourcePage in setOf(
                RaftPages.LOGIN,
                RaftPages.AUTH_CHOOSE_SERVER,
                "compose_auth_profile_setup",
            )
}

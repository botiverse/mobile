package build.raft.app.network

/*
 * PROTOTYPE (agent/lagoon/ios-native-nav-proto, never merged): a login-free capture of the REAL
 * signed-in app (the production app shell, its native tab bar, real pages) on fabricated data.
 *
 * Only a build with visual fixtures (`-PslockVisualFixtures=true`) has an installer
 * ([raftCaptureBackendInstaller], src/visualFixtures); every other build has none and
 * [RaftCaptureBackend.installIfRequested] does nothing. Even in a fixture build the host installs
 * it only when the capture asks (iOS: `RAFT_PROTO_FAKE_BACKEND=1` in the launch environment).
 * Installed, it writes a fabricated signed-in session and replaces the bottom HTTP executor with
 * one that answers from fixture data in-process: nothing leaves the device.
 */
object RaftCaptureBackend {
    internal var executorFactory: (() -> RaftHttpExecutor)? = null

    /** True once installed. */
    val active: Boolean get() = executorFactory != null

    internal var pageDataProvider: ((String) -> String?)? = null

    /**
     * Capture script: the pageData JSON the app itself would open [target] with
     * (`thread:<channelId>`, `drafts`, `settings-account`), or null.
     */
    fun pageDataJson(target: String): String? = pageDataProvider?.invoke(target)

    /** Installs the capture backend if [requested] and this build carries one. True when installed. */
    fun installIfRequested(requested: Boolean): Boolean {
        if (!requested) return false
        val installer = raftCaptureBackendInstaller ?: return false
        if (!active) installer()
        return active
    }
}

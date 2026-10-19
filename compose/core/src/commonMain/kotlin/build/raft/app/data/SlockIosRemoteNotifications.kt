package build.raft.app.data

/**
 * Enables or disables APNs delivery for the installed iOS application.
 *
 * Registration is auth-gated. Logout and auth loss disable APNs before local
 * credentials disappear, matching Apple's documented logout use case for
 * `unregisterForRemoteNotifications()`.
 */
expect suspend fun slockSetIosRemoteNotificationsEnabled(enabled: Boolean)

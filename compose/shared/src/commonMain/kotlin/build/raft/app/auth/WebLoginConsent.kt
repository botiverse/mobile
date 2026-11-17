package build.raft.app

/** The launch boundary also checks consent: a stale/enqueued click must not start login. */
internal inline fun startWebLoginWithConsent(
    consentAccepted: Boolean,
    starting: Boolean,
    start: () -> Unit,
) {
    if (consentAccepted && !starting) start()
}

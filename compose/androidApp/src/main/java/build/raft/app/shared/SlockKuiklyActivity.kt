package build.raft.app.shared

/**
 * Former Kuikly page host, kept as a manifest entry point only: the debug diagnostics deep-link
 * alias targets it and Intents built by older app versions (pinned shortcuts, pending
 * notifications, instrumentation) may still name it. It renders exactly like [SlockCmpActivity];
 * new code launches pages through [SlockCmpActivity.start].
 */
internal open class SlockKuiklyActivity : SlockCmpActivity()

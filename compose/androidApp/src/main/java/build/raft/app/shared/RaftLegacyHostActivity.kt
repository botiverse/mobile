package build.raft.app.shared

/**
 * Former Kuikly page host, kept as a manifest entry point only: the debug diagnostics deep-link
 * alias targets it and Intents built by older app versions (pinned shortcuts, pending
 * notifications, instrumentation) may still name it. It renders exactly like [RaftCmpActivity];
 * new code launches pages through [RaftCmpActivity.start].
 */
internal open class RaftLegacyHostActivity : RaftCmpActivity()

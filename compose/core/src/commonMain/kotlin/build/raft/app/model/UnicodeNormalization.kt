package build.raft.app.model

/**
 * Platform Unicode normalization forms required by UTS #39 confusable skeleton
 * (`HandleConfusables.kt`, parity with `String.prototype.normalize("NFKC" / "NFD")` in slock).
 */
internal expect fun raftNormalizeNfkc(text: String): String

internal expect fun raftNormalizeNfd(text: String): String

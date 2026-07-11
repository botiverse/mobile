package build.raft.app.network

// iOS routes NetworkKMM through the library's pooled Darwin/ktor engine,
// not libcurl (task #24), so there is no curl runtime to install.
internal actual fun ensurePlatformCurlRuntimeInstalled(warningLogger: (String) -> Unit): Boolean = true

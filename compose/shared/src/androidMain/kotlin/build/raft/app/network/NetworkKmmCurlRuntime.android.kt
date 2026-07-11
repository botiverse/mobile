package build.raft.app.network

// Android routes NetworkKMM through the library's pooled OkHttp/ktor engine,
// not libcurl (decision A, task #24), so there is no curl runtime to install.
internal actual fun ensurePlatformCurlRuntimeInstalled(warningLogger: (String) -> Unit): Boolean = true

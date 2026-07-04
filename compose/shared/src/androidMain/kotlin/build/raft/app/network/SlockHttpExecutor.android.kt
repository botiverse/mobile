package build.raft.app.network

internal actual fun createPlatformSlockHttpExecutor(
    warningLogger: (String) -> Unit
): SlockHttpExecutor = KtorSlockHttpExecutor(warningLogger)

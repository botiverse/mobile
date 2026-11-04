package build.raft.app.network

import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAppScopeApiEnvironment
import build.raft.app.data.RaftSharedAuthSessionKeyValueStoreProvider
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import kotlinx.coroutines.runBlocking

/**
 * PROTOTYPE capture backend (see [RaftCaptureBackend]): a fabricated signed-in session on a
 * capture-only environment (nothing resolves there: realtime stays offline) and the in-process
 * fake server [RaftCaptureFakeServer] as the HTTP executor.
 */
internal val raftCaptureBackendInstaller: (() -> Unit)? = {
    RaftAppScopeApiEnvironment.store().save(
        SharedSettingsApiEnvironmentRequest(
            key = "custom",
            label = "Capture (fake data)",
            apiBaseUrl = RaftCaptureFakeServer.BaseUrl,
            socketBaseUrl = RaftCaptureFakeServer.BaseUrl,
            webUrl = "https://capture.raft.invalid",
        )
    )
    val store = KeyValueAuthSessionStore(RaftSharedAuthSessionKeyValueStoreProvider.create().store)
    runBlocking { store.saveSession(RaftCaptureFakeServer.session()) }
    RaftCaptureBackend.executorFactory = { RaftCaptureFakeServer.executor }
    RaftCaptureBackend.pageDataProvider = { target -> build.raft.app.raftCaptureScriptPageData(target) }
    // The capture's labels say it is fake data; the environment chip would cover the bar's buttons.
    build.raft.app.setRaftEnvironmentIndicatorHiddenForCapture(true)
    println("RAFT_CAPTURE installed base=${RaftCaptureFakeServer.BaseUrl}")
}

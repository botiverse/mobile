package build.raft.app.network

/** No capture backend outside the visual-fixture build (see [RaftCaptureBackend]). */
internal val raftCaptureBackendInstaller: (() -> Unit)? = null

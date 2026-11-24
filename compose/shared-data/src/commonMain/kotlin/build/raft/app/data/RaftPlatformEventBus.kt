package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi expect fun createRaftPlatformEventBus(): RaftEventBus

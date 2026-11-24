package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi actual fun createRaftPlatformEventBus(): RaftEventBus = RaftEnhancedNotifyCenter

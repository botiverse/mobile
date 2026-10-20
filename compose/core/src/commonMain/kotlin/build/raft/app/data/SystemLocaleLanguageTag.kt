package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/** Returns the device's current preferred system language without consulting App Language. */
@RaftCoreInternalApi
expect fun currentSystemLanguageTag(): String?

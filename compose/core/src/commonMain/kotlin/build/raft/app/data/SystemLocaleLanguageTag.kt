package build.raft.app.data

import build.raft.app.SlockCoreInternalApi

/** Returns the device's current preferred system language without consulting App Language. */
@SlockCoreInternalApi
expect fun currentSystemLanguageTag(): String?

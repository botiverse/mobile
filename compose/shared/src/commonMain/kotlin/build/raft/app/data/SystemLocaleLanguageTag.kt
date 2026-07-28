package build.raft.app.data

/** Returns the device's current preferred system language without consulting App Language. */
internal expect fun currentSystemLanguageTag(): String?

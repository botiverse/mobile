package build.raft.app.data

private val ChannelNamePattern = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

internal fun String.isValidChannelName(): Boolean =
    ChannelNamePattern.matches(trim())


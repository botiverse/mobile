package build.raft.app.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerId(val value: String)

@Serializable
data class ChannelId(val value: String)

@Serializable
data class ThreadId(val value: String)

@Serializable
data class MessageId(val value: String)

@Serializable
data class TaskId(val value: String)

@Serializable
data class MemberId(val value: String)

@Serializable
data class AttachmentId(val value: String)

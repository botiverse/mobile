package ai.slock.android.core.model

// Re-export KMP identity types
typealias ServerId = ai.slock.shared.model.ServerId
typealias ChannelId = ai.slock.shared.model.ChannelId
typealias ThreadId = ai.slock.shared.model.ThreadId
typealias MessageId = ai.slock.shared.model.MessageId
typealias TaskId = ai.slock.shared.model.TaskId
typealias MemberId = ai.slock.shared.model.MemberId
typealias AttachmentId = ai.slock.shared.model.AttachmentId

// Re-export KMP models that are fully compatible
typealias SessionSnapshot = ai.slock.shared.model.SessionSnapshot
typealias ServerSummary = ai.slock.shared.model.ServerSummary
typealias ServerPermissions = ai.slock.shared.model.ServerPermissions
typealias ConversationKind = ai.slock.shared.model.ConversationKind
typealias ThreadConversation = ai.slock.shared.model.ThreadConversation
typealias ConversationPermissions = ai.slock.shared.model.ConversationPermissions
typealias MemberKind = ai.slock.shared.model.MemberKind
typealias Presence = ai.slock.shared.model.Presence
typealias AgentStatus = ai.slock.shared.model.AgentStatus
typealias TaskStatus = ai.slock.shared.model.TaskStatus
typealias AvatarReference = ai.slock.shared.model.AvatarReference
typealias MemberProfile = ai.slock.shared.model.MemberProfile
typealias AgentProfile = ai.slock.shared.model.AgentProfile
typealias Message = ai.slock.shared.model.Message
typealias MessageType = ai.slock.shared.model.MessageType
typealias MessageDeliveryState = ai.slock.shared.model.MessageDeliveryState
typealias Attachment = ai.slock.shared.model.Attachment
typealias TaskSummary = ai.slock.shared.model.TaskSummary
typealias InboxItemKind = ai.slock.shared.model.InboxItemKind
typealias InboxItem = ai.slock.shared.model.InboxItem
typealias RealtimeEvent = ai.slock.shared.model.RealtimeEvent
typealias MessageNew = ai.slock.shared.model.MessageNew
typealias MessageUpdated = ai.slock.shared.model.MessageUpdated
typealias DirectMessageCreated = ai.slock.shared.model.DirectMessageCreated
typealias ThreadUpdated = ai.slock.shared.model.ThreadUpdated
typealias TaskCreated = ai.slock.shared.model.TaskCreated
typealias TaskUpdated = ai.slock.shared.model.TaskUpdated
typealias TaskDeleted = ai.slock.shared.model.TaskDeleted
typealias MemberPresenceUpdated = ai.slock.shared.model.MemberPresenceUpdated
typealias SyncResumeResponse = ai.slock.shared.model.SyncResumeResponse
typealias Heartbeat = ai.slock.shared.model.Heartbeat
typealias SettingsSummary = ai.slock.shared.model.SettingsSummary

// Android-specific ConversationSummary (extends KMP with Android-specific avatar handling)
typealias ConversationSummary = ai.slock.shared.model.ConversationSummary

// Extension functions are now in KMP - just import them from ai.slock.shared.model.*

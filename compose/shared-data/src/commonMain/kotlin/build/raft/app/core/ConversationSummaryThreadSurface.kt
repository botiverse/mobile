package build.raft.app

import build.raft.app.model.ConversationSummary

// t5/#419 finale: the ConversationSummary thread-surface family delegates to the canonical
// ThreadLaunchConversationIdentity extensions (the conversion is a plain field copy), so the
// semantics live exactly once. Kind projections route through the #417 contract.

@RaftCoreInternalApi fun ConversationSummary.threadSurfaceKind(): String =
    toThreadLaunchConversationIdentity().threadSurfaceKind()

@RaftCoreInternalApi fun ConversationSummary.usesMemberScopedMentions(): Boolean =
    toThreadLaunchConversationIdentity().usesMemberScopedMentions()

@RaftCoreInternalApi fun ConversationSummary.threadStreamChannelId(): String? =
    toThreadLaunchConversationIdentity().threadStreamChannelId()

@RaftCoreInternalApi fun ConversationSummary.threadParentChannelId(): String? =
    toThreadLaunchConversationIdentity().threadParentChannelId()

@RaftCoreInternalApi fun ConversationSummary.threadParentMessageId(): String? =
    toThreadLaunchConversationIdentity().threadParentMessageId()

@RaftCoreInternalApi fun ConversationSummary.threadParentSurfaceKind(): String? =
    toThreadLaunchConversationIdentity().threadParentSurfaceKind()

@RaftCoreInternalApi fun ConversationSummary.threadParentChannelKind(): String? =
    toThreadLaunchConversationIdentity().threadParentChannelKind()

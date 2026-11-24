package build.raft.app.network.sync

/** Schema version of the normalized reaction contract vector (`canonical-message-v2-reaction.vector.json`). */
const val CANONICAL_MESSAGE_V2_SCHEMA_VERSION: Int = 5

/** Reactor names a reaction fact carries for its preview. */
const val CANONICAL_REACTION_PREVIEW_LIMIT: Int = 3

/** Reactions one message fact carries at most. */
const val CANONICAL_REACTION_LIMIT: Int = 30

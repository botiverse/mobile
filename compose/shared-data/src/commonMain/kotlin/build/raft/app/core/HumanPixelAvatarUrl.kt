package build.raft.app

@RaftCoreInternalApi const val RAFT_HUMAN_PIXEL_AVATAR_SCHEME = "pixel-human:"
@RaftCoreInternalApi const val RAFT_HUMAN_PIXEL_AVATAR_RECIPE_VERSION = "v1"

/** The marker of an avatar the Server picked (a new account's random one), after every field. */
@RaftCoreInternalApi const val RaftHumanPixelAvatarAutoSuffix = "/auto"

/** Longest value a reader looks at; a real recipe is far shorter. */
private const val RaftHumanPixelAvatarMaxValueLength = 128
private val RaftHumanPixelAvatarValuePattern = Regex("^pixel-human:v[0-9]{1,3}:[a-z0-9.]*(?:/auto)?$")

@RaftCoreInternalApi fun isRaftHumanPixelAvatarUrl(value: String?): Boolean =
    value != null && value.length <= RaftHumanPixelAvatarMaxValueLength && RaftHumanPixelAvatarValuePattern.matches(value)

/** True when the avatar is one the Server picked, not one the person built. */
@RaftCoreInternalApi fun isAutoRaftHumanPixelAvatarUrl(value: String?): Boolean =
    isRaftHumanPixelAvatarUrl(value) && value!!.endsWith(RaftHumanPixelAvatarAutoSuffix)

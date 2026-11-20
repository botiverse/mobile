package build.raft.app

import build.raft.app.model.MemberGuestChannelRef
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web `isAddableToChannel` / `isAddableToNewChannel` (botiverse/slock #15354, #15362): a Guest is
 * offered only in its own bound, non-joint channel and never for a new channel; everyone else is
 * unchanged.
 */
class ChannelGuestAddabilityTest {
    private fun person(id: String, role: String?, kind: MemberKind = MemberKind.Human, bound: String? = null) =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind,
            serverRole = role,
            guestChannel = bound?.let { MemberGuestChannelRef(id = it, name = it) },
        )

    @Test
    fun addToAChannelOffersAGuestOnlyInItsBoundNonJointChannel() {
        val cases =
            listOf(
                Triple(person("m", "member"), "c1" to "channel", true),
                Triple(person("a", null, MemberKind.Agent), "c1" to "joint", true),
                Triple(person("g", "guest", bound = "c1"), "c1" to "channel", true),
                Triple(person("g", "Guest ", bound = "c1"), "c1" to "private", true),
                Triple(person("g", "guest", bound = "c1"), "c2" to "channel", false),
                Triple(person("g", "guest", bound = "c1"), "c1" to "joint", false),
                Triple(person("g", "guest", bound = null), "c1" to "channel", false),
            )
        cases.forEach { (member, channel, offered) ->
            assertEquals(offered, member.isAddableToChannel(channel.first, channel.second), "$member in $channel")
        }
    }

    @Test
    fun aNewChannelNeverOffersAGuest() {
        assertEquals(false, person("g", "guest", bound = "c1").isAddableToNewChannel())
        assertEquals(true, person("m", "member").isAddableToNewChannel())
        assertEquals(true, person("a", null, MemberKind.Agent).isAddableToNewChannel())
    }
}

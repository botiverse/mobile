package build.raft.app

import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.members.MembersManagedAppDetailTarget
import build.raft.app.members.appBotIdentityOrNull
import build.raft.app.members.membersManagedAppDetailTarget
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Contract tests for App bot profile App-detail links (botiverse/slock #14286):
 * - Wire decoding carries `managedByApp.clientId` (the App row id `oauth_clients.id`, NOT public
 *   `client_id`), `logoUrl`, `botStatusText`, and `profileProjection`.
 * - [membersManagedAppDetailTarget] matches web `useManagedAppDetailLink`: enabled for non-guest
 *   viewers on the bot's own server with a full profile; null for guests, another server's bot in
 *   a joint channel, bounded `channel_summary` projections, or missing App attribution.
 * - [membersManagedAppFromParts] splits localized `"From %1$s"` templates cleanly.
 */
class ManagedAppDetailLinkTest {
    @Test
    fun agentsDirectoryParserExtractsManagedAppRowIdLogoStatusAndProjection() {
        val members =
            parseSharedSettingsAgentsAsMembers(
                """
                [
                  {
                    "id":"agent-stamp",
                    "serverId":"srv-1",
                    "name":"stamp",
                    "displayName":"Stamp",
                    "status":"active",
                    "runtime":"external",
                    "managedByAppInstallId":"install-1",
                    "managedByApp":{
                      "clientId":"stamp-row-id",
                      "name":"Stamp",
                      "logoUrl":"https://cdn.example.test/stamp.png",
                      "description":"Code review",
                      "botStatusText":"Watching 3 repos"
                    }
                  },
                  {
                    "id":"agent-bounded",
                    "serverId":"srv-1",
                    "name":"bounded-bot",
                    "status":"active",
                    "runtime":"external",
                    "managedByAppInstallId":"install-2",
                    "profileProjection":"channel_summary",
                    "managedByApp":{
                      "clientId":"app-row-2",
                      "name":"Vault",
                      "logoUrl":null
                    }
                  }
                ]
                """.trimIndent(),
            )

        assertNotNull(members)
        val stamp = members.single { it.id.value == "agent-stamp" }
        assertEquals("install-1", stamp.managedByAppInstallId)
        assertEquals("stamp-row-id", stamp.managedByAppClientId)
        assertEquals("Stamp", stamp.managedByAppName)
        assertEquals("https://cdn.example.test/stamp.png", stamp.managedByAppLogoUrl)
        assertEquals("Watching 3 repos", stamp.managedByAppBotStatusText)
        assertNull(stamp.profileProjection)

        val identity = stamp.appBotIdentityOrNull()
        assertNotNull(identity)
        assertEquals("stamp-row-id", identity.appClientId)
        assertEquals("https://cdn.example.test/stamp.png", identity.appLogoUrl)

        val bounded = members.single { it.id.value == "agent-bounded" }
        assertEquals("channel_summary", bounded.profileProjection)
        assertNull(bounded.managedByAppLogoUrl)
    }

    @Test
    fun linkTargetEnabledForNonGuestOnOwnServerFullProfile() {
        val bot = appBotMember(serverId = "srv-1")

        for (role in listOf("owner", "admin", "member")) {
            val target =
                membersManagedAppDetailTarget(
                    member = bot,
                    viewerServerRole = role,
                    currentServerId = "srv-1",
                    accessMode = MemberDetailAccessMode.LocalFull,
                )
            assertEquals(MembersManagedAppDetailTarget(appId = "stamp-row-id", appName = "Stamp"), target, role)
        }
    }

    @Test
    fun linkTargetNullForGuestUnknownRoleRemoteJointOrBoundedProjection() {
        val bot = appBotMember(serverId = "srv-1")

        assertNull(
            membersManagedAppDetailTarget(
                member = bot,
                viewerServerRole = "guest",
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot,
                viewerServerRole = null,
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot,
                viewerServerRole = "member",
                currentServerId = "srv-1",
                accessMode = MemberDetailAccessMode.RemoteProfileOnly,
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = appBotMember(serverId = "srv-other"),
                viewerServerRole = "member",
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot.copy(profileProjection = "channel_summary"),
                viewerServerRole = "member",
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot.copy(managedByAppClientId = null),
                viewerServerRole = "member",
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot.copy(managedByAppName = null),
                viewerServerRole = "member",
                currentServerId = "srv-1",
            ),
        )
        assertNull(
            membersManagedAppDetailTarget(
                member = bot.copy(managedByAppInstallId = null),
                viewerServerRole = "member",
                currentServerId = "srv-1",
            ),
        )
    }

    private fun appBotMember(serverId: String) =
        MemberProfile(
            id = MemberId("agent-stamp"),
            displayName = "Stamp",
            handle = "stamp",
            kind = MemberKind.Agent,
            serverId = ServerId(serverId),
            managedByAppInstallId = "install-1",
            managedByAppClientId = "stamp-row-id",
            managedByAppName = "Stamp",
            managedByAppLogoUrl = "https://cdn.example.test/stamp.png",
        )
}

package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedInviteHumanDialogTest {
    @Test
    fun normalizeInviteHumanJoinUrlAddsRaftAppOriginToRelativePaths() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("/join/AB-Rt1m"))
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("join/AB-Rt1m"))
    }

    @Test
    fun normalizeInviteHumanJoinUrlUsesConfiguredWebOriginForRelativePaths() {
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/botiverse/join/AB-Rt1m",
            normalizeInviteHumanJoinUrl("/join/AB-Rt1m", "https://raft-app-staging.botiverse.dev/s/botiverse/")
        )
        assertEquals(
            "https://localhost:5173/join/AB-Rt1m",
            normalizeInviteHumanJoinUrl("join/AB-Rt1m", "https://localhost:5173")
        )
    }

    @Test
    fun normalizeInviteHumanJoinUrlPreservesAbsoluteUrls() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("https://app.raft.build/join/AB-Rt1m"))
        assertEquals("http://localhost:5173/join/AB-Rt1m", normalizeInviteHumanJoinUrl("http://localhost:5173/join/AB-Rt1m"))
    }

    @Test
    fun normalizeInviteHumanJoinUrlReturnsNullForBlank() {
        assertNull(normalizeInviteHumanJoinUrl(null))
        assertNull(normalizeInviteHumanJoinUrl("  "))
    }

    @Test
    fun inviteHumanJoinUrlCopyPayloadMatchesDisplayedJoinUrl() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("/join/AB-Rt1m"))
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("join/AB-Rt1m"))
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/botiverse/join/AB-Rt1m",
            inviteHumanJoinUrlCopyPayload("/join/AB-Rt1m", "https://raft-app-staging.botiverse.dev/s/botiverse/")
        )
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("https://app.raft.build/join/AB-Rt1m"))
        assertNull(inviteHumanJoinUrlCopyPayload("  "))
    }

    @Test
    fun linkLimitChoicesOfferKeepCurrentOnlyForALimitTheLinkHas() {
        val unlimited = InviteHumanLinkLimits.None
        assertEquals(listOf("unlimited", "1", "5", "10", "25"), inviteHumanMaxUsesChoices(unlimited))
        assertEquals(listOf("never", "1", "7", "30"), inviteHumanExpiryChoices(unlimited))
        assertEquals("unlimited", inviteHumanDefaultMaxUsesChoice(unlimited))
        assertEquals("never", inviteHumanDefaultExpiryChoice(unlimited))

        val limited = InviteHumanLinkLimits(maxUses = 5, expiresAt = "2026-11-01T00:00:00.000Z")
        assertEquals("keep", inviteHumanMaxUsesChoices(limited).first())
        assertEquals("keep", inviteHumanExpiryChoices(limited).first())
        assertEquals("keep", inviteHumanDefaultMaxUsesChoice(limited))
        assertEquals("keep", inviteHumanDefaultExpiryChoice(limited))
    }

    @Test
    fun updateLinkResolvesKeepToTheCurrentLimitsAndDaysToAnIsoExpiry() {
        val current = InviteHumanLinkLimits(maxUses = 10, expiresAt = "2026-12-01T00:00:00.000Z")
        val now = 1_792_454_400_000L // 2026-10-20T00:00:00.000Z
        // (maxUses choice, expiry choice) -> limits sent to POST /join-links.
        val cases =
            listOf(
                Triple("keep", "keep", current),
                Triple("unlimited", "never", InviteHumanLinkLimits.None),
                Triple("25", "keep", InviteHumanLinkLimits(25, current.expiresAt)),
                Triple("keep", "7", InviteHumanLinkLimits(10, "2026-10-27T00:00:00.000Z")),
            )
        cases.forEach { (uses, expiry, expected) ->
            assertEquals(expected, resolveInviteHumanLinkLimits(uses, expiry, current, now), "$uses/$expiry")
        }
    }

    @Test
    fun isoUtcMatchesJavascriptToIsoString() {
        assertEquals("1970-01-01T00:00:00.000Z", inviteHumanIsoUtc(0L))
        assertEquals("2024-02-29T23:59:59.999Z", inviteHumanIsoUtc(1_709_251_199_999L))
    }

    @Test
    fun inviteEmailValidationAndTargetsFollowWeb() {
        assertTrue(isValidInviteEmailAddress(" name@company.com "))
        listOf("", "name", "@company.com", "a@b", "a@@b.com", "a b@c.com", "a@.b.com", "a@b..com").forEach {
            assertFalse(isValidInviteEmailAddress(it), it)
        }
        val targets =
            inviteHumanTargets(
                listOf(
                    InviteHumanInvitee(0, " a@b.com ", "guest"),
                    InviteHumanInvitee(1, "   "),
                    InviteHumanInvitee(2, "c@d.com"),
                )
            )
        assertEquals(listOf("a@b.com" to "guest", "c@d.com" to "member"), targets.map { it.email to it.role })
    }

    @Test
    fun joinLinkWireLimitsAndInviteRoleReachTheSharedDtos() {
        val link =
            build.raft.app.data.parseSharedSettingsJoinLinks(
                """[{"id":"l1","token":"tok","maxUses":5,"useCount":2,"expiresAt":"2026-11-01T00:00:00.000Z"},""" +
                    """{"id":"l2","token":"tok2","maxUses":null,"expiresAt":null}]"""
            )!!
        assertEquals(Triple(5, 2, "2026-11-01T00:00:00.000Z"), Triple(link[0].maxUses, link[0].useCount, link[0].expiresAt))
        assertEquals(InviteHumanLinkLimits.None, InviteHumanLinkLimits.of(link[1]))

        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            build.raft.app.data.buildSharedSettingsServerInviteCreateBody("a@b.com", "guest")
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("\"guest\"", body["role"].toString())
        val memberDefault = kotlinx.serialization.json.Json.parseToJsonElement(
            build.raft.app.data.buildSharedSettingsServerInviteCreateBody("a@b.com")
        ) as kotlinx.serialization.json.JsonObject
        assertNull(memberDefault["role"])
    }
}

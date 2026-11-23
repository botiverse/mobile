package build.raft.app

import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.InviteeRefusal
import build.raft.app.data.ResolvedServerRefusal
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.ServerRefusalInput
import build.raft.app.data.resolveServerRefusal
import build.raft.app.resources.RawStringDesc
import build.raft.app.resources.ResourceFormattedStringDesc
import build.raft.app.resources.ResourceStringDesc
import build.raft.app.resources.StringDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for `#proj-mobile:#319`: single four-tier server refusal resolution, verbatim server
 * wording for unknown/dynamic refusal codes (never hardcoding limits like 150), web-locale known
 * codes (including `deployment_user_limit_reached`), and multi-server refusal toast merging.
 */
class ChannelConversionServerWordsTest {
    private val fencedTooLarge =
        "This channel has 151 messages, which is more than can be moved to a shared channel right now. Nothing was changed. Start a new shared channel instead."
    private val onlineTooLarge =
        "This channel has 160 threads, which is more than can be moved to a shared channel right now. Nothing was changed. Start a new shared channel instead."

    @Test
    fun resolverAppliesFourTierPrecedenceAcrossRequestAndPerItemRefusals() {
        // Tier 1: Infrastructure / Auth / Edge markers never surface raw body.
        assertIs<ResolvedServerRefusal.Infrastructure>(
            resolveServerRefusal(
                ServerRefusalInput(status = 403, code = null, serverMessage = "edge_blocked", traceId = "t1"),
                knownCode = { _, _ -> null },
            )
        )
        assertIs<ResolvedServerRefusal.Infrastructure>(
            resolveServerRefusal(
                ServerRefusalInput(status = 401, code = "auth_required", serverMessage = "Token expired", traceId = "t2"),
                knownCode = { _, _ -> null },
            )
        )

        // Tier 2: Known localized code wins over server prose.
        val known =
            assertIs<ResolvedServerRefusal.KnownCode<String>>(
                resolveServerRefusal(
                    ServerRefusalInput(status = 409, code = "channel_full", serverMessage = "Full", traceId = "t3"),
                    knownCode = { code, _ -> if (code == "channel_full") "localized_full" else null },
                )
            )
        assertEquals("localized_full", known.known)
        assertEquals("t3", known.traceId)

        // Tier 3: Unknown or dynamic non-5xx refusal codes surface the server's sentence verbatim
        // regardless of code prefix (fenced vs online wording, non-channel_conversion_* codes).
        for ((code, sentence) in listOf(
            "channel_conversion_too_large" to fencedTooLarge,
            "channel_conversion_too_large" to onlineTooLarge,
            "external_binding_privacy_unavailable" to "Private channels cannot use this external binding right now.",
            "connect_unavailable" to "Server connections are temporarily unavailable for this workspace.",
            "future_server_refusal_code" to "A new server rule refused this action.",
        )) {
            val words =
                assertIs<ResolvedServerRefusal.ServerWords>(
                    resolveServerRefusal(
                        ServerRefusalInput(status = 409, code = code, serverMessage = sentence, traceId = "t4"),
                        knownCode = { _, _ -> null },
                    )
                )
            assertEquals(sentence, words.message)
            assertEquals("t4", words.traceId)
        }

        // Tier 4: 5xx / internal_error / blank / HTML / stack traces fall back to generic.
        for (input in listOf(
            ServerRefusalInput(status = 500, code = "internal_error", serverMessage = "Failed to connect the server", traceId = "t5"),
            ServerRefusalInput(status = 502, code = "bad_gateway", serverMessage = "upstream down", traceId = "t6"),
            ServerRefusalInput(status = 409, code = "channel_conversion_too_large", serverMessage = "   ", traceId = "t7"),
            ServerRefusalInput(status = 403, code = "proxy_deny", serverMessage = "<html><body>403 Forbidden</body></html>", traceId = "t8"),
            ServerRefusalInput(status = 400, code = "oops", serverMessage = "Error: boom\n    at handler (index.ts:42)", traceId = "t9"),
        )) {
            assertIs<ResolvedServerRefusal.Generic>(
                resolveServerRefusal(input, knownCode = { _, _ -> null }),
                "expected Generic fallback for $input",
            )
        }
    }

    @Test
    fun externalInviteAndInviteeAndMemberConnectSurfacesUseSingleResolverWithoutPrefixWhitelist() {
        // Request-level ExternalInviteRefusal: dynamic fenced/online messages and non-conversion unknown codes.
        val fenced = ExternalInviteRefusal(409, "channel_conversion_too_large", fencedTooLarge, traceId = "t1").toFailure()
        assertEquals(ExternalInviteError.Generic, fenced.error)
        assertEquals(fencedTooLarge, fenced.serverMessage)
        assertEquals("t1", fenced.traceId)

        val online = ExternalInviteRefusal(409, "channel_conversion_too_large", onlineTooLarge, traceId = "t2").toFailure()
        assertEquals(onlineTooLarge, online.serverMessage)

        val nonConversion =
            ExternalInviteRefusal(409, "external_binding_privacy_unavailable", "Privacy mode unavailable.", traceId = "t3").toFailure()
        assertEquals(ExternalInviteError.Generic, nonConversion.error)
        assertEquals("Privacy mode unavailable.", nonConversion.serverMessage)

        // Known localized code keeps app sentence.
        val dailyCap = ExternalInviteRefusal(429, "daily_cap", "Too many").toFailure()
        assertEquals(ExternalInviteError.DailyCap, dailyCap.error)
        assertNull(dailyCap.serverMessage)

        // 500 internal_error stays generic.
        val serverErr = ExternalInviteRefusal(500, "internal_error", "Failed to connect", traceId = "t4").toFailure()
        assertEquals(ExternalInviteError.Generic, serverErr.error)
        assertNull(serverErr.serverMessage)

        // Per-server 200 outcome: channel_conversion_too_large, unknown non-conversion code, and web-translated deployment_user_limit_reached.
        val perServerTooLarge =
            assertIs<ExternalInviteServerRefusal.Invitee>(
                externalInviteServerRefusal(
                    ServerConnectOutcome.Refused("s1", 409, "channel_conversion_too_large", fencedTooLarge)
                )
            )
        assertEquals(InviteeMessage.Generic, perServerTooLarge.message)
        assertEquals(fencedTooLarge, perServerTooLarge.serverWords)

        val perServerUnknown =
            assertIs<ExternalInviteServerRefusal.Invitee>(
                externalInviteServerRefusal(
                    ServerConnectOutcome.Refused("s2", 409, "connect_unavailable", "Connections are paused.")
                )
            )
        assertEquals("Connections are paused.", perServerUnknown.serverWords)

        val licenseLimit =
            assertIs<ExternalInviteServerRefusal.Invitee>(
                externalInviteServerRefusal(
                    ServerConnectOutcome.Refused("s3", 403, "deployment_user_limit_reached", "License limit reached.")
                )
            )
        assertEquals(InviteeMessage.DeploymentUserLimitReached, licenseLimit.message)
        assertNull(licenseLimit.serverWords)

        // InviteeRefusal with unknown non-conversion code preserves server words via single exit.
        val inviteeUnknown = InviteeRefusal(status = 409, code = "membership_changed", error = "Your role changed on that server.")
        val (inviteeMsg, inviteeWords) = resolveInviteeRefusal(inviteeUnknown, InviteeAction.Connect, target = null, targetId = "s1")
        assertEquals(InviteeMessage.Generic, inviteeMsg)
        assertEquals("Your role changed on that server.", inviteeWords)

        // JointMemberConnect per-server and whole-request refusals.
        val memberPerServer =
            memberConnectRefusedLine(
                ServerConnectOutcome.Refused("s4", 409, "external_binding_privacy_unavailable", "Cannot share this channel.")
            )
        assertEquals("Cannot share this channel.", memberPerServer.serverWords)

        // Wire per-item refusal omitting `status` (decoded as 0) must still preserve Tier 2/Tier 3.
        val omittedStatusWords =
            memberConnectRefusedLine(
                ServerConnectOutcome.Refused("s5", 0, "channel_conversion_too_large", fencedTooLarge)
            )
        assertEquals(fencedTooLarge, omittedStatusWords.serverWords)
        val omittedStatusInvitee =
            assertIs<ExternalInviteServerRefusal.Invitee>(
                externalInviteServerRefusal(
                    ServerConnectOutcome.Refused("s5", 0, "channel_conversion_too_large", fencedTooLarge)
                )
            )
        assertEquals(fencedTooLarge, omittedStatusInvitee.serverWords)

        // Tier 1 edge_blocked 403 must resolve to generic (never Forbidden / closed=true).
        val edgeExternal = ExternalInviteRefusal(403, null, "edge_blocked", traceId = "t-edge").toFailure()
        assertEquals(ExternalInviteError.Generic, edgeExternal.error)
        assertNull(edgeExternal.serverMessage)
        val (edgeMemberLine, edgeClosed) = resolveMemberConnectRequestLine(403, null, "edge_blocked", "t-edge")
        assertEquals(MemberConnectMessage.Generic, edgeMemberLine.message)
        assertNull(edgeMemberLine.serverWords)
        assertEquals(false, edgeClosed)

        // Real server 404/403 responses carry `code: "channel_not_found"` (slock routes/channels.ts:945)
        // or other non-null codes; match web externalInvites.ts:420-427 and memberConnect.ts:137-146
        // (known code first, else status 404/403 -> localized copy, never raw English).
        for (code in listOf("channel_not_found", null)) {
            val notFoundInvite = ExternalInviteRefusal(404, code, "Channel not found", traceId = "t-404").toFailure()
            assertEquals(ExternalInviteError.NotFound, notFoundInvite.error)
            assertNull(notFoundInvite.serverMessage)
            val (channelNotFoundLine, channelNotFoundClosed) =
                resolveMemberConnectRequestLine(404, code, "Channel not found", "t-404")
            assertEquals(MemberConnectMessage.NotAllowed, channelNotFoundLine.message)
            assertNull(channelNotFoundLine.serverWords)
            assertEquals(true, channelNotFoundClosed)
        }
        for (code in listOf("server_admin_required", null)) {
            val forbiddenInvite = ExternalInviteRefusal(403, code, "Forbidden", traceId = "t-403").toFailure()
            assertEquals(ExternalInviteError.Forbidden, forbiddenInvite.error)
            assertNull(forbiddenInvite.serverMessage)
            val (forbiddenMemberLine, forbiddenClosed) =
                resolveMemberConnectRequestLine(403, code, "Forbidden", "t-403")
            assertEquals(MemberConnectMessage.NotAllowed, forbiddenMemberLine.message)
            assertNull(forbiddenMemberLine.serverWords)
            assertEquals(true, forbiddenClosed)
        }

        // Mixed-case free_external_limit still sets upgrade = true.
        val mixedCaseFreeLimit = ExternalInviteRefusal(403, " FREE_EXTERNAL_LIMIT ", "Limit").toFailure()
        assertEquals(ExternalInviteError.FreeLimit, mixedCaseFreeLimit.error)
        assertTrue(mixedCaseFreeLimit.upgrade)

        val (memberRequestLine, closed) =
            resolveMemberConnectRequestLine(409, "reserved_channel", "This channel is reserved.", "t5")
        assertEquals("This channel is reserved.", memberRequestLine.serverWords)
        assertEquals(false, closed)
    }

    @Test
    fun buildExternalInviteToastsMergesMultipleServerRefusalsWithoutNewlinesInTitle() {
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val stubResolve: (StringDesc) -> String = { desc ->
            when (desc) {
                is ResourceStringDesc -> desc.stringRes.key
                is ResourceFormattedStringDesc -> desc.stringRes.key + desc.args.joinToString(prefix = "(", postfix = ")")
                is RawStringDesc -> desc.string
            }
        }

        // Single server refused uses singular resource (`channel_external_invite_server_refused`).
        val singleRefused =
            buildExternalInviteToasts(
                effects =
                    listOf(
                        ExternalInvitesEffect.ServerRefused(
                            "Alpha",
                            ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic, serverWords = fencedTooLarge),
                            traceId,
                        ),
                    ),
                joint = false,
                channel = "#general",
                host = "Host",
                resolveDesc = stubResolve,
            ).single()
        assertEquals("channel_external_invite_server_refused(Alpha)", singleRefused.title)
        assertEquals(fencedTooLarge, singleRefused.reason)

        // Two servers refused with the SAME conversion sentence -> single toast with plural resource, sentence shown once, no \n in title.
        val sameReasonToasts =
            buildExternalInviteToasts(
                effects =
                    listOf(
                        ExternalInvitesEffect.ServerRefused(
                            "Alpha",
                            ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic, serverWords = fencedTooLarge),
                            traceId,
                        ),
                        ExternalInvitesEffect.ServerRefused(
                            "Beta",
                            ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic, serverWords = fencedTooLarge),
                            traceId,
                        ),
                    ),
                joint = false,
                channel = "#general",
                host = "Host",
                resolveDesc = stubResolve,
            )
        val mergedSame = sameReasonToasts.single()
        assertEquals("channel_external_invite_servers_refused(Alpha, Beta)", mergedSame.title)
        assertTrue(!mergedSame.title.contains("\n"), "toast title must never contain newline: ${mergedSame.title}")
        assertEquals(fencedTooLarge, mergedSame.reason)
        assertEquals(traceId, mergedSame.traceId)
        assertTrue(mergedSame.isError)

        // Two servers refused with DIFFERENT reasons -> single toast listing each server's reason in description.
        val diffReasonToasts =
            buildExternalInviteToasts(
                effects =
                    listOf(
                        ExternalInvitesEffect.ServerRefused(
                            "Alpha",
                            ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic, serverWords = fencedTooLarge),
                            traceId,
                        ),
                        ExternalInvitesEffect.ServerRefused(
                            "Beta",
                            ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic, serverWords = "Connections are paused."),
                            traceId,
                        ),
                    ),
                joint = false,
                channel = "#general",
                host = "Host",
                resolveDesc = stubResolve,
            )
        val mergedDiff = diffReasonToasts.single()
        assertTrue(!mergedDiff.title.contains("\n"), "toast title must never contain newline: ${mergedDiff.title}")
        assertEquals("Alpha: $fencedTooLarge\nBeta: Connections are paused.", mergedDiff.reason)
        assertEquals(traceId, mergedDiff.traceId)
    }
}

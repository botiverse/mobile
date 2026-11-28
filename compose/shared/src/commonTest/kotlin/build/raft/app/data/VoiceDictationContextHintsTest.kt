package build.raft.app.data

import build.raft.app.RaftLogLevel
import build.raft.app.RaftPlatformLogHint
import build.raft.app.RaftPlatformLogQueue
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.BodyWriterUploadProgressTracker
import build.raft.app.network.KtorRaftHttpExecutor
import build.raft.app.network.RaftKtorClientProvider
import build.raft.app.network.RaftNetworkRequestLifetimeCoordinator
import build.raft.app.network.RaftRecentFailedTraceId
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.configureRaftKtorClient
import build.raft.app.network.toSafeDiagnosticsToken
import build.raft.app.raftRedactLogMessage
import build.raft.app.thread.buildLocalVoiceDictationHintSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationContextHintsTest {
    private val serverA = "11111111-1111-4111-8111-111111111111"
    private val serverB = "22222222-2222-4222-8222-222222222222"
    private val userSelf = "33333333-3333-4333-8333-333333333333"
    private val validPermitJson =
        """
        {
          "permit": "hv2.k1.payload.sig",
          "url": "wss://live.holt.botiverse.build/v1/stream",
          "expiresAt": "2026-11-26T20:01:00.000Z",
          "maxDurationSeconds": 600,
          "quota": {
            "usedSeconds": 60,
            "limitSeconds": 7200,
            "remainingSeconds": 7140,
            "resetsAt": "2026-11-27T00:00:00.000Z"
          }
        }
        """.trimIndent()

    @AfterTest
    fun tearDownFallbackCache() {
        VoiceDictationCallerContextFallbackCache.clear()
    }

    // -------------------------------------------------------------------------
    // 1. draftBefore normalization & caret extraction
    // -------------------------------------------------------------------------

    @Test
    fun normalizeDraftBeforeStripsCcCfExceptNewlineTrimsAndKeepsTail500CodePointsIntact() {
        assertNull(normalizeVoiceDictationDraftBefore(null))
        assertNull(normalizeVoiceDictationDraftBefore(""))
        assertNull(normalizeVoiceDictationDraftBefore("   \u0000\u200B\r\t  "))

        // Strips Cc (including \u0000, \r, \t) and Cf (including \u200B, \u202E, astral tag U+E0020)
        // while preserving \n (U+000A).
        val astralTag = "\uDB40\uDC20" // U+E0020 (Cf)
        assertEquals(
            "Helloworld\nLine 2!",
            normalizeVoiceDictationDraftBefore("  Hello\u0000\u200B\u202E${astralTag}world\r\nLine 2\t!  "),
        )

        // Keeps the tail near the cursor (<= 500 Unicode code points) without splitting surrogate pairs.
        val astral = "😀".repeat(600)
        val normalizedAstral = assertNotNull(normalizeVoiceDictationDraftBefore("leading prefix $astral"))
        assertEquals("😀".repeat(VOICE_DICTATION_MAX_DRAFT_BEFORE_CODE_POINTS), normalizedAstral)
        assertEquals(1_000, normalizedAstral.length, "each astral emoji must remain 2 UTF-16 code units intact")

        // Mixed BMP + CJK + astral at the exact 500 code-point tail boundary.
        val prefix = "旧内容".repeat(200)
        val tail = "靠近光标\uD842\uDFB7" + "a".repeat(495) // 4 CJK + 1 astral (U+20BB7) + 495 ASCII = 500 code points
        assertEquals(tail, normalizeVoiceDictationDraftBefore("$prefix$tail"))

        // Leading whitespace exposed after tail slice is trimmed cleanly.
        val spacedTail = "a".repeat(100) + "   " + "b".repeat(498)
        assertEquals("b".repeat(498), normalizeVoiceDictationDraftBefore(spacedTail))
    }

    @Test
    fun extractDraftBeforeCaretSlicesBeforeCaretClampsBoundsAndProtectsSurrogatePairs() {
        assertNull(extractVoiceDictationDraftBeforeCaret("", 0))
        assertNull(extractVoiceDictationDraftBeforeCaret("Before after", 0))
        assertNull(extractVoiceDictationDraftBeforeCaret("Before after", -10))
        assertEquals("Before", extractVoiceDictationDraftBeforeCaret("Before after", 6))
        assertEquals("Before after", extractVoiceDictationDraftBeforeCaret("Before after", 12))
        assertEquals("Before after", extractVoiceDictationDraftBeforeCaret("Before after", 999))

        // Caret landing mid-surrogate pair inside "A😀B" (index 2 splits 😀 at UTF-16 [1, 2]).
        val text = "A😀B"
        assertEquals("A", extractVoiceDictationDraftBeforeCaret(text, 2))
        assertEquals("A😀", extractVoiceDictationDraftBeforeCaret(text, 3))
        assertEquals("A😀B", extractVoiceDictationDraftBeforeCaret(text, 4))
    }

    // -------------------------------------------------------------------------
    // 1b. draftAfter normalization & selectionEnd extraction
    // -------------------------------------------------------------------------

    @Test
    fun normalizeDraftAfterPreservesLeadingWhitespaceNormalizesCrStripsCcCfKeepingNewlineTabAndKeepsHead200CodePointsIntact() {
        assertNull(normalizeVoiceDictationDraftAfter(null))
        assertNull(normalizeVoiceDictationDraftAfter(""))
        assertNull(normalizeVoiceDictationDraftAfter("   \u0000\u200B\r\n\t  "))
        assertNull(normalizeVoiceDictationDraftAfter(" ".repeat(250) + "far away"))

        // Normalizes CRLF/CR to \n, strips Cc/Cf (including astral Cf U+E0020) EXCEPT \n and \t,
        // preserves leading newlines/horizontal whitespace, and trims trailing whitespace only.
        val astralTag = "\uDB40\uDC20" // U+E0020 (Cf)
        assertEquals(
            "\n下一段\t内容",
            normalizeVoiceDictationDraftAfter("\r\n下一段\u0000\u200B\u202E${astralTag}\t内容  "),
        )
        assertEquals(" 方案有没有问题", normalizeVoiceDictationDraftAfter(" 方案有没有问题  "))
        assertEquals("\tindented\nnext", normalizeVoiceDictationDraftAfter("\tindented\rnext"))
        assertEquals("\n\npara", normalizeVoiceDictationDraftAfter("\r\r\npara  "))

        // Idempotent under repeated normalization (including leading newline/spaces).
        val preserved = assertNotNull(normalizeVoiceDictationDraftAfter("\n  keep leading ws  "))
        assertEquals("\n  keep leading ws", preserved)
        assertEquals(preserved, normalizeVoiceDictationDraftAfter(preserved))

        // Keeps the HEAD immediately after the cursor (<= 200 Unicode code points) without splitting surrogate pairs.
        val astral = "😀".repeat(250)
        val normalizedAstral = assertNotNull(normalizeVoiceDictationDraftAfter("\n${astral}TRAILING_IGNORED"))
        assertEquals("\n" + "😀".repeat(VOICE_DICTATION_MAX_DRAFT_AFTER_CODE_POINTS - 1), normalizedAstral)
        assertEquals(1 + 199 * 2, normalizedAstral.length, "leading newline + 199 intact astral emoji")

        // Mixed CJK + astral at exact 200 code-point head boundary.
        val head = "方案有没有问题\uD842\uDFB7" + "b".repeat(192) // 7 CJK + 1 astral (U+20BB7) + 192 ASCII = 200 code points
        assertEquals(head, normalizeVoiceDictationDraftAfter(head + "旧后缀".repeat(100)))

        // Trailing whitespace exposed after head 200 code-point slice is trimmed cleanly.
        val spacedHead = "a".repeat(198) + "   " + "b".repeat(50)
        assertEquals("a".repeat(198), normalizeVoiceDictationDraftAfter(spacedHead))
    }

    @Test
    fun extractDraftAfterCaretSlicesAfterSelectionEndOmitsEndOrWhitespaceAndProtectsSurrogatePairs() {
        assertNull(extractVoiceDictationDraftAfterCaret("", 0))
        assertEquals(" after", extractVoiceDictationDraftAfterCaret("Before after", 6))
        assertEquals("Before after", extractVoiceDictationDraftAfterCaret("Before after", 0))
        assertEquals("Before after", extractVoiceDictationDraftAfterCaret("Before after", -10))
        assertNull(extractVoiceDictationDraftAfterCaret("Before after", 12))
        assertNull(extractVoiceDictationDraftAfterCaret("Before after", 999))
        assertEquals("\nNext", extractVoiceDictationDraftAfterCaret("Before\nNext", 6))
        assertNull(extractVoiceDictationDraftAfterCaret("Before   \n  \t", 6))

        // Active selection range excludes selected text from both draftBefore and draftAfter.
        val withSelection = "Before SELECTED after"
        assertEquals("Before", extractVoiceDictationDraftBeforeCaret(withSelection, 6))
        assertEquals(" after", extractVoiceDictationDraftAfterCaret(withSelection, 15))

        // Selection end landing mid-surrogate pair inside "A😀B" (index 2 splits 😀 at UTF-16 [1, 2]).
        val text = "A😀B"
        assertEquals("😀B", extractVoiceDictationDraftAfterCaret(text, 1))
        assertEquals("B", extractVoiceDictationDraftAfterCaret(text, 2))
        assertEquals("B", extractVoiceDictationDraftAfterCaret(text, 3))
        assertNull(extractVoiceDictationDraftAfterCaret("A😀", 2))
    }

    // -------------------------------------------------------------------------
    // 2. contactHints normalization
    // -------------------------------------------------------------------------

    @Test
    fun normalizeContactHintsCollapsesSingleLineDropsOver64CodePointsDedupsAndCapsAt20() {
        assertNull(normalizeVoiceDictationContactHints(null))
        assertNull(normalizeVoiceDictationContactHints(emptyList()))
        assertNull(normalizeVoiceDictationContactHints(listOf(null, "", "   ", "\u0000\u200B\r\n")))

        val exact64Astral = "😀".repeat(VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS)
        val over64Astral = "😀".repeat(VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS + 1)
        val over64Ascii = "a".repeat(VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS + 1)
        val astralTag = "\uDB40\uDC20" // U+E0020 (Cf)

        val normalized =
            normalizeVoiceDictationContactHints(
                listOf(
                    "  Alice\n  Chen\u200B  ",
                    "alice   chen",
                    "ALICE\tCHEN",
                    over64Ascii,
                    over64Astral,
                    exact64Astral,
                    "Bob\u0007\r\nLee",
                    "Caro \u200B${astralTag} Lin",
                    "",
                    "   ",
                    null,
                ),
            )
        assertEquals(
            listOf("Alice Chen", exact64Astral, "Bob Lee", "Caro Lin"),
            normalized,
        )

        // Caps at first 20 unique valid items (skipping earlier invalid/duplicate items).
        val many =
            buildList {
                add("")
                add("x".repeat(65))
                add("First")
                add("first")
                for (i in 2..31) {
                    add("Member $i")
                }
            }
        val capped = assertNotNull(normalizeVoiceDictationContactHints(many))
        assertEquals(VOICE_DICTATION_MAX_CONTACT_HINTS, capped.size)
        assertEquals("First", capped.first())
        assertEquals("Member 20", capped.last())
    }

    // -------------------------------------------------------------------------
    // 3. Server-scoped local hint snapshot collector (5-tier priority + isolation)
    // -------------------------------------------------------------------------

    @Test
    fun collectContactHintsFromSnapshotEnforcesStrictServerIsolation() {
        // Blank serverId or mismatched currentServerId returns null immediately.
        assertNull(
            collectVoiceDictationContactHintsFromSnapshot(
                VoiceDictationLocalHintSnapshot(
                    serverId = "   ",
                    currentServerId = serverA,
                    dmChannels = listOf(VoiceDictationLocalDmHint(id = "dm-a", serverId = serverA, peerDisplayName = "Alice")),
                ),
            ),
        )
        assertNull(
            collectVoiceDictationContactHintsFromSnapshot(
                VoiceDictationLocalHintSnapshot(
                    serverId = serverA,
                    currentServerId = null,
                    dmChannels = listOf(VoiceDictationLocalDmHint(id = "dm-a", serverId = serverA, peerDisplayName = "Alice")),
                ),
            ),
        )
        assertNull(
            collectVoiceDictationContactHintsFromSnapshot(
                VoiceDictationLocalHintSnapshot(
                    serverId = serverA,
                    currentServerId = serverB,
                    dmChannels = listOf(VoiceDictationLocalDmHint(id = "dm-b", serverId = serverB, peerDisplayName = "Bob From B")),
                    channels = listOf(VoiceDictationLocalChannelHint(id = "ch-b", serverId = serverB, name = "secret-b")),
                    agents = listOf(VoiceDictationLocalAgentHint(id = "ag-b", serverId = serverB, displayName = "Agent B", name = "AgentB")),
                    members = listOf(VoiceDictationLocalMemberHint(userId = "u-b", serverId = serverB, displayName = "Member B", name = "member_b")),
                ),
            ),
        )

        // Even when currentServerId matches, per-item serverId or resolveChannelServerId mismatches are dropped.
        val hints =
            collectVoiceDictationContactHintsFromSnapshot(
                VoiceDictationLocalHintSnapshot(
                    serverId = serverA,
                    currentServerId = serverA,
                    currentUserId = userSelf,
                    recentConversationIds = listOf("dm-a", "dm-memo-b"),
                    dmChannels =
                        listOf(
                            VoiceDictationLocalDmHint(id = "dm-a", serverId = serverA, peerDisplayName = "Alice Chen", peerName = "alice"),
                            VoiceDictationLocalDmHint(id = "dm-other", serverId = serverB, peerDisplayName = "Cross Server DM", peerName = "cross_dm"),
                            VoiceDictationLocalDmHint(id = "dm-memo-b", peerDisplayName = "Memo Cross DM", peerName = "memo_dm"),
                        ),
                    channels =
                        listOf(
                            VoiceDictationLocalChannelHint(id = "ch-a", serverId = serverA, name = "#wg-call", joined = true),
                            VoiceDictationLocalChannelHint(id = "ch-other", serverId = serverB, name = "cross-channel", joined = true),
                            VoiceDictationLocalChannelHint(id = "ch-memo-b", name = "memo-cross-channel", joined = true),
                        ),
                    agents =
                        listOf(
                            VoiceDictationLocalAgentHint(id = "ag-a", serverId = serverA, displayName = "Tenny", name = "Tenny"),
                            VoiceDictationLocalAgentHint(id = "ag-other", serverId = serverB, displayName = "Cross Agent", name = "CrossAgent"),
                        ),
                    members =
                        listOf(
                            VoiceDictationLocalMemberHint(userId = userSelf, serverId = serverA, displayName = "My Self", name = "myself"),
                            VoiceDictationLocalMemberHint(userId = "u-a", serverId = serverA, displayName = "Kate Peer", name = "kate_peer"),
                            VoiceDictationLocalMemberHint(userId = "u-other", serverId = serverB, displayName = "Cross Member", name = "cross_member"),
                        ),
                    resolveChannelServerId = { id -> if (id == "dm-memo-b" || id == "ch-memo-b") serverB else serverA },
                ),
            )

        assertEquals(
            listOf("Alice Chen", "alice", "Tenny", "wg-call", "Kate Peer", "kate_peer"),
            hints,
        )
    }

    @Test
    fun collectContactHintsFromSnapshotRanksAllFiveTiersFiltersInvalidItemsAndCapsAt20() {
        val hints =
            collectVoiceDictationContactHintsFromSnapshot(
                VoiceDictationLocalHintSnapshot(
                    serverId = serverA,
                    currentServerId = serverA,
                    currentUserId = userSelf,
                    recentConversationIds = listOf("dm-recent", "ch-recent"),
                    dmChannels =
                        listOf(
                            VoiceDictationLocalDmHint(
                                id = "dm-older",
                                serverId = serverA,
                                peerDisplayName = "Older DM",
                                peerName = "older_dm",
                                lastActivityAt = "2026-11-26T08:00:00Z",
                            ),
                            VoiceDictationLocalDmHint(
                                id = "dm-hot",
                                serverId = serverA,
                                peerDisplayName = "Hot\nDM\u200B",
                                peerName = "hot_dm",
                                lastActivityAt = "2026-11-27T12:00:00Z",
                            ),
                            VoiceDictationLocalDmHint(
                                id = "dm-recent",
                                serverId = serverA,
                                peerDisplayName = "Recent DM",
                                peerName = "recent_dm",
                                lastActivityAt = "2026-11-25T08:00:00Z",
                            ),
                            VoiceDictationLocalDmHint(
                                id = "dm-archived",
                                serverId = serverA,
                                peerDisplayName = "Archived DM",
                                peerName = "archived_dm",
                                archived = true,
                                lastActivityAt = "2026-11-27T23:00:00Z",
                            ),
                            VoiceDictationLocalDmHint(
                                id = "dm-too-long",
                                serverId = serverA,
                                peerDisplayName = "y".repeat(70),
                                peerName = "x".repeat(65),
                                lastActivityAt = "2026-11-27T22:00:00Z",
                            ),
                        ),
                    channels =
                        listOf(
                            VoiceDictationLocalChannelHint(
                                id = "ch-recent",
                                serverId = serverA,
                                name = "##proj-voice",
                                joined = true,
                                lastActivityAt = "2026-11-25T00:00:00Z",
                            ),
                            VoiceDictationLocalChannelHint(
                                id = "ch-hot",
                                serverId = serverA,
                                name = "#wg-mobile",
                                joined = true,
                                lastActivityAt = "2026-11-27T15:00:00Z",
                            ),
                            VoiceDictationLocalChannelHint(
                                id = "ch-cool",
                                serverId = serverA,
                                name = "general",
                                joined = true,
                                lastActivityAt = "2026-11-26T00:00:00Z",
                            ),
                            VoiceDictationLocalChannelHint(
                                id = "ch-unjoined",
                                serverId = serverA,
                                name = "unjoined-room",
                                joined = false,
                                lastActivityAt = "2026-11-27T20:00:00Z",
                            ),
                            VoiceDictationLocalChannelHint(
                                id = "ch-archived",
                                serverId = serverA,
                                name = "old-room",
                                joined = true,
                                archived = true,
                                lastActivityAt = "2026-11-27T21:00:00Z",
                            ),
                            VoiceDictationLocalChannelHint(
                                id = "ch-thread",
                                serverId = serverA,
                                name = "thread-entry",
                                joined = true,
                                isDmOrThread = true,
                            ),
                        ),
                    agents =
                        listOf(
                            VoiceDictationLocalAgentHint(id = "ag-1", serverId = serverA, displayName = "Lagoon", name = "lagoon"),
                            VoiceDictationLocalAgentHint(id = "ag-del", serverId = serverA, displayName = "Deleted Bot", name = "del_bot", deleted = true),
                            VoiceDictationLocalAgentHint(id = "ag-svc", serverId = serverA, displayName = "Bench Svc", name = "bench_svc", serviceAgent = true),
                        ),
                    members =
                        listOf(
                            VoiceDictationLocalMemberHint(userId = userSelf, serverId = serverA, displayName = "Self User", name = "self_user"),
                            VoiceDictationLocalMemberHint(userId = "u-1", serverId = serverA, displayName = "Artin", name = "artin"),
                            VoiceDictationLocalMemberHint(userId = "u-2", serverId = serverA, displayName = "Recent DM", name = "extra_handle"),
                        ),
                ),
            )

        // Exact tier order:
        // Tier 1 (recent): Recent DM, recent_dm, proj-voice
        // Tier 2 (active DMs desc): Hot DM, hot_dm, Older DM, older_dm (Recent DM deduped)
        // Tier 3 (agents): Lagoon (lagoon deduped case-insensitively)
        // Tier 4 (joined channels desc): wg-mobile, general (proj-voice deduped)
        // Tier 5 (members excl self): Artin (artin deduped), extra_handle (Recent DM deduped)
        assertEquals(
            listOf(
                "Recent DM",
                "recent_dm",
                "proj-voice",
                "Hot DM",
                "hot_dm",
                "Older DM",
                "older_dm",
                "Lagoon",
                "wg-mobile",
                "general",
                "Artin",
                "extra_handle",
            ),
            hints,
        )

        // Verify 20-item cap preserves highest priority items first.
        val manyMembers =
            (1..30).map { i ->
                VoiceDictationLocalMemberHint(userId = "m-$i", serverId = serverA, displayName = "Peer $i")
            }
        val capped =
            assertNotNull(
                collectVoiceDictationContactHintsFromSnapshot(
                    VoiceDictationLocalHintSnapshot(
                        serverId = serverA,
                        currentServerId = serverA,
                        agents = listOf(VoiceDictationLocalAgentHint(id = "ag-top", serverId = serverA, displayName = "TopAgent")),
                        members = manyMembers,
                    ),
                ),
            )
        assertEquals(VOICE_DICTATION_MAX_CONTACT_HINTS, capped.size)
        assertEquals("TopAgent", capped.first())
        assertEquals("Peer 19", capped.last())
    }

    @Test
    fun buildLocalHintSnapshotFromStoresMapsHomeAndMembersRespectsHiddenHumanDirectoryAndAccountScope() {
        val srvSummary =
            ServerSummary(
                id = ServerId(serverA),
                name = "Alpha",
                role = "member",
                hideHumansFromMembers = true,
            )
        val selfProfile =
            MemberProfile(
                id = MemberId(userSelf),
                displayName = "Viewer",
                handle = "viewer",
                kind = MemberKind.Human,
            )
        val dmPeerProfile =
            MemberProfile(
                id = MemberId("u-peer"),
                displayName = "Alice Lin",
                handle = "alice_lin",
                kind = MemberKind.Human,
            )
        val hiddenHuman =
            MemberProfile(
                id = MemberId("u-hidden"),
                displayName = "Hidden Human",
                handle = "hidden_human",
                kind = MemberKind.Human,
            )
        val liveAgent =
            MemberProfile(
                id = MemberId("ag-live"),
                displayName = "Kate",
                handle = "kate_agent",
                kind = MemberKind.Agent,
            )
        val deletedAgent =
            MemberProfile(
                id = MemberId("ag-del"),
                displayName = "Old Bot",
                handle = "old_bot",
                kind = MemberKind.Agent,
                agentDeletedAt = "2026-11-01T00:00:00Z",
            )
        val serviceAgent =
            MemberProfile(
                id = MemberId("ag-svc"),
                displayName = "Bench Runner",
                handle = "bench_runner",
                kind = MemberKind.Agent,
                raftbenchService = true,
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = userSelf, email = "viewer@raft.build"),
                accessToken = "tok",
                refreshToken = "ref",
                selectedServerId = serverA,
                servers = listOf(StoredAuthServer(id = serverA, name = "Alpha")),
            )
        val homeSnapshot =
            HomeSnapshot(
                server = srvSummary,
                currentMember = selfProfile,
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("ch-1"),
                            channelId = ChannelId("ch-1"),
                            name = "#wg-call",
                            joined = true,
                            lastMessageAt = "2026-11-27T10:00:00Z",
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-lounge"),
                            channelId = ChannelId("ch-lounge"),
                            name = "kate-lounge",
                            joined = true,
                            loungeAgentId = "ag-live",
                            lastMessageAt = "2026-11-27T11:00:00Z",
                        ),
                    ),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            name = "Alice Lin",
                            presenceMemberId = MemberId("u-peer"),
                            lastMessageAt = "2026-11-27T12:00:00Z",
                        ),
                    ),
                unreadTotal = 0,
            )
        val homeState =
            SharedHomeState(
                content = SharedHomeContentState.Content(snapshot = homeSnapshot, session = session),
                selectedConversationId = "ch-1",
            )
        val membersState =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        serverA to
                            SharedServerMembersEntry(
                                serverId = serverA,
                                ownerAccountId = userSelf,
                                server = srvSummary,
                                members = listOf(selfProfile, dmPeerProfile, hiddenHuman, liveAgent, deletedAgent, serviceAgent),
                            ),
                    ),
            )

        val snapshot =
            assertNotNull(
                buildLocalVoiceDictationHintSnapshot(
                    serverId = serverA,
                    currentServerId = serverA,
                    currentUserId = userSelf,
                    activeChannelId = "dm-1",
                    homeState = homeState,
                    membersState = membersState,
                ),
            )
        val hints = collectVoiceDictationContactHintsFromSnapshot(snapshot)
        // activeChannelId "dm-1" (enriched with handle alice_lin from roster) + selectedConversationId "ch-1"
        // + liveAgent Kate/kate_agent; excludes hidden human directory member, lounge channel, deleted/service agents.
        assertEquals(listOf("Alice Lin", "alice_lin", "wg-call", "Kate", "kate_agent"), hints)

        // Cross-server mismatch returns null.
        assertNull(
            buildLocalVoiceDictationHintSnapshot(
                serverId = serverB,
                currentServerId = serverA,
                currentUserId = userSelf,
                homeState = homeState,
                membersState = membersState,
            ),
        )

        // Mismatched ownerAccountId ignores stale account's member roster.
        val otherAccountSnapshot =
            assertNotNull(
                buildLocalVoiceDictationHintSnapshot(
                    serverId = serverA,
                    currentServerId = serverA,
                    currentUserId = "different-user-id",
                    homeState = homeState,
                    membersState = membersState,
                ),
            )
        val otherAccountHints = collectVoiceDictationContactHintsFromSnapshot(otherAccountSnapshot)
        assertEquals(listOf("wg-call", "Alice Lin"), otherAccountHints)
    }

    // -------------------------------------------------------------------------
    // 4. Privacy & zero diagnostics / log / trace leakage
    // -------------------------------------------------------------------------

    @Test
    fun permitRequestsSuccessFallbackAndFailureNeverLeakDraftOrContactHintsIntoLogsOrTraces() = runBlocking {
        val secretDraft = "SECRET_MOBILE_DRAFT_SENTINEL_9f3a"
        val secretDraftAfter = "SECRET_MOBILE_AFTER_SENTINEL_5e1f"
        val secretHintA = "SECRET_MOBILE_CONTACT_ALPHA_7b2c"
        val secretHintB = "SECRET_MOBILE_CHANNEL_BETA_4d8e"
        val forbiddenTokens =
            listOf(
                secretDraft,
                secretDraftAfter,
                secretHintA,
                secretHintB,
                "draftBefore",
                "draft_before",
                "draftAfter",
                "draft_after",
                "contactHints",
                "contact_hints",
            )

        val diagnosticLogs = mutableListOf<String>()
        val sentBodies = mutableListOf<String>()
        val emittedSpans = mutableListOf<RaftWebSpanRecord>()

        val httpErrorTraceRecorder =
            MobileHttpClientErrorTraceRecorder(
                nowEpochMs = { 1_795_700_000_000L },
                nowMonotonicMs = { 1_000L },
                resolveServerId = { serverA },
                resolveSampleRate = { 1.0 },
                random = { 0.0 },
                emitSpan = { _, span ->
                    emittedSpans += span.copy(attrs = sanitizeRaftClientTraceAttrs(span.attrs))
                },
            )

        var scenario = "success"
        var attempt = 0
        val mockHttpClient =
            HttpClient(
                MockEngine { request ->
                    attempt += 1
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    sentBodies += body
                    when (scenario) {
                        "success" ->
                            respond(
                                validPermitJson,
                                HttpStatusCode.Created,
                                headersOf("Content-Type" to listOf("application/json")),
                            )
                        "fallback_400_then_201" ->
                            if (attempt == 1) {
                                respond(
                                    """{"code":"invalid_request","error":"Unrecognized keys: 'draftBefore', 'draftAfter', 'contactHints' ($secretDraft / $secretDraftAfter)"}""",
                                    HttpStatusCode.BadRequest,
                                    headersOf("Content-Type" to listOf("application/json")),
                                )
                            } else {
                                respond(
                                    validPermitJson,
                                    HttpStatusCode.Created,
                                    headersOf("Content-Type" to listOf("application/json")),
                                )
                            }
                        else ->
                            respond(
                                """{"code":"voice_dictation_unavailable","error":"Service temporarily unavailable for draftBefore=$secretDraft draftAfter=$secretDraftAfter contactHints=[$secretHintA]"}""",
                                HttpStatusCode.ServiceUnavailable,
                                headersOf("Content-Type" to listOf("application/json")),
                            )
                    }
                },
            ) { configureRaftKtorClient() }

        val executor =
            KtorRaftHttpExecutor(
                warningLogger = { diagnosticLogs += it },
                clients = RaftKtorClientProvider { _, _ -> mockHttpClient },
                requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
                transportFacts = { null },
                engineLabel = "ktor_test",
                uploadProgress = BodyWriterUploadProgressTracker,
                failedTraceIds = RaftRecentFailedTraceId(),
                recordHttpFailureTrace = httpErrorTraceRecorder::recordFailure,
            )
        val networkClient =
            SharedRaftNetworkClient(
                sessionStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = userSelf, email = "viewer@raft.build"),
                            accessToken = "access-token",
                            refreshToken = "refresh-token",
                            selectedServerId = serverA,
                            servers = listOf(StoredAuthServer(id = serverA, name = "Alpha")),
                        ),
                    ),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                warningLogger = { diagnosticLogs += it },
                criticalWarningLogger = { diagnosticLogs += it },
                requestScheduler = RaftRequestScheduler(logger = { diagnosticLogs += it }),
                executor = executor,
            )
        val permitApi = RaftVoiceDictationV2PermitApi(networkClient)

        // (a) 201 success
        scenario = "success"
        attempt = 0
        val ok =
            permitApi.requestPermit(
                serverId = serverA,
                language = "en-US",
                channelId = null,
                draftBefore = secretDraft,
                draftAfter = "\n$secretDraftAfter",
                contactHints = listOf(secretHintA, secretHintB),
            )
        assertIs<VoiceDictationV2PermitOutcome.Issued>(ok)
        assertTrue(sentBodies.last().contains(secretDraft), "wire payload must include draftBefore")
        assertTrue(sentBodies.last().contains(secretDraftAfter), "wire payload must include draftAfter")
        assertTrue(sentBodies.last().contains(secretHintA), "wire payload must include contactHints")

        // (b) 400 invalid_request rollback fallback -> retries once stripped -> 201 success
        scenario = "fallback_400_then_201"
        attempt = 0
        val recovered =
            permitApi.requestPermit(
                serverId = serverA,
                language = "en-US",
                channelId = null,
                draftBefore = secretDraft,
                draftAfter = "\n$secretDraftAfter",
                contactHints = listOf(secretHintA, secretHintB),
            )
        assertIs<VoiceDictationV2PermitOutcome.Issued>(recovered)
        assertEquals(2, attempt)
        val retryObj = Json.parseToJsonElement(sentBodies.last()).jsonObject
        assertFalse("draftBefore" in retryObj)
        assertFalse("draftAfter" in retryObj)
        assertFalse("contactHints" in retryObj)

        // (c) 503 failure (does not retry)
        scenario = "failure_503"
        attempt = 0
        val failed =
            permitApi.requestPermit(
                serverId = serverA,
                language = "en-US",
                channelId = null,
                draftBefore = secretDraft,
                draftAfter = "\n$secretDraftAfter",
                contactHints = listOf(secretHintA, secretHintB),
            )
        assertIs<VoiceDictationV2PermitOutcome.Refused>(failed)
        assertIs<VoiceDictationV2PermitFailure.Unavailable>(failed.failure)
        assertEquals(1, attempt)

        assertTrue(diagnosticLogs.isNotEmpty(), "expected diagnostic log lines from Ktor + SharedRaftNetworkClient")
        assertTrue(emittedSpans.isNotEmpty(), "expected mobile.http.client failure trace spans")

        val allDiagnostics = diagnosticLogs.joinToString("\n")
        val allSpansJson =
            emittedSpans.joinToString("\n") { span ->
                Json.encodeToString(RaftWebSpanRecord.serializer(), span)
            }
        for (forbidden in forbiddenTokens) {
            assertFalse(allDiagnostics.contains(forbidden, ignoreCase = true), "leaked '$forbidden' into diagnostics: $allDiagnostics")
            assertFalse(allSpansJson.contains(forbidden, ignoreCase = true), "leaked '$forbidden' into trace spans: $allSpansJson")
        }
    }

    @Test
    fun clientTraceAndLogSanitizersDropVoiceDictationContextFields() = runTest {
        val secretDraft = "SECRET_TRACE_DRAFT_001"
        val secretDraftAfter = "SECRET_TRACE_AFTER_003"
        val secretHint = "SECRET_TRACE_HINT_002"
        val sanitizedAttrs =
            sanitizeRaftClientTraceAttrs(
                buildJsonObject {
                    put("route", "voice/dictation/permits")
                    put("draftBefore", secretDraft)
                    put("draft_before", secretDraft)
                    put("voice.draft_before", secretDraft)
                    put("draftAfter", secretDraftAfter)
                    put("draft_after", secretDraftAfter)
                    put("voice.draft_after", secretDraftAfter)
                    put("contactHints", buildJsonArray { add(JsonPrimitive(secretHint)) })
                    put("contact_hints", buildJsonArray { add(JsonPrimitive(secretHint)) })
                    put("raw_payload", secretDraft)
                    put("token", "secret-token")
                    put(
                        "nested",
                        buildJsonObject {
                            put("keep", "ok")
                            put("draftBefore", secretDraft)
                            put("draftAfter", secretDraftAfter)
                            put(
                                "items",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("contactHints", secretHint)
                                            put("draft_after", secretDraftAfter)
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                },
            )
        val serializedAttrs = sanitizedAttrs.toString()
        assertEquals("voice/dictation/permits", sanitizedAttrs["route"]?.jsonPrimitive?.content)
        assertEquals("ok", sanitizedAttrs["nested"]?.jsonObject?.get("keep")?.jsonPrimitive?.content)
        for (
            forbidden in listOf(
                secretDraft,
                secretDraftAfter,
                secretHint,
                "secret-token",
                "draftBefore",
                "draft_before",
                "draftAfter",
                "draft_after",
                "contactHints",
                "contact_hints",
                "raw_payload",
                "token",
            )
        ) {
            assertFalse(serializedAttrs.contains(forbidden, ignoreCase = true), "leaked '$forbidden' into sanitized trace attrs: $serializedAttrs")
        }

        // Verify RaftClientTraceEventSender drops them on both emit() and emitSpan().
        val batches = mutableListOf<RaftWebTraceBatchRequest>()
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { 1_795_700_000_000L },
                defaultAttrs = { mapOf("clientPlatform" to "ios") },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                foreground = MutableStateFlow(true),
            ).apply {
                bindTransport(
                    object : RaftClientTraceTransport {
                        override suspend fun currentAccount(): String = userSelf

                        override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer =
                            RaftScopeAttestationAnswer(
                                status = 200,
                                response = RaftScopeAttestationResponse(attestation = "att-2", expiresAt = "2026-12-01T00:00:00Z"),
                            )

                        override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                            batches += batch
                            return 200
                        }
                    },
                )
                setAccount(userSelf)
            }
        sender.emit(
            serverId = serverA,
            name = "test.voice.event",
            attrs =
                buildJsonObject {
                    put("mode", "tap")
                    put("draftBefore", secretDraft)
                    put("draftAfter", secretDraftAfter)
                    put("contact_hints", buildJsonArray { add(JsonPrimitive(secretHint)) })
                },
        )
        sender.emitSpan(
            serverId = serverA,
            span =
                RaftWebSpanRecord(
                    traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                    spanId = "00f067aa0ba902b7",
                    name = "test.voice.span",
                    startTime = "2026-11-27T00:00:00.000Z",
                    endTime = "2026-11-27T00:00:01.000Z",
                    attrs =
                        buildJsonObject {
                            put("route", "voice/dictation/permits")
                            put("draft_before", secretDraft)
                            put("draft_after", secretDraftAfter)
                            put("contactHints", buildJsonArray { add(JsonPrimitive(secretHint)) })
                        },
                ),
            events = emptyList(),
        )
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 100L)
        runCurrent()

        val serializedBatch = Json.encodeToString(RaftWebTraceBatchRequest.serializer(), batches.single())
        assertTrue(serializedBatch.contains("test.voice.event"))
        assertTrue(serializedBatch.contains("test.voice.span"))
        for (
            forbidden in listOf(
                secretDraft,
                secretDraftAfter,
                secretHint,
                "draftBefore",
                "draft_before",
                "draftAfter",
                "draft_after",
                "contactHints",
                "contact_hints",
            )
        ) {
            assertFalse(serializedBatch.contains(forbidden, ignoreCase = true), "leaked '$forbidden' into trace batch: $serializedBatch")
        }

        // Verify RaftPlatformLogQueue / raftRedactLogMessage & toSafeDiagnosticsToken redact key/value & names.
        val rawLogLine =
            """{"draftBefore":"$secretDraft","draftAfter":"\n$secretDraftAfter","contactHints":["$secretHint"]} draft_before=$secretDraft draft_after=$secretDraftAfter contact_hints=[$secretHint]"""
        val redactedLog = raftRedactLogMessage(rawLogLine)
        val safeToken = rawLogLine.toSafeDiagnosticsToken()
        val queuedMessages = mutableListOf<String>()
        val queue =
            RaftPlatformLogQueue(
                scope = backgroundScope,
                recordSink = { record -> queuedMessages += record.message },
                volumePolicy = null,
            )
        assertTrue(queue.enqueue(RaftLogLevel.Warn, "VoiceTest", rawLogLine, RaftPlatformLogHint.None))
        queue.flush()

        for (output in listOf(redactedLog, safeToken, queuedMessages.single())) {
            for (
                forbidden in listOf(
                    secretDraft,
                    secretDraftAfter,
                    secretHint,
                    "draftBefore",
                    "draft_before",
                    "draftAfter",
                    "draft_after",
                    "contactHints",
                    "contact_hints",
                )
            ) {
                assertFalse(output.contains(forbidden, ignoreCase = true), "leaked '$forbidden' into log output: $output")
            }
        }
    }
}

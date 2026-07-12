package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mechanical gate for task #123's member-detail boundary contract.
 *
 * Generic member ids remain valid inside repositories, rosters, filters, and wire parsers. This gate only
 * watches the production declarations that move a member identity across projection, click, launch, and
 * detail-dispatch boundaries. Those declarations must carry [MemberDetailIdentity] as one non-separable
 * value, so a human id cannot reach an agent-only API after its kind was dropped.
 */
class MemberDetailIdentityBoundaryTest {

    private data class BoundaryFile(
        val path: String,
        val required: List<String>,
        val forbidden: List<String>,
    )

    private val boundaries = listOf(
        BoundaryFile(
            path = "build/raft/app/MemberDetailIdentity.kt",
            required = listOf(
                "internal data class MemberDetailIdentity(",
                "val memberId: MemberId",
                "val kind: MemberKind",
                "MemberDetailIdentity.dispatchMemberDetailLookup(",
            ),
            forbidden = listOf("val kind: MemberKind?"),
        ),
        BoundaryFile(
            path = "build/raft/app/data/SharedConversationDirectoryStore.kt",
            required = listOf("val peerIdentity: MemberDetailIdentity? = null"),
            forbidden = listOf("val peerMemberId:", "val peerMemberKind:"),
        ),
        BoundaryFile(
            path = "build/raft/app/SharedConversationHeaderProjection.kt",
            required = listOf(
                "val dmPeerIdentity: MemberDetailIdentity? = null",
                "dmPeerIdentity = directory.peerIdentity",
            ),
            forbidden = listOf("val dmPeerMemberId:", "val dmPeerMemberKind:"),
        ),
        BoundaryFile(
            path = "build/raft/app/MembersPage.kt",
            required = listOf(
                "val identity: MemberDetailIdentity? = null",
                "suspend fun cachedContext(\n        identity: MemberDetailIdentity",
                "suspend fun loadMember(\n        identity: MemberDetailIdentity",
                "fun KuiklyMembersPayload.toMemberDetailPageData(\n    identity: MemberDetailIdentity",
            ),
            forbidden = listOf(
                "fun KuiklyMembersPayload.toMemberDetailPageData(\n    memberId: String",
                "suspend fun cachedContext(\n        memberId: String",
                "suspend fun loadMember(\n        memberId: String",
            ),
        ),
        BoundaryFile(
            path = "build/raft/app/ThreadPage.kt",
            required = listOf(
                "val dmPeerIdentity: MemberDetailIdentity? = null",
                "onOpenMemberProfile: (KuiklyThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity)",
                "onOpenMemberProfile: (MemberDetailIdentity) -> Unit",
                "onOpenMember: (MemberDetailIdentity) -> Unit",
                "fun KuiklyThreadMemberItem.memberDetailIdentity(): MemberDetailIdentity? =\n    memberDetailIdentity(\n        memberId = id,\n        kind = kind.parsedMemberKindOrNull()",
                "fun KuiklyThreadMessageItem.memberDetailIdentity(): MemberDetailIdentity? =\n    memberDetailIdentity(\n        memberId = authorId,\n        kind = authorKind.parsedMemberKindOrNull()",
                "fun ForwardMessageItem.memberDetailIdentity(): MemberDetailIdentity? =\n    memberDetailIdentity(\n        memberId = authorId,\n        kind = authorKind.parsedMemberKindOrNull()",
            ),
            forbidden = listOf(
                "onOpenMemberProfile: (String",
                "onOpenMember: (String",
                "selectedMemberKind: MemberKind?",
                "if (kind.equals(\"agent\", ignoreCase = true)) MemberKind.Agent else MemberKind.Human",
                "if (authorKind.equals(\"agent\", ignoreCase = true)) MemberKind.Agent else MemberKind.Human",
                "fun ForwardMessageItem.forwardMemberKind(): MemberKind",
            ),
        ),
        BoundaryFile(
            path = "build/raft/app/SlockHomeComponents.kt",
            required = listOf(
                "onPinnedMemberClick: (MemberDetailIdentity) -> Unit",
                "ChannelRepository.openPinnedMemberDirectMessage(\n    identity: MemberDetailIdentity",
            ),
            forbidden = listOf(
                "onPinnedMemberClick: (kind: String, memberId: String)",
                "openPinnedMemberDirectMessage(\n    kind: String",
            ),
        ),
    )

    @Test
    fun memberDetailBoundariesCarryOneTypedIdentityAndNoLegacyPairs() {
        val commonMain = locateCommonMain()
        val violations = mutableListOf<String>()

        boundaries.forEach { boundary ->
            val file = File(commonMain, boundary.path)
            if (!file.isFile) {
                violations += "${boundary.path}: production boundary file is missing"
                return@forEach
            }
            val source = file.readText().replace("\r\n", "\n")
            boundary.required.forEach { required ->
                if (required !in source) {
                    violations += "${boundary.path}: missing typed boundary `${required.oneLine()}`"
                }
            }
            boundary.forbidden.forEach { forbidden ->
                if (forbidden in source) {
                    violations += "${boundary.path}: legacy bare-id boundary returned `${forbidden.oneLine()}`"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Member-detail projection/click/launch/dispatch boundaries must carry MemberDetailIdentity " +
                "instead of separable String id + nullable kind fields:\n${violations.joinToString("\n")}",
        )
    }

    private fun locateCommonMain(): File = requireNotNull(
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory),
    ) { "Could not locate shared commonMain Kotlin sources" }

    private fun String.oneLine(): String = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}

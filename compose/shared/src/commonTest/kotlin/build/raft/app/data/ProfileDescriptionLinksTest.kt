package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors botiverse/slock #14969 (`packages/shared/src/descriptionLinks.test.ts`) and #15230
 * (`ProfileDescription.tsx`): cutting plain-text profile descriptions around http(s) addresses
 * and resolving Workspace Artifact links against summaries (available, deleted, loading,
 * unavailable/failed) while leaving non-Artifact URLs untouched.
 */
class ProfileDescriptionLinksTest {
    private val hosts = listOf("app.raft.build", "staging.raft.build")
    private val shortId = "TUCt9hmScMEdBpHaTXatxr"
    private val artifactUrl = "https://app.raft.build/artifacts/$shortId"
    private val pinnedUrl = "https://app.raft.build/artifacts/$shortId/v/2"
    private val otherUrl = "https://github.com/botiverse/slock/pull/1"

    private fun sampleSummary(id: String = artifactCanonicalId(shortId), title: String = "Stamp 工作清单"): ArtifactSummary =
        ArtifactSummary(
            id = id,
            title = title,
            description = null,
            kind = ArtifactKind.Markdown,
            version = 1,
            pinned = false,
            latestVersion = 1,
            home = ArtifactHome.Server,
        )

    @Test
    fun descriptionWithNoAddressIsOnePieceOfText() {
        assertEquals(listOf(ProfileDescriptionPiece.Text("Stamp 负责人。")), profileDescriptionPieces("Stamp 负责人。"))
        assertEquals(emptyList(), profileDescriptionPieces(""))
    }

    @Test
    fun addressAfterCjkPunctuationAtEndIsCutOutWhole() {
        assertEquals(
            listOf(
                ProfileDescriptionPiece.Text("Stamp 负责人。工作清单："),
                ProfileDescriptionPiece.Link(artifactUrl),
            ),
            profileDescriptionPieces("Stamp 负责人。工作清单：$artifactUrl"),
        )
    }

    @Test
    fun fullWidthPunctuationStraightAfterAddressEndsIt() {
        for (mark in listOf("，", "。", "、", "；", "：", "！", "？", "）", "」", "】")) {
            assertEquals(
                listOf(
                    ProfileDescriptionPiece.Text("清单："),
                    ProfileDescriptionPiece.Link(artifactUrl),
                    ProfileDescriptionPiece.Text("${mark}其余"),
                ),
                profileDescriptionPieces("清单：$artifactUrl${mark}其余"),
                mark,
            )
        }
    }

    @Test
    fun sentencePunctuationAfterAddressStaysText() {
        assertEquals(
            listOf(
                ProfileDescriptionPiece.Text("See "),
                ProfileDescriptionPiece.Link(artifactUrl),
                ProfileDescriptionPiece.Text("."),
            ),
            profileDescriptionPieces("See $artifactUrl."),
        )
        assertEquals(
            listOf(
                ProfileDescriptionPiece.Text("(list: "),
                ProfileDescriptionPiece.Link(artifactUrl),
                ProfileDescriptionPiece.Text("), then"),
            ),
            profileDescriptionPieces("(list: $artifactUrl), then"),
        )
    }

    @Test
    fun multipleAddressesAreExtractedInOrderIncludingNonArtifactUrls() {
        val pieces = profileDescriptionPieces("清单 $artifactUrl 代码 $otherUrl 旧版 $pinnedUrl")
        assertEquals(
            listOf(
                ProfileDescriptionPiece.Text("清单 "),
                ProfileDescriptionPiece.Link(artifactUrl),
                ProfileDescriptionPiece.Text(" 代码 "),
                ProfileDescriptionPiece.Link(otherUrl),
                ProfileDescriptionPiece.Text(" 旧版 "),
                ProfileDescriptionPiece.Link(pinnedUrl),
            ),
            pieces,
        )
        assertEquals(listOf(artifactUrl, otherUrl, pinnedUrl), profileDescriptionUrls(pieces))
    }

    @Test
    fun piecesAlwaysReconstructOriginalDescription() {
        val samples =
            listOf(
                "a $artifactUrl，b http://x.test/y. c",
                "https:// alone",
                "https://...",
                "$artifactUrl$artifactUrl",
                "nb\u00a0$artifactUrl\u00a0end",
            )
        for (text in samples) {
            val joined =
                profileDescriptionPieces(text).joinToString("") { piece ->
                    when (piece) {
                        is ProfileDescriptionPiece.Text -> piece.text
                        is ProfileDescriptionPiece.Link -> piece.url
                    }
                }
            assertEquals(text, joined, text)
        }
    }

    @Test
    fun targetsDeduplicateByCanonicalKeyAndSkipNonArtifactUrls() {
        val canonicalId = artifactCanonicalId(shortId)
        val canonicalUrl = "https://app.raft.build/artifacts/$canonicalId"
        val lookalikeUrl = "https://app.raft.build.evil.example/artifacts/$shortId"
        val pieces = profileDescriptionPieces("$artifactUrl and $canonicalUrl and $pinnedUrl and $otherUrl and $lookalikeUrl")
        assertEquals(
            listOf(ArtifactLinkTarget(shortId), ArtifactLinkTarget(shortId, 2)),
            profileDescriptionArtifactTargets(pieces, hosts),
        )
    }

    @Test
    fun resolvesAvailableLoadingUnavailableFailedDeletedAndPlainLinks() {
        val targetLatest = ArtifactLinkTarget(shortId)
        val targetV2 = ArtifactLinkTarget(shortId, 2)
        val targetV3 = ArtifactLinkTarget(shortId, 3)
        val targetV4 = ArtifactLinkTarget(shortId, 4)
        val targetV5 = ArtifactLinkTarget(shortId, 5)
        val v2Url = "https://app.raft.build/artifacts/$shortId/v/2"
        val v3Url = "https://app.raft.build/artifacts/$shortId/v/3"
        val v4Url = "https://app.raft.build/artifacts/$shortId/v/4"
        val v5Url = "https://app.raft.build/artifacts/$shortId/v/5"

        val summary = sampleSummary()
        val deleted = ArtifactDeletedShown(title = "Old list", deletedBy = null, deletedAt = null)
        val answers =
            mapOf(
                targetLatest.key to ArtifactSummaryAnswer.Ok(summary),
                targetV2.key to ArtifactSummaryAnswer.Unavailable,
                targetV3.key to ArtifactSummaryAnswer.Failed,
                targetV4.key to ArtifactSummaryAnswer.Deleted(deleted),
                // targetV5 omitted -> Loading
            )
        val pieces = profileDescriptionPieces("A $artifactUrl B $v2Url C $v3Url D $v4Url E $v5Url F $otherUrl")
        val resolved = resolveProfileDescriptionSegments(pieces, hosts, answers, enabled = true)

        assertEquals(
            listOf(
                ProfileDescriptionSegment.Text("A "),
                ProfileDescriptionSegment.Artifact.Available(artifactUrl, targetLatest, summary),
                ProfileDescriptionSegment.Text(" B "),
                ProfileDescriptionSegment.Artifact.Unavailable(v2Url, targetV2),
                ProfileDescriptionSegment.Text(" C "),
                ProfileDescriptionSegment.Artifact.Unavailable(v3Url, targetV3),
                ProfileDescriptionSegment.Text(" D "),
                ProfileDescriptionSegment.Artifact.Deleted(v4Url, targetV4, deleted),
                ProfileDescriptionSegment.Text(" E "),
                ProfileDescriptionSegment.Artifact.Loading(v5Url, targetV5),
                ProfileDescriptionSegment.Text(" F "),
                ProfileDescriptionSegment.Text(otherUrl),
            ),
            resolved,
        )
        // Tooltip words substitute titles for readable/deleted Artifacts and keep URLs for unanswered ones.
        assertEquals(
            "A Stamp 工作清单 B $v2Url C $v3Url D Old list E $v5Url F $otherUrl",
            profileDescriptionPlainWords(resolved),
        )
    }

    @Test
    fun disabledFlagLeavesAllLinksAsPlainText() {
        val target = ArtifactLinkTarget(shortId)
        val pieces = profileDescriptionPieces("清单：$artifactUrl")
        val resolved =
            resolveProfileDescriptionSegments(
                pieces = pieces,
                appHosts = hosts,
                answers = mapOf(target.key to ArtifactSummaryAnswer.Ok(sampleSummary())),
                enabled = false,
            )
        assertEquals(
            listOf(
                ProfileDescriptionSegment.Text("清单："),
                ProfileDescriptionSegment.Text(artifactUrl),
            ),
            resolved,
        )
    }
}

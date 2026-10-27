package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Artifact link recognition (botiverse/slock feat/workspace-artifacts 542e922d6
 * `packages/shared/src/artifactRef.ts` and its test; web `artifactLinks.ts`), only on Raft's own
 * app hosts, never the content domain.
 */
class ArtifactLinksTest {
    private val hosts = listOf("app.raft.build", "staging.raft.build")
    private val id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"

    @Test
    fun recognisesArtifactPathsOnAppHosts() {
        val cases =
            listOf(
                "https://app.raft.build/artifacts/$id" to ArtifactLinkTarget(id),
                "https://app.raft.build/artifacts/$id/v/3" to ArtifactLinkTarget(id, 3),
                "https://APP.raft.build/artifacts/$id/v/12/index.html?x=1#y" to ArtifactLinkTarget(id, 12),
                "https://staging.raft.build/s/botiverse/artifacts/$id" to ArtifactLinkTarget(id),
                "https://app.raft.build:443/artifacts/$id" to ArtifactLinkTarget(id),
                "http://app.raft.build/artifacts/$id/" to ArtifactLinkTarget(id),
                // Exact host match: case, port and a trailing dot don't count.
                "https://app.raft.build./artifacts/$id" to ArtifactLinkTarget(id),
                "https://App.Raft.Build:8443/artifacts/$id" to ArtifactLinkTarget(id),
                // A URL's hostname is after the last `@`.
                "https://evil.example@app.raft.build/artifacts/$id" to ArtifactLinkTarget(id),
            )
        for ((link, expected) in cases) assertEquals(expected, parseArtifactLinkTarget(link, hosts), link)
        assertEquals("$id;version=3", ArtifactLinkTarget(id, 3).key)
        assertEquals(id, ArtifactLinkTarget(id).key)
    }

    @Test
    fun lookalikeHostsAndOtherPathsStayPlainLinks() {
        val plain =
            listOf(
                "https://evil.example/artifacts/$id",
                "https://app.raft.build.evil.example/artifacts/$id",
                "https://xapp.raft.build/artifacts/$id",
                // Siblings and subdomains of an app host are not it (no suffix matching).
                "https://w.raft.build/artifacts/$id",
                "https://evil.app.raft.build/artifacts/$id",
                "https://raft.build/artifacts/$id",
                "https://app.raft.build@evil.example/artifacts/$id",
                "https://app.raft.build/artifacts/not-an-id",
                "https://app.raft.build/artifacts/$id/v/0",
                "https://app.raft.build/artifacts/$id/v/x",
                "https://app.raft.build/s/botiverse/channels/c1",
                "https://app.raft.build/",
                "ftp://app.raft.build/artifacts/$id",
                "app.raft.build/artifacts/$id",
                // The redirect and legacy hosts are not read by this client.
                "https://artifacts.raft.build/$id",
                "https://raft-artifacts.com/p/botiverse/report",
            )
        for (link in plain) assertNull(parseArtifactLinkTarget(link, hosts), link)
    }

    @Test
    fun theContentDomainIsNeverRaftsOwn() {
        // Even a config that (wrongly) lists it changes nothing.
        val withContent = hosts + listOf("raftusercontent.com", "a1b2.w.raftusercontent.com")
        val links =
            listOf(
                "https://a1b2c3.w.raftusercontent.com/artifacts/$id",
                "https://a1b2c3.w.raftusercontent.com/$id/index.html",
                "https://raftusercontent.com/artifacts/$id",
                "https://a-pt-0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11.raftusercontent.com/",
            )
        for (link in links) {
            assertNull(parseArtifactLinkTarget(link, withContent), link)
            assertEquals(emptyList(), artifactMessageLinkTargets(link, withContent), link)
        }
    }

    @Test
    fun noHostsMeansNoArtifactLinks() {
        assertNull(parseArtifactLinkTarget("https://app.raft.build/artifacts/$id", emptyList()))
    }

    @Test
    fun aMessagesBareLinksAreItsTargetsOnceInOrder() {
        val content =
            """
            See https://app.raft.build/artifacts/$id and <https://app.raft.build/artifacts/$id/v/2>.
            Again https://app.raft.build/artifacts/$id, [words](https://app.raft.build/artifacts/pt_other)
            [https://staging.raft.build/artifacts/pt_bare](https://staging.raft.build/artifacts/pt_bare)
            `https://app.raft.build/artifacts/pt_code` and https://evil.example/artifacts/pt_evil
            """.trimIndent()
        assertEquals(
            listOf(ArtifactLinkTarget(id), ArtifactLinkTarget(id, 2), ArtifactLinkTarget("pt_bare")),
            artifactMessageLinkTargets(content, hosts),
        )
    }

    @Test
    fun aMessageNamesAtMostFiftyReferences() {
        val content = (1..60).joinToString(" ") { "https://app.raft.build/artifacts/pt_$it" }
        val targets = artifactMessageLinkTargets(content, hosts)
        assertEquals(OBJECT_SUMMARIES_MAX_REFS, targets.size)
        assertEquals(ArtifactLinkTarget("pt_1"), targets.first())
    }

    @Test
    fun theWebUrlIsOnTheEnvironmentsWebOrigin() {
        assertEquals("https://staging.raft.build/artifacts/$id", artifactWebUrl("https://staging.raft.build/", ArtifactLinkTarget(id)))
        assertEquals("http://localhost:5173/artifacts/$id/v/4", artifactWebUrl("http://localhost:5173", ArtifactLinkTarget(id, 4)))
    }
}

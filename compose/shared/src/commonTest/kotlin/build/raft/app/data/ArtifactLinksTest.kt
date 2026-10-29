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
                // The fixed hosts of the shared parser, on any app host list.
                "https://artifacts.raft.build/$id" to ArtifactLinkTarget(id),
                "https://artifacts.raft.build/$id/v/2" to ArtifactLinkTarget(id, 2),
                "https://a-pt-0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11.raftusercontent.com/index.html" to ArtifactLinkTarget(id),
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
                // A legacy link names an Artifact by name, not id (not read).
                "https://raft-artifacts.com/p/botiverse/report",
                "https://artifacts.raft.build/not-an-id",
            )
        for (link in plain) assertNull(parseArtifactLinkTarget(link, hosts), link)
    }

    @Test
    fun theContentDomainIsNeverRaftsOwn() {
        // Even a config that (wrongly) lists it changes nothing; only the content origin's own
        // `a-pt-<uuid>` host names its Artifact (by host, see recognisesArtifactPathsOnAppHosts).
        val withContent = hosts + listOf("raftusercontent.com", "a1b2.w.raftusercontent.com")
        val links =
            listOf(
                "https://a1b2c3.w.raftusercontent.com/artifacts/$id",
                "https://a1b2c3.w.raftusercontent.com/$id/index.html",
                "https://raftusercontent.com/artifacts/$id",
                "https://a-pt-not-a-uuid.raftusercontent.com/",
                "https://x.a-pt-0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11.raftusercontent.com/",
            )
        for (link in links) {
            assertNull(parseArtifactLinkTarget(link, withContent), link)
            assertEquals(emptyList(), artifactMessageLinkTargets(link, withContent), link)
        }
    }

    @Test
    fun noHostsMeansNoAppHostArtifactLinks() {
        assertNull(parseArtifactLinkTarget("https://app.raft.build/artifacts/$id", emptyList()))
        // The fixed hosts don't depend on the list.
        assertEquals(ArtifactLinkTarget(id), parseArtifactLinkTarget("https://artifacts.raft.build/$id", emptyList()))
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
    fun aMessageReadsAtMostTenReferences() {
        // web useMessageLinkCards.ts MESSAGE_OBJECT_LINK_LIMIT; repeats don't count, in message order.
        val content = "https://app.raft.build/artifacts/pt_1 " + (1..30).joinToString(" ") { "https://app.raft.build/artifacts/pt_$it" }
        val targets = artifactMessageLinkTargets(content, hosts)
        assertEquals(MESSAGE_OBJECT_LINK_LIMIT, targets.size)
        assertEquals(10, MESSAGE_OBJECT_LINK_LIMIT)
        assertEquals((1..10).map { ArtifactLinkTarget("pt_$it") }, targets)
    }

    /**
     * slock staging 3804821160552389b4c7b9ec306317e12605f44d `packages/shared/src/artifactRef.test.ts`
     * "a short id is the same id as pt_<uuid>, bare and in every link form" (the link cases; bare
     * ids and names are not links here). Where web reads the short id as `pt_<uuid>`, the app keeps
     * it as written ([ArtifactLinkTarget.id]) and agrees on [ArtifactLinkTarget.canonicalId] / key.
     */
    @Test
    fun shortIdsInEveryLinkFormUpstreamVectors() {
        val webId = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val short = "2QthvfPeGfDnqwr7LG6itx"
        val appHosts = listOf("app.raft.build")
        val cases =
            listOf(
                "https://app.raft.build/artifacts/$short" to ArtifactLinkTarget(short),
                "https://app.raft.build/artifacts/$short/v/4?c=1#x" to ArtifactLinkTarget(short, 4),
                "https://app.raft.build/s/raft/artifacts/$short/v/2" to ArtifactLinkTarget(short, 2),
                "https://artifacts.raft.build/$short" to ArtifactLinkTarget(short),
            )
        for ((link, expected) in cases) {
            val parsed = parseArtifactLinkTarget(link, appHosts)
            assertEquals(expected, parsed, link)
            assertEquals(webId, parsed!!.canonicalId, link)
        }
        assertNull(parseArtifactLinkTarget("https://evil.example/artifacts/$short", appHosts))
        // Not a short id: one character short, out of the alphabet, or over 16 bytes.
        for (bad in listOf(short.substring(1), short.substring(0, 21) + "0", "YcVfxkQb6JRzqk5kF2tNLw")) {
            assertNull(parseArtifactLinkTarget("https://app.raft.build/artifacts/$bad", appHosts), bad)
            assertNull(artifactIdOrNull(bad), bad)
        }
        // parseArtifactId: a bare id exactly as written.
        assertEquals(short, artifactIdOrNull(short))
        assertEquals(webId, artifactIdOrNull(webId))
        for (bad in listOf("", " $webId", "$short ", "workspace-prototype", "https://app.raft.build/artifacts/$short")) assertNull(artifactIdOrNull(bad), bad)
        // A 22-character name of digits and lowercase that starts with a digit decodes: an id.
        assertEquals("pt_04b0e052-5c59-038c-dd43-b1f482e10dc9", artifactCanonicalId("1abcdefghijkmnopqrstuv"))
        // Lowercase-led, it is over 16 bytes: not an id.
        assertNull(parseArtifactLinkTarget("https://app.raft.build/artifacts/abcdefghijkmnopqrstuvw", appHosts))
        // A pt_ id (fixtures' too) is its own canonical id.
        assertEquals("pt_fake-roadmap", artifactCanonicalId("pt_fake-roadmap"))
    }

    @Test
    fun aShortLinkAndItsPtLinkAreOneReference() {
        val webId = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val short = "2QthvfPeGfDnqwr7LG6itx"
        assertEquals("$webId;version=3", ArtifactLinkTarget(short, 3).key)
        assertEquals(ArtifactLinkTarget(webId).key, ArtifactLinkTarget(short).key)
        // As web's distinct-by-objectRefKey: the first link's form is kept.
        val content = "https://app.raft.build/artifacts/$short and https://app.raft.build/artifacts/$webId"
        assertEquals(listOf(ArtifactLinkTarget(short)), artifactMessageLinkTargets(content, hosts))
        // The viewer's page gets the short id as written.
        assertEquals("https://app.raft.build/artifacts/$short/v/2", artifactWebUrl("https://app.raft.build", ArtifactLinkTarget(short, 2)))
    }

    @Test
    fun theWebUrlIsOnTheEnvironmentsWebOrigin() {
        assertEquals("https://staging.raft.build/artifacts/$id", artifactWebUrl("https://staging.raft.build/", ArtifactLinkTarget(id)))
        assertEquals("http://localhost:5173/artifacts/$id/v/4", artifactWebUrl("http://localhost:5173", ArtifactLinkTarget(id, 4)))
    }
}

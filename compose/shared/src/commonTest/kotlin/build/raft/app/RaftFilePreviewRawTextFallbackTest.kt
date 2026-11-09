package build.raft.app

import build.raft.app.data.AuthorityBoundRaftNetworkClient
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.boundTo
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Hands f1e17cd5: a 17.3 KB `.tsv` showed "Preview unavailable — couldn't load text preview".
 * The server has no TSV provider and no `.tsv` text extension (slock staging
 * `packages/shared/src/attachmentPreview.ts` isTextPreviewCandidate), so an
 * `application/octet-stream` TSV gets the 47-byte `{"status":"unsupported","reason":"unsupported"}`
 * and the client falls back to the raw body via the signed URL. File Preview runs every request
 * through the route-authority [boundTo] client, which did not forward `requestRawAbsoluteUrl`:
 * the interface default answered 501 before any I/O.
 */
class RaftFilePreviewRawTextFallbackTest {

    @Test
    fun anUnsupportedServerPreviewTsvFallsBackToItsRawTextThroughTheRouteBoundClient() = runTest {
        val tsv =
            buildString {
                append("page\towner\tstatus\tupdated_at\n")
                repeat(300) { index ->
                    append("pages/feature-$index.md\tagent-$index\tfrozen\t2026-11-01T12:00:00Z\n")
                }
            }
        val network = FakeRouteBoundNetwork(rawBody = tsv)
        val bound = network.boundTo(
            RaftExpectedAuthBinding(
                accountId = "acct",
                accessToken = "access",
                refreshToken = "refresh",
                serverId = "srv",
                environmentId = "https://api.raft.test/api",
            )
        )
        val payload =
            RaftThreadAttachmentItem(
                id = "att-tsv",
                name = "botiverse-pages-pre-freeze.tsv",
                mimeType = "application/octet-stream",
            ).toThreadAttachmentLazyPreviewPayload(kind = "text")

        val loaded =
            assertNotNull(
                payload.loadThreadAttachmentPreview(
                    threadApi = RaftThreadApiClient(NetworkRaftThreadApi(bound)),
                    networkClient = bound,
                    priority = DataRequestPriority.Foreground,
                    truncatedPreviewHint = "(truncated)",
                )
            )

        assertNull(loaded.error)
        assertEquals("text", loaded.kind)
        assertEquals(tsv, loaded.content)
        assertEquals(
            listOf(
                "GET attachments/att-tsv/preview",
                "GET attachments/att-tsv/url",
                "RAW https://files.raft.test/botiverse-pages-pre-freeze.tsv",
            ),
            network.calls,
        )
    }
}

private class FakeRouteBoundNetwork(
    private val rawBody: String,
) : RaftNetworkClient, AuthorityBoundRaftNetworkClient {
    val calls = mutableListOf<String>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse = error("File Preview must use the route-bound request")

    override suspend fun requestBound(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse {
        calls += "$method $path"
        return when (path) {
            // Server registry answer when no provider matches (47 bytes, as in the ticket log).
            "attachments/att-tsv/preview" ->
                HomeNetworkResponse(code = 200, bodyText = """{"status":"unsupported","reason":"unsupported"}""")
            "attachments/att-tsv/url" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"url":"https://files.raft.test/botiverse-pages-pre-freeze.tsv?X-Amz-Signature=redacted","expiresAt":"2026-11-02T10:00:00.000Z"}"""
                )
            else -> HomeNetworkResponse(code = 404, bodyText = null)
        }
    }

    override suspend fun uploadAttachmentsBound(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += "RAW ${url.substringBefore('?')}"
        return HomeNetworkResponse(code = 200, bodyText = rawBody)
    }
}

package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExternalSharePersistenceTest {
    @Test
    fun durableDraftRoundTripsAllResumeAndDeliveryIdentity() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val repository = KeyValueExternalShareDraftRepository(keyValueStore, ownerAccountId = "account-a")
        val target =
            ForwardMessageTarget(
                serverId = ServerId("server-a"),
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "channel-a",
                title = "general",
                channelId = ChannelId("channel-a"),
                channelKind = ConversationKind.Channel,
                joined = true,
                canForwardNow = true
            )
        val draft =
            ExternalShareDraft(
                envelope =
                    ExternalShareEnvelope(
                        token = "opaque-token_1234567890",
                        createdAtEpochMillis = 42L,
                        sourcePlatform = "android",
                        text = "hello",
                        items = listOf(ExternalShareItem("native://item", "photo.jpg", "image/jpeg", 12L))
                    ),
                serverId = "server-a",
                stage = ExternalShareDraftStage.Delivering,
                selectedTargets = listOf(target),
                comment = "note",
                deliveries =
                    listOf(
                        ExternalShareDelivery(
                            target = target,
                            deliveryId = "delivery-stable",
                            status = ExternalShareDeliveryStatus.Failed,
                            errorCode = "network"
                        )
                    ),
                envelopeConsumed = true
            )

        assertIs<DataResult.Content<Unit>>(repository.save(draft))
        assertEquals(draft, assertIs<DataResult.Content<ExternalShareDraft>>(repository.load(draft.envelope.token)).data)
        assertIs<DataResult.Content<Unit>>(repository.delete(draft.envelope.token))
        assertIs<DataResult.Empty>(repository.load(draft.envelope.token))
        Unit
    }

    @Test
    fun invalidTokenCannotAddressDurableStorage() = runBlocking {
        val repository =
            KeyValueExternalShareDraftRepository(
                InMemoryRaftKeyValueStore(),
                ownerAccountId = "account-a"
            )

        val result = repository.load("../../private")

        assertEquals(
            "external_share_identity_invalid",
            assertIs<DataResult.Error>(result).error.code
        )
        Unit
    }

    @Test
    fun durableDraftsAreIsolatedByCanonicalAccount() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val accountA = KeyValueExternalShareDraftRepository(keyValueStore, "account-a")
        val accountB = KeyValueExternalShareDraftRepository(keyValueStore, "account-b")
        val draft =
            ExternalShareDraft(
                envelope = ExternalShareEnvelope("opaque-token_1234567890", 1L, "android", text = "private"),
                serverId = "server-a",
                stage = ExternalShareDraftStage.SelectingTargets,
                envelopeConsumed = true
            )

        assertIs<DataResult.Content<Unit>>(accountA.save(draft))
        assertEquals(draft, assertIs<DataResult.Content<ExternalShareDraft>>(accountA.load(draft.envelope.token)).data)
        assertIs<DataResult.Empty>(accountB.load(draft.envelope.token))
        Unit
    }

    @Test
    fun unknownTargetKindFailsClosedInsteadOfDefaulting() = runBlocking {
        val token = "opaque-token_1234567890"
        val keyValueStore =
            InMemoryRaftKeyValueStore(
                mapOf(
                    "external-share:draft:v1:account-a:$token" to
                        """{"envelope":{"token":"$token","createdAtEpochMillis":1,"sourcePlatform":"android","text":"private"},"serverId":"server-a","stage":"Confirming","selectedTargets":[{"serverId":{"value":"server-a"},"kind":"UnknownKind","id":"channel-a","title":"general"}]}"""
                )
            )
        val repository = KeyValueExternalShareDraftRepository(keyValueStore, "account-a")

        val result = repository.load(token)

        assertEquals("external_share_draft_decode_failed", assertIs<DataResult.Error>(result).error.code)
        Unit
    }
}

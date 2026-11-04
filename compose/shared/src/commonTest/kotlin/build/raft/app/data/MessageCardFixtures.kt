package build.raft.app.data

/*
 * RFC 082 card message payloads, copied from botiverse/slock staging
 * 1e9266662c9a7853f798a919a157f8d0c4b5b1af:
 *  - [messageCardBuildInputJson]: `buildCard` of `packages/shared/src/messageCard.test.ts` (Hands'
 *    TestFlight build card), as a sender writes it (defaults left out).
 *  - [messageCardBuildStoredJson]: the same card as Raft stores and serves it, defaults filled in
 *    (`messageCardSchema`; the test's first case pins the Text and Stack defaults).
 *  - [messageCardUpdateTestCardJson]: `buildCard(state)` of
 *    `packages/server/src/routes/internalAgentApi.messageCardUpdate.test.ts`, stored.
 *  - [messageCardRowJson]: a `GET /api/messages/channel/:id` row (the sync log's `message` row and
 *    the `message:updated` payload: `messageRealtimeEvents.ts` + the row's `actionMetadata`) of that
 *    update test's card after its update: revision 2, the derived fallback as `content`.
 */

internal const val messageCardBuildInputJson =
    """{"version":1,"root":"root","elements":{""" +
        """"root":{"type":"Stack","props":{},"children":["title","meta","links"]},""" +
        """"title":{"type":"Text","props":{"text":"Raft App 1.19.0 (11900001)"}},""" +
        """"meta":{"type":"Stack","props":{"direction":"row","wrap":true},"children":["state","groups","since","took"]},""" +
        """"state":{"type":"Badge","props":{"text":"In review","tone":"review"}},""" +
        """"groups":{"type":"Text","props":{"text":"Internal, Botiverse","tone":"muted"}},""" +
        """"since":{"type":"RelativeTime","props":{"at":"2026-11-03T12:03:00Z"}},""" +
        """"took":{"type":"Duration","props":{"start":"2026-11-03T11:40:00Z","end":"2026-11-03T12:03:00Z"}},""" +
        """"links":{"type":"Button","props":{"label":"Open in App Store Connect","action":{"kind":"navigate","url":"https://appstoreconnect.apple.com/apps/1"}}}""" +
        """}}"""

internal const val messageCardBuildStoredJson =
    """{"version":1,"root":"root","elements":{""" +
        """"root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["title","meta","links"]},""" +
        """"title":{"type":"Text","props":{"text":"Raft App 1.19.0 (11900001)","tone":"default","mono":false}},""" +
        """"meta":{"type":"Stack","props":{"direction":"row","wrap":true},"children":["state","groups","since","took"]},""" +
        """"state":{"type":"Badge","props":{"text":"In review","tone":"review"}},""" +
        """"groups":{"type":"Text","props":{"text":"Internal, Botiverse","tone":"muted","mono":false}},""" +
        """"since":{"type":"RelativeTime","props":{"at":"2026-11-03T12:03:00Z"}},""" +
        """"took":{"type":"Duration","props":{"start":"2026-11-03T11:40:00Z","end":"2026-11-03T12:03:00Z"}},""" +
        """"links":{"type":"Button","props":{"label":"Open in App Store Connect","variant":"default","action":{"kind":"navigate","url":"https://appstoreconnect.apple.com/apps/1"}}}""" +
        """}}"""

internal fun messageCardUpdateTestCardJson(state: String): String =
    """{"version":1,"root":"root","elements":{""" +
        """"root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["title","state"]},""" +
        """"title":{"type":"Text","props":{"text":"Raft App 1.19.0 (11900001)","tone":"default","mono":false}},""" +
        """"state":{"type":"Badge","props":{"text":"$state","tone":"neutral"}}""" +
        """}}"""

internal fun messageCardMetadataJson(card: String, revision: Int, updatedAt: String): String =
    """{"kind":"message-card","card":$card,"revision":$revision,"updatedAt":"$updatedAt"}"""

/** The update test's message after `buildCard("Ready for testing")` replaced its card (revision 2). */
internal fun messageCardRowJson(
    id: String = "m1",
    channelId: String = "channel-1",
    state: String = "Ready for testing",
    revision: Int = 2,
    updatedAt: String = "2026-11-03T12:10:00.000Z",
): String =
    """{"id":"$id","seq":41,"channelId":"$channelId","senderType":"agent","senderId":"agent-hands",""" +
        """"senderName":"HandsBot","messageType":"chat","content":"Raft App 1.19.0 (11900001) · $state",""" +
        """"randomId":null,"threadId":null,"createdAt":"2026-11-03T12:00:00.000Z","updatedAt":"$updatedAt",""" +
        """"attachments":[],"reactions":[],"mentions":[],""" +
        """"actionMetadata":${messageCardMetadataJson(messageCardUpdateTestCardJson(state), revision, updatedAt)}}"""

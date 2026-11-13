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

/*
 * A card whose Button carries `open: "beside"` (web preview, botiverse/slock #14133, staging
 * 1e66a2f326ae6c208a1c68d987f156f239e91a2d): `beside` of
 * `packages/server/src/routes/internalAgentApi.messageCards.test.ts:130-135`, which the Server
 * stores and serves with `open` as sent (`messageCardButtonElement`, `packages/shared/src/messageCard.ts:152-161`).
 */
internal const val messageCardOpenBesideInputJson =
    """{"version":1,"header":{"title":"Preview of the landing page"},"root":"go","elements":{""" +
        """"go":{"type":"Button","props":{"label":"Open preview","action":{"kind":"navigate","url":"https://preview.example.com/"},"open":"beside"}}""" +
        """}}"""

/** [messageCardOpenBesideInputJson] as stored (`variant` defaulted; `open` has no default and stays as sent). */
internal const val messageCardOpenBesideStoredJson =
    """{"version":1,"header":{"title":"Preview of the landing page"},"root":"go","elements":{""" +
        """"go":{"type":"Button","props":{"label":"Open preview","variant":"default","action":{"kind":"navigate","url":"https://preview.example.com/"},"open":"beside"}}""" +
        """}}"""

/*
 * RFC 086 App-sent cards (botiverse/slock #14723 @ 3a036f809943760a0fee9b6250784a3964b2b720):
 *  - [messageCardActingStoredJson]: `acting_buttons_as_stored` of `packages/shared/src/messageCard.v1.vector.json`
 *    (its `parsed`): Approve / Deny as Raft stores them, navigate Buttons to the App's fallback page with
 *    the reason Raft writes for older clients.
 *  - [messageCardActingActionsJson]: `ACTIONS` of `packages/server/src/routes/messageCardActions.api.test.ts`,
 *    stored beside the card as `actions`.
 *  - [messageCardActingFallback]: the vector case's `fallback` (the message's content when the App wrote none).
 *  - The read answers ([messageCardReadBody]) follow that test's `readCard` answers:
 *    `{ items: [], prompts, message_card: { card_revision, actions, waiting_for?, consent?, app? } }`.
 */
internal const val MessageCardActingPage = "https://vault.example.com/requests/42"

internal const val messageCardActingStoredJson =
    """{"version":1,"header":{"title":"Access request: deploy key"},"root":"root","elements":{""" +
        """"root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["what","row"]},""" +
        """"what":{"type":"Text","props":{"text":"builder-bot asks to use the deploy key until 18:00 UTC.","tone":"default","mono":false}},""" +
        """"row":{"type":"Stack","props":{"direction":"row","wrap":false},"children":["approve","deny"]},""" +
        """"approve":{"type":"Button","props":{"label":"Approve","variant":"default","action":{"kind":"navigate","url":"$MessageCardActingPage"},"disabledReason":"Update Raft to use this button"}},""" +
        """"deny":{"type":"Button","props":{"label":"Deny","variant":"ghost","action":{"kind":"navigate","url":"$MessageCardActingPage"},"disabledReason":"Update Raft to use this button"}}""" +
        """}}"""

internal const val messageCardActingActionsJson =
    """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare"},"deny":{"kind":"execute","action_id":"deny","undoWindowMs":5000}}"""

internal const val messageCardActingFallback =
    "**Access request: deploy key**\n\nbuilder-bot asks to use the deploy key until 18:00 UTC.\n\n" +
        "Approve (disabled: Update Raft to use this button) · Deny (disabled: Update Raft to use this button)"

/** The stored `actionMetadata` of an App's card with acting buttons (RFC 086 §4.1), for [forUserId] when given. */
internal fun messageCardActingMetadataJson(
    actions: String = messageCardActingActionsJson,
    forUserId: String? = "user-alice",
    revision: Int = 1,
): String =
    """{"kind":"message-card","card":$messageCardActingStoredJson,"revision":$revision,"updatedAt":"2026-11-13T09:00:00.000Z",""" +
        """"actions":$actions${forUserId?.let { ""","for":"$it"""" } ?: ""}}"""

/** A card message read's answer: `message_card` as given and the read's `prompts`. */
internal fun messageCardReadBody(messageCard: String, prompts: String = "[]"): String =
    """{"items":[],"prompts":$prompts,"message_card":$messageCard}"""

internal const val MessageCardActingApp = """{"id":"client-vault","name":"Vault","icon":null}"""

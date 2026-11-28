package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

private const val ActionCardMetadataPrefix = "actionCard."

private val threadActionCardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
@RaftCoreInternalApi data class ThreadActionCard(
    val type: String,
    val state: String = "prepared",
    val name: String = "",
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val suggestedComputer: String? = null,
    val requiredComputer: String? = null,
    /**
     * `agent:create`: the runtime the Create Agent dialog starts on, as the preparing agent's
     * suggestion (wire `action.suggestedRuntime`, optional). Read leniently: absent, blank or not a
     * string is none. A suggestion only; the person still picks.
     */
    val suggestedRuntime: String? = null,
    /**
     * `agent:create`: non-secret environment variables proposed by the preparing agent
     * (wire `action.suggestedEnvVars`, botiverse/slock #16341). Shown open and editable in the
     * Create agent dialog; config directories start unticked with a warning.
     */
    val suggestedEnvVars: Map<String, String> = emptyMap(),
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    /**
     * Who executed the card (wire `executedByUserId`). Read only to tell whether the viewer is the
     * sender (a done `feedback:submit` card's "View in Settings", S0.3). Carried by both the REST
     * and the canonical V2 paths (manifest v9, #3739); when absent, the App treats the viewer as not
     * the sender.
     */
    val executedByUserId: String? = null,
    val resultKind: String? = null,
    val resultId: String? = null,
    /** `agent-migration` result: the target computer id the agent started moving to. */
    val resultTargetMachineId: String? = null,
    /** `product-feedback` result (S0.3): the Hands ticket id, and the Hands app it was filed into (wire `source`). */
    val resultTicketId: String? = null,
    val resultSource: String? = null,
    val resultName: String? = null,
    val resultChannelName: String? = null,
    val resultAddedHumanIds: List<String> = emptyList(),
    val resultAddedAgentIds: List<String> = emptyList(),
    val requestId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    /** `integration:connect_mcp`: the MCP App id (`action.mcpServerId` or `result.mcpServerId`). */
    val mcpServerId: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val scopes: List<String> = emptyList(),
    /**
     * `integration:install_marketplace_app`: the app's login scopes and data groups the server
     * bound to the card at prepare time (wire `installScopes` / `installGroups`, audit V-85).
     * Installing approves exactly these. Null (absent on the wire) is a card prepared before the
     * binding existed, which can't be installed; an empty list is "none".
     */
    val installScopes: List<String>? = null,
    val installGroups: List<String>? = null,
    /**
     * `integration:install_marketplace_app` with app prompt-text consent (slock #proj-apps task
     * #4): the app's prompt-text revision the server bound to the card (wire
     * `installTextRevisionId`) and its raw text (wire `installText`). Installing confirms exactly
     * this revision, so the confirmation must show it.
     */
    val installTextRevisionId: String? = null,
    val installText: ThreadActionCardInstallText? = null,
    /** `integration:recover_app_owner`: the agent id that becomes the app's owner. */
    val targetAgent: String? = null,
    val metadataItems: List<ThreadActionCardMetadataItem> = emptyList(),
    val presentationTitle: String? = null,
    val presentationConfirmLabel: String? = null,
    val presentationWarning: String? = null,
    val genericApprovalAllowed: Boolean = false,
    /**
     * The card's own server (wire `sourceServerId`, fixed at prepare time). In a joint channel it
     * can differ from the server the card is viewed in.
     */
    val sourceServerId: String? = null,
    /** Display name of [sourceServerId] at prepare time (wire `targetServerName`): "Acts on <name>". */
    val targetServerName: String? = null,
    /**
     * The card's optimistic credential (wire `confirmationVersion`): sent back as
     * `expectedConfirmationVersion` when it is run, so a card converted meanwhile is refused.
     */
    val confirmationVersion: Int? = null,
    /**
     * Wire `presentation.card` (`action-card.v1`) exactly as the server sent it, kept only when the
     * contract's read parse accepts it. The server's JSON, not the parsed card written back: a read
     * card may hold `unsupported` markers, which the contract refuses as input.
     */
    val presentationCardJson: String? = null,
    /**
     * The card's deadline (wire top-level `expiresAt`, RFC 3339 UTC, served only, never stored;
     * slock #13254): on every `prepared` card, prepared + 30 days or the type's own deadline (a
     * question card, a seat request, a human-started plan upgrade); absent without one. A 30-day
     * card past it is served `expired` and still carries it: it dates the "Expired on" line. Past
     * it on the client clock the App draws the card expired too ([expiredAsOf]); the server's 409
     * is final.
     */
    val expiresAt: String? = null,
    /**
     * Who asked for the card (wire `requesterUserId`): a person's request (a seat or Pro request,
     * web person card), whose expired line is "No owner answered within 7 days".
     */
    val requesterUserId: String? = null,
    /**
     * The agent that prepared the card (wire `requesterAgentId`; slock #16341): names the proposer
     * for `agent:create`'s `suggestedEnvVars` when visible in this viewer's server roster.
     */
    val requesterAgentId: String? = null,
    /**
     * `question:ask`: the person it asks (wire `action.addressee`, `{kind:"user", userId, handle}`,
     * set by the server at prepare). Null with [questionAsksAnyone] false: the server sent no
     * addressee (an older card), so the App treats every viewer as asked and lets the server decide.
     */
    val questionAddresseeUserId: String? = null,
    /** The asked person's handle as the server stored it: a name of last resort, never preferred to the member's display name. */
    val questionAddresseeHandle: String? = null,
    /** `question:ask` asked of anyone in the channel (wire `action.addressee.kind == "anyone"`). */
    val questionAsksAnyone: Boolean = false,
    /**
     * `question:ask`: each question's kind (`single`, `multiple`, `text`) and option count (wire
     * `action.questions`). An answered card's spec keeps no form, so this is all that says it asked one
     * short single choice ([questionCardLayout]).
     */
    val questions: List<ThreadActionCardQuestion> = emptyList(),
    /**
     * `question:ask`: how long the card waited for its answer (wire `action.expiresInMinutes`); the
     * expired line says it ("didn't answer within 3 days"). Null on a card that has none.
     */
    val questionExpiresInMinutes: Int? = null,
    /** `question:ask`: the agent paged its questions (wire `action.pages` present); the server never places such a card inline. */
    val questionHasPages: Boolean = false,
    /**
     * `question:ask` (served only, never stored; slock #16473): true when the server supports
     * closing this prepared question card without answering (`POST /api/actions/:messageId/close`).
     * False on older servers and non-prepared cards so the client never renders a close button that
     * would fail.
     */
    val answererCanClose: Boolean = false,
    /** `question:ask`: when a person closed the card without answering (wire `closedAt`, ISO UTC; slock #16473). */
    val closedAt: String? = null,
    /**
     * `question:ask`: who closed the card without answering (wire `closedBy`, `{ userId, handle }`;
     * slock #16473). A closed card is served as `state = "expired"` with `closedBy` for older
     * clients, or `state = "closed"` directly; presence of [closedBy] tells a closed card apart
     * from an expiry.
     */
    val closedBy: ThreadActionCardClosedBy? = null,
    /**
     * `question:ask`: the person's message this card was withdrawn as handled by (wire `handledBy`,
     * `{ messageId, userId, handle, inCardThread }`; slock #17265). Set only when `state = "withdrawn"`.
     */
    val handledBy: ThreadActionCardHandledBy? = null,
    /** `seats:add`: seat count recorded when the card was prepared (wire `action.seats`). */
    val seatsAddCount: Int? = null,
    /**
     * `seats:add`: what the seat request is for (wire `action.purpose`, slock #16449, #16453).
     * Counted under the card's server's pricing returned by `GET /actions/:id/seat-request`.
     */
    val seatsAddPurpose: SeatsAddPurpose? = null,
    /**
     * `channel:invite_external` (slock #16530, #16671): how many addresses the agent prepared on
     * the card (wire `action.addresses`, 0..20; 0 means connect servers only). The addresses
     * themselves never ride on the message; they are read on button click via
     * `GET /api/actions/:messageId/external-invite-addresses`.
     */
    val externalInviteAddresses: Int? = null,
    /** `external-invites` result (slock #16530, #16671): address count recorded when a
     * `channel:invite_external` card was marked executed (wire `result.addresses`). When 0, the
     * frame hides the result line so it never shows "0 addresses on the card".
     */
    val resultAddresses: Int? = null,
    /**
     * `marketplace-app-installation` result: post-install setup URL (wire `result.setupUrl`).
     * Validated by the caller with `isSafeOAuthReturnUrl` before rendering "Continue setup".
     */
    val resultSetupUrl: String? = null,
    /**
     * `seats:add` outcome recorded on a completed seat request card (wire `seatOutcome`),
     * shown on its own line under who executed it.
     */
    val seatOutcome: ThreadActionCardSeatOutcome? = null,
) {
    /**
     * The card described as data ([parseActionCardSpec]), or null: no `presentation.card`, or one
     * this App cannot read, and the legacy presentation fields above are shown. Strip it with
     * [stripActionCardSpec] before rendering; entities without a `serverId` live on
     * [sourceServerId] ([ActionCardSpecEntityRef.serverIdOr]). Not rendered yet (A2).
     */
    val presentationCard: ActionCardSpecCard? by lazy { presentationCardJson?.let(::cachedActionCardSpec) }

    val isPrepared: Boolean
        get() = state.equals("prepared", ignoreCase = true)

    val isExecuted: Boolean
        get() = state.equals("executed", ignoreCase = true)

    /** Wire `frozen`: its channel is being converted; nobody can run it until that ends. */
    val isFrozen: Boolean
        get() = state.equals("frozen", ignoreCase = true)

    /** Wire `reconfirm_required`: something changed since it was prepared; it waits on Reconfirm. */
    val needsReconfirmation: Boolean
        get() = state.equals("reconfirm_required", ignoreCase = true)

    /**
     * Wire `closed`, or wire `expired` with [closedBy] (shared `isClosedActionCard`, slock #16473,
     * #16530): a person who could act on this `question:ask` or `channel:invite_external` card
     * closed or declined it without running it.
     */
    val isClosed: Boolean
        get() =
            (type.equals(QuestionAskActionCardType, ignoreCase = true) || isInviteExternalAction()) &&
                (state.equals("closed", ignoreCase = true) || (state.equals("expired", ignoreCase = true) && closedBy != null))

    /** Wire `expired`: past its deadline without running (a 30-day card, a question, a seat request). */
    val isExpired: Boolean
        get() = state.equals("expired", ignoreCase = true) && !isClosed

    /** Wire `withdrawn`, a state question and prepared-request cards reach (`raft action withdraw`): its agent took it back. */
    val isWithdrawn: Boolean
        get() =
            state.equals("withdrawn", ignoreCase = true) &&
                (type.equals(QuestionAskActionCardType, ignoreCase = true) || isPreparedRequestAction)

    /**
     * Wire `withdrawn` with [handledBy] (slock #17265): a `question:ask` card its agent withdrew
     * as handled by a person's message. Still counts as [isWithdrawn], [hasEnded], and [isTerminalState].
     */
    val isHandled: Boolean
        get() = type.equals(QuestionAskActionCardType, ignoreCase = true) && isWithdrawn && handledBy != null

    /** Any wire state this build does not know: treated as ended and view-only (never open/Prepared). */
    val isUnknownState: Boolean
        get() = !isPrepared && !isExecuted && !isFrozen && !needsReconfirmation && !isExpired && !isWithdrawn && !isClosed

    /** Ended without running ([isExpired], [isWithdrawn], [isClosed], or [isUnknownState]): folded, no button changes it any more. */
    val hasEnded: Boolean
        get() = isExpired || isWithdrawn || isClosed || isUnknownState
}

/** Who closed a `question:ask` card without answering (wire `closedBy`, slock #16473). */
@Serializable
@RaftCoreInternalApi data class ThreadActionCardClosedBy(
    val userId: String? = null,
    val handle: String? = null,
)

/**
 * The person's message a `question:ask` card was withdrawn as handled by (wire `handledBy`,
 * slock #17265): that message's id, its sender (id and handle, as [ThreadActionCardClosedBy]),
 * and whether it lives in the card's own thread (else in the card's conversation).
 */
@Serializable
@RaftCoreInternalApi data class ThreadActionCardHandledBy(
    val messageId: String,
    val userId: String,
    val handle: String,
    val inCardThread: Boolean = false,
)

@RaftCoreInternalApi const val QuestionAskActionCardType = "question:ask"

/** An agent-prepared request card (RFC 085, wire `action.type`): only the named addressee decides it. */
@RaftCoreInternalApi const val PreparedRequestActionCardType = "request:prepare"

@RaftCoreInternalApi val ThreadActionCard.isPreparedRequestAction: Boolean
    get() = type.equals(PreparedRequestActionCardType, ignoreCase = true)

/** A seat request (web person card `seats:add`): no owner answered within its 7 days once expired. */
@RaftCoreInternalApi const val SeatsAddActionCardType = "seats:add"

/** A Pro plan upgrade request (wire `action.type`): auto-marks Done once the server leaves Free. */
@RaftCoreInternalApi const val PlanUpgradeActionCardType = "plan:upgrade"

/**
 * What came of a completed `seats:add` request (wire `seatOutcome`, slock `SeatRequestOutcome`).
 * Unknown kinds or purposes are ignored leniently so newer server outcomes never break the card.
 */
@Serializable
@RaftCoreInternalApi sealed interface ThreadActionCardSeatOutcome {
    @Serializable
    @kotlinx.serialization.SerialName("invites")
    data class Invites(val sent: Int, val failed: Int) : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("promoted")
    data class Promoted(val name: String, val role: String) : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("ready_agent_create")
    data class ReadyAgentCreate(val name: String) : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("ready_assistant_create")
    data object ReadyAssistantCreate : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("ready_invite")
    data class ReadyInvite(val count: Int) : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("ready_member_promote")
    data class ReadyMemberPromote(val name: String, val role: String) : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("ready_join")
    data object ReadyJoin : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("requester_cannot")
    data object RequesterCannot : ThreadActionCardSeatOutcome

    @Serializable
    @kotlinx.serialization.SerialName("not_needed")
    data object NotNeeded : ThreadActionCardSeatOutcome
}

/**
 * Copy variant when a `plan:upgrade` card is already satisfied because the card's server left the
 * Free plan (web `useUpgradeCardPastFree`).
 */
@RaftCoreInternalApi enum class UpgradeCardPastFreeLine {
    /** Server is on `pro`: "Upgraded to Pro". */
    Pro,

    /** Server is on another non-free plan (e.g. founder/partner): "No longer limited by the Free plan". */
    NotFree,
}

/**
 * Pure frame derivation matching web `useUpgradeCardPastFree`: returns the satisfied-upgrade line
 * when this is a `plan:upgrade` card on the viewer's own server (not cross-server read-only) and
 * [currentServerPlan] is a known non-`free` plan. Nothing is written to the card; if the server
 * returns to `free`, the card reverts to open automatically.
 */
@RaftCoreInternalApi fun ThreadActionCard.upgradeCardPastFreeLine(
    viewerServerId: String?,
    currentServerPlan: String?,
): UpgradeCardPastFreeLine? {
    if (!type.equals(PlanUpgradeActionCardType, ignoreCase = true)) return null
    val source = sourceServerId?.trim()?.takeIf { it.isNotEmpty() }
    val viewer = viewerServerId?.trim()?.takeIf { it.isNotEmpty() }
    if (source != null && viewer != null && source != viewer) return null
    val plan = currentServerPlan?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (plan == "free") return null
    return if (plan == "pro") UpgradeCardPastFreeLine.Pro else UpgradeCardPastFreeLine.NotFree
}

/**
 * Post-install setup URL for a completed `integration:install_marketplace_app` card (web
 * `installedAppSetupUrl`): non-null only when executed with a `marketplace-app-installation` result
 * whose `setupUrl` passes [isSafeUrl] (`isSafeOAuthReturnUrl`).
 */
@RaftCoreInternalApi fun ThreadActionCard.installedAppSetupUrl(isSafeUrl: (String) -> Boolean): String? {
    if (!isExecuted || !isInstallMarketplaceAppAction()) return null
    if (resultKind != "marketplace-app-installation") return null
    val url = resultSetupUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return url.takeIf(isSafeUrl)
}

/** Cindy's Pro free trial offer in the owner's DM (wire `action.type`); a phone shows it as one line. */
@RaftCoreInternalApi const val PlanTrialActionCardType = "plan:trial"

/** [ThreadActionCard.expiresAt] as epoch millis; null when absent or unreadable. */
@RaftCoreInternalApi fun ThreadActionCard.expiresAtEpochMillis(): Long? = parseRaftTimestampEpochMillisOrNull(expiresAt)

/**
 * Whether the client clock ([nowMillis]) is past a `prepared` card's [ThreadActionCard.expiresAt]
 * (web `isActionCardShownExpired`). The client clock decides only whether buttons show and dialogs
 * open; the server's 409 is final.
 */
@RaftCoreInternalApi fun ThreadActionCard.isPastExpiry(nowMillis: Long): Boolean {
    if (!state.equals("prepared", ignoreCase = true)) return false
    val deadline = expiresAtEpochMillis() ?: return false
    return nowMillis > deadline
}

/** [this] as it is drawn at [nowMillis]: `expired` once past [ThreadActionCard.expiresAt], else unchanged. */
@RaftCoreInternalApi fun ThreadActionCard.expiredAsOf(nowMillis: Long): ThreadActionCard =
    if (isPastExpiry(nowMillis)) copy(state = "expired") else this

/**
 * [this] after the server refused it as expired (409 `ACTION_CARD_EXPIRED`, whose body has no
 * date): `expired`, dated by its own [ThreadActionCard.expiresAt] when it has one, else undated.
 * A done or ended card stays as it is.
 */
@RaftCoreInternalApi fun ThreadActionCard.markedExpired(): ThreadActionCard =
    if (isExecuted || hasEnded) this else copy(state = "expired")

/**
 * The server's 409 codes for a card refused because it expired (`{ error, errorCode }`): the
 * 30-day rule's (slock #13254, shared `ACTION_CARD_EXPIRED_ERROR_CODE`), a question card's,
 * and a seat request's (`EXPIRED`, slock `seatRequestService.ts`).
 */
@RaftCoreInternalApi val ActionCardExpiredErrorCodes: Set<String> = setOf("ACTION_CARD_EXPIRED", "QUESTION_EXPIRED", "EXPIRED")

/** Whether a refusal says the card expired ([ActionCardExpiredErrorCodes]). */
@RaftCoreInternalApi fun ScreenError.isActionCardExpiredRefusal(): Boolean =
    serverCode?.trim()?.uppercase()?.let { it in ActionCardExpiredErrorCodes } == true

/**
 * One question of a `question:ask` card as the layout needs it: its kind, how many options it
 * offers (Other not counted) and the width of its widest option label in columns, a CJK or
 * fullwidth character counting 2 (shared `questionAskPlacement`, web `searchSnippetCharWidth`).
 */
@Serializable
@RaftCoreInternalApi data class ThreadActionCardQuestion(
    val kind: String,
    val options: Int = 0,
    val widestOptionColumns: Int = 0,
)

/** [text]'s width in columns as the server's `questionAskPlacement` counts it: East Asian wide / fullwidth characters 2, others 1. */
@RaftCoreInternalApi fun questionLabelColumns(text: String): Int {
    var columns = 0
    var index = 0
    while (index < text.length) {
        val high = text[index]
        val codePoint =
            if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                index++
                (high.code - 0xD800 shl 10) + (text[index].code - 0xDC00) + 0x10000
            } else {
                high.code
            }
        columns += build.raft.app.raftSearchSnippetCharWidth(codePoint)
        index++
    }
    return columns
}

/** Wire `action.addressee` of a `question:ask` card: (userId, handle, anyone). */
private fun JsonObject.questionAddressee(): Triple<String?, String?, Boolean> {
    val addressee = this["addressee"] as? JsonObject ?: return Triple(null, null, false)
    return when (addressee.actionCardStringOrNull("kind")) {
        "user" -> Triple(addressee.actionCardStringOrNull("userId"), addressee.actionCardStringOrNull("handle"), false)
        "anyone" -> Triple(null, null, true)
        else -> Triple(null, null, false)
    }
}

/** Wire `action.questions` of a `question:ask` card, cut to what [ThreadActionCardQuestion] keeps. */
private fun JsonObject.questionShapes(): List<ThreadActionCardQuestion> =
    (this["questions"] as? JsonArray).orEmpty().mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        val kind = question.actionCardStringOrNull("kind") ?: return@mapNotNull null
        val options = (question["options"] as? JsonArray).orEmpty()
        val widest = options.maxOfOrNull { option -> (option as? JsonObject)?.actionCardStringOrNull("label")?.let(::questionLabelColumns) ?: 0 } ?: 0
        ThreadActionCardQuestion(kind, options.size, widest)
    }

/** Wire `installText`: the three fields an app adds to agent prompts. Raw publisher text. */
@Serializable
@RaftCoreInternalApi data class ThreadActionCardInstallText(
    val name: String = "",
    val description: String? = null,
    val whenToUse: String? = null,
)

@Serializable
@RaftCoreInternalApi data class ThreadActionCardMetadataItem(
    val key: String,
    val value: String,
    val redacted: Boolean = false,
)

@Serializable
@RaftCoreInternalApi data class WireActionCardPresentation(
    val title: String? = null,
    val confirmLabel: String? = null,
    val warning: String? = null,
    val genericApprovalAllowed: Boolean = false,
    val displayItems: List<ThreadActionCardMetadataItem> = emptyList(),
    /** `presentation.card` (`action-card.v1`), untrusted until [parseActionCardSpec] reads it. */
    val card: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = WireActionCardMetadataSerializer::class)
@RaftCoreInternalApi data class WireActionCardMetadata(
    val kind: String? = null,
    val version: Int? = null,
    val sourceServerId: String? = null,
    val targetServerName: String? = null,
    val confirmationVersion: Int? = null,
    val forwardedItems: List<WireForwardedBundleItem> = emptyList(),
    /**
     * Keep the canonical action object intact at the wire boundary. Mobile has
     * specialized renderers for a few action types, but a newer server can add
     * fields or action types before the app ships another release. Decoding
     * directly into [WireActionCardAction] would silently discard those fields
     * under `ignoreUnknownKeys`, leaving the fallback card blank.
     */
    val action: JsonObject? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val executedByUserId: String? = null,
    val result: WireActionCardResult? = null,
    val presentation: WireActionCardPresentation? = null,
    /** RFC 082 card message (`kind: "message-card"`): the card, untrusted until [parseRaftMessageCard] reads it. */
    val card: JsonElement? = null,
    /** RFC 082: the card's revision, 1 when posted. */
    val revision: Int? = null,
    /** RFC 082: when the card was posted or last replaced (ISO UTC). */
    val updatedAt: String? = null,
    /**
     * RFC 086 (App-sent cards): a card message's acting buttons, Button element id -> RFC 076
     * execute action, kept as JSON and read leniently ([readMessageCardActingButtons]).
     */
    val actions: JsonElement? = null,
    /** RFC 086: the one person (a user id) a card message's acting buttons are for. */
    @kotlinx.serialization.SerialName("for") val forUserId: JsonElement? = null,
    /** The card's deadline (served only, absent without one): [ThreadActionCard.expiresAt]. */
    val expiresAt: String? = null,
    /** A person's request: [ThreadActionCard.requesterUserId]. */
    val requesterUserId: String? = null,
    /** An agent-prepared card: [ThreadActionCard.requesterAgentId]. */
    val requesterAgentId: String? = null,
    /** `question:ask` (served only; slock #16473): whether answerer close is offered by the server, kept leniently as JSON. */
    val answererCanClose: JsonElement? = null,
    /** `question:ask`: when a person closed it without answering (slock #16473). */
    val closedAt: String? = null,
    /** `question:ask`: who closed it without answering (`{ userId, handle }`), kept leniently as JSON. */
    val closedBy: JsonElement? = null,
    /** `question:ask`: the person's message it was withdrawn as handled by (`{ messageId, userId, handle, inCardThread }`), kept leniently as JSON (slock #17265). */
    val handledBy: JsonElement? = null,
    /** `seats:add`: what came of a completed seat request (`SeatRequestOutcome`), kept leniently as JSON. */
    val seatOutcome: JsonElement? = null,
    /*
     * A channel announcement's change line (`kind: "channel-announcement"`, ChannelAnnouncementApi.kt):
     * kept as JSON so a field of a type this client doesn't expect fails only the notice
     * ([readChannelAnnouncementNotice]), never the whole message.
     */
    val change: JsonElement? = null,
    val actor: JsonElement? = null,
    val artifactId: JsonElement? = null,
    val title: JsonElement? = null,
    /*
     * A task's assignment line (`kind: "task-assignment"`, TaskAssignmentNotice.kt; it also reads
     * [actor]): kept as JSON for the same reason. An explicit `assignee: null` (cleared) and an
     * absent one both decode to null here; either way the line is no hand-off.
     *
     * For [previousAssignee] (botiverse/slock #15631), explicit `null` (nobody held it) must stay
     * apart from absent (older lines written before it was recorded), even under `coerceInputValues
     * = true`. [WireActionCardMetadataSerializer] records key presence in [previousAssigneePresent]
     * from the raw JSON object before field decoding runs.
     */
    val taskNumber: JsonElement? = null,
    val assignee: JsonElement? = null,
    val previousAssignee: JsonElement? = null,
    val previousAssigneePresent: Boolean = previousAssignee != null,
    val reason: JsonElement? = null,
    /*
     * A delegated-send receipt's lines (`kind: "delegated-receipt"`, AssistantDmCards.kt): kept as
     * JSON for the same reason.
     */
    val delegatedReceipt: JsonElement? = null,
    val delegatedReceipts: JsonElement? = null,
    val joinedChannels: JsonElement? = null,
    val joinedNotForwarded: JsonElement? = null,
)

/** The delegated-send receipt's metadata string this `actionMetadata` projects to; empty for anything else. */
@RaftCoreInternalApi fun WireActionCardMetadata.delegatedReceiptMetadata(): Map<String, String> {
    if (kind != DELEGATED_RECEIPT_KIND) return emptyMap()
    return delegatedReceiptMessageMetadata(
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive(DELEGATED_RECEIPT_KIND))
                delegatedReceipt?.let { put("delegatedReceipt", it) }
                delegatedReceipts?.let { put("delegatedReceipts", it) }
                joinedChannels?.let { put("joinedChannels", it) }
                joinedNotForwarded?.let { put("joinedNotForwarded", it) }
            }
        )
    )
}

/** The channel announcement notice this `actionMetadata` carries, read as the web reads it; null for anything else. */
@RaftCoreInternalApi fun WireActionCardMetadata.channelAnnouncementNotice(): ChannelAnnouncementNotice? {
    if (kind != CHANNEL_ANNOUNCEMENT_NOTICE_KIND) return null
    return readChannelAnnouncementNotice(
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive(CHANNEL_ANNOUNCEMENT_NOTICE_KIND))
                change?.let { put("change", it) }
                actor?.let { put("actor", it) }
                artifactId?.let { put("artifactId", it) }
                title?.let { put("title", it) }
            }
        )
    )
}

/** The task assignment notice this `actionMetadata` carries, read as the web reads it; null for anything else. */
@RaftCoreInternalApi fun WireActionCardMetadata.taskAssignmentNotice(): TaskAssignmentNotice? {
    if (kind != TASK_ASSIGNMENT_NOTICE_KIND) return null
    return readTaskAssignmentNotice(
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive(TASK_ASSIGNMENT_NOTICE_KIND))
                taskNumber?.let { put("taskNumber", it) }
                actor?.let { put("actor", it) }
                put("assignee", assignee ?: JsonNull)
                if (previousAssigneePresent) put("previousAssignee", previousAssignee ?: JsonNull)
                reason?.let { put("reason", it) }
            }
        )
    )
}

@OptIn(ExperimentalSerializationApi::class)
@RaftCoreInternalApi object WireActionCardMetadataSerializer :
    JsonTransformingSerializer<WireActionCardMetadata>(WireActionCardMetadata.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return JsonObject(element + ("previousAssigneePresent" to JsonPrimitive("previousAssignee" in element)))
    }

    override fun transformSerialize(element: JsonElement): JsonElement =
        if (element is JsonObject && "previousAssigneePresent" in element) {
            JsonObject(element - "previousAssigneePresent")
        } else {
            element
        }
}

@Serializable
@RaftCoreInternalApi data class WireActionCardAction(
    val type: String = "",
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val suggestedComputer: String? = null,
    val requiredComputer: String? = null,
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
    val requestId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    /** `integration:connect_mcp`: the MCP App id (`action.mcpServerId` or `result.mcpServerId`). */
    val mcpServerId: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val scopes: List<String> = emptyList(),
    val installScopes: List<String>? = null,
    val installGroups: List<String>? = null,
    val installTextRevisionId: String? = null,
    val installText: ThreadActionCardInstallText? = null,
    val targetAgent: String? = null,
)

@Serializable
@RaftCoreInternalApi data class WireActionCardResult(
    val kind: String = "",
    val id: String? = null,
    val name: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val ownerAgentId: String? = null,
    val ownerAgentName: String? = null,
    val agentId: String? = null,
    val mcpServerId: String? = null,
    val targetMachineId: String? = null,
    val addedHumanIds: List<String> = emptyList(),
    val addedAgentIds: List<String> = emptyList(),
    /** `product-feedback`: the Hands ticket (null when Hands named none). */
    val ticketId: String? = null,
    /** `product-feedback` (S0.3): the Hands app the ticket was filed into (`web`, `ios`, `android`, `ohos`); absent on older cards. */
    val source: String? = null,
    /** `external-invites` (slock #16530): number of addresses recorded on the card (0 for connect-only). */
    val addresses: Int? = null,
    /** `marketplace-app-installation`: optional post-install setup URL shown as "Continue setup". */
    val setupUrl: String? = null,
)

@RaftCoreInternalApi fun WireActionCardMetadata?.toThreadActionCard(): ThreadActionCard? {
    val meta = this ?: return null
    if (meta.kind != "action-card") return null
    val actionObject = meta.action ?: return null
    val action =
        runCatching {
            threadActionCardJson.decodeFromJsonElement(WireActionCardAction.serializer(), actionObject)
        }.getOrNull() ?: return null
    val type = action.type.takeIf { it.isNotBlank() } ?: return null
    return ThreadActionCard(
        type = type,
        state = meta.state.ifBlank { "prepared" },
        name = action.name.orEmpty(),
        description = action.description?.takeIf { it.isNotBlank() },
        visibility = action.visibility?.takeIf { it.isNotBlank() },
        channel = action.channel?.takeIf { it.isNotBlank() },
        suggestedComputer = action.suggestedComputer?.takeIf { it.isNotBlank() },
        requiredComputer = action.requiredComputer?.takeIf { it.isNotBlank() },
        suggestedRuntime = actionObject.actionCardStringOrNull("suggestedRuntime")?.trim(),
        suggestedEnvVars = actionObject.actionCardStringMap("suggestedEnvVars"),
        humans = action.humans,
        agents = action.agents,
        initialHumans = action.initialHumans,
        initialAgents = action.initialAgents,
        draftHint = action.draftHint?.takeIf { it.isNotBlank() },
        executedAt = meta.executedAt?.takeIf { it.isNotBlank() },
        executedByUserName = meta.executedByUserName?.takeIf { it.isNotBlank() },
        executedByUserId = meta.executedByUserId?.takeIf { it.isNotBlank() },
        resultKind = meta.result?.kind?.takeIf { it.isNotBlank() },
        resultTicketId = meta.result?.ticketId?.takeIf { it.isNotBlank() },
        resultSource = meta.result?.source?.takeIf { it.isNotBlank() },
        resultId = firstNonBlank(meta.result?.id, meta.result?.channelId, meta.result?.clientId, meta.result?.ownerAgentId, meta.result?.agentId),
        resultTargetMachineId = meta.result?.targetMachineId?.takeIf { it.isNotBlank() },
        resultName = firstNonBlank(meta.result?.name, meta.result?.clientName, meta.result?.ownerAgentName),
        resultChannelName = meta.result?.channelName?.takeIf { it.isNotBlank() },
        resultAddedHumanIds = meta.result?.addedHumanIds.orEmpty(),
        resultAddedAgentIds = meta.result?.addedAgentIds.orEmpty(),
        requestId = action.requestId?.takeIf { it.isNotBlank() },
        agentId = action.agentId?.takeIf { it.isNotBlank() },
        agentName = action.agentName?.takeIf { it.isNotBlank() },
        mcpServerId = firstNonBlank(action.mcpServerId, actionObject.actionCardStringOrNull("mcpServerId"), meta.result?.mcpServerId),
        clientId = action.clientId?.takeIf { it.isNotBlank() },
        clientKey = action.clientKey?.takeIf { it.isNotBlank() },
        clientName = action.clientName?.takeIf { it.isNotBlank() },
        scopes = action.scopes,
        installScopes = action.installScopes,
        installGroups = action.installGroups,
        installTextRevisionId = action.installTextRevisionId?.takeIf { it.isNotBlank() },
        installText = action.installText,
        targetAgent = action.targetAgent?.takeIf { it.isNotBlank() },
        metadataItems = meta.presentation?.displayItems?.toSafeServerPresentationItems()?.takeIf { it.isNotEmpty() }
            ?: actionObject.toSafeActionCardMetadataItems(),
        presentationTitle = meta.presentation?.title?.takeIf { it.isNotBlank() },
        presentationConfirmLabel = meta.presentation?.confirmLabel?.takeIf { it.isNotBlank() },
        presentationWarning = meta.presentation?.warning?.takeIf { it.isNotBlank() },
        genericApprovalAllowed = meta.presentation?.genericApprovalAllowed == true,
        sourceServerId = meta.sourceServerId?.takeIf { it.isNotBlank() },
        targetServerName = meta.targetServerName?.takeIf { it.isNotBlank() },
        confirmationVersion = meta.confirmationVersion,
        presentationCardJson = meta.presentation?.card?.takeIf { parseActionCardSpec(it) != null }?.toString(),
        // Top level beside `state`; else the action's own deadline (a question card from an older
        // server, a card stored expired that keeps it there: web useExpiredCardLine).
        expiresAt = meta.expiresAt?.takeIf { it.isNotBlank() } ?: actionObject.actionCardStringOrNull("expiresAt"),
        requesterUserId = meta.requesterUserId?.takeIf { it.isNotBlank() },
        requesterAgentId = meta.requesterAgentId?.takeIf { it.isNotBlank() },
        questionAddresseeUserId = actionObject.questionAddressee().first,
        questionAddresseeHandle = actionObject.questionAddressee().second,
        questionAsksAnyone = actionObject.questionAddressee().third,
        questions = actionObject.questionShapes(),
        questionHasPages = actionObject["pages"].let { it != null && it !is JsonNull },
        questionExpiresInMinutes = (actionObject["expiresInMinutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        answererCanClose = (meta.answererCanClose as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
        closedAt = meta.closedAt?.takeIf { it.isNotBlank() },
        closedBy =
            (meta.closedBy as? JsonObject)?.let { closer ->
                ThreadActionCardClosedBy(
                    userId = closer.actionCardStringOrNull("userId"),
                    handle = closer.actionCardStringOrNull("handle"),
                )
            },
        handledBy = meta.handledBy.parseActionCardHandledBy(),
        seatsAddCount = (actionObject["seats"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        seatsAddPurpose = parseSeatsAddPurpose(actionObject["purpose"] as? JsonObject),
        externalInviteAddresses =
            (actionObject["addresses"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toDoubleOrNull()
                ?.takeIf { it >= 0.0 && it % 1.0 == 0.0 && it <= Int.MAX_VALUE }
                ?.toInt(),
        resultAddresses = meta.result?.addresses?.takeIf { it >= 0 },
        resultSetupUrl = meta.result?.setupUrl?.trim()?.takeIf { it.isNotBlank() },
        seatOutcome = meta.seatOutcome.parseActionCardSeatOutcome(),
    )
}

/**
 * Parses wire `seatOutcome` (slock `SeatRequestOutcome`) leniently: unknown kinds, purposes, roles,
 * or malformed numeric counts return `null` so a newer server never drops the card.
 */
private fun JsonElement?.parseActionCardSeatOutcome(): ThreadActionCardSeatOutcome? {
    val obj = this as? JsonObject ?: return null
    fun JsonObject.nonNegativeIntOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it % 1.0 == 0.0 && it <= Int.MAX_VALUE }
            ?.toInt()
    fun JsonObject.seatRoleOrNull(): String? =
        actionCardStringOrNull("role")?.trim()?.takeIf { it == "member" || it == "admin" }
    return when (obj.actionCardStringOrNull("kind")) {
        "invites" -> {
            val sent = obj.nonNegativeIntOrNull("sent") ?: return null
            val failed = obj.nonNegativeIntOrNull("failed") ?: return null
            ThreadActionCardSeatOutcome.Invites(sent = sent, failed = failed)
        }
        "promoted" -> {
            val name = obj.actionCardStringOrNull("name")?.trim() ?: return null
            val role = obj.seatRoleOrNull() ?: return null
            ThreadActionCardSeatOutcome.Promoted(name = name, role = role)
        }
        "ready" ->
            when (obj.actionCardStringOrNull("purpose")) {
                "agent:create" -> {
                    val name = obj.actionCardStringOrNull("name")?.trim() ?: return null
                    ThreadActionCardSeatOutcome.ReadyAgentCreate(name = name)
                }
                "assistant:create" -> ThreadActionCardSeatOutcome.ReadyAssistantCreate
                "invite" -> {
                    val count = obj.nonNegativeIntOrNull("count")?.takeIf { it >= 1 } ?: return null
                    ThreadActionCardSeatOutcome.ReadyInvite(count = count)
                }
                "member:promote" -> {
                    val name = obj.actionCardStringOrNull("name")?.trim() ?: return null
                    val role = obj.seatRoleOrNull() ?: return null
                    ThreadActionCardSeatOutcome.ReadyMemberPromote(name = name, role = role)
                }
                "join" -> ThreadActionCardSeatOutcome.ReadyJoin
                else -> null
            }
        "requester_cannot" -> ThreadActionCardSeatOutcome.RequesterCannot
        "not_needed" -> ThreadActionCardSeatOutcome.NotNeeded
        else -> null
    }
}

/**
 * Parses wire `handledBy` (`{ messageId, userId, handle, inCardThread }`, slock #17265) leniently:
 * requires non-blank string `messageId`, `userId`, and `handle`; `inCardThread` defaults to false
 * when absent or null, and any non-boolean value rejects the object cleanly.
 */
private fun JsonElement?.parseActionCardHandledBy(): ThreadActionCardHandledBy? {
    val obj = this as? JsonObject ?: return null
    val messageId = obj.actionCardStringOrNull("messageId")?.trim() ?: return null
    val userId = obj.actionCardStringOrNull("userId")?.trim() ?: return null
    val handle = obj.actionCardStringOrNull("handle")?.trim() ?: return null
    val inCardThread =
        when (val raw = obj["inCardThread"]) {
            null, JsonNull -> false
            is JsonPrimitive -> if (raw.isString) return null else raw.booleanOrNull ?: return null
            else -> return null
        }
    return ThreadActionCardHandledBy(
        messageId = messageId,
        userId = userId,
        handle = handle,
        inCardThread = inCardThread,
    )
}

private fun JsonObject.actionCardStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** Reads a JSON string->string map leniently, preserving entry order and empty string values (slock #16341). */
private fun JsonObject.actionCardStringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    return buildMap {
        for ((entryKey, element) in obj) {
            val primitive = element as? JsonPrimitive ?: continue
            if (primitive.isString) put(entryKey, primitive.content)
        }
    }
}

private const val ACTION_CARD_SPEC_CACHE_SIZE = 64

/**
 * Parsed cards by their JSON: the thread rebuilds a [ThreadActionCard] from message metadata on
 * every read (`RaftThreadMessageItem.threadActionCard()`), so the card is parsed once per JSON, not
 * per recomposition. Copy-on-write and bounded (oldest out); a race at worst parses twice.
 */
private val actionCardSpecCache = atomic(emptyMap<String, ActionCardSpecCard?>())

@RaftCoreInternalApi fun cachedActionCardSpec(json: String): ActionCardSpecCard? {
    val snapshot = actionCardSpecCache.value
    if (json in snapshot) return snapshot[json]
    val card = runCatching { threadActionCardJson.parseToJsonElement(json) }.getOrNull()?.let(::parseActionCardSpec)
    actionCardSpecCache.update { current ->
        LinkedHashMap(current).apply {
            put(json, card)
            while (size > ACTION_CARD_SPEC_CACHE_SIZE) remove(keys.first())
        }
    }
    return card
}

@RaftCoreInternalApi fun WireActionCardAction.toWireJsonObject(): JsonObject =
    threadActionCardJson.encodeToJsonElement(WireActionCardAction.serializer(), this).jsonObject

@RaftCoreInternalApi fun ThreadActionCard.toMessageMetadata(): Map<String, String> =
    buildMap {
        putString("kind", "action-card")
        putString("type", type)
        putString("state", state)
        putString("name", name)
        putString("description", description)
        putString("visibility", visibility)
        putString("channel", channel)
        putString("suggestedComputer", suggestedComputer)
        putString("requiredComputer", requiredComputer)
        putString("suggestedRuntime", suggestedRuntime)
        putStringMap("suggestedEnvVars", suggestedEnvVars)
        putList("humans", humans)
        putList("agents", agents)
        putList("initialHumans", initialHumans)
        putList("initialAgents", initialAgents)
        putString("draftHint", draftHint)
        putString("executedAt", executedAt)
        putString("executedByUserName", executedByUserName)
        putString("executedByUserId", executedByUserId)
        putString("resultKind", resultKind)
        putString("resultTicketId", resultTicketId)
        putString("resultSource", resultSource)
        putString("resultId", resultId)
        putString("resultTargetMachineId", resultTargetMachineId)
        putString("resultName", resultName)
        putString("resultChannelName", resultChannelName)
        putList("resultAddedHumanIds", resultAddedHumanIds)
        putList("resultAddedAgentIds", resultAddedAgentIds)
        putString("requestId", requestId)
        putString("agentId", agentId)
        putString("agentName", agentName)
        putString("mcpServerId", mcpServerId)
        putString("clientId", clientId)
        putString("clientKey", clientKey)
        putString("clientName", clientName)
        putList("scopes", scopes)
        putNullableList("installScopes", installScopes)
        putNullableList("installGroups", installGroups)
        putString("installTextRevisionId", installTextRevisionId)
        installText?.let {
            put("${ActionCardMetadataPrefix}installText", threadActionCardJson.encodeToString(ThreadActionCardInstallText.serializer(), it))
        }
        putString("targetAgent", targetAgent)
        putMetadataItems("metadataItems", metadataItems)
        putString("presentationTitle", presentationTitle)
        putString("presentationConfirmLabel", presentationConfirmLabel)
        putString("presentationWarning", presentationWarning)
        putBoolean("genericApprovalAllowed", genericApprovalAllowed)
        putString("sourceServerId", sourceServerId)
        putString("targetServerName", targetServerName)
        putString("confirmationVersion", confirmationVersion?.toString())
        putString("presentationCard", presentationCardJson)
        putString("expiresAt", expiresAt)
        putString("requesterUserId", requesterUserId)
        putString("requesterAgentId", requesterAgentId)
        putString("questionAddresseeUserId", questionAddresseeUserId)
        putString("questionAddresseeHandle", questionAddresseeHandle)
        putString("questionExpiresInMinutes", questionExpiresInMinutes?.toString())
        if (questionHasPages) putBoolean("questionHasPages", true)
        if (questionAsksAnyone) putBoolean("questionAsksAnyone", true)
        if (questions.isNotEmpty()) {
            put("${ActionCardMetadataPrefix}questions", threadActionCardJson.encodeToString(ListSerializer(ThreadActionCardQuestion.serializer()), questions))
        }
        if (answererCanClose) putBoolean("answererCanClose", true)
        putString("closedAt", closedAt)
        closedBy?.let {
            put("${ActionCardMetadataPrefix}closedBy", threadActionCardJson.encodeToString(ThreadActionCardClosedBy.serializer(), it))
        }
        handledBy?.let {
            put("${ActionCardMetadataPrefix}handledBy", threadActionCardJson.encodeToString(ThreadActionCardHandledBy.serializer(), it))
        }
        putString("seatsAddCount", seatsAddCount?.toString())
        seatsAddPurpose?.let {
            put("${ActionCardMetadataPrefix}seatsAddPurpose", threadActionCardJson.encodeToString(SeatsAddPurpose.serializer(), it))
        }
        putString("externalInviteAddresses", externalInviteAddresses?.toString())
        putString("resultAddresses", resultAddresses?.toString())
        putString("resultSetupUrl", resultSetupUrl)
        seatOutcome?.let {
            put("${ActionCardMetadataPrefix}seatOutcome", threadActionCardJson.encodeToString(ThreadActionCardSeatOutcome.serializer(), it))
        }
    }

/** True when the projected message metadata carries an action card (any type). */
@RaftCoreInternalApi fun messageMetadataHasActionCard(metadata: Map<String, String>): Boolean =
    metadata["${ActionCardMetadataPrefix}kind"] == "action-card"

/** True when [this] action card is in a terminal state (`executed`, `withdrawn`, `expired`, `closed`). */
@RaftCoreInternalApi val ThreadActionCard.isTerminalState: Boolean
    get() = isExecuted || isWithdrawn || isExpired || isClosed

/**
 * True when [held] is an action card in a terminal state (`executed`, `withdrawn`, `expired`,
 * `closed`) and [incoming] would regress it to a non-terminal state (web
 * `isRegressingActionCardState`, slock #16473): an older open copy must never reopen an ended card.
 */
@RaftCoreInternalApi fun isRegressingActionCardState(held: Map<String, String>, incoming: Map<String, String>): Boolean {
    val heldCard = actionCardFromMessageMetadata(held) ?: return false
    val incomingCard = actionCardFromMessageMetadata(incoming) ?: return false
    return heldCard.isTerminalState && !incomingCard.isTerminalState
}

@RaftCoreInternalApi fun actionCardFromMessageMetadata(metadata: Map<String, String>): ThreadActionCard? {
    if (metadata["${ActionCardMetadataPrefix}kind"] != "action-card") return null
    val type = metadata["${ActionCardMetadataPrefix}type"]?.takeIf { it.isNotBlank() } ?: return null
    return ThreadActionCard(
        type = type,
        state = metadata["${ActionCardMetadataPrefix}state"]?.takeIf { it.isNotBlank() } ?: "prepared",
        name = metadata["${ActionCardMetadataPrefix}name"].orEmpty(),
        description = metadata["${ActionCardMetadataPrefix}description"]?.takeIf { it.isNotBlank() },
        visibility = metadata["${ActionCardMetadataPrefix}visibility"]?.takeIf { it.isNotBlank() },
        channel = metadata["${ActionCardMetadataPrefix}channel"]?.takeIf { it.isNotBlank() },
        suggestedComputer = metadata["${ActionCardMetadataPrefix}suggestedComputer"]?.takeIf { it.isNotBlank() },
        requiredComputer = metadata["${ActionCardMetadataPrefix}requiredComputer"]?.takeIf { it.isNotBlank() },
        suggestedRuntime = metadata["${ActionCardMetadataPrefix}suggestedRuntime"]?.trim()?.takeIf { it.isNotEmpty() },
        suggestedEnvVars = metadata.decodeStringMap("suggestedEnvVars"),
        humans = metadata.decodeList("humans"),
        agents = metadata.decodeList("agents"),
        initialHumans = metadata.decodeList("initialHumans"),
        initialAgents = metadata.decodeList("initialAgents"),
        draftHint = metadata["${ActionCardMetadataPrefix}draftHint"]?.takeIf { it.isNotBlank() },
        executedAt = metadata["${ActionCardMetadataPrefix}executedAt"]?.takeIf { it.isNotBlank() },
        executedByUserName = metadata["${ActionCardMetadataPrefix}executedByUserName"]?.takeIf { it.isNotBlank() },
        executedByUserId = metadata["${ActionCardMetadataPrefix}executedByUserId"]?.takeIf { it.isNotBlank() },
        resultKind = metadata["${ActionCardMetadataPrefix}resultKind"]?.takeIf { it.isNotBlank() },
        resultTicketId = metadata["${ActionCardMetadataPrefix}resultTicketId"]?.takeIf { it.isNotBlank() },
        resultSource = metadata["${ActionCardMetadataPrefix}resultSource"]?.takeIf { it.isNotBlank() },
        resultId = metadata["${ActionCardMetadataPrefix}resultId"]?.takeIf { it.isNotBlank() },
        resultTargetMachineId = metadata["${ActionCardMetadataPrefix}resultTargetMachineId"]?.takeIf { it.isNotBlank() },
        resultName = metadata["${ActionCardMetadataPrefix}resultName"]?.takeIf { it.isNotBlank() },
        resultChannelName = metadata["${ActionCardMetadataPrefix}resultChannelName"]?.takeIf { it.isNotBlank() },
        resultAddedHumanIds = metadata.decodeList("resultAddedHumanIds"),
        resultAddedAgentIds = metadata.decodeList("resultAddedAgentIds"),
        requestId = metadata["${ActionCardMetadataPrefix}requestId"]?.takeIf { it.isNotBlank() },
        agentId = metadata["${ActionCardMetadataPrefix}agentId"]?.takeIf { it.isNotBlank() },
        agentName = metadata["${ActionCardMetadataPrefix}agentName"]?.takeIf { it.isNotBlank() },
        mcpServerId = metadata["${ActionCardMetadataPrefix}mcpServerId"]?.takeIf { it.isNotBlank() },
        clientId = metadata["${ActionCardMetadataPrefix}clientId"]?.takeIf { it.isNotBlank() },
        clientKey = metadata["${ActionCardMetadataPrefix}clientKey"]?.takeIf { it.isNotBlank() },
        clientName = metadata["${ActionCardMetadataPrefix}clientName"]?.takeIf { it.isNotBlank() },
        scopes = metadata.decodeList("scopes"),
        installScopes = metadata.decodeNullableList("installScopes"),
        installGroups = metadata.decodeNullableList("installGroups"),
        installTextRevisionId = metadata["${ActionCardMetadataPrefix}installTextRevisionId"]?.takeIf { it.isNotBlank() },
        installText =
            metadata["${ActionCardMetadataPrefix}installText"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(ThreadActionCardInstallText.serializer(), raw) }.getOrNull()
            },
        targetAgent = metadata["${ActionCardMetadataPrefix}targetAgent"]?.takeIf { it.isNotBlank() },
        metadataItems = metadata.decodeMetadataItems("metadataItems"),
        presentationTitle = metadata["${ActionCardMetadataPrefix}presentationTitle"]?.takeIf { it.isNotBlank() },
        presentationConfirmLabel = metadata["${ActionCardMetadataPrefix}presentationConfirmLabel"]?.takeIf { it.isNotBlank() },
        presentationWarning = metadata["${ActionCardMetadataPrefix}presentationWarning"]?.takeIf { it.isNotBlank() },
        genericApprovalAllowed =
            metadata["${ActionCardMetadataPrefix}genericApprovalAllowed"]?.equals("true", ignoreCase = true) == true,
        sourceServerId = metadata["${ActionCardMetadataPrefix}sourceServerId"]?.takeIf { it.isNotBlank() },
        targetServerName = metadata["${ActionCardMetadataPrefix}targetServerName"]?.takeIf { it.isNotBlank() },
        confirmationVersion = metadata["${ActionCardMetadataPrefix}confirmationVersion"]?.toIntOrNull(),
        presentationCardJson = metadata["${ActionCardMetadataPrefix}presentationCard"]?.takeIf { it.isNotBlank() },
        expiresAt = metadata["${ActionCardMetadataPrefix}expiresAt"]?.takeIf { it.isNotBlank() },
        requesterUserId = metadata["${ActionCardMetadataPrefix}requesterUserId"]?.takeIf { it.isNotBlank() },
        requesterAgentId = metadata["${ActionCardMetadataPrefix}requesterAgentId"]?.takeIf { it.isNotBlank() },
        questionAddresseeUserId = metadata["${ActionCardMetadataPrefix}questionAddresseeUserId"]?.takeIf { it.isNotBlank() },
        questionAddresseeHandle = metadata["${ActionCardMetadataPrefix}questionAddresseeHandle"]?.takeIf { it.isNotBlank() },
        questionExpiresInMinutes = metadata["${ActionCardMetadataPrefix}questionExpiresInMinutes"]?.toIntOrNull(),
        questionHasPages = metadata["${ActionCardMetadataPrefix}questionHasPages"]?.equals("true", ignoreCase = true) == true,
        questionAsksAnyone = metadata["${ActionCardMetadataPrefix}questionAsksAnyone"]?.equals("true", ignoreCase = true) == true,
        questions =
            metadata["${ActionCardMetadataPrefix}questions"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(ListSerializer(ThreadActionCardQuestion.serializer()), raw) }.getOrNull()
            }.orEmpty(),
        answererCanClose = metadata["${ActionCardMetadataPrefix}answererCanClose"]?.equals("true", ignoreCase = true) == true,
        closedAt = metadata["${ActionCardMetadataPrefix}closedAt"]?.takeIf { it.isNotBlank() },
        closedBy =
            metadata["${ActionCardMetadataPrefix}closedBy"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(ThreadActionCardClosedBy.serializer(), raw) }.getOrNull()
            },
        handledBy =
            metadata["${ActionCardMetadataPrefix}handledBy"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(ThreadActionCardHandledBy.serializer(), raw) }
                    .getOrNull()
                    ?.takeIf { it.messageId.isNotBlank() && it.userId.isNotBlank() && it.handle.isNotBlank() }
            },
        seatsAddCount = metadata["${ActionCardMetadataPrefix}seatsAddCount"]?.toIntOrNull(),
        seatsAddPurpose =
            metadata["${ActionCardMetadataPrefix}seatsAddPurpose"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(SeatsAddPurpose.serializer(), raw) }.getOrNull()
            },
        externalInviteAddresses = metadata["${ActionCardMetadataPrefix}externalInviteAddresses"]?.toIntOrNull()?.takeIf { it >= 0 },
        resultAddresses = metadata["${ActionCardMetadataPrefix}resultAddresses"]?.toIntOrNull()?.takeIf { it >= 0 },
        resultSetupUrl = metadata["${ActionCardMetadataPrefix}resultSetupUrl"]?.trim()?.takeIf { it.isNotBlank() },
        seatOutcome =
            metadata["${ActionCardMetadataPrefix}seatOutcome"]?.let { raw ->
                runCatching { threadActionCardJson.decodeFromString(ThreadActionCardSeatOutcome.serializer(), raw) }.getOrNull()
            },
    )
}

@RaftCoreInternalApi fun ThreadActionCard.withExecutedAgent(id: String, name: String, executedByUserName: String? = null): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "agent",
        resultId = id,
        resultName = name
    )

@RaftCoreInternalApi fun ThreadActionCard.withExecutedChannel(id: String, name: String, executedByUserName: String? = null): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "channel",
        resultId = id,
        resultName = name
    )

@RaftCoreInternalApi fun ThreadActionCard.withExecutedChannelMembers(
    channelId: String,
    channelName: String,
    addedHumanIds: List<String>,
    addedAgentIds: List<String>,
    executedByUserName: String? = null
): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "channel-members",
        resultId = channelId,
        resultChannelName = channelName,
        resultAddedHumanIds = addedHumanIds,
        resultAddedAgentIds = addedAgentIds
    )

@RaftCoreInternalApi fun ThreadActionCard.withExecutedAgentMigration(
    agentId: String,
    targetMachineId: String,
    executedByUserName: String? = null
): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "agent-migration",
        resultId = agentId,
        resultTargetMachineId = targetMachineId
    )

@RaftCoreInternalApi fun ThreadActionCard.withExecutedExternalInvites(
    channelId: String,
    addresses: Int,
    executedByUserName: String? = null,
): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "external-invites",
        resultId = channelId,
        resultAddresses = addresses.coerceAtLeast(0),
    )

@RaftCoreInternalApi fun ThreadActionCard.withExecutedMcpConnection(
    mcpServerId: String,
    agentId: String,
    executedByUserName: String? = null,
): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        mcpServerId = mcpServerId,
        agentId = agentId,
        resultKind = "mcp-connection",
        resultId = agentId,
    )

/**
 * Resolves the MCP App id on an `integration:connect_mcp` card: prefers [ThreadActionCard.mcpServerId]
 * (REST / metadata), falling back to [ThreadActionCard.metadataItems] if a projected carrier dropped
 * the top-level action key.
 */
@RaftCoreInternalApi val ThreadActionCard.resolvedMcpServerId: String?
    get() =
        mcpServerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: metadataItems.firstOrNull { it.key.equals("mcpServerId", ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

@RaftCoreInternalApi const val ChannelInviteExternalActionCardType = "channel:invite_external"

@RaftCoreInternalApi fun ThreadActionCard.isInviteExternalAction(): Boolean =
    type.equals(ChannelInviteExternalActionCardType, ignoreCase = true)

@RaftCoreInternalApi const val AgentMigrateActionCardType = "agent:migrate"

@RaftCoreInternalApi fun ThreadActionCard.isAgentMigrateAction(): Boolean =
    type.equals(AgentMigrateActionCardType, ignoreCase = true)

/**
 * `feedback:submit`: never executed inline (the server refuses a generic execute); it is sent from
 * the feedback form, which in the App is the native feedback form on the card's draft (see
 * `raftFeedbackCardEntryOrNull`).
 */
@RaftCoreInternalApi fun ThreadActionCard.isFeedbackSubmitAction(): Boolean =
    type.equals(FeedbackSubmitActionCardType, ignoreCase = true)

@RaftCoreInternalApi const val FeedbackSubmitActionCardType = "feedback:submit"

@RaftCoreInternalApi fun ThreadActionCard.isSupportedCommitAction(): Boolean =
    type.equals("agent:create", ignoreCase = true) ||
        isAgentMigrateAction() ||
        type.equals("channel:create", ignoreCase = true) ||
        type.equals("channel:add_member", ignoreCase = true) ||
        type.equals("integration:approve_agent_login", ignoreCase = true) ||
        isInstallMarketplaceAppAction() ||
        isGenericInlineCommitAction()

/**
 * Whether this app can act on the card's button itself: reconfirm it, run it, or open the native
 * feedback form ([feedbackEntryEnabled]). Otherwise the card offers "Open on the web".
 */
@RaftCoreInternalApi fun ThreadActionCard.runsInApp(feedbackEntryEnabled: Boolean): Boolean =
    needsReconfirmation || isSupportedCommitAction() || (feedbackEntryEnabled && isFeedbackSubmitAction() && !isExecuted)

@RaftCoreInternalApi const val InstallMarketplaceAppActionCardType = "integration:install_marketplace_app"

/**
 * `integration:connect_mcp`: an agent has no authorized connection to an MCP App. The web grants
 * one in a picker it mounts under the card (`McpConnectCardSlot`); the App has none, so the card
 * opens on the web (`genericApprovalAllowed: false`).
 */
@RaftCoreInternalApi const val ConnectMcpActionCardType = "integration:connect_mcp"

@RaftCoreInternalApi fun ThreadActionCard.isConnectMcpAction(): Boolean = type.equals(ConnectMcpActionCardType, ignoreCase = true)

/**
 * `integration:install_marketplace_app` (web `InstallAppCardDialog`, audit V-85): installing
 * approves the app's whole declared set on this Server, not just this login's scopes, so the
 * card's button opens a confirmation of the set bound to the card; only that dialog's Install
 * executes it. The server marks it `genericApprovalAllowed`, so it must never fall into the
 * generic one-tap execute.
 */
@RaftCoreInternalApi fun ThreadActionCard.isInstallMarketplaceAppAction(): Boolean =
    type.equals(InstallMarketplaceAppActionCardType, ignoreCase = true)

/** The prompt text bound to an install card (web `readInstallCardPromptText`); null when none. */
@RaftCoreInternalApi val ThreadActionCard.boundInstallText: ThreadActionCardInstallText?
    get() = installText.takeIf { installTextRevisionId != null }

/** The server bound the install set to the card; a card prepared before that can't be installed (409). */
@RaftCoreInternalApi val ThreadActionCard.installSetBound: Boolean
    get() = installScopes != null && installGroups != null

/**
 * Server-prepared operation cards are canonical, human-commit requests. A
 * newer server action can therefore use the generic execute endpoint before
 * mobile has a bespoke renderer. The one historical update-card type is
 * deliberately terminal on the server (HTTP 410), so an old carrier must not
 * regain an enabled button through this fallback.
 */
@RaftCoreInternalApi fun ThreadActionCard.isGenericInlineCommitAction(): Boolean =
    type.isNotBlank() &&
        !type.equals("agent:create", ignoreCase = true) &&
        !isAgentMigrateAction() &&
        !type.equals("channel:create", ignoreCase = true) &&
        !type.equals("channel:add_member", ignoreCase = true) &&
        !isInviteExternalAction() &&
        !type.equals("integration:approve_agent_login", ignoreCase = true) &&
        !type.equals("integration:update_app_registration", ignoreCase = true) &&
        !isInstallMarketplaceAppAction() &&
        (
            genericApprovalAllowed ||
                type.equals("integration:register_app", ignoreCase = true) ||
                type.equals("integration:recover_app_owner", ignoreCase = true)
        )

private fun MutableMap<String, String>.putString(key: String, value: String?) {
    val normalized = value?.takeIf { it.isNotBlank() } ?: return
    put("$ActionCardMetadataPrefix$key", normalized)
}

private fun MutableMap<String, String>.putList(key: String, value: List<String>) {
    if (value.isEmpty()) return
    put("$ActionCardMetadataPrefix$key", threadActionCardJson.encodeToString(ListSerializer(String.serializer()), value))
}

private fun MutableMap<String, String>.putStringMap(key: String, value: Map<String, String>) {
    if (value.isEmpty()) return
    put("$ActionCardMetadataPrefix$key", threadActionCardJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), value))
}

/** Like [putList], but an empty list is kept ("[]"): absent and empty mean different things. */
private fun MutableMap<String, String>.putNullableList(key: String, value: List<String>?) {
    if (value == null) return
    put("$ActionCardMetadataPrefix$key", threadActionCardJson.encodeToString(ListSerializer(String.serializer()), value))
}

private fun MutableMap<String, String>.putBoolean(key: String, value: Boolean) {
    if (value) put("$ActionCardMetadataPrefix$key", "true")
}

private fun MutableMap<String, String>.putMetadataItems(key: String, value: List<ThreadActionCardMetadataItem>) {
    if (value.isEmpty()) return
    put(
        "$ActionCardMetadataPrefix$key",
        threadActionCardJson.encodeToString(ListSerializer(ThreadActionCardMetadataItem.serializer()), value)
    )
}

private fun Map<String, String>.decodeList(key: String): List<String> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        threadActionCardJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: SerializationException) {
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}

private fun Map<String, String>.decodeNullableList(key: String): List<String>? {
    if (!containsKey("$ActionCardMetadataPrefix$key")) return null
    return decodeList(key)
}

private fun Map<String, String>.decodeStringMap(key: String): Map<String, String> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return try {
        threadActionCardJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
    } catch (_: SerializationException) {
        emptyMap()
    }
}

private fun Map<String, String>.decodeMetadataItems(key: String): List<ThreadActionCardMetadataItem> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        threadActionCardJson.decodeFromString(ListSerializer(ThreadActionCardMetadataItem.serializer()), raw)
    } catch (_: SerializationException) {
        emptyList()
    }
}

private val sensitiveActionCardFieldFragments =
    listOf(
        "secret",
        "token",
        "password",
        "credential",
        "authorization",
        "privatekey",
        "private_key",
        "accesstoken",
        "access_token",
        "refreshtoken",
        "refresh_token",
    )

private fun JsonObject.toSafeActionCardMetadataItems(): List<ThreadActionCardMetadataItem> =
    entries
        .asSequence()
        .filterNot { (key, _) -> key.equals("type", ignoreCase = true) || key.equals("draftHint", ignoreCase = true) }
        .take(24)
        .map { (key, element) ->
            val sensitive = key.isSensitiveActionCardField()
            ThreadActionCardMetadataItem(
                key = key,
                value = if (sensitive) "Hidden" else element.toSafeActionCardDisplayValue(),
                redacted = sensitive,
            )
        }
        .filter { item -> item.value.isNotBlank() }
        .toList()

private fun List<ThreadActionCardMetadataItem>.toSafeServerPresentationItems(): List<ThreadActionCardMetadataItem> =
    asSequence()
        .filter { item -> item.key.isNotBlank() }
        .take(24)
        .map { item ->
            val sensitive = item.redacted || item.key.isSensitiveActionCardField()
            item.copy(
                value = if (sensitive) "Hidden" else item.value.take(500),
                redacted = sensitive,
            )
        }
        .filter { item -> item.value.isNotBlank() }
        .toList()

private fun JsonElement.toSafeActionCardDisplayValue(): String {
    val rendered =
        when (this) {
            JsonNull -> "-"
            is JsonPrimitive -> content
            is JsonArray -> joinToString(", ") { child -> child.toSafeActionCardDisplayValue() }
            is JsonObject ->
                entries.joinToString(", ") { (key, value) ->
                    "$key: ${if (key.isSensitiveActionCardField()) "Hidden" else value.toSafeActionCardDisplayValue()}"
                }
        }
    return if (rendered.length <= 500) rendered else rendered.take(497) + "..."
}

private fun String.isSensitiveActionCardField(): Boolean {
    val normalized = lowercase().replace("-", "").replace(".", "")
    return sensitiveActionCardFieldFragments.any { fragment -> normalized.contains(fragment) }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

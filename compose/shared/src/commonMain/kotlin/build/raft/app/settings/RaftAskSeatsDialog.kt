package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftMemberAvatar
import build.raft.app.RaftMemberAvatarIdentity
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftUnifiedDialog
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.base.Utils
import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.NetworkSeatRequestApi
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SEAT_REQUEST_REASON_MAX_LENGTH
import build.raft.app.data.SeatJoinSeatUsage
import build.raft.app.data.SeatRequestPurpose
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.get
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.memberDetailIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.userFacingMessage
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoicePicker
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/*
 * Native non-owner "Ask an owner for seats" dialog (`POST /api/actions/seat-requests`,
 * botiverse/slock `#11507`, web `AddSeatsDialog.tsx` `AskSeatsPart` + `OwnerPicker.tsx`).
 *
 * Gated behind `pro_seat_add` and Android/Desktop ONLY (`settingsBillingOffersUpgrade()`):
 * NEVER shown on iOS under App Store Guideline 3.1.1.
 */

internal const val ASK_SEATS_DIALOG_TAG: String = "ask_seats_dialog"
internal const val ASK_SEATS_OWNER_ROW_TAG: String = "ask_seats_owner_row"
internal const val ASK_SEATS_OWNER_PICKER_TAG: String = "ask_seats_owner_picker"
internal const val ASK_SEATS_REASON_FIELD_TAG: String = "ask_seats_reason_field"
internal const val ASK_SEATS_SEND_BUTTON_TAG: String = "ask_seats_send_button"
internal const val ASK_SEATS_ERROR_BANNER_TAG: String = "ask_seats_error_banner"

/**
 * Whether a Pro seat-limit hit should open the native non-owner Ask-an-owner dialog instead of
 * the generic web-handoff [RaftUpgradeDialog]: Android/Desktop only (`offersUpgrade`), flag
 * `pro_seat_add` ON, non-owner, [RaftUpgradeMode.AddSeats], and a typed [SeatRequestPurpose].
 */
internal fun raftShouldOpenNativeAskSeatsDialog(
    mode: RaftUpgradeMode,
    isOwner: Boolean,
    proSeatAddEnabled: Boolean,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
    purpose: SeatRequestPurpose?,
): Boolean =
    offersUpgrade &&
        proSeatAddEnabled &&
        !isOwner &&
        mode == RaftUpgradeMode.AddSeats &&
        purpose != null

internal data class AskSeatsOwner(
    val userId: String,
    val displayName: String,
    val avatarWireValue: String? = null,
    val isCreator: Boolean = false,
)

/**
 * Extracts and sorts the server's human owners, matching web `serverOwners` (`OwnerPicker.tsx`):
 * creator first, then alphabetical by display name (case-insensitive), then user ID.
 */
internal fun sortAskSeatsOwners(
    members: List<MemberProfile>,
    creatorUserId: String?,
): List<AskSeatsOwner> {
    val cleanCreatorId = creatorUserId?.trim()?.takeIf { it.isNotEmpty() }
    return members
        .asSequence()
        .filter { it.kind == MemberKind.Human && it.serverRole?.trim()?.lowercase() == "owner" }
        .distinctBy { it.id.value }
        .map { member ->
            val label = member.displayName.trim().ifEmpty { member.handle.trim().ifEmpty { member.id.value } }
            val avatarRef = member.avatar
            AskSeatsOwner(
                userId = member.id.value,
                displayName = label,
                avatarWireValue = avatarRef?.wireValue,
                isCreator = cleanCreatorId != null && member.id.value == cleanCreatorId,
            )
        }
        .sortedWith(
            compareByDescending<AskSeatsOwner> { it.isCreator }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.userId }
        )
        .toList()
}

internal sealed interface AskSeatsOwnerState {
    /** Server hides human directory from this member (`hideHumansFromMembers`): server picks owner. */
    data object ServerChosen : AskSeatsOwnerState

    data object Loading : AskSeatsOwnerState

    data object Failed : AskSeatsOwnerState

    data class Loaded(
        val owners: List<AskSeatsOwner>,
        val selectedOwnerId: String,
    ) : AskSeatsOwnerState {
        val selectedOwner: AskSeatsOwner?
            get() = owners.firstOrNull { it.userId == selectedOwnerId } ?: owners.firstOrNull()
    }
}

internal data class RaftAskSeatsRequest(
    val pagerId: String,
    val storeScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    val serverId: String,
    val serverName: String,
    val purpose: SeatRequestPurpose,
    val creatorUserId: String? = null,
    val rosterHidden: Boolean = false,
    val requesterRole: String? = null,
    val usage: SeatJoinSeatUsage? = null,
    val onSent: (() -> Unit)? = null,
    val onOpenDirectMessage: ((ThreadLaunchConversationIdentity) -> Unit)? = null,
)

/**
 * Process-wide open Ask-an-owner dialog state. Backed by [MutableStateFlow] (never Compose
 * snapshot state) so off-main-thread reads never trigger `SnapshotKt.readError`.
 */
internal object RaftAskSeatsDialogState {
    private val requestFlow = MutableStateFlow<RaftAskSeatsRequest?>(null)

    var request: RaftAskSeatsRequest?
        get() = requestFlow.value
        internal set(value) {
            requestFlow.value = value
        }

    @Composable
    fun observeRequest(): RaftAskSeatsRequest? = requestFlow.collectAsState().value

    fun close() {
        requestFlow.value = null
    }
}

/**
 * Opens the native non-owner "Ask an owner for seats" dialog on Android/Desktop.
 * Strictly a no-op on iOS (`!settingsBillingOffersUpgrade()`, App Store Guideline 3.1.1).
 */
internal fun Pager.openRaftAskSeatsDialog(
    storeScope: RaftShellStoreScope,
    serverId: String,
    serverName: String,
    purpose: SeatRequestPurpose,
    creatorUserId: String? = null,
    rosterHidden: Boolean = false,
    requesterRole: String? = null,
    usage: SeatJoinSeatUsage? = null,
    onSent: (() -> Unit)? = null,
    onOpenDirectMessage: ((ThreadLaunchConversationIdentity) -> Unit)? = null,
) {
    if (!settingsBillingOffersUpgrade()) return
    val cleanServerId = serverId.trim()
    if (cleanServerId.isEmpty()) return
    RaftAskSeatsDialogState.request =
        RaftAskSeatsRequest(
            pagerId = pagerId,
            storeScope = storeScope,
            serverId = cleanServerId,
            serverName = serverName.trim(),
            purpose = purpose,
            creatorUserId = creatorUserId?.trim()?.takeIf { it.isNotEmpty() },
            rosterHidden = rosterHidden,
            requesterRole = requesterRole?.trim()?.takeIf { it.isNotEmpty() },
            usage = usage,
            onSent = onSent,
            onOpenDirectMessage = onOpenDirectMessage,
        )
}

@Composable
internal fun RaftAskSeatsDialogHost(pager: Pager) {
    if (!settingsBillingOffersUpgrade()) return
    val request = RaftAskSeatsDialogState.observeRequest()?.takeIf { it.pagerId == pager.pagerId } ?: return
    val bridge = Utils.bridgeModule(pager.pagerId)
    val toastHost = LocalRaftToastHostState.current
    val scope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val sendFailedFallback = stringResource(Res.string.ask_seats_send_failed)
    val viewActionLabel = stringResource(Res.string.ask_seats_toast_view)
    val sentToOwnerTitle = stringResource(Res.string.ask_seats_sent_to_owner_toast)
    val sentDescription = askSeatsSentDescriptionText(request.purpose)

    var reason by remember(request) { mutableStateOf("") }
    var sending by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }
    var retryNonce by remember(request) { mutableIntStateOf(0) }
    var ownerState by remember(request) {
        mutableStateOf<AskSeatsOwnerState>(
            if (request.rosterHidden) AskSeatsOwnerState.ServerChosen else AskSeatsOwnerState.Loading
        )
    }

    LaunchedEffect(request, retryNonce) {
        if (request.rosterHidden) {
            ownerState = AskSeatsOwnerState.ServerChosen
            return@LaunchedEffect
        }
        val sharedStores = RaftSharedStoreRegistry.get(bridge)
        val membersStore = sharedStores.serverMembersStore(request.storeScope)
        val serverKey = ServerId(request.serverId)
        val cachedOwners = sortAskSeatsOwners(membersStore.currentServerMembers(serverKey), request.creatorUserId)
        if (cachedOwners.isNotEmpty()) {
            ownerState = AskSeatsOwnerState.Loaded(cachedOwners, cachedOwners.first().userId)
            return@LaunchedEffect
        }
        ownerState = AskSeatsOwnerState.Loading
        val result =
            runCatchingPreservingCancellation {
                membersStore.refreshMembers(serverKey, priority = DataRequestPriority.Foreground, reason = "ask_seats_owners")
            }.getOrNull()
        val loadedOwners = sortAskSeatsOwners(membersStore.currentServerMembers(serverKey), request.creatorUserId)
        ownerState =
            when {
                loadedOwners.isNotEmpty() -> AskSeatsOwnerState.Loaded(loadedOwners, loadedOwners.first().userId)
                result is DataResult.Content && request.requesterRole?.lowercase() == "member" ->
                    // Server returned a member-filtered roster without owners: let the server pick the owner.
                    AskSeatsOwnerState.ServerChosen
                else -> AskSeatsOwnerState.Failed
            }
    }

    val selectedOwner = (ownerState as? AskSeatsOwnerState.Loaded)?.selectedOwner
    val sentToNamedTitle =
        selectedOwner?.let { owner -> stringResource(Res.string.ask_seats_sent_toast, owner.displayName) }

    RaftAskSeatsDialog(
        serverId = request.serverId,
        serverName = request.serverName,
        purpose = request.purpose,
        usage = request.usage,
        ownerState = ownerState,
        reason = reason,
        onReasonChange = { reason = it },
        onSelectOwner = { ownerId ->
            val loaded = ownerState as? AskSeatsOwnerState.Loaded ?: return@RaftAskSeatsDialog
            if (loaded.owners.any { it.userId == ownerId }) {
                ownerState = loaded.copy(selectedOwnerId = ownerId)
            }
        },
        onRetryOwners = { retryNonce += 1 },
        sending = sending,
        errorMessage = errorMessage,
        onSend = {
            if (sending) return@RaftAskSeatsDialog
            val currentOwnerState = ownerState
            val recipient =
                when (currentOwnerState) {
                    AskSeatsOwnerState.ServerChosen -> null
                    is AskSeatsOwnerState.Loaded -> currentOwnerState.selectedOwner ?: return@RaftAskSeatsDialog
                    AskSeatsOwnerState.Loading,
                    AskSeatsOwnerState.Failed -> return@RaftAskSeatsDialog
                }
            if (reason.length > SEAT_REQUEST_REASON_MAX_LENGTH) return@RaftAskSeatsDialog
            sending = true
            errorMessage = null
            scope.launch {
                val sharedStores = RaftSharedStoreRegistry.get(bridge)
                val api = NetworkSeatRequestApi(sharedStores.networkClient(request.storeScope))
                val result =
                    api.requestSeats(
                        serverId = ServerId(request.serverId),
                        purpose = request.purpose,
                        ownerUserId = recipient?.userId,
                        reason = reason.trim().takeIf { it.isNotEmpty() },
                    )
                when (result) {
                    is DataResult.Content -> {
                        val responseData = result.data
                        val dmIdentity =
                            buildThreadLaunchConversationIdentity(
                                kind = ConversationKind.DirectMessage,
                                channelId = ChannelId(responseData.channelId),
                                channelType = RaftThreadSurfaceKind.DirectMessage,
                                dmPeerIdentity =
                                    recipient?.let { owner ->
                                        memberDetailIdentity(
                                            memberId = owner.userId,
                                            kind = MemberKind.Human,
                                            sourceServerId = request.serverId,
                                        )
                                    },
                            )
                        val openDm: () -> Unit = {
                            val shellOpen = request.onOpenDirectMessage
                            if (shellOpen != null) {
                                shellOpen(dmIdentity)
                            } else {
                                val launchPayload =
                                    buildStandaloneConversationThreadLaunchPayload(
                                        conversation = dmIdentity,
                                        timeContext = null,
                                    )
                                if (launchPayload != null) {
                                    pager.openStandaloneThreadLaunchPayload(launchPayload, request.storeScope)
                                }
                            }
                        }
                        RaftAskSeatsDialogState.close()
                        request.onSent?.invoke()
                        val toastTitle = sentToNamedTitle ?: sentToOwnerTitle
                        if (toastHost != null) {
                            toastHost.show(
                                title = toastTitle,
                                description = sentDescription,
                                intent = RaftToastIntent.Success,
                                actionLabel = viewActionLabel,
                                onAction = openDm,
                            )
                        } else {
                            bridge.toast(toastTitle)
                            openDm()
                        }
                    }
                    is DataResult.Error -> {
                        val serverMsg = result.error.serverMessage?.trim()?.takeIf { it.isNotEmpty() }
                        errorMessage = serverMsg ?: result.error.userFacingMessage(errorStrings).ifBlank { sendFailedFallback }
                        sending = false
                    }
                    is DataResult.NoPermission -> {
                        errorMessage = result.blocker.userFacingMessage(errorStrings).ifBlank { sendFailedFallback }
                        sending = false
                    }
                    is DataResult.Empty -> {
                        errorMessage = sendFailedFallback
                        sending = false
                    }
                }
            }
        },
        onDismiss = {
            if (!sending) RaftAskSeatsDialogState.close()
        },
    )
}

@Composable
internal fun RaftAskSeatsDialog(
    serverId: String,
    serverName: String,
    purpose: SeatRequestPurpose,
    ownerState: AskSeatsOwnerState,
    reason: String,
    onReasonChange: (String) -> Unit,
    onSelectOwner: (String) -> Unit,
    onRetryOwners: () -> Unit,
    sending: Boolean,
    errorMessage: String?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    usage: SeatJoinSeatUsage? = null,
) {
    val joinPurpose = purpose as? SeatRequestPurpose.Join
    val title =
        if (joinPurpose != null) {
            stringResource(Res.string.ask_seats_join_title, joinPurpose.name)
        } else {
            stringResource(Res.string.ask_seats_title, serverName)
        }
    val reasonTooLong = reason.length > SEAT_REQUEST_REASON_MAX_LENGTH
    val selectedOwner = (ownerState as? AskSeatsOwnerState.Loaded)?.selectedOwner
    val canShowSend =
        ownerState is AskSeatsOwnerState.ServerChosen ||
            (ownerState is AskSeatsOwnerState.Loaded && selectedOwner != null)

    RaftUnifiedDialog(
        title = title,
        overline = stringResource(Res.string.ask_seats_eyebrow),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ASK_SEATS_DIALOG_TAG),
        dismissEnabled = !sending,
        dismissOnOutsideTap = false,
        maxWidth = 480.dp,
        titleMaxLines = 3,
    ) {
        val seatCount = usage?.seats ?: 0
        if (seatCount > 0 && serverName.isNotBlank()) {
            RaftText(
                text =
                    stringResource(
                        if (seatCount == 1) Res.string.ask_seats_hint_one else Res.string.ask_seats_hint_other,
                        seatCount,
                        serverName,
                    ),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            RaftBanner(
                description = errorMessage,
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.fillMaxWidth().testTag(ASK_SEATS_ERROR_BANNER_TAG),
            )
        }
        RaftText(
            text = askSeatsIntroText(purpose),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foreground,
        )
        AskSeatsOwnerSection(
            serverId = serverId,
            ownerState = ownerState,
            sending = sending,
            onSelectOwner = onSelectOwner,
            onRetryOwners = onRetryOwners,
        )
        RaftField(
            label = stringResource(Res.string.ask_seats_reason_label),
            modifier = Modifier.fillMaxWidth(),
            enabled = !sending,
        ) {
            RaftTextarea(
                value = reason,
                onValueChange = onReasonChange,
                modifier = Modifier.fillMaxWidth(),
                inputModifier = Modifier.testTag(ASK_SEATS_REASON_FIELD_TAG),
                placeholder = stringResource(Res.string.ask_seats_reason_placeholder),
                enabled = !sending,
                invalid = reasonTooLong,
                limit = SEAT_REQUEST_REASON_MAX_LENGTH,
            )
        }
        if (canShowSend) {
            val sendLabel =
                if (selectedOwner != null) {
                    stringResource(Res.string.ask_seats_send_to, selectedOwner.displayName)
                } else {
                    stringResource(Res.string.ask_seats_send_to_owner)
                }
            RaftDialogFooter {
                RaftButton(
                    text = sendLabel,
                    onClick = onSend,
                    modifier = Modifier.testTag(ASK_SEATS_SEND_BUTTON_TAG),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = !sending && !reasonTooLong,
                    loading = sending,
                    loadingLabel = stringResource(Res.string.ask_seats_sending),
                )
            }
        }
    }
}

@Composable
private fun AskSeatsOwnerSection(
    serverId: String,
    ownerState: AskSeatsOwnerState,
    sending: Boolean,
    onSelectOwner: (String) -> Unit,
    onRetryOwners: () -> Unit,
) {
    when (ownerState) {
        AskSeatsOwnerState.ServerChosen -> Unit
        AskSeatsOwnerState.Loading ->
            RaftText(
                text = stringResource(Res.string.ask_seats_owners_loading),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
        AskSeatsOwnerState.Failed ->
            RaftBanner(
                description = stringResource(Res.string.ask_seats_owners_failed),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                action = {
                    RaftButton(
                        text = stringResource(Res.string.common_retry),
                        onClick = onRetryOwners,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                    )
                },
            )
        is AskSeatsOwnerState.Loaded -> {
            if (ownerState.owners.size > 1) {
                val creatorLabel = stringResource(Res.string.ask_seats_creator_badge)
                val pickerLabel =
                    stringResource(
                        if (ownerState.owners.size == 1) Res.string.ask_seats_owner_picker_one else Res.string.ask_seats_owner_picker_other,
                        ownerState.owners.size,
                    )
                val options =
                    remember(ownerState.owners, creatorLabel) {
                        ownerState.owners.map { owner ->
                            RaftComboboxOption(
                                value = owner.userId,
                                label = owner.displayName,
                                badge = if (owner.isCreator) creatorLabel else null,
                                badgeAppearance = RaftBadgeAppearance.Outline,
                                badgeVariant = RaftBadgeVariant.Muted,
                                badgeInTrigger = false,
                            )
                        }
                    }
                RaftField(
                    label = pickerLabel,
                    modifier = Modifier.fillMaxWidth().testTag(ASK_SEATS_OWNER_PICKER_TAG),
                    enabled = !sending,
                ) {
                    RaftChoicePicker(
                        value = ownerState.selectedOwner?.userId,
                        options = options,
                        onValueChange = onSelectOwner,
                        title = pickerLabel,
                        emptyText = { "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                    )
                }
            } else {
                val singleOwner = ownerState.selectedOwner ?: return
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(ASK_SEATS_OWNER_ROW_TAG),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftMemberAvatar(
                        identity = RaftMemberAvatarIdentity(MemberKind.Human, singleOwner.userId, serverId),
                        label = singleOwner.displayName,
                        avatarSize = 32.dp,
                        carriedAvatarWireValue = singleOwner.avatarWireValue,
                    )
                    RaftText(
                        text = singleOwner.displayName,
                        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
                        color = RaftTheme.colors.foreground,
                    )
                    RaftBadge(
                        text = stringResource(Res.string.ask_seats_owner_badge),
                        appearance = RaftBadgeAppearance.Outline,
                    )
                }
            }
        }
    }
}

@Composable
internal fun askSeatsIntroText(purpose: SeatRequestPurpose): String =
    when (purpose) {
        is SeatRequestPurpose.Join -> stringResource(Res.string.ask_seats_intro_join, purpose.name)
        is SeatRequestPurpose.Invite -> stringResource(Res.string.ask_seats_intro_invite)
        is SeatRequestPurpose.AgentCreate -> stringResource(Res.string.ask_seats_intro_agent, purpose.name)
        SeatRequestPurpose.AssistantCreate -> stringResource(Res.string.ask_seats_intro_assistant)
        is SeatRequestPurpose.MemberPromote ->
            stringResource(
                if (purpose.role.trim().lowercase() == "admin") {
                    Res.string.ask_seats_intro_promote_admin
                } else {
                    Res.string.ask_seats_intro_promote_member
                },
                purpose.name,
            )
    }

@Composable
internal fun askSeatsSentDescriptionText(purpose: SeatRequestPurpose): String =
    when (purpose) {
        is SeatRequestPurpose.Join -> stringResource(Res.string.ask_seats_sent_desc_join, purpose.name)
        is SeatRequestPurpose.Invite -> stringResource(Res.string.ask_seats_sent_desc_invite)
        is SeatRequestPurpose.AgentCreate -> stringResource(Res.string.ask_seats_sent_desc_agent, purpose.name)
        SeatRequestPurpose.AssistantCreate -> stringResource(Res.string.ask_seats_sent_desc_assistant)
        is SeatRequestPurpose.MemberPromote ->
            stringResource(
                if (purpose.role.trim().lowercase() == "admin") {
                    Res.string.ask_seats_sent_desc_promote_admin
                } else {
                    Res.string.ask_seats_sent_desc_promote_member
                },
                purpose.name,
            )
    }

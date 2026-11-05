package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftTextField
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftTextFieldAddonVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.ServerPublicExposedChannel
import build.raft.app.data.ServerPublicVisibilityRepository
import org.jetbrains.compose.resources.StringResource
import build.raft.app.data.SERVER_ACTION_CONFIRM
import build.raft.app.data.SERVER_ACTION_DELETE
import build.raft.app.data.SERVER_ACTION_LEAVE
import build.raft.app.data.SERVER_ACTION_PROFILE_SAVE
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.serverConfirmationItemId
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SettingsServerSectionSnapshot
import build.raft.app.data.SettingsServerSnapshot
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberSettingsServerPublicAccessBinding(
    repository: ServerPublicVisibilityRepository?,
    serverId: String?,
    role: String?,
    authDataSource: SharedAuthDataSource,
    webBaseUrl: String,
    onCopyUrl: (String) -> Unit,
): SettingsServerPublicAccessBinding? {
    if (repository == null) return null
    val scope = rememberCoroutineScope()
    val store =
        remember(repository, scope, authDataSource) {
            ServerPublicVisibilityStore(
                repository = repository,
                scope = scope,
                refreshServerList = { authDataSource.loadServers() }
            )
        }
    LaunchedEffect(store, serverId, role) {
        store.dispatch(ServerPublicVisibilityIntent.Start(serverId = serverId, role = role))
    }
    val state by store.state.collectAsState()
    return SettingsServerPublicAccessBinding(
        state = state,
        webBaseUrl = webBaseUrl,
        onIntent = store::dispatch,
        onCopyUrl = onCopyUrl,
    )
}

internal data class SettingsServerPublicAccessBinding(
    val state: ServerPublicVisibilityState,
    val webBaseUrl: String,
    val onIntent: (ServerPublicVisibilityIntent) -> Unit,
    val onCopyUrl: (String) -> Unit,
)

@Composable
internal fun SharedSettingsServerContent(
    payload: RaftSharedSettingsPayload,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    serverSnapshot: SettingsServerSnapshot?,
    serverRole: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    expectedServerSlug: String = "",
    deleteConfirmationName: String = "",
    onDeleteConfirmationNameChange: (String) -> Unit = {},
    showProfileCard: Boolean = true,
    /** Owner "Public access" section; null on surfaces that do not host it (Administration, fixtures). */
    publicAccess: SettingsServerPublicAccessBinding? = null,
    onAction: (String, String?) -> Unit
) {
    val visualFullHeight = settingsVisualFullHeightEnabled()
    // Web: each section is `mb-6`, 24dp apart.
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (showProfileCard) {
            item {
                SettingsServerProfileFormCard(
                    profile = serverSnapshot?.profile,
                    draftName = profileName,
                    fallbackName = payload.heroLabel,
                    fallbackSlug = expectedServerSlug,
                    role = serverRole,
                    actionState = actionState,
                    onNameChange = onProfileNameChange,
                    onSubmit = { onAction(SERVER_ACTION_PROFILE_SAVE, profileName.trim()) }
                )
            }
        }
        // Web ServerTabContent order: Profile, then Public access.
        if (publicAccess != null && publicAccess.state.sectionVisible) {
            item {
                SettingsServerPublicAccessCard(
                    state = publicAccess.state,
                    webBaseUrl = publicAccess.webBaseUrl,
                    onIntent = publicAccess.onIntent,
                    onCopyUrl = publicAccess.onCopyUrl
                )
            }
        }
        payload.sections.forEach { section ->
            when (section.title) {
                "Profile", "Danger Zone" -> Unit
                else -> item { SharedSettingsSectionCard(section, onAction = onAction) }
            }
        }
        item {
            SettingsServerDangerZoneCard(
                serverName = serverSnapshot?.profile?.name ?: payload.heroLabel,
                expectedServerSlug = expectedServerSlug,
                role = serverRole,
                dangerSection = serverSnapshot?.sections?.firstOrNull { it.key == "danger" },
                confirmationSlug = deleteConfirmationName,
                actionState = actionState,
                onConfirmationSlugChange = onDeleteConfirmationNameChange,
                onLeave = { onAction(SERVER_ACTION_LEAVE, null) },
                onDelete = { onAction(SERVER_ACTION_DELETE, deleteConfirmationName.trim()) }
            )
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
        if (visualFullHeight) {
            item { SettingsVisualFullHeightSentinel() }
        }
    }
}

@Composable
private fun SettingsServerProfileFormCard(
    profile: build.raft.app.data.SettingsServerProfileSnapshot?,
    draftName: String,
    fallbackName: String,
    fallbackSlug: String,
    role: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val currentName = profile?.name ?: fallbackName
    val currentSlug = profile?.slug?.takeIf { it.isNotBlank() } ?: fallbackSlug
    val canSave = profile?.canSave == true
    val busy = actionState?.action == SERVER_ACTION_PROFILE_SAVE && actionState.inProgress
    val trimmedName = draftName.trim()
    val dirty = trimmedName.isNotBlank() && trimmedName != currentName
    val enabled = canSave && dirty && !busy
    val status = actionState.serverLocalizedStatusFor(SERVER_ACTION_PROFILE_SAVE, if (canSave) stringResource(Res.string.settings_server_action_save_profile) else stringResource(Res.string.settings_server_state_read_only))
    // Web ProfileSection: the "Profile" header over SettingsProfileCard (the server's tile, its
    // name and "/slug"), then Name, the read-only "/" slug and Save Profile.
    SettingsFormSection(iconName = RaftLucideIconName.Building2, title = stringResource(Res.string.settings_profile_title)) {
        SettingsProfileIdentityCard(
            title = currentName,
            subtitle = currentSlug.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty(),
            avatar = { SettingsServerProfileAvatar(name = currentName, serverId = profile?.serverId, avatarUrl = profile?.avatarUrl) }
        ) {
            RaftField(
                label = stringResource(Res.string.settings_server_label_name),
                modifier = Modifier.fillMaxWidth(),
                labelSize = RaftFieldLabelSize.Sm,
                enabled = canSave && !busy
            ) {
                RaftTextField(
                    value = if (canSave) draftName else currentName,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = onNameChange,
                    placeholder = currentName.ifBlank { stringResource(Res.string.settings_server_placeholder_name) }
                )
            }
            RaftField(
                label = stringResource(Res.string.settings_server_label_slug),
                modifier = Modifier.fillMaxWidth(),
                labelSize = RaftFieldLabelSize.Sm
            ) {
                SettingsReadOnlyPrefixedField(
                    prefix = "/",
                    text = currentSlug.ifBlank { stringResource(Res.string.settings_server_state_not_provided) },
                    copyText = currentSlug.takeIf { it.isNotBlank() }
                )
            }
            if (actionState?.action == SERVER_ACTION_PROFILE_SAVE && actionState.errorCode != null) {
                SettingsFormErrorText(actionState)
            }
            if (canSave) {
                // Web: `<Button variant="accent" size="md">`, no icon.
                RaftButton(
                    text = status,
                    onClick = onSubmit,
                    enabled = enabled,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Md
                )
            }
        }
    }
}

/**
 * Web `AvatarSlot context="profile-tile" type="server"`: raft-ui's lg (51dp) server avatar, the
 * uploaded image or the name's initial in `brand` on `sticker-ink`.
 */
@Composable
private fun SettingsServerProfileAvatar(name: String, serverId: String?, avatarUrl: String?) {
    val initial = name.trim().take(1).uppercase().ifBlank { "S" }
    val source = avatarUrl?.takeIf { it.isNotBlank() }
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    RaftAvatar(
        type = RaftAvatarType.Server,
        size = RaftAvatarSize.Lg,
        initials = initial,
        image =
            source?.let { url ->
                @Composable {
                    RaftAvatarHttpImage(
                        source = url,
                        modifier = Modifier.fillMaxSize(),
                        cacheSubject = cleanServerId?.let { id -> RaftAvatarCacheSubject.Server(id) },
                        cacheServerId = cleanServerId,
                        serverDiagnosticsId = cleanServerId,
                        fallback = { SettingsServerProfileAvatarInitial(initial) },
                        placeholder = { SettingsServerProfileAvatarInitial(initial) }
                    )
                }
            }
    )
}

@Composable
private fun SettingsServerProfileAvatarInitial(initial: String) {
    RaftAvatar(type = RaftAvatarType.Server, size = RaftAvatarSize.Lg, initials = initial)
}

@Composable
private fun SettingsServerDangerZoneCard(
    serverName: String,
    expectedServerSlug: String,
    role: String,
    dangerSection: SettingsServerSectionSnapshot?,
    confirmationSlug: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    onConfirmationSlugChange: (String) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    val normalizedRole = role.lowercase()
    val canLeave = normalizedRole == "admin" || normalizedRole == "member"
    val canDelete = normalizedRole == "owner" || dangerSection?.state?.name == "Available"
    var showDeleteModal by remember { mutableStateOf(false) }
    var modalConfirmationSlug by remember { mutableStateOf("") }
    if (!canLeave && !canDelete) {
        return
    }
    val leaveConfirmed = actionState.isServerConfirmation(SERVER_ACTION_LEAVE)
    val deleteBusy = actionState?.action == SERVER_ACTION_DELETE && actionState.inProgress
    val leaveBusy = actionState?.action == SERVER_ACTION_LEAVE && actionState.inProgress
    val trimmedModalSlug = modalConfirmationSlug.trim()
    val deleteEnabled = canDelete && !deleteBusy && expectedServerSlug.isNotBlank() && trimmedModalSlug == expectedServerSlug
    // Web DangerZoneSection: Leave Server and Delete Server are cards of their own, 12dp apart.
    SettingsFormSection(iconName = RaftLucideIconName.TriangleAlert, title = stringResource(Res.string.settings_section_danger_zone_title)) {
        if (canLeave) {
            RaftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsServerActionRow(
                        title = stringResource(Res.string.settings_server_action_leave),
                        detail = stringResource(Res.string.settings_server_leave_detail),
                        buttonLabel = actionState.serverLocalizedStatusFor(SERVER_ACTION_LEAVE, stringResource(Res.string.settings_server_action_leave)),
                        buttonIcon = RaftLucideIconName.LogOut,
                        buttonVariant = RaftButtonVariant.Warning,
                        enabled = !leaveBusy,
                        onClick = onLeave
                    )
                    if (leaveConfirmed) {
                        Text(
                            stringResource(Res.string.settings_server_leave_confirm),
                            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray
                        )
                    }
                }
            }
        }
        if (canDelete) {
            SettingsDangerActionCard(
                title = stringResource(Res.string.settings_server_action_delete),
                detail = stringResource(Res.string.settings_server_delete_detail),
                buttonLabel = stringResource(Res.string.settings_server_action_delete),
                buttonIcon = RaftLucideIconName.Trash2,
                enabled = !deleteBusy,
                onClick = { showDeleteModal = true }
            )
        }
    }
    if (showDeleteModal) {
        SettingsServerDeleteConfirmationDialog(
            serverName = serverName,
            expectedServerSlug = expectedServerSlug,
            confirmationSlug = modalConfirmationSlug,
            deleteBusy = deleteBusy,
            deleteEnabled = deleteEnabled,
            errorMessage =
                actionState
                    ?.takeIf { it.action == SERVER_ACTION_DELETE }
                    ?.userFacingErrorMessage(localizedErrorPresentationStrings()),
            onDismiss = {
                if (!deleteBusy) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            },
            onConfirmationSlugChange = { modalConfirmationSlug = it },
            onCancel = {
                if (!deleteBusy) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            },
            onDelete = {
                onConfirmationSlugChange(modalConfirmationSlug)
                onDelete()
                if (actionState?.action != SERVER_ACTION_DELETE || actionState.errorCode == null) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            }
        )
    }
}

/**
 * Web `DangerActionCard` on a phone: the title (subhead semibold) over the description (footnote,
 * muted), then the red button under them at its own width.
 */
@Composable
private fun SettingsDangerActionCard(
    title: String,
    detail: String,
    buttonLabel: String,
    buttonIcon: RaftLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit
) {
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    title,
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = RaftTheme.colors.foreground
                )
                RaftText(detail, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
            }
            RaftButton(
                text = buttonLabel,
                onClick = onClick,
                enabled = enabled,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Md,
                leadingIcon = { RaftSlotIcon(buttonIcon) }
            )
        }
    }
}

@Composable
internal fun SettingsServerDeleteConfirmationDialog(
    serverName: String,
    expectedServerSlug: String,
    confirmationSlug: String,
    deleteBusy: Boolean,
    deleteEnabled: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirmationSlugChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val resolvedName = serverName.ifBlank { stringResource(Res.string.settings_server_this_server) }
    val resolvedSlug = expectedServerSlug.ifBlank { "server-slug" }
    Modal(onWillDismiss = { if (!deleteBusy) onDismiss() }) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            RaftModalDismissBarrier(
                overlayAlpha = 0.58f,
                onDismiss = onDismiss,
                // Destructive confirm (web ConfirmDialog): no scrim dismiss; Back, × and Cancel close it.
                dismissEnabled = false
            )
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 18.dp)
                        .widthIn(max = 384.dp)
                        .raftFloatingContentClickTarget()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .raftLookOverlaySurface(RaftAppRadius.Dialog)
                            // raft-ui Dialog: p-5 insets.
                            .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // raft-ui Dialog title: 17/22 bold in source case (web
                        // "Delete Server"), with the ghost 32dp close.
                        Text(
                            stringResource(Res.string.settings_server_action_delete),
                            style = RaftType.Headline.copy(color = RaftColor.TextBlack),
                            color = RaftColor.TextBlack,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RaftBackButton(
                            onClick = onDismiss,
                            label = stringResource(Res.string.common_close),
                            glyph = RaftGlyph.X,
                            enabled = !deleteBusy,
                        )
                    }
                    // web DeleteServerConfirmDialog: the confirmation (its label, then the slug field)
                    // sits inside the destructive message, under the warning (`stack-y-4`, `mb-2`).
                    SettingsServerDeleteWarningBanner(serverName = resolvedName) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(Res.string.settings_server_delete_type_prefix) + " ",
                                // Web DeleteServerConfirmDialog: text-footnote font-semibold (13/18 600),
                                // the slug in <code> font-mono text-mono-code (13/20).
                                style = RaftType.Footnote.withRaftFontWeight(FontWeight.SemiBold).copy(color = RaftColor.TextBlack),
                                color = RaftColor.TextBlack
                            )
                            Text(
                                resolvedSlug,
                                // The slug to type: a long press copies it (not the "server-slug" stand-in).
                                // `<code class="rounded-sticker bg-fill-muted px-1">`.
                                modifier =
                                    Modifier
                                        .raftCopyable(expectedServerSlug)
                                        .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker))
                                        .padding(horizontal = 4.dp),
                                style = RaftTheme.typography.monoCode.copy(color = RaftColor.TextBlack),
                                color = RaftColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(Res.string.settings_server_delete_confirm_suffix),
                                style = RaftType.Footnote.withRaftFontWeight(FontWeight.SemiBold).copy(color = RaftColor.TextBlack),
                                color = RaftColor.TextBlack
                            )
                        }
                        SettingsServerSlugInput(
                            value = confirmationSlug,
                            placeholder = resolvedSlug,
                            enabled = !deleteBusy,
                            onValueChange = onConfirmationSlugChange
                        )
                    }
                    errorMessage?.takeIf { it.isNotBlank() }?.let {
                        SettingsServerDeleteWarningBanner(serverName = null, text = it)
                    }
                    RaftDialogFooter {
                        RaftButton(
                            text = stringResource(Res.string.common_cancel),
                            onClick = onCancel,
                            enabled = !deleteBusy,
                            modifier = Modifier.widthIn(min = 72.dp),
                            variant = RaftButtonVariant.Ghost
                        )
                        RaftButton(
                            text = if (deleteBusy) stringResource(Res.string.settings_server_action_deleting) else stringResource(Res.string.settings_server_action_delete),
                            onClick = onDelete,
                            enabled = deleteEnabled,
                            modifier = Modifier.widthIn(min = 112.dp),
                            variant = RaftButtonVariant.Danger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsServerDeleteWarningBanner(
    serverName: String?,
    text: String? = null,
    content: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null
) {
    val fallbackName = stringResource(Res.string.settings_server_this_server)
    val prefix = stringResource(Res.string.settings_server_delete_warning_prefix)
    val suffix = stringResource(Res.string.settings_server_delete_warning_suffix)
    val message = remember(serverName, text, fallbackName, prefix, suffix) {
        settingsServerDeleteWarningBannerText(
            serverName = serverName,
            text = text,
            fallbackName = fallbackName,
            prefix = prefix,
            suffix = suffix
        )
    }
    // raft-ui Banner `destructive`: deleting the server cannot be undone.
    RaftBanner(
        description = message,
        status = RaftBannerStatus.Destructive,
        modifier = Modifier.fillMaxWidth(),
        content = content
    )
}

internal fun settingsServerDeleteWarningBannerText(
    serverName: String?,
    text: String? = null,
    fallbackName: String = "this server",
    prefix: String = "This will permanently delete ",
    suffix: String = " and all its data (agents, channels, messages). This cannot be undone."
): AnnotatedString {
    if (text != null) return AnnotatedString(text)
    val resolvedName = serverName ?: fallbackName
    return buildAnnotatedString {
        append(prefix)
        append(resolvedName)
        append(suffix)
        if (serverName != null) {
            addStyle(
                raftSpanStyleForWeight(FontWeight.Bold),
                start = prefix.length,
                end = prefix.length + resolvedName.length
            )
        }
    }
}

@Composable
private fun SettingsServerSlugInput(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    // Caller-local TextFieldValue owns the confirmation-slug editor for this
    // modal's mount; the durable store keeps the String projection (task #130
    // Phase D). The slug path applies no per-keystroke filtering or truncation
    // (the only normalisation is a .trim() at submit), so there is nothing to
    // coerce the selection against here — the editor value is forwarded verbatim.
    var slugValue by remember { mutableStateOf(textFieldValueAtEnd(value)) }
    // Web `SlugInput`: InputGroup with a container `/` addon.
    RaftTextField(
        value = slugValue,
        onValueChange = {
            slugValue = it
            onValueChange(it.text)
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder.ifEmpty { null },
        enabled = enabled,
        prefix = "/",
        addonVariant = RaftTextFieldAddonVariant.Container,
        keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions
    )
}

/**
 * Owner-only "Public access" section (web SettingsPanel.tsx PublicVisibilitySection,
 * botiverse/slock@9df0d1b3f lines 2160-2436). State and every write go through
 * [ServerPublicVisibilityStore]; this composable only renders state and emits intents.
 * [webBaseUrl] is the active Developer environment web URL and is used for both display and copy.
 */
@Composable
private fun SettingsServerPublicAccessCard(
    state: ServerPublicVisibilityState,
    webBaseUrl: String,
    onIntent: (ServerPublicVisibilityIntent) -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    if (!state.sectionVisible) return
    val publicUrl = state.publicUrl(webBaseUrl)
    val channels = state.readback?.exposedChannels.orEmpty()
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Globe,
        title = stringResource(Res.string.settings_public_visibility_section_label)
    ) {
        SettingsPublicAccessSwitchRow(
            title = stringResource(Res.string.settings_public_visibility_title),
            detail = stringResource(Res.string.settings_public_visibility_description),
            checked = state.visibilityChecked,
            enabled = state.visibilitySwitchEnabled,
            nested = false,
            onToggle = { onIntent(ServerPublicVisibilityIntent.VisibilityToggled(!state.visibilityChecked)) }
        )
        if (publicUrl != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.settings_public_visibility_public_url),
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextMutedGray
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RaftReadOnlyField(
                        text = publicUrl,
                        modifier = Modifier.weight(1f)
                    )
                    RaftButton(
                        text = stringResource(Res.string.settings_public_visibility_copy_url),
                        onClick = { onCopyUrl(publicUrl) },
                        size = RaftButtonSize.Lg,
                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) }
                    )
                }
            }
        }
        if (state.guestJoinApplicable) {
            SettingsPublicAccessSwitchRow(
                title = stringResource(Res.string.settings_public_visibility_allow_guest_join),
                detail = stringResource(Res.string.settings_public_visibility_allow_guest_join_description),
                checked = state.guestJoinChecked,
                enabled = state.subSwitchesEnabled,
                nested = true,
                onToggle = { onIntent(ServerPublicVisibilityIntent.GuestJoinToggled(!state.guestJoinChecked)) }
            )
        }
        SettingsPublicAccessSwitchRow(
            title = stringResource(Res.string.settings_public_visibility_allow_search),
            detail = stringResource(Res.string.settings_public_visibility_allow_search_description),
            checked = state.searchChecked,
            enabled = state.subSwitchesEnabled,
            nested = true,
            onToggle = { onIntent(ServerPublicVisibilityIntent.SearchToggled(!state.searchChecked)) }
        )
        RaftSeparator()
        Text(
            stringResource(Res.string.settings_public_visibility_channel_list_label),
            style = RaftType.Button.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        if (state.loading) {
            RaftSkeleton(Modifier.fillMaxWidth().height(64.dp))
        } else {
            SettingsPublicAccessChannelList(channels)
        }
        state.error?.let { error ->
            SettingsFormErrorText(error.serverMessage ?: stringResource(error.kind.fallbackMessage()))
        }
    }
    if (state.confirmOpen) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.settings_public_visibility_confirm_title),
            onDismiss = { onIntent(ServerPublicVisibilityIntent.DismissConfirm) },
            dismissEnabled = !state.saving,
            uppercaseTitle = false
        ) {
            Text(
                stringResource(Res.string.settings_public_visibility_confirm_description),
                style = RaftType.Body,
                color = RaftColor.TextBlack
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item { SettingsPublicAccessChannelList(channels) }
            }
            SettingsFormDetailText(stringResource(Res.string.settings_public_visibility_confirm_audience_change))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = { onIntent(ServerPublicVisibilityIntent.DismissConfirm) },
                    enabled = !state.saving,
                    size = RaftButtonSize.Lg
                )
                RaftButton(
                    text = if (state.saving) {
                            stringResource(Res.string.settings_public_visibility_confirm_saving)
                        } else {
                            stringResource(Res.string.settings_public_visibility_confirm_action)
                        },
                    onClick = { onIntent(ServerPublicVisibilityIntent.ConfirmMakePublic) },
                    enabled = !state.saving,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg
                )
            }
        }
    }
}

private fun ServerPublicVisibilityErrorKind.fallbackMessage(): StringResource =
    when (this) {
        ServerPublicVisibilityErrorKind.Load -> Res.string.settings_public_visibility_failed_load
        ServerPublicVisibilityErrorKind.Update -> Res.string.settings_public_visibility_failed_update
        ServerPublicVisibilityErrorKind.GuestJoinUpdate -> Res.string.settings_public_visibility_failed_guest_join_update
        ServerPublicVisibilityErrorKind.SearchUpdate -> Res.string.settings_public_visibility_failed_search_update
    }

@Composable
private fun SettingsPublicAccessSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    nested: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (nested) {
            // Second level (web: indented with a guide line under the main switch).
            Box(
                Modifier
                    .padding(start = 4.dp, end = 14.dp)
                    .width(RaftStroke.Strong)
                    .heightIn(min = 40.dp)
                    .background(RaftTheme.colors.lineHairline)
            )
        }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .toggleable(value = checked, enabled = enabled, role = Role.Switch) { onToggle() }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = RaftType.Button.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
                SettingsFormDetailText(detail)
            }
            RaftSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsPublicAccessChannelList(channels: List<ServerPublicExposedChannel>) {
    if (channels.isEmpty()) {
        RaftCard(modifier = Modifier.fillMaxWidth()) {
            RaftText(
                stringResource(Res.string.settings_public_visibility_empty_channels),
                modifier = Modifier.fillMaxWidth(),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
        channels.forEachIndexed { index, channel ->
            if (index > 0) {
                RaftSeparator()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RaftIconTile(size = RaftIconTileSize.Sm) {
                    RaftLucideIcon(
                        RaftLucideIconName.Hash,
                        modifier = Modifier.size(RaftIconTileSize.Sm.icon),
                        color = raftArgbHex(RaftColor.TextMuted)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        channel.name,
                        style = RaftType.Button.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    channel.description?.let {
                        Text(
                            it,
                            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScreenLoadState<SettingsServerSnapshot>.toServerPayload(
    session: StoredAuthSession?,
    refreshing: Boolean,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    timeContext: RaftTimeContext?
): RaftSharedSettingsPayload {
    val server = session?.currentServerSummary()
    val snapshot = (this as? ScreenLoadState.Content<SettingsServerSnapshot>)?.data
    val role = server?.role?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Member"
    val detail =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing workspace settings..." else "Loading workspace settings..."
            is ScreenLoadState.Error -> "Workspace settings failed to load: ${error.localizedUserFacingMessage()}"
            is ScreenLoadState.NoPermission -> "Workspace settings unavailable: ${blocker.localizedUserFacingMessage()}"
            is ScreenLoadState.Empty -> "Workspace settings unavailable: ${reason.localizedUserFacingMessage()}"
            is ScreenLoadState.Content -> "Manage this workspace's profile. Admin actions live under Administration."
        }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-server",
        title = "Server Profile",
        heroIconName = RaftLucideIconName.Building2,
        heroLabel = snapshot?.profile?.name ?: server?.name ?: "Workspace settings",
        detail = detail,
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Profile",
                    rows =
                        listOf(
                            RaftSharedSettingsRow("serverName", RaftLucideIconName.BadgeInfo, "Workspace name", "Current selected workspace profile.", status = snapshot?.profile?.name ?: server?.name ?: "Unknown"),
                            RaftSharedSettingsRow(
                                SERVER_ACTION_PROFILE_SAVE,
                                RaftLucideIconName.Save,
                                "Save workspace profile",
                                "Save changes to the workspace name.",
                                status = actionState.serverStatusFor(SERVER_ACTION_PROFILE_SAVE, if (snapshot?.profile?.canSave == true) "Ready" else "Read only"),
                                enabled = snapshot?.profile?.canSave == true && actionState?.inProgress != true
                            ),
                            RaftSharedSettingsRow("serverSlug", RaftLucideIconName.Link, "Slug", snapshot?.profile?.slug?.let { "/$it" } ?: server?.slug?.let { "/$it" } ?: "Not provided", status = if (snapshot?.profile?.canSave == true) "Editable" else "Read only"),
                            RaftSharedSettingsRow("serverRole", RaftLucideIconName.ShieldCheck, "Your role", "Permission scope for workspace settings.", status = role),
                            RaftSharedSettingsRow(
                                "openAdministration",
                                RaftLucideIconName.Shield,
                                "Administration",
                                "Manage admins, invites, join links, pre-join agreement, onboarding, member permissions, translation, and sidebar order.",
                                status = "Open",
                                enabled = true
                            )
                        )
                )
            )
    )
}

private fun build.raft.app.data.SharedSettingsActionState?.serverStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        isServerConfirmation(action, itemId) -> "Confirm"
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> "Loading"
        hasUserFacingError() -> "Error"
        else -> "Saved"
    }

@Composable
private fun build.raft.app.data.SharedSettingsActionState?.serverLocalizedStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        isServerConfirmation(action, itemId) -> stringResource(Res.string.settings_state_confirm)
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> stringResource(Res.string.settings_state_loading)
        hasUserFacingError() -> stringResource(Res.string.settings_state_error)
        else -> stringResource(Res.string.settings_state_saved)
    }

private fun build.raft.app.data.SharedSettingsActionState?.isServerConfirmation(
    action: String,
    itemId: String? = null
): Boolean =
    this?.action == SERVER_ACTION_CONFIRM &&
        this.itemId == serverConfirmationItemId(action, itemId)

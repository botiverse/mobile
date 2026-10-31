package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * The agent's change-avatar dialog: web `AgentProfileEditDialog` with `field="avatar"`
 * (botiverse/slock staging, packages/web/src/components/agent/AgentProfileEditDialog.tsx),
 * opened from the profile hero avatar (`agent-change-avatar` in AgentDetailPanel.tsx).
 * On a phone it is the NarrowDialog sheet: × and the title, Save in the head.
 */

/** The avatar choice staged in the dialog; nothing is sent until Save (web `AvatarDraft`). */
internal sealed interface MembersAgentAvatarDraft {
    data object Unchanged : MembersAgentAvatarDraft

    /** A pixel avatar other than the default. */
    data class Preset(val key: String) : MembersAgentAvatarDraft

    /** The default avatar (robot): the profile's avatar is cleared. */
    data object Default : MembersAgentAvatarDraft

    /** A picked image, uploaded on Save. */
    data class Upload(val file: PlatformFile) : MembersAgentAvatarDraft
}

/** The web's `isAvatarFileTooLarge`: 5 MB. */
internal const val MembersAgentAvatarMaxUploadBytes: Long = 5L * 1024L * 1024L

/** A container at least this wide shows a series in one row of 8 (web `@min-[408px]:grid-cols-8`). */
internal val MembersAgentAvatarEightColumnMinWidth: Dp = 408.dp

private const val MembersAgentPixelAvatarPrefix = "pixel:"
private val MembersAgentAvatarTileSize = 44.dp
private val MembersAgentAvatarTileGap = 8.dp

/** Avatars per row: one series per row of 8; narrower than 8 tiles, two rows of 4. */
internal fun membersAgentAvatarGridColumns(width: Dp): Int = if (width >= MembersAgentAvatarEightColumnMinWidth) 8 else 4

/** The agent's avatar as the server stores it (`pixel:<key>` or an image URL). */
private fun MemberProfile.membersAgentAvatarWire(): String? =
    avatar?.wireValue?.trim()?.takeIf { it.isNotBlank() }

/** True when the agent's avatar is an uploaded image (web `isCustomAvatar`: set and not `pixel:`). */
internal fun MemberProfile.membersAgentHasCustomAvatar(): Boolean =
    membersAgentAvatarWire()?.startsWith(MembersAgentPixelAvatarPrefix) == false

/** The staged choice for a picked image, or null when the image is over the 5 MB limit. */
internal fun membersAgentAvatarUploadDraft(file: PlatformFile): MembersAgentAvatarDraft.Upload? =
    file.takeUnless { (it.sizeBytes ?: 0L) > MembersAgentAvatarMaxUploadBytes }?.let { MembersAgentAvatarDraft.Upload(it) }

/** The pixel avatar to stage for a tile: the default key stages [MembersAgentAvatarDraft.Default]. */
internal fun membersAgentAvatarPresetDraft(key: String, defaultKey: String = PixelAvatarActiveDefaultKey): MembersAgentAvatarDraft =
    if (key == defaultKey) MembersAgentAvatarDraft.Default else MembersAgentAvatarDraft.Preset(key)

/**
 * The pixel avatar shown as chosen (web `selectedPreset`): the staged one, else the agent's own
 * pixel avatar (the default when it has none); null while an image is staged or is the avatar.
 */
internal fun membersAgentAvatarSelectedPreset(
    draft: MembersAgentAvatarDraft,
    member: MemberProfile,
    defaultKey: String = PixelAvatarActiveDefaultKey
): String? =
    when (draft) {
        is MembersAgentAvatarDraft.Preset -> draft.key
        MembersAgentAvatarDraft.Default -> defaultKey
        is MembersAgentAvatarDraft.Upload -> null
        // Web `parsePixelAvatar(agent.avatarUrl) ?? DEFAULT_AVATAR_KEY`.
        MembersAgentAvatarDraft.Unchanged ->
            if (member.membersAgentHasCustomAvatar()) {
                null
            } else {
                member.membersAgentAvatarWire()?.removePrefix(MembersAgentPixelAvatarPrefix)?.takeIf { it.isNotBlank() } ?: defaultKey
            }
    }

/** True when the upload tile shows as chosen: an image is staged, or the avatar is one. */
internal fun membersAgentAvatarUploadSelected(draft: MembersAgentAvatarDraft, member: MemberProfile): Boolean =
    draft is MembersAgentAvatarDraft.Upload ||
        (draft == MembersAgentAvatarDraft.Unchanged && member.membersAgentHasCustomAvatar())

/** The profile update's `avatarUrl` for a staged pixel choice; an upload goes through the upload call. */
internal fun MembersAgentAvatarDraft.profileAvatarUrl(): String? =
    when (this) {
        is MembersAgentAvatarDraft.Preset -> "$MembersAgentPixelAvatarPrefix$key"
        else -> null
    }

/** What the dialog's warning banner says (web: an inline `Banner status="warning"`). */
internal sealed interface MembersAgentAvatarError {
    data object TooLarge : MembersAgentAvatarError

    data object Unchanged : MembersAgentAvatarError

    data class Upload(val error: ScreenError) : MembersAgentAvatarError

    data class Save(val error: ScreenError) : MembersAgentAvatarError

    data class Permission(val blocker: PermissionBlocker) : MembersAgentAvatarError
}

/** The banner for a failed Save of [draft]. */
internal fun membersAgentAvatarSaveError(
    draft: MembersAgentAvatarDraft,
    result: DataResult<*>
): MembersAgentAvatarError? =
    when (result) {
        is DataResult.Content -> null
        is DataResult.Empty -> MembersAgentAvatarError.Unchanged
        is DataResult.NoPermission -> MembersAgentAvatarError.Permission(result.blocker)
        is DataResult.Error ->
            if (draft is MembersAgentAvatarDraft.Upload) {
                MembersAgentAvatarError.Upload(result.error)
            } else {
                MembersAgentAvatarError.Save(result.error)
            }
    }

@Composable
private fun MembersAgentAvatarError.message(): String {
    val errorStrings = localizedErrorPresentationStrings()
    return when (this) {
        MembersAgentAvatarError.TooLarge -> stringResource(Res.string.member_avatar_too_large)
        MembersAgentAvatarError.Unchanged -> stringResource(Res.string.member_error_avatar_unchanged)
        is MembersAgentAvatarError.Upload ->
            stringResource(Res.string.member_error_avatar_upload, error.userFacingMessage(errorStrings))
        is MembersAgentAvatarError.Save ->
            stringResource(Res.string.member_error_avatar_save, error.userFacingMessage(errorStrings))
        is MembersAgentAvatarError.Permission ->
            stringResource(Res.string.member_error_permission, blocker.userFacingMessage(errorStrings))
    }
}

@Composable
private fun membersAgentAvatarSeriesTitle(seriesKey: String?): String? =
    when (seriesKey) {
        "bots" -> stringResource(Res.string.member_avatar_series_bots)
        "critters" -> stringResource(Res.string.member_avatar_series_critters)
        "space" -> stringResource(Res.string.member_avatar_series_space)
        "desk" -> stringResource(Res.string.member_avatar_series_desk)
        "food" -> stringResource(Res.string.member_avatar_series_food)
        "quest" -> stringResource(Res.string.member_avatar_series_quest)
        "weather" -> stringResource(Res.string.member_avatar_series_weather)
        "plants" -> stringResource(Res.string.member_avatar_series_plants)
        "spooky" -> stringResource(Res.string.member_avatar_series_spooky)
        // A series the app has no name for yet (a newer avatar table) shows without a heading.
        else -> null
    }

/**
 * The profile hero avatar as the change-avatar trigger (web `PROFILE_AVATAR_BUTTON_CLASS` with
 * `AvatarEditVeil icon={Pencil}`): a button named "Change avatar" that wraps the sticker
 * exactly; the veil (the `layer-backdrop` scrim with the pencil in HUD ink) shows while it is
 * pressed, as the web's shows on hover and focus. Not [enabled]: the plain sticker.
 */
@Composable
internal fun MembersAgentAvatarEditTrigger(
    enabled: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit
) {
    if (!enabled) {
        avatar()
        return
    }
    val label = stringResource(Res.string.member_action_choose_avatar)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier =
            Modifier
                .semantics { contentDescription = label }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = label,
                    role = Role.Button,
                    onClick = onClick
                )
    ) {
        avatar()
        if (pressed) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(RaftRadius.Sticker))
                        .background(RaftTheme.colors.layerBackdrop),
                contentAlignment = Alignment.Center
            ) {
                RaftGlyphIcon(RaftGlyph.Pencil, size = 20.dp, color = RaftTheme.colors.foregroundOnHud)
            }
        }
    }
}

/**
 * Change avatar (web `AgentProfileEditDialog`, `field="avatar"`, `narrow="sheet"`): the staged
 * avatar's preview next to the upload tile, then the pixel avatars by series. A choice is only
 * staged; Save (disabled until something changed, busy while it runs) uploads the picked image
 * or writes the pixel avatar, then closes. × with a staged change asks to discard it first.
 * A failure stays in the dialog as a warning banner.
 */
@Composable
internal fun MembersAgentAvatarDialog(
    member: MemberProfile,
    currentAvatarWireValue: String?,
    onPickFile: suspend () -> DataResult<PlatformFile>,
    onSave: suspend (MembersAgentAvatarDraft) -> DataResult<*>,
    onDismiss: () -> Unit
) {
    var draft by remember(member.id.value) { mutableStateOf<MembersAgentAvatarDraft>(MembersAgentAvatarDraft.Unchanged) }
    var saving by remember(member.id.value) { mutableStateOf(false) }
    var picking by remember(member.id.value) { mutableStateOf(false) }
    var error by remember(member.id.value) { mutableStateOf<MembersAgentAvatarError?>(null) }
    var confirmDiscard by remember(member.id.value) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = draft != MembersAgentAvatarDraft.Unchanged

    fun requestClose() {
        if (saving) return
        if (dirty) confirmDiscard = true else onDismiss()
    }

    fun save() {
        val staged = draft
        if (staged == MembersAgentAvatarDraft.Unchanged || saving) return
        saving = true
        error = null
        scope.launch {
            val result =
                try {
                    onSave(staged)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_avatar_save_failed"))
                }
            saving = false
            val failure = membersAgentAvatarSaveError(staged, result)
            if (failure == null) onDismiss() else error = failure
        }
    }

    fun pickUpload() {
        if (picking || saving) return
        picking = true
        scope.launch {
            val picked =
                try {
                    onPickFile()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_avatar_picker_failed"))
                }
            picking = false
            when (picked) {
                is DataResult.Content -> {
                    val staged = membersAgentAvatarUploadDraft(picked.data)
                    if (staged == null) {
                        error = MembersAgentAvatarError.TooLarge
                    } else {
                        error = null
                        draft = staged
                    }
                }
                // The picker was closed without a choice: nothing changes (web: no file, no-op).
                is DataResult.Empty -> Unit
                is DataResult.Error -> error = MembersAgentAvatarError.Upload(picked.error)
                is DataResult.NoPermission -> error = MembersAgentAvatarError.Permission(picked.blocker)
            }
        }
    }

    RaftDialogSheet(
        onDismissRequest = ::requestClose,
        title = stringResource(Res.string.member_action_choose_avatar),
        commitLabel = stringResource(Res.string.common_save),
        onCommit = ::save,
        commitEnabled = dirty && !picking,
        commitLoading = saving,
        // Web `closeDisabled={saving}`.
        closeEnabled = !saving,
        closeLabel = stringResource(Res.string.common_close),
        cancelLabel = stringResource(Res.string.common_cancel)
    ) {
        MembersAgentAvatarDialogBody(
            member = member,
            currentAvatarWireValue = currentAvatarWireValue,
            draft = draft,
            picking = picking,
            enabled = !saving,
            error = error,
            onUpload = ::pickUpload,
            onSelectPreset = { key ->
                error = null
                draft = membersAgentAvatarPresetDraft(key)
            }
        )
    }

    if (confirmDiscard) {
        MembersAgentAvatarDiscardDialog(
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            }
        )
    }
}

/** Web AlertDialog `agent-profile-edit-discard`: Keep Editing, then the red Discard Changes. */
@Composable
private fun MembersAgentAvatarDiscardDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    RaftDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(Res.string.member_avatar_unsaved_title),
        description = stringResource(Res.string.member_avatar_discard_body),
        dismissOnClickOutside = false,
        showClose = false,
        footer = {
            RaftButton(
                text = stringResource(Res.string.member_avatar_keep_editing),
                onClick = onKeepEditing,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = stringResource(Res.string.member_avatar_discard_changes),
                onClick = onDiscard,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg
            )
        }
    )
}

/**
 * The dialog's body: the preview (`profile-tile`, the Lg sticker) of what Save would set and
 * the upload tile, the warning banner, then one group per series, a `group` heading (footnote
 * bold, hint) over 44dp tiles 8dp apart. Scrolls inside the sheet. The web puts the banner at
 * the end of its body; under nine series on a phone that is out of sight, so it leads here.
 */
@Composable
internal fun ColumnScope.MembersAgentAvatarDialogBody(
    member: MemberProfile,
    currentAvatarWireValue: String?,
    draft: MembersAgentAvatarDraft,
    picking: Boolean,
    enabled: Boolean,
    error: MembersAgentAvatarError?,
    onUpload: () -> Unit,
    onSelectPreset: (String) -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val defaultKey = PixelAvatarActiveDefaultKey
    val selectedPreset = membersAgentAvatarSelectedPreset(draft, member, defaultKey)
    val groups = remember(RaftPixelAvatarRuntime.current) { pixelAvatarPickerGroups() }
    val scroll = rememberScrollState()
    // The banner sits under the preview, so a failure shows where the list starts.
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scroll)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MembersAgentAvatarPreview(member, currentAvatarWireValue, draft, defaultKey)
                MembersAgentAvatarUploadTile(
                    selected = membersAgentAvatarUploadSelected(draft, member),
                    busy = picking,
                    enabled = enabled,
                    onClick = onUpload
                )
            }
            if (error != null) {
                RaftBanner(description = error.message(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = membersAgentAvatarGridColumns(maxWidth)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    groups.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            membersAgentAvatarSeriesTitle(group.seriesKey)?.let { title ->
                                RaftText(
                                    text = title,
                                    style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                                    color = colors.foregroundHint,
                                    modifier = Modifier.semantics { heading() }
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(MembersAgentAvatarTileGap)) {
                                group.avatars.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(MembersAgentAvatarTileGap)) {
                                        row.forEach { key ->
                                            MembersAgentAvatarPresetTile(
                                                key = key,
                                                selected = selectedPreset == key,
                                                enabled = enabled,
                                                onClick = { onSelectPreset(key) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What Save would set, as the Lg sticker: the staged image, the staged pixel avatar, or today's. */
@Composable
private fun MembersAgentAvatarPreview(
    member: MemberProfile,
    currentAvatarWireValue: String?,
    draft: MembersAgentAvatarDraft,
    defaultKey: String
) {
    val size = RaftAvatarSize.Lg
    val face = size.edge - RaftStroke.Control * 2
    RaftAvatar(
        type = RaftAvatarType.Agent,
        size = size,
        image = {
            val previewUri = (draft as? MembersAgentAvatarDraft.Upload)?.file?.previewUri?.takeIf { it.isNotBlank() }
            if (previewUri != null) {
                // A picked local image: the image loader reads the file's own URI.
                RaftCachedImage(
                    source = previewUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                val wireValue =
                    when (draft) {
                        is MembersAgentAvatarDraft.Preset -> "$MembersAgentPixelAvatarPrefix${draft.key}"
                        MembersAgentAvatarDraft.Default -> "$MembersAgentPixelAvatarPrefix$defaultKey"
                        else -> currentAvatarWireValue ?: member.avatarWireValueForAvatarSlot()
                    }
                RaftAppAvatar(
                    avatarWireValue = wireValue,
                    label = member.avatarLabel,
                    avatarSize = face,
                    borderWidth = 0.dp,
                    cacheSubject = if (draft == MembersAgentAvatarDraft.Unchanged) member.raftAvatarCacheSubject() else null,
                    cacheServerId = member.serverId?.value,
                    fallbackFill = raftAvatarFallbackFill(MemberKind.Agent)
                )
            }
        }
    )
}

/**
 * The upload tile (web `presetButtonClass(selected, upload = true)`): dashed `line-strong` until
 * used, the chosen look once an image is staged or is the avatar; the spinner while the picker
 * is open.
 */
@Composable
private fun MembersAgentAvatarUploadTile(
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = RaftTheme.colors
    val label = stringResource(Res.string.member_avatar_upload_image)
    val dashed = colors.lineStrong
    MembersAgentAvatarTileFrame(
        selected = selected,
        enabled = enabled && !busy,
        label = label,
        onClick = onClick,
        modifier =
            if (selected) {
                Modifier
            } else {
                Modifier.drawBehind {
                    val stroke = RaftStroke.Control.toPx()
                    val radius = RaftRadius.Control.toPx() - stroke / 2
                    drawRoundRect(
                        color = dashed,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * stroke, 2 * stroke)))
                    )
                }
            }
    ) {
        val ink = if (selected) RaftUiPalette.stickerInk else colors.foregroundMuted
        if (busy) {
            RaftSpinner(color = ink)
        } else {
            RaftLucideIcon(RaftLucideIconName.Upload, modifier = Modifier.size(18.dp), color = raftArgbHex(ink))
        }
    }
}

/** One pixel avatar (web preset button: the `panel-header` sticker in a 44dp tile), named by its key. */
@Composable
private fun MembersAgentAvatarPresetTile(
    key: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    MembersAgentAvatarTileFrame(selected = selected, enabled = enabled, label = key, onClick = onClick) {
        val size = RaftAvatarSize.Md
        RaftAvatar(
            type = RaftAvatarType.Agent,
            size = size,
            image = {
                RaftAppAvatar(
                    avatarWireValue = "$MembersAgentPixelAvatarPrefix$key",
                    label = key,
                    avatarSize = size.edge - RaftStroke.Control * 2,
                    borderWidth = 0.dp,
                    fallbackFill = raftAvatarFallbackFill(MemberKind.Agent)
                )
            }
        )
    }
}

/**
 * A 44dp tile with the control radius and a 1.5dp edge. Chosen: "you chose this" is yellow, the
 * `selected` sticker (Web `bg-selected`, yellow in every theme) with the `sticker-ink` edge behind the sprite; else no edge.
 */
@Composable
private fun MembersAgentAvatarTileFrame(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier =
            modifier
                .size(MembersAgentAvatarTileSize)
                .clip(shape)
                .background(if (selected) RaftUiPalette.selected else Color.Transparent, shape)
                .border(RaftStroke.Control, if (selected) RaftUiPalette.stickerInk else Color.Transparent, shape)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.MemberServerRef
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/*
 * Member labels (#wg-rbac:#46, web botiverse/slock #14535 `components/member/MemberLabelParts.tsx`):
 * the marks of someone from another server that raft-ui has no part for yet, kept here as the
 * web keeps them out of raft-ui:
 *
 * - [RaftAvatarWithOriginCorner]: another server's tile on an avatar's top-left corner (the
 *   bottom-right stays the presence light or the delegated face), in the message author row,
 *   the channel member list and the @ list (a plain dot there);
 * - [RaftProfilePreviewExternalRibbon]: the band across the top of the profile card with the
 *   person's servers and "External".
 *
 * Where someone is from is [build.raft.app.data.memberOrigin]; the servers passed here are its
 * `servers` (the viewed server left out), empty for anyone of the viewed server.
 */

/**
 * The logos of the servers the viewer belongs to (session servers, id → `avatarUrl`), as the web
 * reads `serverStore.servers`: the API names another server but sends no logo for it, so only a
 * server the viewer is also a member of shows its image; any other shows its initial.
 */
internal val LocalRaftViewerServerLogos = compositionLocalOf<Map<String, String>> { emptyMap() }

/** [LocalRaftViewerServerLogos]'s value from the session's servers. */
internal fun raftViewerServerLogos(servers: List<Pair<String, String?>>): Map<String, String> =
    buildMap {
        for ((id, avatarUrl) in servers) {
            val cleanId = id.trim().takeIf { it.isNotEmpty() } ?: continue
            val url = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            put(cleanId, url)
        }
    }

/** "Acme Corp, Lumen Labs": every server's name (web `originServerNames`). */
internal fun raftOriginServerNames(servers: List<MemberServerRef>): String =
    servers.map { it.originLabel() }.filter { it.isNotEmpty() }.joinToString(", ")

internal fun MemberServerRef.originLabel(): String = name.trim().ifBlank { slug.trim() }

/** A server tile's letter: the first of its name (web AvatarSlot `serverInitial`). */
internal fun MemberServerRef.originInitial(): String = originLabel().take(1).uppercase()

/**
 * Where the corner sits and how large it is, from the avatar as drawn (web: a query container over
 * the face): the tile is 42% of the face, 7–19dp; it overhangs the top and left edges by 9%, 3–5dp;
 * its initial is 23%, 8–11sp. Under a 26dp face (and always with [forceDot], the @ list's faces)
 * it is a plain dot without an initial.
 */
@Immutable
internal data class RaftAvatarOriginCornerGeometry(
    val tile: Dp,
    val overhang: Dp,
    val initialSize: TextUnit,
    val dot: Boolean,
)

internal fun raftAvatarOriginCornerGeometry(avatarEdge: Dp, forceDot: Boolean = false): RaftAvatarOriginCornerGeometry {
    val edge = avatarEdge.value
    return RaftAvatarOriginCornerGeometry(
        tile = (edge * 0.42f).coerceIn(7f, 19f).dp,
        overhang = (edge * 0.09f).coerceIn(3f, 5f).dp,
        initialSize = (edge * 0.23f).coerceIn(8f, 11f).sp,
        dot = forceDot || edge < 26f,
    )
}

internal const val RaftAvatarOriginCornerTestTag = "avatar-origin-corner"
internal const val RaftProfilePreviewExternalRibbonTestTag = "profile-card-external-ribbon"

/**
 * [avatar] (an [avatarEdge] square) with the first of [servers]' tile on its top-left corner, or
 * [avatar] alone when [servers] is empty (someone of the viewed server, or an external member
 * whose server was not named). The corner is decoration, hidden from accessibility services,
 * unless [contentDescription] is given (the author row's "From Acme Corp").
 */
@Composable
internal fun RaftAvatarWithOriginCorner(
    servers: List<MemberServerRef>,
    avatarEdge: Dp,
    modifier: Modifier = Modifier,
    forceDot: Boolean = false,
    contentDescription: String? = null,
    avatar: @Composable () -> Unit,
) {
    val first = servers.firstOrNull()
    if (first == null) {
        Box(modifier) { avatar() }
        return
    }
    Box(modifier) {
        avatar()
        RaftAvatarOriginCorner(first, raftAvatarOriginCornerGeometry(avatarEdge, forceDot), contentDescription)
    }
}

@Composable
private fun RaftAvatarOriginCorner(server: MemberServerRef, geometry: RaftAvatarOriginCornerGeometry, contentDescription: String?) {
    val stickerEdge = RaftTheme.colors.stickerEdge
    Box(
        Modifier
            .offset(x = -geometry.overhang, y = -geometry.overhang)
            .size(geometry.tile)
            .testTag(RaftAvatarOriginCornerTestTag)
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        if (geometry.dot) {
            // `rounded-full border border-sticker-ink bg-sticker-ink shadow-[0_0_0_1.5px_var(--sticker-edge)]`.
            Box(
                Modifier
                    .fillMaxSize()
                    .originOuterRing(stickerEdge, circle = true)
                    .background(RaftUiPalette.stickerInk, CircleShape),
            )
        } else {
            RaftServerOriginTile(server, geometry.tile, geometry.initialSize)
        }
    }
}

/**
 * A server's own tile (web AvatarSlot `type="server"`, raft-ui Avatar `server`): `sticker-ink`
 * under the server's logo when the viewer has it ([LocalRaftViewerServerLogos]), else its initial
 * in `brand`; the 1.5dp die-cut `sticker-edge` ring outside.
 */
@Composable
internal fun RaftServerOriginTile(server: MemberServerRef, edge: Dp, initialSize: TextUnit) {
    val logo = LocalRaftViewerServerLogos.current[server.id.trim()]
    val stickerEdge = RaftTheme.colors.stickerEdge
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    val initial: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RaftText(
                text = server.originInitial(),
                style =
                    RaftTheme.typography.caption.copy(
                        fontSize = initialSize,
                        lineHeight = initialSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                color = RaftTheme.colors.brand,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    Box(
        Modifier
            .size(edge)
            .originOuterRing(stickerEdge, circle = false)
            .background(RaftUiPalette.stickerInk, shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo == null) {
            initial()
        } else {
            RaftAvatarHttpImage(
                source = logo,
                modifier = Modifier.fillMaxSize(),
                cacheSubject = RaftAvatarCacheSubject.Server(server.id),
                cacheServerId = server.id,
                serverDiagnosticsId = server.id,
                fallback = initial,
                placeholder = initial,
            )
        }
    }
}

/** `shadow-[0_0_0_1.5px_var(--sticker-edge)]`: a 1.5dp ring outside the box; transparent in light. */
private fun Modifier.originOuterRing(color: Color, circle: Boolean): Modifier =
    if (color.alpha == 0f) {
        this
    } else {
        drawBehind {
            val width = RaftStroke.Control.toPx()
            val inset = width / 2f
            if (circle) {
                drawCircle(color = color, radius = size.minDimension / 2f + inset, style = Stroke(width))
            } else {
                val radius = RaftRadius.Sticker.toPx() + inset
                drawRoundRect(
                    color = color,
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + width, size.height + width),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width),
                )
            }
        }
    }

/**
 * The profile card's mark of someone from another server (web `ExternalOriginRibbon`): a 28dp
 * `info-soft` band across the card's top, 16dp in from its sides, with every server's 14dp tile and
 * their names (`·` between, truncated) in caption medium `foreground`, and "External" at its end
 * in `foreground-muted`. Read out as one sentence, "External: Acme Corp, Lumen Labs". Nothing for
 * someone of the viewed server.
 */
@Composable
internal fun RaftProfilePreviewExternalRibbon(external: Boolean, servers: List<MemberServerRef>, modifier: Modifier = Modifier) {
    if (!external) return
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Medium)
    val externalLabel = stringResource(Res.string.profile_preview_external)
    val names = raftOriginServerNames(servers)
    val spoken = if (names.isNotEmpty()) stringResource(Res.string.profile_preview_external_from, names) else externalLabel
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.infoSoft)
            .padding(horizontal = 16.dp)
            .testTag(RaftProfilePreviewExternalRibbonTestTag)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            servers.forEach { server -> RaftServerOriginTile(server, 14.dp, 8.sp) }
            RaftText(
                text = servers.map { it.originLabel() }.filter { it.isNotEmpty() }.joinToString(" · "),
                modifier = Modifier.weight(1f, fill = false),
                style = style,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RaftText(text = externalLabel, style = style, color = colors.foregroundMuted, maxLines = 1)
    }
}

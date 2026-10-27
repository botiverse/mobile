package build.raft.app

import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.ServerAppearance
import build.raft.app.data.ServerAppearanceRepository
import build.raft.app.data.ServerAppearanceUpdateResult
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.applyStoredServerAppearance
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.ServerId
import build.raft.app.model.isFreeThemePlan
import build.raft.app.model.raftServerThemeLook
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * Settings > Appearance > "Theme on {server}": the member's own look on the selected server.
 * Web ServerThemeSection (botiverse/slock@aed2ba714cea38507191289c3c17669b46b25b6f
 * packages/web/src/components/settings/ServerThemeSection.tsx:64-135).
 *
 * The stored server is the single source of truth: a pick is written onto it at once (the
 * app-wide follower repaints from it, and this section reads it back), then saved. A failed save
 * puts back what the server last confirmed (web `confirmed`), and only the latest save may do so
 * (web `saveSequence`). A Pro theme on a Free plan saves nothing and asks for the upgrade.
 */

/** The theme groups and their order, web `BASIC_THEMES` / `AVATAR_THEMES`. */
internal val SettingsServerThemeBasics: List<RaftThemeId> = listOf(RaftThemeId.Raft, RaftThemeId.Boring)

internal val SettingsServerThemeAvatars: List<RaftThemeId> =
    listOf(
        RaftThemeId.Bots,
        RaftThemeId.Critters,
        RaftThemeId.Space,
        RaftThemeId.Desk,
        RaftThemeId.Food,
        RaftThemeId.Quest,
        RaftThemeId.Weather,
        RaftThemeId.Plants,
        RaftThemeId.Spooky,
    )

/** Web serverTheme.ts `raftThemeHasDecor`: the themes whose series draws pixel art on the frame. */
internal fun RaftThemeId.hasThemeDecor(): Boolean = this != RaftThemeId.Raft && this != RaftThemeId.Boring

internal data class SettingsServerThemeState(
    /** The selected server; null hides the section. */
    val serverId: String? = null,
    val serverName: String = "",
    val plan: String? = null,
    /** The stored choice; null is Raft Yellow. */
    val choice: RaftThemeId? = null,
    val decor: Boolean = true,
) {
    val visible: Boolean
        get() = serverId != null

    /** Web `freePlan`: no plan or `"free"`; the Pro themes show locked. */
    val freePlan: Boolean
        get() = isFreeThemePlan(plan)

    /** What the member sees, web `look`: a Pro theme on Free reads as Raft Yellow. */
    val look: RaftServerThemeLook
        get() = raftServerThemeLook(choice, decor, plan)

    /** Web `hasDecor`: the switch is off and disabled for a theme without pixel art. */
    val decorAvailable: Boolean
        get() = look.theme.hasThemeDecor()

    val decorChecked: Boolean
        get() = decorAvailable && look.decor
}

internal class SettingsServerThemeStore(
    private val repository: ServerAppearanceRepository,
    private val sessionStore: RaftAuthSessionStore,
    private val scope: CoroutineScope,
    /** A save failed and the previous look is back (web `settings.theme.saveFailed`). */
    private val onSaveFailed: () -> Unit,
    /**
     * A Free member picked a Pro theme: nothing was applied or sent. The upgrade hook (web
     * `openUpgradeDialog("pro_theme")`).
     */
    private val onUpgradeRequired: () -> Unit,
) {
    private val mutableState = MutableStateFlow(SettingsServerThemeState())
    val state: StateFlow<SettingsServerThemeState> = mutableState.asStateFlow()

    private var accountId: String? = null
    private var saveSequence = 0L
    private var confirmed: ServerAppearance? = null
    private var pending: ServerAppearance? = null
    private var wakeJob: Job? = null

    /** Reads the stored session now and again after every [wakes] signal (the auth store's events). */
    fun start(wakes: Flow<Any?>) {
        wakeJob?.cancel()
        wakeJob =
            scope.launch {
                refresh()
                wakes.collect { refresh() }
            }
    }

    /** Web `handlePick`. */
    fun pickTheme(theme: RaftThemeId) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        // Compare with the stored choice, not the look: a stored Pro theme shows as Raft Yellow
        // on Free, and picking Raft Yellow must still clear it.
        if (theme == (current.choice ?: RaftThemeId.Raft)) return
        if (current.freePlan && theme.isPro) {
            onUpgradeRequired()
            return
        }
        val next = ServerAppearance(theme = theme.takeUnless { it == RaftThemeId.Raft }, themeDecor = current.decor)
        save(serverId, current, next) { repository.update(ServerId(serverId), next.theme, next.themeDecor) }
    }

    /** Web `handleDecorChange`: the PATCH carries only `themeDecor`. */
    fun setDecor(enabled: Boolean) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (!current.decorAvailable || enabled == current.decor) return
        val next = ServerAppearance(theme = current.choice, themeDecor = enabled)
        save(serverId, current, next) { repository.updateDecor(ServerId(serverId), enabled) }
    }

    private fun save(
        serverId: String,
        before: SettingsServerThemeState,
        next: ServerAppearance,
        request: suspend () -> ServerAppearanceUpdateResult,
    ) {
        val account = accountId ?: return
        // A failed save goes back to what the server last confirmed, not to the previous
        // optimistic value: two saves that both fail leave nothing unsaved on screen. With no
        // save on its way, the stored server is the latest the server told us (list, realtime).
        val stored = ServerAppearance(theme = before.choice, themeDecor = before.decor)
        if (pending == null) confirmed = stored
        val fallback = confirmed ?: stored
        val sequence = ++saveSequence
        pending = next
        mutableState.value = before.copy(choice = next.theme, decor = next.themeDecor)
        scope.launch {
            write(account, serverId, next)
            val result =
                runCatchingPreservingCancellation { request() }.getOrNull()
                    ?: ServerAppearanceUpdateResult.Failed(code = 0, serverMessage = null)
            // The member may have switched servers meanwhile: then nothing here is theirs to touch.
            if (mutableState.value.serverId != serverId) return@launch
            val latest = sequence == saveSequence
            when (result) {
                is ServerAppearanceUpdateResult.Saved -> {
                    confirmed = result.appearance
                    if (latest) {
                        pending = null
                    } else {
                        // The repository wrote this older answer onto the stored server; a newer
                        // pick is still on its way, so keep that one on screen.
                        pending?.let { write(account, serverId, it) }
                    }
                }
                else -> {
                    if (!latest) return@launch
                    pending = null
                    write(account, serverId, fallback)
                    if (result == ServerAppearanceUpdateResult.ProRequired) onUpgradeRequired() else onSaveFailed()
                }
            }
            refresh()
        }
    }

    private suspend fun write(account: String, serverId: String, appearance: ServerAppearance) {
        sessionStore.applyStoredServerAppearance(account, serverId, appearance.themeWire, appearance.themeDecor)
        refresh()
    }

    private suspend fun refresh() {
        val session = sessionStore.readSession()
        accountId = session?.user?.id
        val server = session?.selectedThemeServer()
        val previous = mutableState.value
        if (server?.id != previous.serverId) {
            // Another server (or none): the confirmations and the pick on the way are not this
            // one's. The sequence keeps counting, so an older save can never pass for the latest.
            confirmed = null
            pending = null
        }
        // A pick on its way stays on screen even when a read lands before its write.
        val shown = pending
        mutableState.value =
            if (server == null) {
                SettingsServerThemeState()
            } else {
                SettingsServerThemeState(
                    serverId = server.id,
                    serverName = server.name,
                    plan = server.plan,
                    choice = if (shown != null) shown.theme else server.raftTheme,
                    decor = shown?.themeDecor ?: server.themeDecor,
                )
            }
    }
}

/** The session's selected server, or null when none is selected (the section hides). */
private fun StoredAuthSession.selectedThemeServer(): StoredAuthServer? {
    val serverId = currentServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return servers.firstOrNull { it.id == serverId }
}

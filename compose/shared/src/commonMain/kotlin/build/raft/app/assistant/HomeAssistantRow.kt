package build.raft.app.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.data.HomeAssistantEntryMemory
import build.raft.app.data.MyAssistantKey
import build.raft.app.data.MyAssistantState
import build.raft.app.data.PERSONAL_ASSISTANT_FEATURE_FLAG_KEY
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.homeAssistantEntryAnswer
import build.raft.app.data.homeAssistantEntryRemembers
import build.raft.app.data.homeAssistantEntryShown
import build.raft.app.data.myAssistantState
import build.raft.app.model.ServerId

/**
 * What the phone Home needs for its Assistant row: "my assistant" ([store]), the server flags,
 * the remembered answer ([memory]) and what a tap does ([onOpen]: the event, then the page).
 * Only the phone layout passes one (web `mobileInline`; the wide layout has the rail).
 */
internal class HomeAssistantEntrySource(
    val store: SharedMyAssistantStore,
    val featureFlags: SharedServerFeatureFlags,
    val memory: HomeAssistantEntryMemory,
    val onOpen: () -> Unit,
)

/**
 * "my assistant" for one person in one server as a page reads it (web `useMyAssistant`), and
 * whether the `personal_assistant` flag could not be read ([flagUndetermined], web
 * `undetermined`). [key] is null while the server or the person is not known.
 */
internal data class MyAssistantGate(
    val key: MyAssistantKey?,
    val state: MyAssistantState,
    val flagUndetermined: Boolean,
)

/**
 * Web `useMyAssistant`: follows the `personal_assistant` flag live, and asks [store] for the
 * person's assistant only while the flag is on and the viewer is not a guest (one read per person
 * and server, shared with the composer's otter and the profile).
 */
@Composable
internal fun rememberMyAssistantGate(
    store: SharedMyAssistantStore,
    flags: SharedServerFeatureFlags,
    serverId: String?,
    userId: String?,
    viewerIsGuest: Boolean,
): MyAssistantGate {
    val server = serverId?.trim()?.takeIf(String::isNotEmpty)
    val user = userId?.trim()?.takeIf(String::isNotEmpty)
    val revision by flags.snapshotRevision.collectAsState()
    val flagEnabled = remember(flags, server, revision) { server != null && flags.isEnabled(ServerId(server), PERSONAL_ASSISTANT_FEATURE_FLAG_KEY) }
    val flagPending = remember(flags, server, revision) { server != null && flags.isDecisionPending(ServerId(server), PERSONAL_ASSISTANT_FEATURE_FLAG_KEY) }
    val flagUndetermined = remember(flags, server, revision) { server != null && flags.isDecisionUndetermined(ServerId(server), PERSONAL_ASSISTANT_FEATURE_FLAG_KEY) }
    val key = if (server != null && user != null) MyAssistantKey(server, user) else null
    val shouldAsk = flagEnabled && !viewerIsGuest && key != null
    LaunchedEffect(store, key, shouldAsk) {
        if (shouldAsk && key != null) store.ensureLoaded(key)
    }
    val answers by store.answers.collectAsState()
    val state =
        myAssistantState(
            viewerIsGuest = viewerIsGuest,
            flagDecisionPending = flagPending,
            flagEnabled = flagEnabled,
            answer = key?.let(answers::get),
        )
    return MyAssistantGate(key, state, flagUndetermined)
}

/** Whether a server role is a guest's (guests have no assistant). */
internal fun assistantViewerIsGuest(serverRole: String?): Boolean = serverRole?.trim()?.equals("guest", ignoreCase = true) == true

/**
 * The phone Home's Assistant row for [serverId] (web `useRememberedAssistantShown`): its tap
 * ([HomeAssistantEntrySource.onOpen]) while the row shows, null while it is hidden. Until "my
 * assistant" answers, the row is as it was the last time ([HomeAssistantEntryMemory]); each known
 * answer is remembered, except while the flag could not be read.
 */
@Composable
internal fun rememberHomeAssistantRow(
    source: HomeAssistantEntrySource?,
    serverId: String,
    userId: String?,
    serverRole: String?,
): (() -> Unit)? {
    source ?: return null
    val shown = rememberAssistantEntryShown(source.store, source.featureFlags, source.memory, serverId, userId, serverRole)
    return if (shown) source.onOpen else null
}

/**
 * Whether an Assistant entry shows for [serverId] (web `useRememberedAssistantShown`, shared by
 * the phone Home's row and the wide rail's Assistant tab): "my assistant" is ready (not a guest,
 * the flag on, the API says enabled), with or without an assistant yet; until it answers, as the
 * last time ([memory]); each known answer is remembered, except while the flag could not be read.
 */
@Composable
internal fun rememberAssistantEntryShown(
    store: SharedMyAssistantStore,
    featureFlags: SharedServerFeatureFlags,
    memory: HomeAssistantEntryMemory,
    serverId: String?,
    userId: String?,
    serverRole: String?,
): Boolean {
    val server = serverId?.trim()?.takeIf(String::isNotEmpty)
    val user = userId?.trim()?.takeIf(String::isNotEmpty)
    val gate = rememberMyAssistantGate(store, featureFlags, server, user, assistantViewerIsGuest(serverRole))
    val answer = homeAssistantEntryAnswer(gate.state)
    val remembered = remember(memory, user, server) { memory.read(user, server) }
    val remembers = homeAssistantEntryRemembers(answer, gate.flagUndetermined)
    LaunchedEffect(memory, user, server, answer, remembers) {
        if (remembers && answer != null) memory.write(user, server, answer)
    }
    return homeAssistantEntryShown(answer, remembered)
}

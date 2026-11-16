package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.model.MemberId

/**
 * The agents shown by agent profiles that are on screen, per shell agent-detail store.
 *
 * A shell has one [SharedAgentDetailStore] holding one agent, and every profile page takes it over
 * when it loads. Pages under the top one stay composed, so a profile uncovered by Back (Hands
 * 11c0ed95: A's profile → its computer → D's profile → Back → Back) finds the store holding another
 * agent. [MemberDetailAgentStoreReclaimEffect] takes it back, but only from an agent no visible
 * profile shows: two profiles on screen together (the wide layout's Members split next to a
 * supporting-pane profile) leave the store with whichever loaded last instead of taking turns.
 */
internal object MemberDetailVisibleAgentClaims {
    private class Claim(val store: SharedAgentDetailStore, val agentId: MemberId)

    private val claims = mutableStateMapOf<Any, Claim>()

    fun claim(token: Any, store: SharedAgentDetailStore, agentId: MemberId) {
        claims[token] = Claim(store, agentId)
    }

    fun release(token: Any) {
        claims.remove(token)
    }

    /** Whether a visible profile other than [token]'s shows [agentId] from [store] (snapshot read). */
    fun shownByAnotherVisibleProfile(token: Any, store: SharedAgentDetailStore, agentId: MemberId?): Boolean =
        agentId != null && claims.any { (other, claim) -> other !== token && claim.store === store && claim.agentId == agentId }
}

/**
 * Takes the shell's agent-detail store back for this profile: [onReclaim] re-runs the profile's load
 * (cached detail at once, else a fresh select and fetch).
 *
 * It does so once per event, and only from another agent (or none) no other visible profile shows:
 * - the page becomes visible again (uncovered by Back, the app back in the foreground);
 * - the agent the store holds stops being shown by another visible profile (on the wide layout, the
 *   supporting-pane profile next to this one closes).
 * A store already on this agent (its own load or refresh in flight) is left alone, and so is an
 * agent seeded for a profile about to open (it has not been shown by a visible profile yet). Two
 * visible profiles leave the store with whichever loaded last instead of taking turns.
 *
 * [agentId] is the profile's agent (null for a human); [routeIsAgent] is false while the profile's
 * first load runs or after it failed, when that load (or Retry) owns the store.
 */
@Composable
internal fun MemberDetailAgentStoreReclaimEffect(
    store: SharedAgentDetailStore,
    agentId: MemberId?,
    visible: Boolean,
    routeIsAgent: Boolean,
    onReclaim: () -> Unit,
) {
    val token = remember { Any() }
    val claimed = visible && agentId != null
    DisposableEffect(token, store, agentId, claimed) {
        if (claimed && agentId != null) MemberDetailVisibleAgentClaims.claim(token, store, agentId)
        onDispose { MemberDetailVisibleAgentClaims.release(token) }
    }
    val heldAgentId = store.state.collectAsState().value.agentId
    val storeHoldsThisAgent = agentId != null && heldAgentId == agentId
    val heldByAnotherVisibleProfile =
        MemberDetailVisibleAgentClaims.shownByAnotherVisibleProfile(token, store, heldAgentId)
    var armed by remember(store, agentId) { mutableStateOf(false) }
    var heldWasShownElsewhere by remember(store, agentId, heldAgentId) { mutableStateOf(false) }
    // Arm on each time the page becomes visible.
    LaunchedEffect(visible) {
        if (visible) armed = true
    }
    // Arm when the held agent's other visible profile goes away.
    LaunchedEffect(heldWasShownElsewhere, heldByAnotherVisibleProfile) {
        if (heldByAnotherVisibleProfile) {
            heldWasShownElsewhere = true
        } else if (heldWasShownElsewhere) {
            heldWasShownElsewhere = false
            armed = true
        }
    }
    LaunchedEffect(storeHoldsThisAgent) {
        if (storeHoldsThisAgent) armed = false
    }
    val reclaimDue = armed && claimed && routeIsAgent && !storeHoldsThisAgent && !heldByAnotherVisibleProfile
    val currentOnReclaim by rememberUpdatedState(onReclaim)
    LaunchedEffect(store, agentId, reclaimDue) {
        if (!reclaimDue || store.state.value.agentId == agentId) return@LaunchedEffect
        armed = false
        currentOnReclaim()
    }
}

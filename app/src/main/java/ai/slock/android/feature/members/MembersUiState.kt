package ai.slock.android.feature.members

import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentDetailSnapshot
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState

data class MembersUiState(
    val loadState: ScreenLoadState<MembersContent>,
    val selectedAgentDetailState: ScreenLoadState<AgentDetailSnapshot> = ScreenLoadState.Loading,
    val selectedAgentDmsState: ScreenLoadState<AgentDmSnapshot> = ScreenLoadState.Loading,
    val selectedAgentRemindersState: ScreenLoadState<AgentReminderSnapshot> = ScreenLoadState.Loading,
    val selectedAgentActivityState: ScreenLoadState<AgentActivitySnapshot> = ScreenLoadState.Loading,
    val searchQuery: String = "",
    val selectedKindFilter: MemberKind? = null,
    val agentProfileSaveState: AgentProfileSaveState = AgentProfileSaveState.Idle
)

data class MembersContent(
    val server: ServerSummary,
    val members: List<MemberProfile>
)

sealed interface AgentProfileSaveState {
    data object Idle : AgentProfileSaveState
    data object Saving : AgentProfileSaveState
    data object Saved : AgentProfileSaveState
    data class Error(val error: ScreenError) : AgentProfileSaveState
}

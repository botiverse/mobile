package ai.slock.android.feature.members

import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState

data class MembersUiState(
    val loadState: ScreenLoadState<MembersContent>,
    val searchQuery: String = "",
    val selectedKindFilter: MemberKind? = null
)

data class MembersContent(
    val server: ServerSummary,
    val members: List<MemberProfile>
)


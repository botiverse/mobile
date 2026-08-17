package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.SharedAgentCreateDataSchema
import build.raft.app.data.SharedAgentCreateFieldCopy
import build.raft.app.data.SharedAgentCreateFieldSchema
import build.raft.app.data.SharedAgentCreateFormCapabilities
import build.raft.app.data.SharedAgentCreateFormDefinition
import build.raft.app.data.SharedAgentCreateFormOption
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedAgentCreateFormOptionSourceRef
import build.raft.app.data.SharedAgentCreateUiSchema
import build.raft.app.data.SharedAgentCreateVisibilityRule
import build.raft.app.data.SharedResolvedAgentCreateForm
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.slockSharedPreferenceStore
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

/**
 * Visual coverage for the server-switcher unread badge.
 *
 * Before this host the badge had zero capture coverage in every state, which is how mobile #2119
 * shipped a user-visible regression (per-server unread numbers degraded to neutral dots) with a
 * fully green visual suite. Every row below pins one branch of
 * [homeServerSwitcherBadgePresentation] so the next change to that precedence has to restate its
 * baseline instead of passing silently.
 *
 * Row 3 carries the contract's teeth: an authoritative `activityUnreadCount = 0` must suppress the
 * badge even though a nonzero legacy `broaderUnreadCount` is present, because the two counts are
 * different quantities and the legacy one may never stand in for the exact one.
 */
@Page("compose_home_server_switcher_badge_visual", supportInLocal = true)
internal class ComposeHomeServerSwitcherBadgeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeServerSwitcherBadgeVisualContent() }
    }
}

@Composable
private fun HomeServerSwitcherBadgeVisualContent() {
    SlockStructuralHomeShell(
        currentServerName = VisualFixtureData.Server.NAME,
        currentServerId = VisualFixtureData.Server.ID,
        servers =
            listOf(
                // Current server, no unread of either kind.
                SlockServerSwitcherItem(
                    id = VisualFixtureData.Server.ID,
                    name = VisualFixtureData.Server.NAME,
                    slug = VisualFixtureData.Server.SLUG
                ),
                // 1. Exact Activity count present -> exact number.
                SlockServerSwitcherItem(
                    id = "badge-exact",
                    name = "Exact Three",
                    slug = "exact-three",
                    broaderUnreadCount = 0,
                    activityUnreadCount = 3L
                ),
                // 2. Exact count unknown, legacy broader count present -> the fallback branch.
                //    This is the row artin's ruling changes from a neutral dot back to the broader
                //    number; expect this row to move when that fix lands.
                SlockServerSwitcherItem(
                    id = "badge-fallback",
                    name = "Unknown Seven",
                    slug = "unknown-seven",
                    broaderUnreadCount = 7,
                    activityUnreadCount = null
                ),
                // 3. Authoritative zero outranks a nonzero legacy count -> no badge at all.
                SlockServerSwitcherItem(
                    id = "badge-authoritative-zero",
                    name = "Authoritative Zero",
                    slug = "authoritative-zero",
                    broaderUnreadCount = 5,
                    activityUnreadCount = 0L
                ),
                // 4. Exact count above the display cap -> "99+".
                SlockServerSwitcherItem(
                    id = "badge-capped",
                    name = "Capped Count",
                    slug = "capped-count",
                    broaderUnreadCount = 0,
                    activityUnreadCount = 128L
                )
            ),
        channels = emptyList(),
        directMessages = emptyList(),
        // The opt-in drawer is the surface the regression was reported on, and with
        // serverDrawerSections left empty the panel derives its sections straight from `servers`
        // above. Non-current servers land on `Unloaded`, not `Unavailable`, so their badges render.
        serverSwitcherExpanded = true,
        serverDrawerEnabled = true,
        showBottomBar = true,
        outerPadding = 0.dp
    )
}

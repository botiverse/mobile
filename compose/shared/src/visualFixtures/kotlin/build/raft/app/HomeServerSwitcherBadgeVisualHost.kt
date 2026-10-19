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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
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
 * Rows are referred to by server name below, never by position. The list numbering here and the
 * top-to-bottom order in the captured frame disagree, because the frame also shows the current
 * server, which pins no branch — so "row 3" means one thing in this file and another in the image.
 *
 * `Authoritative Zero` carries the contract's teeth: an authoritative `activityUnreadCount = 0` must
 * suppress the badge even though a nonzero legacy `broaderUnreadCount` is present, because the two
 * counts are different quantities and the legacy one may never stand in for the exact one.
 *
 * `Capped Count` and `Fallback Capped` are a pair, and only mean something together: contract
 * section 3a requires a client to apply its display cap identically to the exact count and to the
 * transitional fallback count, so changing which field feeds the badge must not quietly change
 * capping behaviour. Read `Capped Count` alone and a fallback path that bypasses the shared
 * formatter would go unnoticed.
 *
 * Note that the pair does not guard the cap yet. Until the fallback renders a number,
 * `Fallback Capped` is a dot while `Capped Count` is a capped value, so they legitimately differ;
 * the pair starts holding the cap only once both read the same capped value.
 *
 * Verified frame (dispatch run 32028728530, shard 0), in frame order: `Visual Server` no badge,
 * `Exact Three` 3, `Unknown Seven` dot, `Authoritative Zero` no badge, `Capped Count` 99+,
 * `Fallback Capped` dot — confirming in particular that `Authoritative Zero` is suppressed despite
 * its nonzero legacy count.
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
                //    Renders a dot until the client fix lands and the broader number afterwards.
                //    `Fallback Capped` takes the same path, so BOTH fallback rows move when that fix
                //    lands -- a migration declaration naming only this row would leave the other
                //    unexplained.
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
                ),
                // 5. Legacy fallback count above the cap -> also "99+", matching `Capped Count`.
                //    Contract section 3a requires one client to cap the exact count and the fallback
                //    count identically, so the pair only holds if both read the same. `Capped Count`
                //    alone cannot see a fallback path that skips the shared formatter.
                //
                //    ⚠️ RETIRE THIS ROW (`Fallback Capped`) when the transitional fallback branch is
                //    deleted. Once the server supplies the exact count and the fallback is removed
                //    there is only one number source: the cap applies to a single path, so this pair
                //    stops testing anything and this row renders like `Unknown Seven` again.
                //    Deleting the fallback therefore owes two things -- drop this row, keeping
                //    `Capped Count` as the single cap row, and declare the baseline migration in
                //    #wg-nightly-visual-fix, because `Unknown Seven` and `Fallback Capped` revert
                //    from 7 and 99+ back to a dot or nothing. `Authoritative Zero` does not move.
                //    That declaration is easy to miss: it lands in a routine dead-code cleanup that
                //    looks unrelated to visuals.
                SlockServerSwitcherItem(
                    id = "badge-fallback-capped",
                    name = "Fallback Capped",
                    slug = "fallback-capped",
                    broaderUnreadCount = 150,
                    activityUnreadCount = null
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
